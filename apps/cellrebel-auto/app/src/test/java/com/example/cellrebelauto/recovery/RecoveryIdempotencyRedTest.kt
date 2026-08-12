package com.example.cellrebelauto.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Same-key recovery / idempotency + crash windows + schedule-advance consumer gate
 * (Issue #5 Task 4, areas 2 & 3) — Sol round-3 Finding 2 rewrite.
 *
 * TRUSTWORTHY RED: these tests assert DURABLE EFFECTS through TWO separated seams — the
 * [RecordingExternalApplyExecutor] (the external provider call; owns the at-most-once EFFECT counter)
 * and the [FakeDurableRecoveryLog] (pure receipt + checkpoint storage). The skeleton
 * [RecoveryCoordinator] ignores both, so every assertion that needs a real apply / receipt stays RED.
 *
 * Why two seams (not one merged `recordApply`): the round-2 seam executed + wrote-receipt + counted
 * in one sync call, eliminating the most dangerous crash window — **M-CR-02 = "provider already
 * applied, Auto has no receipt"** — and letting a coordinator that NEVER calls a provider green all
 * recovery tests by side-effecting the store. The fix:
 *  - the executor models the provider's OWN idempotency (§6.3.4): a repeat call for an already-applied
 *    key is a no-op EFFECT, returning `providerHadAlreadyApplied=true`;
 *  - the receipt store ONLY stores receipts (§10.1 — no `applyCount`/`lastConflictKey` on the prod
 *    seam; the effect counter lives on the executor's test fake).
 * The crash windows are modelled by the TEST directly setting up the partial durable/executor state
 * each window leaves behind, then driving a fresh coordinator (post-crash restart).
 *
 * The three banked crash windows:
 *  - (a) crash BEFORE the external call      ⇒ no receipt, executor untouched (the fresh-advance case);
 *  - (b) crash AFTER call, BEFORE receipt (M-CR-02) ⇒ provider applied (effect 1), NO receipt —
 *        post-crash reconcile MUST re-invoke the executor (idempotent ⇒ effect stays 1) then record;
 *  - (c) crash AFTER receipt, BEFORE checkpoint ⇒ receipt present — post-crash reconcile is a
 *        REPLAYED_APPLY and MUST NOT re-invoke the executor.
 *
 * # 幂等/冲突 + 三崩溃窗口（含 M-CR-02）+ schedule-advance 门（RED，断言分离的 executor effect + receipt）
 */
class RecoveryIdempotencyRedTest {

    private fun newCoordinator() = RecoveryCoordinator(RecordingExternalApplyExecutor(), FakeDurableRecoveryLog())

    // ---- AREA 3: same-key recovery / idempotency / conflict ----

    @Test
    fun `reconcile of a fresh attempt calls the executor once, records a receipt, and advances to release`() {
        // Crash window (a): nothing pre-seeded — the prior process died before calling anything.
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(executor, log)

        val outcome = rc.reconcile(attemptId = 42L, idempotencyKey = "k-42", requestDigest = "digest-v1", now = 1000L)

        // RED (INV-15): skeleton returns INSUFFICIENT_EVIDENCE and never calls the executor. GREEN must
        // drive the external apply exactly once, record a receipt, and return ADVANCED_TO_RELEASE.
        assertEquals(
            "a fresh recoverable attempt must advance to release (got $outcome)",
            ReconcileOutcome.ADVANCED_TO_RELEASE,
            outcome
        )
        assertEquals(
            "the provider side effect must happen exactly once (at-most-once effect)",
            1,
            executor.effectCount(42L)
        )
        assertEquals("the executor must be invoked exactly once for a fresh apply", 1, executor.invocationCount("k-42"))
        val receipt = log.receiptFor("k-42")
        assertNotNull("a durable receipt must exist for the idempotency key", receipt)
        assertEquals("digest-v1", receipt?.requestDigest)
    }

    @Test
    fun `reconcile replaying the same key and digest does NOT re-invoke the executor and returns REPLAYED_APPLY`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        // Simulate a prior completed apply: provider applied once + a durable receipt exists.
        executor.apply(attemptId = 42L, idempotencyKey = "k-42", requestDigest = "digest-v1", now = 1000L)
        log.seedReceipt(idempotencyKey = "k-42", requestDigest = "digest-v1", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        // Replay the SAME idempotency key + canonical digest (e.g. a post-crash re-reconcile).
        val outcome = rc.reconcile(attemptId = 42L, idempotencyKey = "k-42", requestDigest = "digest-v1", now = 2000L)

        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must short-circuit on the existing receipt
        // (REPLAYED_APPLY) WITHOUT calling the executor again — at-most-once.
        assertEquals(
            "same-key/same-digest replay must be REPLAYED_APPLY (got $outcome)",
            ReconcileOutcome.REPLAYED_APPLY,
            outcome
        )
        assertEquals(
            "replaying a receipt must NOT re-invoke the executor (at-most-once)",
            1,
            executor.invocationCount("k-42")
        )
        assertEquals("the provider effect must stay at one across the replay", 1, executor.effectCount(42L))
    }

    @Test
    fun `reconcile with the same key but a different digest is a conflict, preserves the prior receipt, and never calls the executor`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        // A prior receipt exists for "k-42" with digest-v1.
        log.seedReceipt(idempotencyKey = "k-42", requestDigest = "digest-v1", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        // Same idempotency key, DIFFERENT canonical request digest (INV-13).
        val outcome = rc.reconcile(attemptId = 42L, idempotencyKey = "k-42", requestDigest = "digest-v2", now = 2000L)

        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must surface the conflict.
        assertEquals(
            "same-key/different-digest must be IDEMPOTENCY_CONFLICT (got $outcome)",
            ReconcileOutcome.IDEMPOTENCY_CONFLICT,
            outcome
        )
        assertEquals(
            "a conflicting apply must NOT invoke the executor (no second side effect)",
            0,
            executor.invocationCount("k-42")
        )
        assertEquals(
            "the prior receipt must survive the conflict unchanged",
            "digest-v1",
            log.receiptFor("k-42")?.requestDigest
        )
    }

    // ---- AREA 2: crash windows (reconcile is a pure function of durable + executor state) ----

    @Test
    fun `crash window b - provider applied but no receipt - post-crash reconcile re-invokes the executor idempotently and records a receipt`() {
        // M-CR-02: the prior process called the provider (effect 1) but crashed BEFORE recording the
        // receipt. Post-crash there is NO receipt, yet the provider has already applied.
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 7L, idempotencyKey = "k-7", requestDigest = "digest-7", now = 1000L)
        assertNull("M-CR-02: provider applied but no durable receipt exists", log.receiptFor("k-7"))
        assertEquals("provider already applied once before the crash", 1, executor.effectCount(7L))

        // Brand-new coordinator over the SAME executor + log = post-crash restart.
        val outcome = RecoveryCoordinator(executor, log)
            .reconcile(attemptId = 7L, idempotencyKey = "k-7", requestDigest = "digest-7", now = 3000L)

        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must recover M-CR-02: re-invoke the
        // executor (the provider idempotently no-ops, effect stays 1), record the receipt, advance.
        assertEquals(
            "M-CR-02 post-crash reconcile must advance to release (got $outcome)",
            ReconcileOutcome.ADVANCED_TO_RELEASE,
            outcome
        )
        assertEquals(
            "the executor MUST be re-invoked post-crash (receipt was absent) — invocation goes 1 → 2",
            2,
            executor.invocationCount("k-7")
        )
        assertEquals(
            "the provider effect MUST stay at one across the crash (provider idempotency, at-most-once)",
            1,
            executor.effectCount(7L)
        )
        assertNotNull("a receipt must now be recorded for the recovered attempt", log.receiptFor("k-7"))
    }

    @Test
    fun `crash window c - receipt present before checkpoint - post-crash reconcile is REPLAYED_APPLY and does NOT re-invoke the executor`() {
        // The prior process applied (effect 1) AND recorded a receipt, but crashed before checkpoint.
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 8L, idempotencyKey = "k-8", requestDigest = "digest-8", now = 1000L)
        log.seedReceipt(idempotencyKey = "k-8", requestDigest = "digest-8", outcome = "RELEASED", createdAt = 1000L)

        val outcome = RecoveryCoordinator(executor, log)
            .reconcile(attemptId = 8L, idempotencyKey = "k-8", requestDigest = "digest-8", now = 3000L)

        // RED: skeleton returns INSUFFICIENT_EVIDENCE. GREEN must see the receipt and REPLAY (no re-apply).
        assertEquals(
            "post-crash reconcile with a receipt present must be REPLAYED_APPLY (got $outcome)",
            ReconcileOutcome.REPLAYED_APPLY,
            outcome
        )
        assertEquals(
            "the executor MUST NOT be re-invoked when a receipt already exists",
            1,
            executor.invocationCount("k-8")
        )
        assertEquals("the provider effect must stay at one", 1, executor.effectCount(8L))
        // R5-F2 checkpoint repair (§11.7): window (c) crashed AFTER recording the receipt but BEFORE the
        // checkpoint. Post-crash reconcile MUST repair that missing checkpoint — record it bound to the
        // receipt key — so a SECOND post-ADVANCED crash can still replay. This is the durable half of F2
        // (reconcile holds the log directly; no caller-delegation seam): an attack that returns
        // REPLAYED_APPLY but skips the checkpoint repair fails here. Dormant under the skeleton (the
        // REPLAYED_APPLY assertion above fails first), so it is a durable-effect gate, not a new RED.
        val repairedCheckpoint = log.checkpointFor(8L)
        assertNotNull("window-c reconcile MUST repair the missing checkpoint", repairedCheckpoint)
        assertEquals(
            "the repaired checkpoint must bind to the replayed receipt key",
            "k-8",
            repairedCheckpoint!!.receiptKey
        )
    }

    // ---- Schedule-advance consumer gate (Issue #5 addendum, §5 boundary; Sol round-4 §11.2 F2) ----
    //
    // The round-4 signature injects three ACQUIRERS (observe / receipt-revision / trusted-quota) rather
    // than caller-supplied booleans. The GREEN gate MUST call each acquirer internally to learn its fact
    // and AND the results against a durable receipt. Each test wires RECORDING fakes (a captured call
    // counter) so the ADVANCED case can assert the calls happened — defeating a `receipt≠null ∧ boolean`
    // false oracle that ANDs whatever booleans the test would have passed (§11.2 F2). The three
    // discriminating negatives each flip ONE acquired fact to false and assert NOT_ADVANCED, so a
    // hardcode-ADVANCED bad impl that ignores the acquirers fails them. Together: a cheap attack cannot
    // green the ADVANCED case (zero acquirer calls) nor the negatives (hardcode returns ADVANCED).

    @Test
    fun `schedule advance without a durable receipt is never assumed`() {
        val rc = newCoordinator()
        // No receipt for "k-sched". Passes now (skeleton NOT_ADVANCED, ignores the acquirers) and stays
        // valid GREEN: without a durable receipt Auto MUST NOT assume the schedule advanced regardless
        // of what the acquirers would say.
        val result = rc.scheduleAdvanced(
            attemptId = 1L, idempotencyKey = "k-sched", now = 1000L,
            observe = ObserveIntentAcquirer { _ -> true },
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = TrustedQuotaAcquirer { _ -> true }
        )
        assertEquals(
            "no durable receipt ⇒ NOT_ADVANCED regardless of acquired facts",
            ScheduleAdvanceState.NOT_ADVANCED,
            result
        )
    }

    @Test
    fun `schedule advance with a durable receipt and all three facts confirming is ADVANCED`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-sched", requestDigest = "digest-sched", outcome = "RELEASED", createdAt = 500L)
        val rc = RecoveryCoordinator(executor, log)

        var observeCalls = 0
        var revisionCalls = 0
        var quotaCalls = 0
        var observeAttempt: Long? = null
        var revisionKey: String? = null
        var quotaAttempt: Long? = null
        val observe = ObserveIntentAcquirer { attemptId -> observeAttempt = attemptId; observeCalls++; true }
        val revision = ReceiptRevisionAcquirer { key, _ -> revisionKey = key; revisionCalls++; true }
        val quota = TrustedQuotaAcquirer { attemptId -> quotaAttempt = attemptId; quotaCalls++; true }

        // RED: skeleton returns NOT_ADVANCED and acquires NOTHING (zero calls), even though a durable
        // receipt exists and all three injected facts confirm. GREEN must read the receipt, call all
        // three acquirers, AND the acquired facts, and ADVANCE.
        val result = rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L, observe, revision, quota)
        assertEquals(
            "receipt + observe-match + fresh revision + quota capacity ⇒ ADVANCED",
            ScheduleAdvanceState.ADVANCED,
            result
        )
        // Window (c) durable effect (Sol round-4 Finding 2): ADVANCED is not a bare return value — it
        // MUST persist a checkpoint bound to the receipt so a post-ADVANCED crash can replay. A GREEN
        // that returns ADVANCED without recording a checkpoint fails here. Dormant under the skeleton
        // (the ADVANCED assertion above fails first), so it adds a durable-effect gate, not a new RED.
        val checkpoint = log.checkpointFor(1L)
        assertNotNull("ADVANCED must record a window-c checkpoint", checkpoint)
        assertEquals("the checkpoint must bind to the advanced receipt key", "k-sched", checkpoint!!.receiptKey)
        // §11.2 F2 acquisition gate: the GREEN body must have called each acquirer exactly once — the
        // facts were acquired INSIDE the gate, not handed in as booleans. A hardcode-ADVANCED impl that
        // returns ADVANCED + writes a checkpoint but never acquires (zero calls) fails here. Dormant
        // under the skeleton (the ADVANCED assertion fails first).
        assertTrue(
            "scheduleAdvanced must internally acquire all three facts; got observe=$observeCalls revision=$revisionCalls quota=$quotaCalls",
            observeCalls == 1 && revisionCalls == 1 && quotaCalls == 1
        )
        // R5-F2 identity-coherence gate (§11.7): the gate must forward the REAL receipt identity into
        // each acquirer — the acquisition is bound to attempt 1L / key "k-sched", NOT a default/garbage
        // identity. Sol's round-4 zero-arg attack greened this by relaying a caller boolean with no
        // identity binding at all (it won't compile against the identity-bearing signature); an adapted
        // attack forwarding a WRONG identity (e.g. 0L / "") fails here. Dormant under the skeleton.
        assertEquals(
            "observe must acquire for the REAL attempt identity (not a default/garbage value)",
            1L,
            observeAttempt
        )
        assertEquals(
            "revision must acquire for the REAL receipt key (not a default/garbage value)",
            "k-sched",
            revisionKey
        )
        assertEquals(
            "quota must acquire for the REAL attempt identity (not a default/garbage value)",
            1L,
            quotaAttempt
        )
    }

    @Test
    fun `schedule advance with a receipt but a mismatching observation is NOT_ADVANCED`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-sched", requestDigest = "digest-sched", outcome = "RELEASED", createdAt = 500L)
        val rc = RecoveryCoordinator(executor, log)
        // Discriminating negative (defeats a hardcode-ADVANCED-when-receipt≠null bad impl): a durable
        // receipt is present, but the independent observation does NOT match ⇒ must NOT advance. Passes
        // now (skeleton NOT_ADVANCED); a bad impl that ignores [observe] and hardcodes ADVANCED fails.
        val result = rc.scheduleAdvanced(
            attemptId = 1L, idempotencyKey = "k-sched", now = 1000L,
            observe = ObserveIntentAcquirer { _ -> false }, // mismatch
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = TrustedQuotaAcquirer { _ -> true }
        )
        assertEquals(
            "receipt but mismatching observation ⇒ NOT_ADVANCED",
            ScheduleAdvanceState.NOT_ADVANCED,
            result
        )
        assertNull("NOT_ADVANCED must record no checkpoint", log.checkpointFor(1L))
    }

    @Test
    fun `schedule advance with a receipt and matching observation but a STALE revision is NOT_ADVANCED`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-sched", requestDigest = "digest-sched", outcome = "RELEASED", createdAt = 500L)
        val rc = RecoveryCoordinator(executor, log)
        // Discriminating negative (defeats a `receipt≠null ∧ observe-match` false oracle): observation
        // matches but the receipt's revision is STALE ⇒ must NOT advance. Passes now; a bad impl that
        // ignores [receiptRevision] and hardcodes ADVANCED fails.
        val result = rc.scheduleAdvanced(
            attemptId = 1L, idempotencyKey = "k-sched", now = 1000L,
            observe = ObserveIntentAcquirer { _ -> true },
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> false }, // stale
            trustedQuota = TrustedQuotaAcquirer { _ -> true }
        )
        assertEquals(
            "receipt + matching observation but stale revision ⇒ NOT_ADVANCED",
            ScheduleAdvanceState.NOT_ADVANCED,
            result
        )
        assertNull("NOT_ADVANCED must record no checkpoint", log.checkpointFor(1L))
    }

    @Test
    fun `schedule advance with a receipt and matching observation but QUOTA EXHAUSTED is NOT_ADVANCED`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-sched", requestDigest = "digest-sched", outcome = "RELEASED", createdAt = 500L)
        val rc = RecoveryCoordinator(executor, log)
        // Discriminating negative (defeats a `receipt≠null ∧ observe-match ∧ fresh` false oracle):
        // observation matches and revision is fresh but the task's trusted quota is EXHAUSTED ⇒ must
        // NOT advance again. Passes now; a bad impl that ignores [trustedQuota] and hardcodes ADVANCED fails.
        val result = rc.scheduleAdvanced(
            attemptId = 1L, idempotencyKey = "k-sched", now = 1000L,
            observe = ObserveIntentAcquirer { _ -> true },
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = TrustedQuotaAcquirer { _ -> false } // exhausted
        )
        assertEquals(
            "receipt + matching observation but quota exhausted ⇒ NOT_ADVANCED",
            ScheduleAdvanceState.NOT_ADVANCED,
            result
        )
        assertNull("NOT_ADVANCED must record no checkpoint", log.checkpointFor(1L))
    }

    @Test
    fun `schedule advance is a read-only consumer gate - it records no receipt and invokes no executor`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(executor, log)
        rc.scheduleAdvanced(
            attemptId = 1L, idempotencyKey = "k-sched", now = 1000L,
            observe = ObserveIntentAcquirer { _ -> true },
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = TrustedQuotaAcquirer { _ -> true }
        )
        // The consumer gate must not mint receipts, drive the provider, or record a checkpoint as a side
        // effect (no fake trust, no fake advance) — it only READS durable state to gate the consumer.
        assertTrue(
            "scheduleAdvanced must record no receipt, no checkpoint, and invoke no executor",
            log.receiptFor("k-sched") == null && log.checkpointFor(1L) == null && executor.invocationCount("k-sched") == 0
        )
    }
}
