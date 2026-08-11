package com.example.cellrebelauto.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-key recovery / idempotency + crash-window + schedule-advance consumer gate
 * (Issue #5 Task 4, areas 2 & 3).
 *
 * TRUSTWORTHY RED (INV-13/15): these tests assert DURABLE EFFECTS through the [DurableRecoveryLog]
 * seam — apply count, receipt presence, conflict key — NOT the coordinator's return value alone.
 * The skeleton [RecoveryCoordinator] ignores its injected log entirely, so every durable-effect
 * assertion stays RED until GREEN actually reads/writes the log. A constant-return "false oracle"
 * GREEN that never touches the log cannot pass either, because the effect assertions (applyCount,
 * receiptFor, lastConflictKey) are driven by the fake, not the return value.
 *
 * AREA 3 (idempotency/conflict, INV-13): reconcile must replay the same idempotency key + canonical
 * digest; same-key/same-digest replays are a no-op (at-most-once effect); same-key/different-digest
 * is an [ReconcileOutcome.IDEMPOTENCY_CONFLICT] and the prior receipt is preserved.
 *
 * AREA 2 (crash windows): reconcile must be a pure function of durable state — a fresh coordinator
 * instance (post-crash) over the SAME durable log reconstructs the same outcome and never
 * double-applies.
 *
 * Schedule-advance consumer gate (Issue #5 addendum / §5 boundary): without a durable receipt Auto
 * never assumes the schedule advanced; with a receipt Auto still independently observes and matches
 * the effective intent/revision.
 *
 * # 相同 key 恢复/幂等（RED，断言 durable effect）+ 崩溃窗口（同 log 新建 coordinator）+ schedule-advance 门
 */
class RecoveryIdempotencyRedTest {

    private fun bucket(attemptId: Long) = "attempt-$attemptId"

    // ---- AREA 3: same-key recovery / idempotency / conflict ----

    @Test
    fun `reconcile of a fresh attempt records exactly one durable apply and advances to release`() {
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(log)
        val outcome = rc.reconcile(
            attemptId = 42L,
            idempotencyKey = "k-42",
            requestDigest = "digest-v1",
            now = 1000L
        )
        // RED (INV-15): skeleton returns INSUFFICIENT_EVIDENCE and never writes the log. GREEN must
        // record exactly one apply for this attempt's bucket and return ADVANCED_TO_RELEASE.
        assertEquals(
            "a fresh recoverable attempt must advance to release (got $outcome)",
            ReconcileOutcome.ADVANCED_TO_RELEASE,
            outcome
        )
        assertEquals(
            "exactly one durable apply must be recorded for the attempt (at-most-once effect)",
            1,
            log.applyCount(bucket(42L))
        )
        val receipt = log.receiptFor("k-42")
        assertNotNull("a durable receipt must exist for the idempotency key", receipt)
        assertEquals("digest-v1", receipt?.requestDigest)
    }

    @Test
    fun `reconcile replaying the same key and digest is a no-op effect and returns REPLAYED_APPLY`() {
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(log)
        rc.reconcile(42L, "k-42", "digest-v1", 1000L)
        // Replay the SAME idempotency key + canonical digest.
        val outcome = rc.reconcile(42L, "k-42", "digest-v1", 2000L)
        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must return REPLAYED_APPLY.
        assertEquals(
            "same-key/same-digest replay must be REPLAYED_APPLY (got $outcome)",
            ReconcileOutcome.REPLAYED_APPLY,
            outcome
        )
        // At-most-once: the underlying apply effect ran exactly once across both calls (INV-13).
        assertEquals(
            "replaying the same key+digest must NOT re-apply (at-most-once)",
            1,
            log.applyCount(bucket(42L))
        )
    }

    @Test
    fun `reconcile with the same key but a different digest is a conflict and preserves the prior receipt`() {
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(log)
        rc.reconcile(42L, "k-42", "digest-v1", 1000L)
        // Same idempotency key, DIFFERENT canonical request digest (INV-13).
        val outcome = rc.reconcile(42L, "k-42", "digest-v2", 2000L)
        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must surface the conflict.
        assertEquals(
            "same-key/different-digest must be IDEMPOTENCY_CONFLICT (got $outcome)",
            ReconcileOutcome.IDEMPOTENCY_CONFLICT,
            outcome
        )
        // The durable store must record the conflict on the key the GREEN implementation reads.
        assertEquals(
            "the conflict key must be recorded in the durable log",
            "k-42",
            log.lastConflictKey
        )
        // Prior receipt is preserved (not overwritten by the conflicting request).
        assertEquals(
            "the prior receipt must survive the conflict unchanged",
            "digest-v1",
            log.receiptFor("k-42")?.requestDigest
        )
        assertEquals("the conflicting apply must NOT run", 1, log.applyCount(bucket(42L)))
    }

    // ---- AREA 2: crash windows (reconcile is a pure function of durable state) ----

    @Test
    fun `a crash between reconciles does not double-apply - new coordinator over the same durable log replays`() {
        val log = FakeDurableRecoveryLog()
        // First coordinator records the apply, then the process crashes (in-memory state lost).
        RecoveryCoordinator(log).reconcile(7L, "k-7", "digest-7", 1000L)
        val appliedBeforeCrash = log.applyCount(bucket(7L))

        // Brand-new coordinator over the SAME durable log = post-crash restart.
        val afterCrash = RecoveryCoordinator(log).reconcile(7L, "k-7", "digest-7", 3000L)

        assertEquals(
            "post-crash reconcile of an already-applied attempt must REPLAYED_APPLY (got $afterCrash)",
            ReconcileOutcome.REPLAYED_APPLY,
            afterCrash
        )
        // At-most-once across a crash: the apply effect count must not increase (INV-13/15).
        assertEquals(
            "a crash must not cause a second durable apply",
            appliedBeforeCrash,
            log.applyCount(bucket(7L))
        )
    }

    @Test
    fun `a crash before any apply leaves no receipt - post-crash reconcile is a fresh advance`() {
        val log = FakeDurableRecoveryLog()
        // No prior reconcile (the process crashed before recording anything).
        assertNull("no receipt before any successful apply", log.receiptFor("k-9"))

        val outcome = RecoveryCoordinator(log).reconcile(9L, "k-9", "digest-9", 1000L)
        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must treat a durable-empty attempt as
        // a fresh advance (crash-before-write ⇒ no side effect happened ⇒ safe to apply now).
        assertEquals(
            "crash-before-write must advance as a fresh apply (got $outcome)",
            ReconcileOutcome.ADVANCED_TO_RELEASE,
            outcome
        )
        assertEquals(1, log.applyCount(bucket(9L)))
    }

    // ---- Schedule-advance consumer gate (Issue #5 addendum, §5 boundary) ----

    @Test
    fun `schedule advance without a durable receipt is never assumed`() {
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(log)
        // No receipt seeded for "k-sched". Passes now (skeleton NOT_ADVANCED) and stays valid GREEN:
        // without a durable receipt Auto MUST NOT assume the schedule advanced.
        assertEquals(
            ScheduleAdvanceState.NOT_ADVANCED,
            rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", intentRevisionMatches = true, now = 1000L)
        )
        assertNull(log.receiptFor("k-sched"))
    }

    @Test
    fun `schedule advance with a durable receipt and matching intent is ADVANCED`() {
        val log = FakeDurableRecoveryLog()
        log.seedReceipt("k-sched", "digest-sched", "RELEASED", 500L)
        val rc = RecoveryCoordinator(log)
        // RED: skeleton returns NOT_ADVANCED even though a durable receipt exists AND an independent
        // observe() matches the effective intent/revision. GREEN must read the receipt and ADVANCE.
        assertEquals(
            "durable receipt + matching intent revision ⇒ ADVANCED",
            ScheduleAdvanceState.ADVANCED,
            rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", intentRevisionMatches = true, now = 1000L)
        )
    }

    @Test
    fun `schedule advance with a receipt but mismatching intent is NOT_ADVANCED`() {
        val log = FakeDurableRecoveryLog()
        log.seedReceipt("k-sched", "digest-sched", "RELEASED", 500L)
        val rc = RecoveryCoordinator(log)
        // A durable receipt alone is insufficient — the independent observe() must match. Passes now
        // (skeleton NOT_ADVANCED) and stays valid GREEN.
        assertEquals(
            "receipt but mismatching intent revision ⇒ NOT_ADVANCED",
            ScheduleAdvanceState.NOT_ADVANCED,
            rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", intentRevisionMatches = false, now = 1000L)
        )
    }

    @Test
    fun `schedule advance never writes the log - it is a read-only consumer gate`() {
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(log)
        rc.scheduleAdvanced(1L, "k-sched", intentRevisionMatches = true, now = 1000L)
        // The consumer gate must not mint receipts as a side effect (no fake trust).
        assertTrue(
            "scheduleAdvanced must not record any apply",
            log.applyCount(bucket(1L)) == 0 && log.receiptFor("k-sched") == null
        )
    }
}
