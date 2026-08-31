package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PR #62 review R3 P1-2 — monotonic schedule reset for prepare_10a.
 *
 * The previous seed cleared qwy_schedule_v1 wholesale, so the next
 * ScheduleReinitPolicy initialization wrote scheduleVersion **1** — a version
 * ROLLBACK. Canonical spec L1895/2056 and M-AD-24 require every schedule
 * reinitialization (including a same-topology exhausted reset) to advance
 * V → V+1: clearing permits old (schedule, item, version) identities from a
 * previous run to collide with the new one, making stale CAS preconditions
 * and completion proofs reusable across generations.
 *
 * The reset is therefore computed as a PURE plan (this object) and written as
 * ONE atomic prefs commit by the seeder: versionAfter = versionBefore + 1
 * (strictly monotonic for every input), pointer at profile-1, exhausted
 * cleared, advance count zeroed — and then READ BACK; any mismatch fails the
 * seed. On the next provider boot, initFromProfileIds sees the same item set
 * with a present scheduleId and takes the NoOp rule, PRESERVING the V+1
 * generation — the monotonic write and the production policy compose instead
 * of fighting.
 */
class APlus10AScheduleResetTest {

    private val tenIds = (1..10).map { "profile-$it" }

    // ------------------------------------------------------------------
    // plan() — strict monotonicity on every path
    // ------------------------------------------------------------------

    @Test
    fun freshStore_startsAtVersionOne() {
        val plan = APlus10AScheduleReset.plan(existingVersion = 0L, itemIds = tenIds)
        assertEquals(1L, plan.scheduleVersion)
        assertEquals("profile-1", plan.currentItemId)
        assertEquals(false, plan.exhausted)
        assertEquals(0L, plan.advanceCount)
        assertEquals(tenIds, plan.itemIds)
    }

    @Test
    fun midRunStore_bumpsNotRollsBack() {
        // A previous run advanced to version 7 with the pointer mid-schedule.
        val plan = APlus10AScheduleReset.plan(existingVersion = 7L, itemIds = tenIds)
        assertEquals("V → V+1, never back to 1 (M-AD-24 / spec L1895/2056)", 8L, plan.scheduleVersion)
        assertEquals("pointer must reset to the first item", "profile-1", plan.currentItemId)
    }

    @Test
    fun exhaustedStore_clearsExhaustedWithTheBump() {
        // Terminal exhausted=true from the old run: the clear MUST ride a bump
        // (the exact M-AD-24 pairing), never a rollback or a same-version write.
        val plan = APlus10AScheduleReset.plan(existingVersion = 12L, itemIds = tenIds)
        assertEquals(13L, plan.scheduleVersion)
        assertEquals(false, plan.exhausted)
    }

    @Test
    fun monotonicity_holdsForArbitraryVersions() {
        listOf(0L, 1L, 2L, 41L, 999L).forEach { v ->
            val plan = APlus10AScheduleReset.plan(existingVersion = v, itemIds = tenIds)
            assertTrue(
                "versionAfter must be strictly greater than every prior version (got ${plan.scheduleVersion} for $v)",
                plan.scheduleVersion > v,
            )
            assertEquals(v + 1, plan.scheduleVersion)
        }
    }

    @Test
    fun negativeStoredVersion_isRejectedNotLaundered() {
        // A corrupt store must fail loud, not be "fixed" into a plausible value.
        try {
            APlus10AScheduleReset.plan(existingVersion = -3L, itemIds = tenIds)
            org.junit.Assert.fail("negative stored version is corrupt state and must be rejected")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun emptyItemIds_isRejected() {
        try {
            APlus10AScheduleReset.plan(existingVersion = 3L, itemIds = emptyList())
            org.junit.Assert.fail("a reset to zero items is not a seedable schedule")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // itemIds wire format — must match QwyScheduleStore's JSON array codec
    // ------------------------------------------------------------------

    @Test
    fun encodedItemIds_isTheProductionJsonArrayShape() {
        val encoded = APlus10AScheduleReset.encodeItemIds(tenIds)
        // QwyScheduleStore decodes with org.json.JSONArray; the canonical
        // JSONArray rendering of these strings is exactly this.
        assertEquals("""["profile-1","profile-2","profile-3","profile-4","profile-5","profile-6","profile-7","profile-8","profile-9","profile-10"]""", encoded)
    }

    // ------------------------------------------------------------------
    // verifyReadback() — the written generation must be read back verbatim
    // ------------------------------------------------------------------

    private fun plan(v: Long = 8L) = APlus10AScheduleReset.plan(existingVersion = v - 1, itemIds = tenIds)

    @Test
    fun readbackMatch_passes() {
        val expected = plan()
        assertNull(APlus10AScheduleReset.verifyReadback(read = expected, expected = expected))
    }

    @Test
    fun readbackVersionMismatch_failsLoud() {
        val expected = plan(8L)
        val drifted = expected.copy(scheduleVersion = 1L) // the rollback shape
        val mismatch = APlus10AScheduleReset.verifyReadback(read = drifted, expected = expected)
        assertNotNull("a version rollback surviving the write must fail the seed", mismatch)
        assertTrue(mismatch!!.contains("scheduleVersion"))
    }

    @Test
    fun readbackPointerMismatch_failsLoud() {
        val expected = plan()
        val drifted = expected.copy(currentItemId = "profile-5") // mid-run pointer survived
        val mismatch = APlus10AScheduleReset.verifyReadback(read = drifted, expected = expected)
        assertNotNull("a surviving mid-run pointer must fail the seed", mismatch)
        assertTrue(mismatch!!.contains("currentItemId"))
    }

    @Test
    fun readbackExhaustedMismatch_failsLoud() {
        val expected = plan()
        val drifted = expected.copy(exhausted = true) // terminal state survived
        val mismatch = APlus10AScheduleReset.verifyReadback(read = drifted, expected = expected)
        assertNotNull("a surviving exhausted=true must fail the seed", mismatch)
        assertTrue(mismatch!!.contains("exhausted"))
    }

    @Test
    fun readbackAbsent_failsLoud() {
        // Commit claimed success but nothing is readable — partial/failed write.
        val mismatch = APlus10AScheduleReset.verifyReadback(read = null, expected = plan())
        assertNotNull("an unreadable store after commit must fail the seed", mismatch)
    }

    @Test
    fun readbackItemIdsMismatch_failsLoud() {
        val expected = plan()
        val drifted = expected.copy(itemIds = expected.itemIds.dropLast(1))
        val mismatch = APlus10AScheduleReset.verifyReadback(read = drifted, expected = expected)
        assertNotNull("a partial item list after commit must fail the seed", mismatch)
        assertTrue(mismatch!!.contains("itemIds"))
    }
}
