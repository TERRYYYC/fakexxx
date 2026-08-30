package name.caiyao.fakegps.mockprovider

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic unit test for the G2 §5A 10-address fixture seeder (FX-G2-10A).
 *
 * TWO false-green families are pinned here:
 *
 * 1. EXPLICIT-ID drift (opus5 抽验①): ProfileEntity autoGenerates ids and
 *    `deleteAll()` does not reset sqlite_sequence, so implicit-id seeding
 *    yields profile-11.. while the fixture froze profile-1..10 — a green seed
 *    binding every journey to the WRONG schedule item.
 *
 * 2. CALLER-CHOSEN payloads (PR #62 review P1-2): the payload AND its digest
 *    arrive from the same caller, so the digest alone cannot stop a
 *    hand-built "FX-G2-10A" with the wrong shape. The parser must bind
 *    independently to the REGISTERED fixture structure (exactly 10, ordered,
 *    profile-N aligned, schedule id, quota sum 17) — verified positive
 *    against the committed fixture file (whose registered sha256 is pinned
 *    here) and negative against tampered/truncated/reordered payloads.
 */
class APlus10AFixtureSeedTest {

    // ------------------------------------------------------------------
    // Payload builder — the registered fixture's exact structure, mutable
    // per-test so each negative changes ONE thing.
    // ------------------------------------------------------------------

    /** Per-item quotas of the frozen fixture (sum 17). */
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
    // POSITIVE — the committed, registered fixture file itself
    // ------------------------------------------------------------------

    private fun repoFixtureFile(): File {
        val moduleRoot = sequenceOf(File("."), File("app"), File("../app"))
            .map { it.absoluteFile.normalize() }
            .firstOrNull { File(it, "src/debug/AndroidManifest.xml").isFile }
            ?: error("cannot locate the app module root")
        return File(moduleRoot, "../../../docs/acceptance/a-plus-10a-fixture.json").normalize()
    }

    @Test
    fun committedFixtureFileParsesAndMatchesTheRegisteredStructure() {
        val bytes = repoFixtureFile().readBytes()
        // Pin the REGISTERED digest (a-plus-device-matrix.md). If the fixture
        // file changes, this test goes red and forces the re-registration
        // protocol (version bump + digest re-registration) plus a deliberate
        // update here — the structure bind and the registration can never
        // drift apart silently.
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "committed fixture must be the registered frozen bytes",
            "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852",
            sha,
        )

        val items = APlus10AFixtureSeed.parsePayload(String(bytes, Charsets.UTF_8))
        assertEquals(10, items.size)
        assertEquals((1..10).map { "profile-$it" }, items.map { it.expectedScheduleItemId })
        assertEquals(17, items.sumOf { it.requiredSuccesses })
        val rows = APlus10AFixtureSeed.toProfileRows(items)
        assertEquals((1L..10L).toList(), rows.map { it.id })
    }

    @Test
    fun builderPayloadParses_andRowsCarryExplicitIdsAndFixtureFields() {
        val items = APlus10AFixtureSeed.parsePayload(payload())
        val rows = APlus10AFixtureSeed.toProfileRows(items)
        assertEquals(10, rows.size)
        // The load-bearing assertion: every row carries its FIXTURE id, never 0.
        assertEquals((1L..10L).toList(), rows.map { it.id })
        rows.forEach {
            assertTrue("no seeded row may keep the autoGenerate sentinel id=0", it.id != 0L)
        }
        val first = rows.first { it.id == 1L }
        assertEquals(50.401, first.latitude!!, 1e-9)
        assertEquals(27101, first.tac)
        assertEquals("G2-A10-01", first.wifiSsid)
    }

    // ------------------------------------------------------------------
    // NEGATIVES — each mutates exactly one structural fact (P1-2)
    // ------------------------------------------------------------------

    private fun assertRejected(reason: String, json: String) {
        try {
            APlus10AFixtureSeed.parsePayload(json)
            fail("parser must reject: $reason")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun rejectsTruncatedFixture() = assertRejected(
        "9 items (truncated)",
        payload(items = (1..9).map { itemJson(it, frozenQuotas[it - 1]) }, declaredTotal = 15),
    )

    @Test
    fun rejectsReorderedItems() {
        val items = (1..10).map { itemJson(it, frozenQuotas[it - 1]) }
        assertRejected("items 1 and 2 swapped", payload(items = listOf(items[1], items[0]) + items.drop(2)))
    }

    @Test
    fun rejectsTamperedQuota() = assertRejected(
        "item 2 quota 1→2 (sum 18)",
        payload(items = (1..10).map { itemJson(it, if (it == 2) 2 else frozenQuotas[it - 1]) }, declaredTotal = 18),
    )

    @Test
    fun rejectsQuotaSumDisagreeingWithDeclaredTotal() = assertRejected(
        "declared total 16 vs actual 17",
        payload(declaredTotal = 16),
    )

    @Test
    fun rejectsWrongScheduleId() = assertRejected(
        "foreign schedule id",
        payload(scheduleId = "some-other-schedule"),
    )

    @Test
    fun rejectsWrongFixtureId() = assertRejected("foreign fixtureId", payload(fixtureId = "FX-OTHER"))

    @Test
    fun rejectsMisalignedScheduleItemId() = assertRejected(
        "item 3 targeting profile-7",
        payload(items = (1..10).map { itemJson(it, frozenQuotas[it - 1], if (it == 3) "profile-7" else "profile-$it") }),
    )

    @Test
    fun rejectsNonContiguousFixtureIndex() {
        val items = (1..10).map { itemJson(if (it == 5) 6 else it, frozenQuotas[it - 1]) }
        assertRejected("fixtureIndex 5 skipped", payload(items = items))
    }

    // ------------------------------------------------------------------
    // scheduleItemDbId — "profile-N" → N, everything else rejected
    // ------------------------------------------------------------------

    @Test
    fun scheduleItemDbId_parsesProfilePrefix() {
        assertEquals(1L, APlus10AFixtureSeed.scheduleItemDbId("profile-1"))
        assertEquals(10L, APlus10AFixtureSeed.scheduleItemDbId("profile-10"))
    }

    @Test
    fun scheduleItemDbId_rejectsIdZeroAndMalformed() {
        listOf("profile-0", "", "profile-", "profile-x", "7", "profile-1x", "PROFILE-1", "profile--1", "profile-01")
            .forEach { bad ->
                try {
                    APlus10AFixtureSeed.scheduleItemDbId(bad)
                    fail("malformed schedule item id must be rejected: '$bad'")
                } catch (e: IllegalArgumentException) {
                    // expected — profile-0 collides with the autoGenerate sentinel,
                    // the rest are not canonical profile-N spellings
                }
            }
    }

    // ------------------------------------------------------------------
    // seedReport — the fixtureIndex ↔ dbId map + drift refusal
    // ------------------------------------------------------------------

    @Test
    fun seedReport_emitsMappingAndEchoesDigest() {
        val items = APlus10AFixtureSeed.parsePayload(payload())
        val report = APlus10AFixtureSeed.seedReport(
            items = items,
            insertedIds = (1L..10L).toList(),
            fixtureDigest = "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852",
        )
        assertTrue(report.contains("fixtureIndex=1"))
        assertTrue(report.contains("J10A-01"))
        assertTrue(report.contains("profile-10"))
        assertTrue(report.contains("cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852"))
    }

    @Test
    fun seedReport_failsOnInsertedIdDrift() {
        val items = APlus10AFixtureSeed.parsePayload(payload())
        try {
            APlus10AFixtureSeed.seedReport(items, insertedIds = (11L..20L).toList(), fixtureDigest = "x")
            fail("seedReport must reject inserted ids that differ from the fixture ids")
        } catch (e: IllegalStateException) {
            // expected — AUTOINCREMENT drift must never render a green mapping
        }
    }
}
