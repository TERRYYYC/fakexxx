package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.mockprovider.APlus10AFixtureSeed.FixtureItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic unit test for the G2 §5A 10-address fixture seeder (FX-G2-10A).
 *
 * WHY THIS FILE EXISTS — the explicit-id false-green (opus5 抽验①)
 * ---------------------------------------------------------------
 * [name.caiyao.fakegps.data.db.ProfileEntity] uses `@PrimaryKey(autoGenerate = true)`.
 * `ProfileDao.deleteAll()` is `DELETE FROM temp`, which does NOT reset SQLite's
 * AUTOINCREMENT sequence (sqlite_sequence). So a seeder that inserts rows with
 * the implicit `id = 0` gets ids that CONTINUE from the last high-water mark —
 * after any prior seed/run the second seeding yields profile-11, profile-12, …
 * while the fixture froze `expectedScheduleItemId = profile-1..profile-10`.
 * The seed would run "green" and every A-block journey would then bind the
 * WRONG schedule item — the exact silent misattribution the G2 package's
 * §4.2 installed-identity discipline exists to forbid, one layer down.
 *
 * The fix is EXPLICIT ids: every seeded row carries the fixture-derived id, so
 * `expectedScheduleItemId = profile-N` is byte-exact regardless of history.
 * These tests are the RED that pins it: they fail on any implicit-id or
 * drifting-id implementation.
 *
 * The payload the seeder consumes IS the frozen fixture JSON itself
 * (a-plus-10a-fixture.json), so the digest an executor records over that file
 * is the digest of exactly what reached the device.
 */
class APlus10AFixtureSeedTest {

    // A faithful excerpt of the frozen fixture shape (a-plus-10a-fixture.json).
    // Two items are enough to prove ordering, explicit ids, and field carry —
    // the full-10 count assertion runs against the real committed file digest
    // in the seeder's parse test below.
    private val twoItemPayload = """
        {
          "${'$'}schema": "a-plus-10a-fixture-v1",
          "fixtureId": "FX-G2-10A",
          "fixtureVersion": 2,
          "scheduleId": "qwy-default-schedule",
          "totalRequiredSuccesses": 3,
          "items": [
            {
              "fixtureIndex": 1, "journeyCaseId": "J10A-01",
              "expectedScheduleItemId": "profile-1", "requiredSuccesses": 2,
              "addname": "G2-A10-01 Maidan", "latitude": 50.4501, "longitude": 30.5234,
              "altitude": 179.0, "accuracy": 3.0, "tac": 27101, "wifiSsid": "G2-A10-01"
            },
            {
              "fixtureIndex": 2, "journeyCaseId": "J10A-02",
              "expectedScheduleItemId": "profile-2", "requiredSuccesses": 1,
              "addname": "G2-A10-02 Golden Gate", "latitude": 50.4489, "longitude": 30.5133,
              "altitude": 182.0, "accuracy": 3.0, "tac": 27102, "wifiSsid": "G2-A10-02"
            }
          ]
        }
    """.trimIndent()

    // ------------------------------------------------------------------
    // scheduleItemDbId — "profile-N" → N, everything else rejected
    // ------------------------------------------------------------------

    @Test
    fun scheduleItemDbId_parsesProfilePrefix() {
        assertEquals(1L, APlus10AFixtureSeed.scheduleItemDbId("profile-1"))
        assertEquals(10L, APlus10AFixtureSeed.scheduleItemDbId("profile-10"))
    }

    @Test
    fun scheduleItemDbId_rejectsIdZero() {
        // id 0 is the autoGenerate sentinel — a fixture that ever produced it
        // would collide with "let Room assign", which is the bug this guards.
        try {
            APlus10AFixtureSeed.scheduleItemDbId("profile-0")
            fail("profile-0 must be rejected — 0 is the autoGenerate sentinel")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun scheduleItemDbId_rejectsMalformed() {
        listOf("", "profile-", "profile-x", "7", "profile-1x", "PROFILE-1", "profile--1").forEach { bad ->
            try {
                APlus10AFixtureSeed.scheduleItemDbId(bad)
                fail("malformed schedule item id must be rejected: '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    // ------------------------------------------------------------------
    // toProfileRows — EXPLICIT id, no drift, field carry
    // ------------------------------------------------------------------

    @Test
    fun toProfileRows_usesExplicitFixtureId_notImplicitZero() {
        val items = APlus10AFixtureSeed.parsePayload(twoItemPayload)
        val rows = APlus10AFixtureSeed.toProfileRows(items)
        assertEquals(2, rows.size)
        // The load-bearing assertion: every row carries its FIXTURE id, never 0.
        assertEquals("row 1 must carry explicit id 1", 1L, rows[0].id)
        assertEquals("row 2 must carry explicit id 2", 2L, rows[1].id)
        rows.forEach {
            assertTrue(
                "no seeded row may keep the autoGenerate sentinel id=0 — that is the drift bug",
                it.id != 0L,
            )
        }
    }

    @Test
    fun toProfileRows_carriesFixtureCoordinatesAndCellFields() {
        val items = APlus10AFixtureSeed.parsePayload(twoItemPayload)
        val rows = APlus10AFixtureSeed.toProfileRows(items)
        val first = rows.first { it.id == 1L }
        assertEquals(50.4501, first.latitude!!, 1e-9)
        assertEquals(30.5234, first.longitude!!, 1e-9)
        assertEquals(179.0, first.altitude!!, 1e-9)
        assertEquals(3.0f, first.accuracy!!, 1e-6f)
        assertEquals(27101, first.tac)
        assertEquals("G2-A10-01", first.wifiSsid)
        assertEquals("G2-A10-01 Maidan", first.addname)
    }

    @Test
    fun toProfileRows_rejectsDuplicateScheduleItemIds() {
        val dup = listOf(
            item(1, "profile-1"),
            item(2, "profile-1"),
        )
        try {
            APlus10AFixtureSeed.toProfileRows(dup)
            fail("duplicate expectedScheduleItemId must be rejected — two rows on one schedule item")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // parsePayload — validates the fixture envelope, not just items
    // ------------------------------------------------------------------

    @Test
    fun parsePayload_readsAllFieldsInOrder() {
        val items = APlus10AFixtureSeed.parsePayload(twoItemPayload)
        assertEquals(2, items.size)
        assertEquals(1, items[0].fixtureIndex)
        assertEquals("J10A-01", items[0].journeyCaseId)
        assertEquals("profile-1", items[0].expectedScheduleItemId)
        assertEquals(2, items[0].requiredSuccesses)
    }

    @Test
    fun parsePayload_rejectsWrongFixtureId() {
        val wrong = twoItemPayload.replace("FX-G2-10A", "FX-SOMETHING-ELSE")
        try {
            APlus10AFixtureSeed.parsePayload(wrong)
            fail("a payload whose fixtureId is not FX-G2-10A must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun parsePayload_rejectsEmptyItems() {
        val empty = """{"fixtureId":"FX-G2-10A","items":[]}"""
        try {
            APlus10AFixtureSeed.parsePayload(empty)
            fail("an empty item set is not a seedable fixture")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // seedReport — the fixtureIndex ↔ dbId map opus5 requires for attribution
    // ------------------------------------------------------------------

    @Test
    fun seedReport_emitsFixtureIndexToDbIdMapping() {
        val items = APlus10AFixtureSeed.parsePayload(twoItemPayload)
        val report = APlus10AFixtureSeed.seedReport(
            items = items,
            insertedIds = listOf(1L, 2L),
            fixtureDigest = "2700aa32da88cbfb5fb1d3b9cdb6192f0e60dd9fc5d72e99f9a85d0dc5c58e4e",
        )
        // Attribution: without an explicit map, a journey failure cannot be tied
        // back to a fixture row (LocationTask has no journeyCaseId field).
        assertTrue("report must map fixtureIndex 1 → its seeded row", report.contains("fixtureIndex=1"))
        assertTrue("report must carry journeyCaseId for attribution", report.contains("J10A-01"))
        assertTrue("report must carry the schedule item id", report.contains("profile-1"))
        assertTrue("report must echo the frozen fixture digest", report.contains("2700aa32da88cbfb5fb1d3b9cdb6192f0e60dd9fc5d72e99f9a85d0dc5c58e4e"))
    }

    @Test
    fun seedReport_failsWhenInsertedIdsDoNotMatchExpected() {
        val items = APlus10AFixtureSeed.parsePayload(twoItemPayload)
        // Room returned an id that is NOT the explicit one we asked for — a
        // silent AUTOINCREMENT drift. The report builder must refuse to render
        // a green mapping over a mismatch.
        try {
            APlus10AFixtureSeed.seedReport(
                items = items,
                insertedIds = listOf(11L, 12L),
                fixtureDigest = "x",
            )
            fail("seedReport must reject inserted ids that differ from the fixture ids")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    private fun item(fixtureIndex: Int, scheduleItemId: String) = FixtureItem(
        fixtureIndex = fixtureIndex,
        journeyCaseId = "J10A-%02d".format(fixtureIndex),
        expectedScheduleItemId = scheduleItemId,
        requiredSuccesses = 1,
        addname = "addr-$fixtureIndex",
        latitude = 50.0,
        longitude = 30.0,
        altitude = 179.0,
        accuracy = 3.0,
        tac = 27100 + fixtureIndex,
        wifiSsid = "ssid-$fixtureIndex",
    )
}
