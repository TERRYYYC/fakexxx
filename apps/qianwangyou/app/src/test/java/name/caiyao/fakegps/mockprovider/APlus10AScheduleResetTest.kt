package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.mockprovider.APlus10AScheduleReset.PriorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PR #62 R3 P1-2 + R4 P1-2 — monotonic schedule reset for prepare_10a.
 *
 * R3 established WHY not clear(): a wholesale clear lets the next boot
 * re-Initialize at version 1 — a rollback violating M-AD-24 / spec
 * L1895/2056 (every reinit advances V → V+1).
 *
 * R4 established two further holes in the first fix:
 *  - the raw read-modify-write is OUTSIDE the production owner fence: a
 *    concurrent completeAndAdvance between the seed's read (V7) and write
 *    (V8) makes the seed reuse production's generation number. The seed must
 *    therefore only run under PROVEN QUIESCENCE (no in-flight lease, owner
 *    service not running), bracketed before AND after the write — modeled
 *    here as the pure [APlus10AScheduleReset.quiescenceMismatch].
 *  - an absent version key was defaulted to 0 even when other schedule keys
 *    survived, laundering a PARTIAL old store into a fresh version-1
 *    generation. Prior state is now classified Pristine / Complete / Partial
 *    and Partial is fail-closed.
 */
class APlus10AScheduleResetTest {

    private val tenIds = (1..10).map { "profile-$it" }
    private val allKeys = APlus10AScheduleReset.GENERATION_KEYS

    // ------------------------------------------------------------------
    // classifyPriorState — Pristine / Complete / Partial (R4: no laundering)
    // ------------------------------------------------------------------

    @Test
    fun classify_allAbsent_isPristine() {
        val prior = APlus10AScheduleReset.classifyPriorState(presentKeys = emptySet(), storedVersion = null)
        assertEquals(PriorState.Pristine, prior)
    }

    @Test
    fun classify_allPresent_isCompleteWithVersion() {
        val prior = APlus10AScheduleReset.classifyPriorState(presentKeys = allKeys.toSet(), storedVersion = 7L)
        assertEquals(PriorState.Complete(7L), prior)
    }

    @Test
    fun classify_partialStore_isPartialNamingMissingKeys() {
        // scheduleId/items/pointer survive but the version key is gone — the
        // exact R4 counterexample that was previously laundered into V=1.
        val present = allKeys.toSet() - APlus10AScheduleReset.KEY_SCHEDULE_VERSION
        val prior = APlus10AScheduleReset.classifyPriorState(presentKeys = present, storedVersion = null)
        val partial = prior as? PriorState.Partial ?: error("expected Partial, got $prior")
        assertTrue(partial.missingKeys.contains(APlus10AScheduleReset.KEY_SCHEDULE_VERSION))
    }

    @Test
    fun classify_versionPresentButUnreadable_isPartial() {
        // Key present in the prefs but the stored value could not be read as a
        // long — corrupt, never "0".
        val prior = APlus10AScheduleReset.classifyPriorState(presentKeys = allKeys.toSet(), storedVersion = null)
        assertTrue(prior is PriorState.Partial)
    }

    // ------------------------------------------------------------------
    // plan() — strict monotonicity; Partial and overflow fail closed
    // ------------------------------------------------------------------

    @Test
    fun pristine_startsAtVersionOne() {
        val plan = APlus10AScheduleReset.plan(PriorState.Pristine, tenIds)
        assertEquals(1L, plan.scheduleVersion)
        assertEquals("profile-1", plan.currentItemId)
        assertEquals(false, plan.exhausted)
        assertEquals(0L, plan.advanceCount)
    }

    @Test
    fun completeMidRun_bumpsNotRollsBack() {
        val plan = APlus10AScheduleReset.plan(PriorState.Complete(7L), tenIds)
        assertEquals("V → V+1, never back to 1 (M-AD-24 / spec L1895/2056)", 8L, plan.scheduleVersion)
        assertEquals("profile-1", plan.currentItemId)
    }

    @Test
    fun completeExhausted_clearsExhaustedWithTheBump() {
        val plan = APlus10AScheduleReset.plan(PriorState.Complete(12L), tenIds)
        assertEquals(13L, plan.scheduleVersion)
        assertEquals(false, plan.exhausted)
    }

    @Test
    fun monotonicity_holdsForArbitraryCompleteVersions() {
        listOf(1L, 2L, 41L, 999L).forEach { v ->
            val plan = APlus10AScheduleReset.plan(PriorState.Complete(v), tenIds)
            assertTrue(plan.scheduleVersion > v)
            assertEquals(v + 1, plan.scheduleVersion)
        }
    }

    @Test
    fun partialPriorState_isRejectedNotLaundered() {
        try {
            APlus10AScheduleReset.plan(
                PriorState.Partial(missingKeys = listOf(APlus10AScheduleReset.KEY_SCHEDULE_VERSION)),
                tenIds,
            )
            fail("a partial pre-state must be rejected, never laundered into a fresh generation")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains(APlus10AScheduleReset.KEY_SCHEDULE_VERSION))
        }
    }

    @Test
    fun nonPositiveStoredVersion_isRejected() {
        listOf(0L, -3L).forEach { v ->
            try {
                APlus10AScheduleReset.plan(PriorState.Complete(v), tenIds)
                fail("a complete store with version $v is corrupt and must be rejected")
            } catch (e: IllegalArgumentException) {
                // expected — a COMPLETE store always has version >= 1
            }
        }
    }

    @Test
    fun overflowRunway_reservesTenHops_failsClosedNotWraps() {
        // R5 P2: +1 alone is not enough headroom — a full A-block run performs
        // up to 10 advances AFTER the seed, each bumping the version. The seed
        // must guarantee the whole 10-hop runway cannot wrap.
        try {
            APlus10AScheduleReset.plan(PriorState.Complete(Long.MAX_VALUE), tenIds)
            fail("Long.MAX_VALUE must fail closed, not wrap negative")
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            APlus10AScheduleReset.plan(PriorState.Complete(Long.MAX_VALUE - 5), tenIds)
            fail("a version without full 10-hop runway must fail closed")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("runway"))
        }
        // The largest version WITH a full runway still plans normally.
        val edge = Long.MAX_VALUE - 1 - APlus10AScheduleReset.POST_SEED_HOP_RUNWAY
        assertEquals(edge + 1, APlus10AScheduleReset.plan(PriorState.Complete(edge), tenIds).scheduleVersion)
    }

    @Test
    fun emptyItemIds_isRejected() {
        try {
            APlus10AScheduleReset.plan(PriorState.Pristine, emptyList())
            fail("a reset to zero items is not a seedable schedule")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // quiescenceMismatch — the owner-fence precondition (R4 P1-2, R5 hardened)
    // ------------------------------------------------------------------

    @Test
    fun quiescence_serviceRunning_isMismatch() {
        val m = APlus10AScheduleReset.quiescenceMismatch(
            blockingLeaseState = null, ownerServiceRunning = true, advancePendingPresent = false)
        assertNotNull("a live owner service can reinit/advance concurrently — must refuse", m)
        assertTrue(m!!.contains("service"))
    }

    @Test
    fun quiescence_serviceLivenessUnknown_isMismatch_failClosed() {
        // R5 P2: an UNKNOWN liveness (ActivityManager threw) previously mapped
        // to false = fail-OPEN. Unknown must refuse.
        val m = APlus10AScheduleReset.quiescenceMismatch(
            blockingLeaseState = null, ownerServiceRunning = null, advancePendingPresent = false)
        assertNotNull("unknown owner liveness must fail closed, never be treated as quiescent", m)
        assertTrue(m!!.contains("unknown"))
    }

    @Test
    fun quiescence_inFlightLease_isMismatch() {
        listOf("ACQUIRING", "ACTIVE", "RELEASING").forEach { state ->
            val m = APlus10AScheduleReset.quiescenceMismatch(
                blockingLeaseState = state, ownerServiceRunning = false, advancePendingPresent = false)
            assertNotNull("in-flight lease $state means the owner may advance concurrently", m)
            assertTrue(m!!.contains(state))
        }
    }

    @Test
    fun quiescence_nonReleasedBlockingStates_areMismatch_failClosed() {
        // R5 P2: REVOKED / RELEASE_INCOMPLETE / EXPIRED were waved through as
        // "at rest". They still BLOCK new applies and reference the OLD
        // generation identities; boot recovery also mutates them. Only a fully
        // converged store (no lease, or RELEASED) may be seeded over.
        listOf("REVOKED", "RELEASE_INCOMPLETE", "EXPIRED").forEach { state ->
            val m = APlus10AScheduleReset.quiescenceMismatch(
                blockingLeaseState = state, ownerServiceRunning = false, advancePendingPresent = false)
            assertNotNull("non-RELEASED blocking state $state must refuse the seed", m)
            assertTrue(m!!.contains(state))
        }
    }

    @Test
    fun quiescence_advancePendingSlot_isMismatch() {
        // R5 P1: a durable ADVANCE_PENDING slot is a committed advance whose
        // external mutation has not finished — the next fenced entry/boot
        // REPLAYS it on top of whatever schedule exists, including a fresh
        // seed. Must refuse until the owner settles it.
        val m = APlus10AScheduleReset.quiescenceMismatch(
            blockingLeaseState = null, ownerServiceRunning = false, advancePendingPresent = true)
        assertNotNull("a durable pending advance would replay onto the new seed — must refuse", m)
        assertTrue(m!!.contains("ADVANCE_PENDING"))
    }

    @Test
    fun quiescence_convergedStates_pass() {
        listOf(null, "RELEASED").forEach { state ->
            assertNull(
                "a converged lease state ($state) with owner down and no pending advance is seedable",
                APlus10AScheduleReset.quiescenceMismatch(
                    blockingLeaseState = state, ownerServiceRunning = false, advancePendingPresent = false),
            )
        }
    }

    // ------------------------------------------------------------------
    // itemIds wire format + readback verification (unchanged contracts)
    // ------------------------------------------------------------------

    @Test
    fun encodedItemIds_isTheProductionJsonArrayShape() {
        assertEquals(
            """["profile-1","profile-2","profile-3","profile-4","profile-5","profile-6","profile-7","profile-8","profile-9","profile-10"]""",
            APlus10AScheduleReset.encodeItemIds(tenIds),
        )
    }

    private fun plan(v: Long = 8L) = APlus10AScheduleReset.plan(PriorState.Complete(v - 1), tenIds)

    @Test
    fun readbackMatch_passes() {
        val expected = plan()
        assertNull(APlus10AScheduleReset.verifyReadback(read = expected, expected = expected))
    }

    @Test
    fun readbackVersionMismatch_failsLoud() {
        val expected = plan(8L)
        val mismatch = APlus10AScheduleReset.verifyReadback(read = expected.copy(scheduleVersion = 1L), expected = expected)
        assertNotNull(mismatch)
        assertTrue(mismatch!!.contains("scheduleVersion"))
    }

    @Test
    fun readbackPointerMismatch_failsLoud() {
        val expected = plan()
        assertNotNull(APlus10AScheduleReset.verifyReadback(read = expected.copy(currentItemId = "profile-5"), expected = expected))
    }

    @Test
    fun readbackExhaustedMismatch_failsLoud() {
        val expected = plan()
        assertNotNull(APlus10AScheduleReset.verifyReadback(read = expected.copy(exhausted = true), expected = expected))
    }

    @Test
    fun readbackAbsent_failsLoud() {
        assertNotNull(APlus10AScheduleReset.verifyReadback(read = null, expected = plan()))
    }

    @Test
    fun readbackItemIdsMismatch_failsLoud() {
        val expected = plan()
        assertNotNull(APlus10AScheduleReset.verifyReadback(read = expected.copy(itemIds = expected.itemIds.dropLast(1)), expected = expected))
    }
}
