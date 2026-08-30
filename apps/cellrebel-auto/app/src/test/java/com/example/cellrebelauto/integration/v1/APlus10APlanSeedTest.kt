package com.example.cellrebelauto.integration.v1

import com.example.cellrebelauto.integration.v1.APlus10APlanSeed.FixtureItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure-logic unit test for the Auto-side G2 §5A plan seeder (fixture FX-G2-10A).
 *
 * WHY THIS FILE EXISTS
 * --------------------
 * §5A needs Auto to hold a 10-task plan whose trust targets are the SAME
 * coordinates the provider mocks. Two invariants must not silently break:
 *
 *   1. ATTRIBUTION — [com.example.cellrebelauto.model.plan.LocationTask] has no
 *      journeyCaseId field, so the ONLY link from a run outcome back to a
 *      fixture journey is the seed report's fixtureIndex ↔ taskId map. A seeder
 *      that inserts tasks without emitting that map makes every failure
 *      unattributable (opus5's explicit requirement).
 *   2. COORDINATE CARRY — the A-block trust predicate (TrustPolicy) compares the
 *      provider's observed effective coordinates against `task.latitude/longitude`.
 *      If the seeder drops coordinates, every trusted-completion check fails the
 *      haversine leg — a harness-induced false red on all 10 journeys. (This was
 *      gap⑤: fixture v1's `autoSideConsumes` line read as if coordinates never
 *      reach Auto — wire semantics written as consumption semantics. Fixture v2
 *      corrected the wording, registered not silently rewritten; see the
 *      fixture's `v2ChangeLog`. The seeder consumed coordinates either way.)
 *
 * Ordering is load-bearing too: Auto executes tasks in (priority, csvRow) order
 * and the provider advances profile-1..profile-10 in id order. Both are seeded
 * from the fixture items in array order, so task[i] pairs with provider item[i].
 */
class APlus10APlanSeedTest {

    private val twoItemPayload = """
        {
          "fixtureId": "FX-G2-10A",
          "items": [
            {
              "fixtureIndex": 1, "journeyCaseId": "J10A-01",
              "expectedScheduleItemId": "profile-1", "requiredSuccesses": 2,
              "latitude": 50.4501, "longitude": 30.5234
            },
            {
              "fixtureIndex": 2, "journeyCaseId": "J10A-02",
              "expectedScheduleItemId": "profile-2", "requiredSuccesses": 1,
              "latitude": 50.4489, "longitude": 30.5133
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsePayload_readsItemsAndValidatesFixtureId() {
        val items = APlus10APlanSeed.parsePayload(twoItemPayload)
        assertEquals(2, items.size)
        assertEquals(1, items[0].fixtureIndex)
        assertEquals("J10A-01", items[0].journeyCaseId)
        assertEquals(2, items[0].requiredSuccesses)
        assertEquals(50.4501, items[0].latitude, 1e-9)
        assertEquals(30.5234, items[0].longitude, 1e-9)
    }

    @Test
    fun parsePayload_rejectsWrongFixtureId() {
        try {
            APlus10APlanSeed.parsePayload(twoItemPayload.replace("FX-G2-10A", "OTHER"))
            fail("wrong fixtureId must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun toPlan_totalRequiredSuccessesIsTheSum() {
        val items = APlus10APlanSeed.parsePayload(twoItemPayload)
        val plan = APlus10APlanSeed.toPlan(items, globalBufferSeconds = 60)
        assertEquals(2, plan.totalRows)
        assertEquals("sum of per-item requiredSuccesses (2+1)", 3, plan.totalRequiredSuccesses)
        assertEquals("FX-G2-10A", plan.sourceFileName)
    }

    @Test
    fun toTasks_carriesCoordinatesAndRequiredSuccessesInOrder() {
        val items = APlus10APlanSeed.parsePayload(twoItemPayload)
        val tasks = APlus10APlanSeed.toTasks(items)
        assertEquals(2, tasks.size)
        // Coordinate carry — the trust target. Dropping this false-reds all 10.
        assertEquals(50.4501, tasks[0].latitude, 1e-9)
        assertEquals(30.5234, tasks[0].longitude, 1e-9)
        assertEquals(2, tasks[0].requiredSuccesses)
        // Order binding: csvRow follows fixtureIndex so execution order == fixture order.
        assertEquals(1, tasks[0].csvRow)
        assertEquals(2, tasks[1].csvRow)
        assertTrue("csvRow must be strictly increasing with fixture order",
            tasks[0].csvRow < tasks[1].csvRow)
    }

    @Test
    fun seedReport_emitsFixtureIndexToTaskIdMap() {
        val items = APlus10APlanSeed.parsePayload(twoItemPayload)
        val report = APlus10APlanSeed.seedReport(
            items = items,
            planId = 7L,
            taskIds = listOf(101L, 102L),
            fixtureDigest = "2700aa32da88cbfb5fb1d3b9cdb6192f0e60dd9fc5d72e99f9a85d0dc5c58e4e",
        )
        assertTrue("must bind fixtureIndex 1 to its task id", report.contains("fixtureIndex=1"))
        assertTrue("must carry taskId for attribution", report.contains("taskId=101"))
        assertTrue("must carry journeyCaseId (LocationTask has no such field)", report.contains("J10A-01"))
        assertTrue("must carry planId", report.contains("planId=7"))
        assertTrue("must echo the frozen fixture digest",
            report.contains("2700aa32da88cbfb5fb1d3b9cdb6192f0e60dd9fc5d72e99f9a85d0dc5c58e4e"))
    }

    @Test
    fun seedReport_failsWhenTaskCountDoesNotMatch() {
        val items = APlus10APlanSeed.parsePayload(twoItemPayload)
        try {
            APlus10APlanSeed.seedReport(items, planId = 7L, taskIds = listOf(101L), fixtureDigest = "x")
            fail("a taskId list shorter than the fixture must be rejected — unattributable seed")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Suppress("unused")
    private fun item(i: Int) = FixtureItem(i, "J10A-%02d".format(i), "profile-$i", 1, 50.0, 30.0)
}
