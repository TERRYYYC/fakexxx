package com.example.cellrebelauto.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-key recovery / idempotency + crash-window + schedule-advance consumer gate
 * (Issue #5 Task 4, areas 2 & 3).
 *
 * AREA 3 (RED, INV-13): [RecoveryCoordinator.reconcile] must replay the same idempotency key +
 * canonical digest and reconcile the durable receipt; the skeleton returns INSUFFICIENT_EVIDENCE
 * unconditionally, so the "recoverable ⇒ ADVANCED/REPLAYED" assertion FAILS until GREEN.
 *
 * AREA 2 (crash windows): reconcile must be a pure function of durable state — a fresh instance
 * (post-crash, no in-memory cache) reconstructs the same outcome. Combined with the recoverable
 * signal this is the RED evidence that reconcile must READ durable state, not guess.
 *
 * Schedule-advance consumer gate (Issue #5 addendum): without a durable receipt Auto never assumes
 * the schedule advanced; with a receipt Auto still independently observes and matches the effective
 * intent/revision before the next attempt.
 *
 * # 相同 key 恢复/幂等（RED）+ 崩溃窗口（纯 durable 函数）+ schedule-advance 消费门
 */
class RecoveryIdempotencyRedTest {

    // ---- AREA 3: same-key recovery / idempotency ----

    @Test
    fun `reconcile distinguishes a recoverable replay from insufficient evidence`() {
        val rc = RecoveryCoordinator()
        // RED (INV-13): skeleton returns INSUFFICIENT_EVIDENCE for every attempt. GREEN will consult
        // durable state (idempotency key + canonical digest + durable receipt) to return
        // ADVANCED_TO_RELEASE or REPLAYED_APPLY when recoverable. Until then this fails.
        val outcome = rc.reconcile(attemptId = 42L)
        assertTrue(
            "a recoverable attempt must advance or replay-apply, not collapse to INSUFFICIENT_EVIDENCE (got $outcome)",
            outcome == ReconcileOutcome.ADVANCED_TO_RELEASE || outcome == ReconcileOutcome.REPLAYED_APPLY
        )
    }

    @Test
    fun `reconcile replay is idempotent across a crash`() {
        // AREA 2 crash-window: a crash leaves no in-memory state. Two independent instances over the
        // same durable state must agree — replaying the same key yields a stable outcome (INV-13).
        val rc1 = RecoveryCoordinator()
        val first = rc1.reconcile(attemptId = 7L)
        // simulate crash: brand-new instance, identical durable state
        val rc2 = RecoveryCoordinator()
        val afterCrash = rc2.reconcile(attemptId = 7L)
        assertEquals(
            "reconcile must be a pure function of durable state (idempotent across crash)",
            first,
            afterCrash
        )
    }

    // ---- Schedule-advance consumer gate (Issue #5 addendum, §5 boundary) ----

    @Test
    fun `schedule advance without a durable receipt is never assumed`() {
        val rc = RecoveryCoordinator()
        // Passes now (skeleton returns NOT_ADVANCED for all) and stays valid GREEN: without a
        // durable receipt Auto MUST NOT assume the schedule advanced.
        assertEquals(
            ScheduleAdvanceState.NOT_ADVANCED,
            rc.scheduleAdvanced(attemptId = 1L, hasDurableReceipt = false, intentRevisionMatches = true)
        )
    }

    @Test
    fun `schedule advance with a durable receipt and matching intent is ADVANCED`() {
        val rc = RecoveryCoordinator()
        // RED: skeleton returns NOT_ADVANCED even when a durable receipt exists AND an independent
        // observe() matches the effective intent/revision. GREEN must return ADVANCED here.
        assertEquals(
            "durable receipt + matching intent revision ⇒ ADVANCED",
            ScheduleAdvanceState.ADVANCED,
            rc.scheduleAdvanced(attemptId = 1L, hasDurableReceipt = true, intentRevisionMatches = true)
        )
    }

    @Test
    fun `schedule advance with a receipt but mismatching intent is NOT_ADVANCED`() {
        val rc = RecoveryCoordinator()
        // A durable receipt alone is insufficient — the independent observe() must match. Passes
        // now (skeleton NOT_ADVANCED) and stays valid GREEN.
        assertEquals(
            ScheduleAdvanceState.NOT_ADVANCED,
            rc.scheduleAdvanced(attemptId = 1L, hasDurableReceipt = true, intentRevisionMatches = false)
        )
    }
}
