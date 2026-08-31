package com.example.cellrebelauto.integration.v1

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        assertEquals("frozen quota vector", listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2), items.map { it.requiredSuccesses })
    }

    @Test
    fun committedFileSha_feedsThroughRequireRegisteredDigest() {
        // R4 P2: feed the COMPUTED committed-file SHA through the pin so a drift
        // of the runtime constant away from the file goes red here (the byte
        // literal / self-pin tests could both stay green under a constant-only
        // mutation).
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module root")
        val bytes = File(moduleRoot, "../../../docs/acceptance/a-plus-10a-fixture.json").normalize().readBytes()
        val computed = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        APlus10APlanSeed.requireRegisteredDigest(computed, computed)
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
    fun placeholder_isStructurallyOutOfGeographicDomain() {
        // PR #62 R3 P3: assert the SEMANTIC property (out of the legal lat/lng
        // domain), not merely == the constant — the earlier self-test stayed
        // green if the constant were changed back to 0.0 (a real place). The
        // dispatch hard constraint requires a value that cannot be mistaken for
        // a real target.
        val p = APlus10APlanSeed.COORDINATE_PLACEHOLDER
        assertTrue(
            "placeholder $p must be outside lat [-90,90] AND lng [-180,180] on both axes",
            p < -90.0 || p > 90.0,
        )
        assertTrue("placeholder must also be outside the longitude domain", p < -180.0 || p > 180.0)
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
    // verifyPlanTopology — start_run binds to the SEEDED plan (P2)
    // ------------------------------------------------------------------

    @Test
    fun verifyPlanTopology_acceptsTheSeededPlan() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        val tasks = APlus10APlanSeed.toTasks(items)
        assertNull("the seeded FX-G2-10A plan must verify", APlus10APlanSeed.verifyPlanTopology(plan, tasks))
    }

    @Test
    fun verifyPlanTopology_rejectsForeignSource() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items).copy(sourceFileName = "user-import.csv")
        assertNotNull(APlus10APlanSeed.verifyPlanTopology(plan, APlus10APlanSeed.toTasks(items)))
    }

    @Test
    fun verifyPlanTopology_rejectsRealCoordinates() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        // A CSV-imported plan with the same shape but REAL coordinates must be
        // refused — it is not this KB-8 seeder's output.
        val csvTasks = APlus10APlanSeed.toTasks(items).mapIndexed { i, t ->
            t.copy(latitude = 50.4 + i * 0.001, longitude = 30.5 + i * 0.001)
        }
        val mismatch = APlus10APlanSeed.verifyPlanTopology(plan, csvTasks)
        assertNotNull(mismatch)
        assertTrue(mismatch!!.contains("real coordinates"))
    }

    @Test
    fun verifyPlanTopology_rejectsWrongRowCount() {
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        assertNotNull(APlus10APlanSeed.verifyPlanTopology(plan, APlus10APlanSeed.toTasks(items).dropLast(1)))
    }

    @Test
    fun verifyPlanTopology_rejectsSameTotalQuotaRedistribution() {
        // R4 P1-4: a plan whose per-item quotas are a same-total redistribution
        // (items 1↔2 swapped) has total 17 and every other current predicate
        // true — start_run must still refuse it (wrong per-address attribution).
        val items = APlus10APlanSeed.parsePayload(payload())
        val plan = APlus10APlanSeed.toPlan(items)
        val tasks = APlus10APlanSeed.toTasks(items).toMutableList()
        val t0 = tasks[0]; val t1 = tasks[1]
        tasks[0] = t0.copy(requiredSuccesses = t1.requiredSuccesses)
        tasks[1] = t1.copy(requiredSuccesses = t0.requiredSuccesses)
        val mismatch = APlus10APlanSeed.verifyPlanTopology(plan, tasks)
        assertNotNull("same-total quota redistribution must be refused", mismatch)
        assertTrue(mismatch!!.contains("registered"))
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
    fun rejectsSameTotalQuotaRedistribution() {
        // R4 P1-4: swap items 1↔2 quotas (sum still 17) — a sum-only check would
        // pass; the exact ordered vector must reject it.
        val swapped = frozenQuotas.toMutableList().also { it[0] = frozenQuotas[1]; it[1] = frozenQuotas[0] }
        assertRejected(
            "items 1↔2 quota redistribution (same total)",
            payload(items = (1..10).map { itemJson(it, swapped[it - 1]) }),
        )
    }

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

    // ------------------------------------------------------------------
    // PR #62 P1-1 — registered-digest pin (Auto runtime path)
    // ------------------------------------------------------------------

    @Test
    fun requireRegisteredDigest_acceptsTheRegisteredPin() {
        APlus10APlanSeed.requireRegisteredDigest(
            APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
            APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
        )
    }

    /** changed-bytes + recomputed self-consistent digest: still rejected by the pin. */
    @Test
    fun requireRegisteredDigest_rejectsChangedBytes() {
        val fabricatedHash = MessageDigest.getInstance("SHA-256")
            .digest("fabricated FX-G2-10A payload with a same-total quota swap".toByteArray())
            .joinToString("") { "%02x".format(it) }
        try {
            APlus10APlanSeed.requireRegisteredDigest(fabricatedHash, fabricatedHash)
            fail("a payload that does not hash to the registered digest must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    /** caller-substituted registration: real bytes, foreign declared digest — rejected. */
    @Test
    fun requireRegisteredDigest_rejectsCallerSubstitutedDeclaration() {
        try {
            APlus10APlanSeed.requireRegisteredDigest(
                APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
                "0000000000000000000000000000000000000000000000000000000000000000",
            )
            fail("the caller may not substitute its own declared digest")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
