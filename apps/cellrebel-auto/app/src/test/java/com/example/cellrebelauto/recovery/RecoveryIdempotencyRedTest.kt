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
        assertTrue("a fresh recoverable attempt must advance to release (got $outcome)", outcome is ReconcileResult.AdvancedToRelease)
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
        assertTrue("same-key/same-digest replay must be REPLAYED_APPLY (got $outcome)", outcome is ReconcileResult.ReplayedApply)
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
        assertTrue("same-key/different-digest must be IDEMPOTENCY_CONFLICT (got $outcome)", outcome is ReconcileResult.IdempotencyConflict)
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
        assertTrue("M-CR-02 post-crash reconcile must advance to release (got $outcome)", outcome is ReconcileResult.AdvancedToRelease)
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
        assertTrue("post-crash reconcile with a receipt present must be REPLAYED_APPLY (got $outcome)", outcome is ReconcileResult.ReplayedApply)
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

    // ---- Schedule-advance consumer gate (Issue #5 addendum, §5 boundary; Sol round-6 §11.7 F2) ----
    //
    // R6-F2 (§11.7): the three readers are now CONSTRUCTOR-OWNED deps; scheduleAdvanced takes only the
    // coordinator-owned (attemptId, key, now). The fakes below are IDENTITY-KEYED — each holds a
    // Map<Identity, Fact> and looks the fact up by the identity the coordinator forwards, returning the
    // default (false) on a miss. So the fact is NOT caller-supplied per call: forwarding the REAL identity
    // yields the seeded fact; forwarding a wrong/garbage identity yields false and the AND flips to
    // NOT_ADVANCED. This closes Sol's round-5 residual (the closure ANSWER was still caller-injected).
    // Each fake also records the identities it was called with, so the ADVANCED case asserts both the
    // result AND that the coordinator forwarded the real identity exactly once.
    //
    // Robustness (why the SET is greenproof, not any single test): a "call-all-then-hardcode-ADVANCED"
    // attack greens the positive (calls + identity satisfied) but FAILS the three negatives (each seeds
    // one fact false for the real identity ⇒ expects NOT_ADVANCED ⇒ hardcode returns ADVANCED ⇒ fail).
    // A "forward-wrong-identity" attack fails the positive twice (identity-keyed miss ⇒ false ⇒
    // NOT_ADVANCED, AND recorded identity ≠ real). The combined-attack self-gate (§11.7) verifies this
    // empirically before re-requesting review.

    /** Identity-keyed observe fake: returns the seeded fact for [attemptId], else false. Records call identities. */
    private class SeededObserve(private val facts: Map<Long, Boolean>) : ObserveIntentAcquirer {
        val calls = mutableListOf<Long>()
        override fun matches(attemptId: Long): Boolean {
            calls += attemptId
            return facts[attemptId] ?: false
        }
    }

    /** Identity-keyed revision fake: returns the seeded fact for [idempotencyKey], else false. Records call identities. */
    private class SeededRevision(private val facts: Map<String, Boolean>) : ReceiptRevisionAcquirer {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun isFresh(idempotencyKey: String, now: Long): Boolean {
            calls += idempotencyKey to now
            return facts[idempotencyKey] ?: false
        }
    }

    /** Identity-keyed quota fake: returns the seeded fact for [attemptId], else false. Records call identities. */
    private class SeededQuota(private val facts: Map<Long, Boolean>) : TrustedQuotaAcquirer {
        val calls = mutableListOf<Long>()
        override fun hasCapacity(attemptId: Long): Boolean {
            calls += attemptId
            return facts[attemptId] ?: false
        }
    }

    @Test
    fun `schedule advance without a durable receipt is never assumed`() {
        // No receipt for "k-sched". Readers seeded true for the real identity, but that must NOT matter —
        // without a durable receipt Auto MUST NOT advance. Skeleton NOT_ADVANCED (ignores readers); GREEN
        // must check the receipt FIRST and short-circuit regardless of acquired facts.
        val rc = RecoveryCoordinator(
            RecordingExternalApplyExecutor(), FakeDurableRecoveryLog(),
            SeededObserve(mapOf(1L to true)), SeededRevision(mapOf("k-sched" to true)), SeededQuota(mapOf(1L to true))
        )
        val result = rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L)
        assertEquals(
            "no durable receipt ⇒ NOT_ADVANCED regardless of acquired facts",
            ScheduleAdvanceState.NOT_ADVANCED,
            result
        )
    }

    @Test
    fun `schedule advance with a durable receipt and all three facts confirming is ADVANCED and acquires each fact for the REAL identity`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-sched", requestDigest = "digest-sched", outcome = "RELEASED", createdAt = 500L)
        val observe = SeededObserve(mapOf(1L to true))
        val revision = SeededRevision(mapOf("k-sched" to true))
        val quota = SeededQuota(mapOf(1L to true))
        val rc = RecoveryCoordinator(executor, log, observe, revision, quota)

        // RED: skeleton returns NOT_ADVANCED and acquires NOTHING (zero reader calls), even though a durable
        // receipt exists and all three facts are seeded true for the real identity. GREEN must read the
        // receipt, call all three constructor-owned readers with the real owned identity, AND the acquired
        // facts, ADVANCE, and record a window-c checkpoint bound to the receipt.
        val result = rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L)
        assertEquals(
            "receipt + observe-match + fresh revision + quota capacity ⇒ ADVANCED",
            ScheduleAdvanceState.ADVANCED,
            result
        )
        val checkpoint = log.checkpointFor(1L)
        assertNotNull("ADVANCED must record a window-c checkpoint", checkpoint)
        assertEquals("the checkpoint must bind to the advanced receipt key", "k-sched", checkpoint!!.receiptKey)
        // R6-F2 acquisition gate: the GREEN body must have called each reader exactly once — the facts were
        // acquired INSIDE the gate from coordinator-owned readers, not handed in as booleans. A hardcode-
        // ADVANCED impl that returns ADVANCED + writes a checkpoint but never acquires (zero calls) fails here.
        assertTrue(
            "scheduleAdvanced must internally acquire all three facts; got observe=${observe.calls.size} revision=${revision.calls.size} quota=${quota.calls.size}",
            observe.calls.size == 1 && revision.calls.size == 1 && quota.calls.size == 1
        )
        // R6-F2 identity gate (§11.7): the readers are identity-keyed — forwarding the REAL identity yields
        // the seeded true; forwarding a WRONG identity yields the default false and the AND flips to
        // NOT_ADVANCED. So the ADVANCED result already PROVES the coordinator forwarded the real identity;
        // these assertions pin it explicitly. An attack relaying a garbage identity (0L / "") fails twice:
        // the fake returns false ⇒ NOT_ADVANCED (result assertion fails), AND the recorded identity ≠ real.
        assertEquals(
            "observe must acquire for the REAL attempt identity 1L (identity-keyed ⇒ wrong identity returns false)",
            listOf(1L),
            observe.calls
        )
        assertEquals(
            "revision must acquire for the REAL receipt key k-sched at now 1000L",
            listOf("k-sched" to 1000L),
            revision.calls
        )
        assertEquals(
            "quota must acquire for the REAL attempt identity 1L (identity-keyed ⇒ wrong identity returns false)",
            listOf(1L),
            quota.calls
        )
    }

    @Test
    fun `schedule advance with a receipt but a mismatching observation is NOT_ADVANCED`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-sched", requestDigest = "digest-sched", outcome = "RELEASED", createdAt = 500L)
        // observe seeded FALSE for the real identity 1L — the independent observation does NOT match.
        val rc = RecoveryCoordinator(
            executor, log,
            SeededObserve(mapOf(1L to false)), SeededRevision(mapOf("k-sched" to true)), SeededQuota(mapOf(1L to true))
        )
        val result = rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L)
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
        val rc = RecoveryCoordinator(
            executor, log,
            SeededObserve(mapOf(1L to true)), SeededRevision(mapOf("k-sched" to false)), SeededQuota(mapOf(1L to true))
        )
        val result = rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L)
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
        val rc = RecoveryCoordinator(
            executor, log,
            SeededObserve(mapOf(1L to true)), SeededRevision(mapOf("k-sched" to true)), SeededQuota(mapOf(1L to false))
        )
        val result = rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L)
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
        // No receipt seeded. Readers seeded true but irrelevant (no receipt ⇒ NOT_ADVANCED).
        val rc = RecoveryCoordinator(
            executor, log,
            SeededObserve(mapOf(1L to true)), SeededRevision(mapOf("k-sched" to true)), SeededQuota(mapOf(1L to true))
        )
        rc.scheduleAdvanced(attemptId = 1L, idempotencyKey = "k-sched", now = 1000L)
        // The consumer gate must not mint receipts, drive the provider, or record a checkpoint as a side
        // effect (no fake trust, no fake advance) — it only READS durable state to gate the consumer.
        assertTrue(
            "scheduleAdvanced must record no receipt, no checkpoint, and invoke no executor",
            log.receiptFor("k-sched") == null && log.checkpointFor(1L) == null && executor.invocationCount("k-sched") == 0
        )
    }

    // ---- release durability (Sol round-10 P1-3 / round-11 P1-3) ----

    @Test
    fun `release with a durable receipt replays WITHOUT re-calling the provider`() {
        // M-CR-08 zero-reinvoke: a durable release receipt for the same lease/key/digest is a REPLAY.
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNotNull("a durable release receipt must replay", receipt)
        assertEquals("release must NOT re-call the provider when a durable receipt exists", 0, executor.releaseInvocationCount("r-1"))
        assertEquals("the release effect stays at zero (replay, no new effect)", 0, executor.releaseEffectCount(1L))
    }

    @Test
    fun `release whose provider returns FAILED yields no durable release receipt`() {
        // A failed/incomplete release must NOT forge a RELEASED receipt (Sol round-10 P1-3).
        val executor = RecordingExternalApplyExecutor(outcome = "FAILED")
        val log = FakeDurableRecoveryLog()
        val rc = RecoveryCoordinator(executor, log)

        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNull("a FAILED release must not record a durable RELEASED receipt", receipt)
    }

    // ---- apply receipt rejection + true M-CR-08 + release receipt conflict (Sol round-13) ----

    @Test
    fun `dispatchApply with a conflicting receipt does not leak the lease`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        // A prior receipt for the same key but a DIFFERENT digest → recordReceipt rejects (conflict).
        log.seedReceipt(idempotencyKey = "k-1", requestDigest = "digest-other", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        val outcome = rc.dispatchApply(attemptId = 1L, idempotencyKey = "k-1", requestDigest = "digest-v1", now = 2000L)

        assertNull("a rejected/conflicting receipt must not leak the lease", outcome.leaseId)
        assertEquals("RECEIPT_NOT_DURABLE", outcome.outcome)
    }

    @Test
    fun `release re-invoked after provider released but before receipt records the receipt and keeps effect at one`() {
        // True M-CR-08: provider release EFFECT done, Auto receipt NOT yet saved → re-invoke (1→2), effect stays 1.
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.release(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 1000L)
        assertNull("M-CR-08: provider released but no durable receipt", log.releaseReceiptFor("lease-1"))
        val rc = RecoveryCoordinator(executor, log)

        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNotNull("the re-invoked release must record a durable receipt", receipt)
        assertEquals("release re-invoked (1 → 2)", 2, executor.releaseInvocationCount("r-1"))
        assertEquals("release effect stays at one (at-most-once)", 1, executor.releaseEffectCount(1L))
    }

    @Test
    fun `a FAILED release receipt for the same tuple is not returned as success`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", outcome = "FAILED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNull("a FAILED release receipt must not be returned as a successful replay", receipt)
        assertEquals("a FAILED receipt must not re-call the provider", 0, executor.releaseInvocationCount("r-1"))
    }

    // ---- apply/release conflict preflight (Sol round-14 P1-1/P1-2) ----

    @Test
    fun `dispatchApply with a known conflicting receipt fails closed BEFORE the provider call`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReceipt(idempotencyKey = "k-1", requestDigest = "digest-other", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        val outcome = rc.dispatchApply(attemptId = 1L, idempotencyKey = "k-1", requestDigest = "digest-v1", now = 2000L)

        assertNull("a known conflict must not leak a lease", outcome.leaseId)
        assertEquals("a known conflict must produce ZERO provider effect (preflight)", 0, executor.invocationCount("k-1"))
        assertEquals(0, executor.effectCount(1L))
    }

    @Test
    fun `release with the same lease but a different operation key fails closed with zero provider call`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(idempotencyKey = "r-other", leaseId = "lease-1", releaseDigest = "rd-1", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNull("same lease / different key must fail closed", receipt)
        assertEquals("zero provider call", 0, executor.releaseInvocationCount("r-1"))
    }

    @Test
    fun `release with the same operation key but a different lease fails closed with zero provider call`() {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(idempotencyKey = "r-1", leaseId = "lease-other", releaseDigest = "rd-1", outcome = "RELEASED", createdAt = 1000L)
        val rc = RecoveryCoordinator(executor, log)

        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = "r-1", leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNull("same key / different lease must fail closed", receipt)
        assertEquals("zero provider call", 0, executor.releaseInvocationCount("r-1"))
    }
}
