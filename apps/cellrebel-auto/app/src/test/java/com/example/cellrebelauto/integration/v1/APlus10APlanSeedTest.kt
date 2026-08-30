package com.example.cellrebelauto.integration.v1

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic unit test for the Auto-side G2 §5A plan seeder (fixture FX-G2-10A).
 *
 * Pinned invariants:
 *
 * 1. KB-8 (PR #62 review P1-1): canonical spec v1.62 freezes coordinate
 *    ownership with the provider — Auto does not import/hold/assert
 *    coordinates. The seeder consumes ONLY {order, journeyCaseId,
 *    requiredSuccesses}; the legacy non-null LocationTask coordinate columns
 *    receive the inert placeholder, never fixture values. (The compile-time
 *    half is FixtureItem having no coordinate fields at all.)
 * 2. Structure bind (P1-2): the payload+digest share a caller, so the parser
 *    independently validates the registered structure — exactly 10 items,
 *    contiguous order, profile-N alignment, schedule id, quota sum 17 —
 *    positive against the committed fixture (registered sha256 pinned here),
 *    negative against tampered/truncated/reordered payloads.
 * 3. Attribution: LocationTask has no journeyCaseId — the seed report's
 *    fixtureIndex ↔ taskId map is the only link from a run outcome back to a
 *    fixture journey, and ordering (csvRow/priority = fixtureIndex) is what
 *    keeps Auto task[i] aligned with provider item profile-(i+1).
 */
class APlus10APlanSeedTest {

    private val frozenQuotas = listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2)

    private fun itemJson(index: Int, quota: Int, scheduleItemId: String = "profile-$index"): String = """
        {
          "fixtureIndex": $index, "journeyCaseId": "J10A-${"%02d".format(index)}",
          "expectedScheduleItemId": "$scheduleItemId", "requiredSuccesses": $quota,
          "addname": "G2-A10-${"%02d".format(index)} Place", "latitude": ${50.4 + index * 0.001},
          "longitude": ${30.5 + index * 0.001}, "altitude": 150.0, "accuracy": 3.0,
          "tac": ${27100 + index}, "wifiSsid": "G2-A10-${"%02d".format(index)}"
        }
    """.trimIndent()

    private fun payload(
        items: List<String> = (1..10).map { itemJson(it, frozenQuotas[it - 1]) },
        fixtureId: String = "FX-G2-10A",
        scheduleId: String = "qwy-default-schedule",
        declaredTotal: Int = 17,
    ): String = """
        {
          "fixtureId": "$fixtureId",
          "scheduleId": "$scheduleId",
          "totalRequiredSuccesses": $declaredTotal,
          "items": [${items.joinToString(",")}]
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // POSITIVE — committed registered fixture
    // ------------------------------------------------------------------

    @Test
    fun committedFixtureFileParsesAndMatchesTheRegisteredStructure() {
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module root")
        val bytes = File(moduleRoot, "../../../docs/acceptance/a-plus-10a-fixture.json")
            .normalize().readBytes()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "committed fixture must be the registered frozen bytes",
            "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852",
            sha,
        )

        val items = APlus10APlanSeed.parsePayload(String(bytes, Charsets.UTF_8))
        assertEquals(10, items.size)
        assertEquals((1..10).map { "profile-$it" }, items.map { it.expectedScheduleItemId })
        assertEquals(17, items.sumOf { it.requiredSuccesses })
    }

    // ------------------------------------------------------------------
    // KB-8 — no fixture coordinates reach Auto
    // ------------------------------------------------------------------

    @Test
    fun kb8_tasksCarryOnlyThePlaceholderCoordinates() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val tasks = APlus10APlanSeed.toTasks(items)
        assertEquals(10, tasks.size)
        tasks.forEach { task ->
            assertEquals(
                "KB-8: Auto must not import fixture latitudes — legacy column gets the placeholder",
                APlus10APlanSeed.COORDINATE_PLACEHOLDER, task.latitude, 0.0,
            )
            assertEquals(
                "KB-8: Auto must not import fixture longitudes — legacy column gets the placeholder",
                APlus10APlanSeed.COORDINATE_PLACEHOLDER, task.longitude, 0.0,
            )
        }
        // Order binding survives without coordinates.
        assertEquals((1..10).toList(), tasks.map { it.csvRow })
        assertEquals((1..10).toList(), tasks.map { it.priority })
        assertEquals(frozenQuotas, tasks.map { it.requiredSuccesses })
    }

    @Test
    fun toPlan_totalRequiredSuccessesIsTheSum() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items, globalBufferSeconds = 60)
        assertEquals(10, plan.totalRows)
        assertEquals(17, plan.totalRequiredSuccesses)
        assertEquals("FX-G2-10A", plan.sourceFileName)
    }

    // ------------------------------------------------------------------
    // NEGATIVES — one structural mutation each (P1-2)
    // ------------------------------------------------------------------

    private fun assertRejected(reason: String, json: String) {
        try {
            APlus10APlanSeed.parsePayload(json)
            fail("parser must reject: $reason")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsTruncatedFixture() = assertRejected(
        "9 items",
        payload(items = (1..9).map { itemJson(it, frozenQuotas[it - 1]) }, declaredTotal = 15),
    )

    @Test
    fun rejectsReorderedItems() {
        val items = (1..10).map { itemJson(it, frozenQuotas[it - 1]) }
        assertRejected("items 1/2 swapped", payload(items = listOf(items[1], items[0]) + items.drop(2)))
    }

    @Test
    fun rejectsTamperedQuota() = assertRejected(
        "sum 18",
        payload(items = (1..10).map { itemJson(it, if (it == 2) 2 else frozenQuotas[it - 1]) }, declaredTotal = 18),
    )

    @Test
    fun rejectsQuotaSumDisagreeingWithDeclaredTotal() = assertRejected("declared 16", payload(declaredTotal = 16))

    @Test
    fun rejectsWrongScheduleId() = assertRejected("foreign schedule", payload(scheduleId = "other"))

    @Test
    fun rejectsWrongFixtureId() = assertRejected("foreign fixtureId", payload(fixtureId = "FX-OTHER"))

    @Test
    fun rejectsMisalignedScheduleItemId() = assertRejected(
        "item 3 → profile-7",
        payload(items = (1..10).map { itemJson(it, frozenQuotas[it - 1], if (it == 3) "profile-7" else "profile-$it") }),
    )

    // ------------------------------------------------------------------
    // seedReport — attribution map
    // ------------------------------------------------------------------

    @Test
    fun seedReport_emitsFixtureIndexToTaskIdMap() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val report = APlus10APlanSeed.seedReport(
            items = items,
            planId = 7L,
            taskIds = (101L..110L).toList(),
            fixtureDigest = "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852",
        )
        assertTrue(report.contains("fixtureIndex=1"))
        assertTrue(report.contains("taskId=101"))
        assertTrue(report.contains("J10A-01"))
        assertTrue(report.contains("planId=7"))
        assertTrue(report.contains("NOT consumed (KB-8"))
        assertTrue(report.contains("cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852"))
    }

    @Test
    fun seedReport_failsWhenTaskCountDoesNotMatch() {
        val items = APlus10APlanSeed.parsePayload(payload())
        try {
            APlus10APlanSeed.seedReport(items, planId = 7L, taskIds = listOf(101L), fixtureDigest = "x")
            fail("a taskId list shorter than the fixture must be rejected — unattributable seed")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
