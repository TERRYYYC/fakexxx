package com.example.cellrebelauto.matrix

import com.example.cellrebelauto.automation.advance.AdvanceDecision
import com.example.cellrebelauto.automation.advance.ScheduleContext
import com.example.cellrebelauto.support.ConsumerHarness
import com.example.cellrebelauto.support.CrashSimulation
import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 advance rows owned by the Auto consumer lane — M-AD-14 through M-AD-19.
 *
 * Spec source: v1.46 (quota gate + exhausted digest + observe tuple),
 *              v1.68 (four-leg readback for terminal, scheduleVersion fourth leg).
 *
 * These rows test the AUTO CONSUMER's advance protocol decisions, NOT the
 * provider's rejection codes (those are in the qwy-side AdvanceMatrixTest).
 * The system under test is [AdvanceCoordinator]: given a durable quota store
 * and a provider that behaves in a specific way, does the coordinator make
 * the right decision?
 *
 * P1-1 fix (production integration): The coordinator takes [QuotaReader] and
 * [ProviderGateway] interfaces — the SAME interfaces the production code
 * (AutomationEngine with Room-backed QuotaReader and AIDL-backed ProviderGateway)
 * uses. These tests exercise the coordinator through [TrustedQuotaLedger] and
 * [FakeProviderGateway] as test doubles of those production interfaces.
 *
 * P1-2 fix (quota re-read): The coordinator reads quota via [QuotaReader], never
 * from a caller-supplied `quotaCount: Int`. The M-AD-15 crash test proves that
 * after a crash between durable quota write and coordinator evaluation, the
 * coordinator re-reads from the durable store.
 *
 * P1-3 fix (idempotency): M-AD-19 uses the SAME key across forks.
 * [TrustedQuotaLedger] has attempt-keyed idempotency (UNIQUE constraint analog).
 *
 * Constraint: semantic only. No v1 AIDL method set / DTO field·order·type /
 * wire value changes. All contract types used here are already frozen.
 */
class AdvanceMatrixTest {

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-14: Quota committed but NOT met → no advance
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M-AD-14: After release, if `count(TrustedQuotaEntry) < requiredSuccesses`,
     * the coordinator must NOT issue a completeAndAdvance call. The attempt
     * closes without advancing — the current item is preserved for the next
     * attempt.
     *
     * This is the fix for the v1.46 defect: the original state machine treated
     * "quota committed" (inserted one TrustedQuotaEntry) as "quota met", which
     * would advance a `requiredSuccesses = 3` task after the first attempt.
     *
     * Three sub-cases: (a) quotaCount=0, (b) quotaCount=1 with required=3,
     * (c) exactly at threshold → advance (boundary).
     *
     * P1-2: coordinator reads from TrustedQuotaLedger (via QuotaReader
     * interface), not from a caller-supplied int.
     */
    @Test
    fun M_AD_14_quotaNotMet_noAdvance() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        // (a) Zero quota committed → QuotaNotMet
        val decision0 = h.coordinator.evaluateAfterRelease(
            taskId = "task-14",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-14a",
            idempotencyKey = "ad14-k0",
        )
        assertTrue(
            "zero quota → QuotaNotMet, got $decision0",
            decision0 is AdvanceDecision.QuotaNotMet,
        )
        assertEquals("no advance issued", 0, h.schedule.advanceCount)
        assertEquals("pointer untouched", "item-1", h.schedule.currentItemId)

        // (b) Partial quota: 1 out of 3 → QuotaNotMet
        h.commitQuota("task-14", 1)
        val decision1 = h.coordinator.evaluateAfterRelease(
            taskId = "task-14",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-14b",
            idempotencyKey = "ad14-k1",
        )
        assertTrue(
            "partial quota (1/3) → QuotaNotMet, got $decision1",
            decision1 is AdvanceDecision.QuotaNotMet,
        )
        assertEquals("still no advance", 0, h.schedule.advanceCount)

        // (c) Exactly at threshold: 3/3 → SHOULD advance (boundary)
        h.commitQuota("task-14", 2) // now 3 total
        val decisionMet = h.coordinator.evaluateAfterRelease(
            taskId = "task-14",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-14c",
            idempotencyKey = "ad14-k2",
        )
        assertTrue(
            "exact quota (3/3) → Advanced, got $decisionMet",
            decisionMet is AdvanceDecision.Advanced,
        )
        assertEquals("advance happened", 1, h.schedule.advanceCount)
        assertEquals("pointer moved to item-2", "item-2", h.schedule.currentItemId)
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-15: Crash between quota commit and met determination
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M-AD-15: If Auto crashes between committing the quota entry to the durable
     * store (Room / TrustedQuotaLedger) and the coordinator evaluating whether
     * quota is met, recovery must re-read from the durable store and make the
     * correct decision.
     *
     * P1-2 fix: The crash window is AFTER durable quota write, BEFORE
     * `count >= requiredSuccesses` determination (inside the coordinator).
     * The coordinator reads via [QuotaReader.countTrustedEntries] at call time —
     * not from a stale caller-supplied snapshot. This test proves:
     *
     *   1. Quota is durably written (TrustedQuotaLedger retains entries across
     *      the simulated crash boundary)
     *   2. The coordinator was never called before the crash — the first call is
     *      the recovery call
     *   3. The coordinator reads from the durable store and makes the correct
     *      decision based on what it finds
     *   4. If quota not met: no advance. If quota met after further entries: advance.
     *
     * INV-15: reconcile first, advance second. Never advance with stale quota.
     */
    @Test
    fun M_AD_15_crashBetweenQuotaWriteAndMetDetermination_recoveryReReads() {
        val h = ConsumerHarness.create()

        // ── Phase 1: Durable quota write, then crash ──
        // Simulate: Room transaction commits 2 entries for task-15.
        // Process crashes BEFORE the coordinator is called.
        h.commitQuota("task-15", 2)

        // ── "CRASH" ──
        // The TrustedQuotaLedger (simulating Room) retains the 2 entries.
        // The coordinator was never called — no stale snapshot exists.

        // ── Phase 2: Recovery ──
        // The coordinator is called for the first time post-crash.
        // It reads from the durable store: 2 entries, required 3 → QuotaNotMet.
        val ctx = h.scheduleContext()
        val afterCrash = h.coordinator.evaluateAfterRelease(
            taskId = "task-15",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-15",
            idempotencyKey = "ad15-k1",
        )
        assertTrue(
            "recovery reads 2/3 from durable store → QuotaNotMet, got $afterCrash",
            afterCrash is AdvanceDecision.QuotaNotMet,
        )
        assertEquals("no advance issued (quota not met)", 0, h.schedule.advanceCount)

        // ── Phase 3: More entries committed, re-evaluate ──
        // A new attempt succeeds and commits the third entry.
        h.commitQuota("task-15", 1) // now 3/3 in the durable store

        val afterThird = h.coordinator.evaluateAfterRelease(
            taskId = "task-15",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-15",
            idempotencyKey = "ad15-k2", // new key for new attempt
        )
        assertTrue(
            "re-read 3/3 from durable store → Advanced, got $afterThird",
            afterThird is AdvanceDecision.Advanced,
        )
        assertEquals("advance happened", 1, h.schedule.advanceCount)
    }

    /**
     * M-AD-15 supplement: crash DURING the provider call (after provider commits
     * the advance, before coordinator sees the receipt). Recovery replays with
     * the SAME idempotency key — provider returns cached receipt, coordinator
     * completes normally.
     *
     * This is a complementary crash window to the main M-AD-15 test: the main
     * test covers crash between quota-write and coordinator call; this covers
     * crash inside the coordinator's provider call.
     */
    @Test
    fun M_AD_15_supplement_crashDuringProviderCall_idempotentReplay() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        // Quota is met: 3/3
        h.commitQuota("task-15s", 3)

        // Inject a crash in the provider: it commits the advance but throws
        // before the receipt reaches the coordinator. We simulate this by
        // overriding the gateway to throw on the first call.
        val gateway = object : com.example.cellrebelauto.automation.advance.ProviderGateway {
            private var firstCall = true
            private val realGateway = h.let {
                // Get the real gateway behavior by advancing a parallel schedule
                val parallel = ConsumerHarness.create()
                parallel.commitQuota("task-15s", 3)
                parallel
            }

            override fun completeAndAdvance(
                request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
            ): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1 {
                // Delegate to the real schedule so it advances
                val receipt = h.schedule.advance(request)
                if (firstCall) {
                    firstCall = false
                    throw CrashSimulation("crash after provider commit, before receipt seen")
                }
                return receipt
            }

            override fun observe(
                leaseId: String,
                context: com.example.cellrebelauto.automation.advance.ScheduleContext,
            ) = h.schedule.honestObservation(leaseId)

            override fun discover() = h.schedule.honestSnapshot()
        }

        // Create a coordinator with the crash-injecting gateway
        val crashCoordinator = com.example.cellrebelauto.automation.advance.AdvanceCoordinator(
            gateway, h.quotaLedger,
        )

        // First call: crashes after provider commits
        var crashed = false
        try {
            crashCoordinator.evaluateAfterRelease(
                taskId = "task-15s",
                requiredSuccesses = 3,
                scheduleContext = ctx,
                leaseId = "lease-15s",
                idempotencyKey = "ad15-sk1",
            )
        } catch (e: CrashSimulation) {
            crashed = true
        }
        assertTrue("crash should propagate", crashed)
        assertEquals("provider advanced despite crash", 1, h.schedule.advanceCount)

        // Recovery: same key → provider returns cached receipt (idempotent)
        val recovered = crashCoordinator.evaluateAfterRelease(
            taskId = "task-15s",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-15s",
            idempotencyKey = "ad15-sk1", // SAME key → idempotent replay
        )
        assertTrue(
            "recovery with same key → Advanced (idempotent replay), got $recovered",
            recovered is AdvanceDecision.Advanced,
        )
        assertEquals("still only one advance (idempotent)", 1, h.schedule.advanceCount)
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-16: EXHAUSTED receipt digest mismatch → reject, no terminal state
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M-AD-16: An EXHAUSTED receipt whose `receiptDigest` fails recomputation
     * must be rejected and the coordinator must enter RECOVERY_REQUIRED — NOT
     * fall through to terminal CLOSED.
     *
     * This verifies that the exhausted path does NOT bypass receipt digest
     * verification (v1.46 defect: the original state machine had
     * `ADVANCE_EXHAUSTED → CLOSED` skipping `receiptDigest` recomputation,
     * while the non-exhausted path required it).
     *
     * "A receipt whose digest does not recompute is not a 'weaker receipt' —
     * it is not a receipt." (§6.7.3)
     */
    @Test
    fun M_AD_16_exhaustedReceiptDigestMismatch_recoveryRequired() {
        // Set up a schedule at the last item so advance produces EXHAUSTED.
        val h = ConsumerHarness.create(items = listOf("item-1"))
        val ctx = h.scheduleContext()
        h.commitQuota("task-16", 3)

        // Corrupt the receipt digest
        h.corruptNextReceiptDigest()

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-16",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-16",
            idempotencyKey = "ad16-k1",
        )

        assertTrue(
            "corrupted EXHAUSTED receipt digest → RecoveryRequired, got $decision",
            decision is AdvanceDecision.RecoveryRequired,
        )
        val recovery = decision as AdvanceDecision.RecoveryRequired
        assertTrue(
            "reason names ADVANCE_DIGEST_MISMATCH",
            recovery.reason.contains("ADVANCE_DIGEST_MISMATCH"),
        )
        // The provider DID advance (the receipt was generated), but Auto must
        // NOT trust it and must NOT enter CLOSED.
    }

    /**
     * M-AD-16 supplement: verify that an honest EXHAUSTED receipt WITH valid
     * digest IS accepted (control case — proves the test isn't vacuously true).
     */
    @Test
    fun M_AD_16_control_honestExhaustedReceiptDigest_accepted() {
        val h = ConsumerHarness.create(items = listOf("item-1"))
        val ctx = h.scheduleContext()
        h.commitQuota("task-16c", 3)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-16c",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-16c",
            idempotencyKey = "ad16-ck1",
        )

        assertTrue(
            "honest EXHAUSTED receipt → Exhausted, got $decision",
            decision is AdvanceDecision.Exhausted,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-17: Non-terminal observe intentHash mismatch → RECOVERY_REQUIRED
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M-AD-17: After a non-terminal advance, if `observe()` returns an
     * observation where `acceptedIntentHash ≠ receipt.effectiveIntentHash`
     * (but scheduleItemId matches), the coordinator must enter
     * RECOVERY_REQUIRED with a typed reason naming the intentHash leg.
     *
     * This is the OBSERVED_TUPLE_MISMATCH path. Without this check, wrong
     * intent attribution would go undetected when the item happens to match
     * but the intent was silently swapped.
     */
    @Test
    fun M_AD_17_nonTerminalObserve_intentHashMismatch_recoveryRequired() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-17", 3)

        // Override observation: item matches, but intentHash is wrong.
        // We need to know what item-2 will be after advance, and what the
        // receipt's effectiveIntentHash will be.
        val fakeObservation = h.schedule.honestObservation("lease-17").copy(
            scheduleItemId = "item-2", // matches advancedToItemId
            scheduleVersion = 2L,      // matches scheduleVersionAfter
            acceptedIntentHash = "WRONG-intent-hash", // ← THIS is the mismatch
            environmentRevision = 200L, // matches effectiveEnvironmentRevision
        )
        h.overrideNextObservation(fakeObservation)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-17",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-17",
            idempotencyKey = "ad17-k1",
        )

        assertTrue(
            "intentHash mismatch → RecoveryRequired, got $decision",
            decision is AdvanceDecision.RecoveryRequired,
        )
        val recovery = decision as AdvanceDecision.RecoveryRequired
        assertTrue(
            "reason names OBSERVED_TUPLE_MISMATCH",
            recovery.reason.contains("OBSERVED_TUPLE_MISMATCH"),
        )
        assertTrue(
            "reason names the intentHash leg specifically",
            recovery.reason.contains("acceptedIntentHash"),
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-18: Non-terminal observe environmentRevision mismatch
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M-AD-18: After a non-terminal advance, if `observe()` returns an
     * observation where `environmentRevision ≠ receipt.effectiveEnvironmentRevision`
     * (but item AND intentHash both match), the coordinator must enter
     * RECOVERY_REQUIRED with a typed reason naming the revision leg.
     *
     * This is distinct from M-AD-17: single-leg readers miss each other's
     * failure mode. Only a four-leg conjunction catches both.
     */
    @Test
    fun M_AD_18_nonTerminalObserve_revisionMismatch_recoveryRequired() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-18", 3)

        // Override observation: item AND intentHash match, revision is wrong.
        val fakeObservation = h.schedule.honestObservation("lease-18").copy(
            scheduleItemId = "item-2",
            scheduleVersion = 2L,
            acceptedIntentHash = "intent-hash-item-2", // matches
            environmentRevision = 999L, // ← THIS is the mismatch (should be 200)
        )
        h.overrideNextObservation(fakeObservation)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-18",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-18",
            idempotencyKey = "ad18-k1",
        )

        assertTrue(
            "revision mismatch → RecoveryRequired, got $decision",
            decision is AdvanceDecision.RecoveryRequired,
        )
        val recovery = decision as AdvanceDecision.RecoveryRequired
        assertTrue(
            "reason names OBSERVED_TUPLE_MISMATCH",
            recovery.reason.contains("OBSERVED_TUPLE_MISMATCH"),
        )
        assertTrue(
            "reason names the environmentRevision leg specifically",
            recovery.reason.contains("environmentRevision"),
        )
    }

    /**
     * M-AD-18 supplement: version leg mismatch (v1.68 fourth leg).
     * After a non-terminal advance, if observation.scheduleVersion ≠
     * receipt.scheduleVersionAfter, it must also be caught — this is the leg
     * that stops same-topology reinit from being silently accepted (M-AD-25).
     */
    @Test
    fun M_AD_18_supplement_versionLegMismatch_recoveryRequired() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-18s", 3)

        // item, intentHash, revision all match — but version is wrong.
        val fakeObservation = h.schedule.honestObservation("lease-18s").copy(
            scheduleItemId = "item-2",
            scheduleVersion = 999L, // ← version mismatch
            acceptedIntentHash = "intent-hash-item-2",
            environmentRevision = 200L,
        )
        h.overrideNextObservation(fakeObservation)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-18s",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-18s",
            idempotencyKey = "ad18-sk1",
        )

        assertTrue(
            "version mismatch → RecoveryRequired, got $decision",
            decision is AdvanceDecision.RecoveryRequired,
        )
        val recovery = decision as AdvanceDecision.RecoveryRequired
        assertTrue(
            "reason names scheduleVersion leg",
            recovery.reason.contains("scheduleVersion"),
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-19: Cross-fork same-key replay — idempotent, no double advance
    // ════════════════════════════════════════════════════════════════════════

    /**
     * M-AD-19: When the SAME idempotency key is used across two forks (one
     * on the quota-not-met path, one on the quota-met path), the result must
     * be:
     *   - No double advance
     *   - Provider idempotency returns cached receipt on replay
     *
     * P1-3 fix: uses the SAME key across forks, not different keys.
     *
     * Fork scenario:
     *   Fork 1: quota 2/3 (not met) → QuotaNotMet, provider never called,
     *           key NOT consumed by provider idempotency store
     *   Fork 2: quota 3/3 (met), SAME key → Advanced, provider called with
     *           key for the first time, advance committed
     *   Fork 3: quota 3/3 (met), SAME key → Advanced (cached receipt),
     *           provider returns idempotent replay, no second advance
     */
    @Test
    fun M_AD_19_crossForkSameKeyReplay_idempotent_noDoubleAdvance() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        val SHARED_KEY = "ad19-shared-key"

        // Fork 1: quota not met (2/3). The advance request is never sent
        // because QuotaNotMet short-circuits before calling the provider.
        h.commitQuota("task-19", 2)
        val fork1 = h.coordinator.evaluateAfterRelease(
            taskId = "task-19",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19",
            idempotencyKey = SHARED_KEY,
        )
        assertTrue("fork 1 (2/3) → QuotaNotMet", fork1 is AdvanceDecision.QuotaNotMet)
        assertEquals("no advance yet", 0, h.schedule.advanceCount)

        // Fork 2: same task, quota now met (3/3). SAME key — the provider
        // sees this key for the first time (fork 1 never reached it).
        h.commitQuota("task-19", 1) // now 3/3
        val fork2 = h.coordinator.evaluateAfterRelease(
            taskId = "task-19",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19",
            idempotencyKey = SHARED_KEY, // SAME key
        )
        assertTrue("fork 2 (3/3, same key) → Advanced", fork2 is AdvanceDecision.Advanced)
        assertEquals("exactly one advance", 1, h.schedule.advanceCount)

        // Fork 3: replay of SAME key. The provider returns the cached
        // receipt (idempotent). The coordinator should still succeed with
        // Advanced, not double-advance.
        val fork3 = h.coordinator.evaluateAfterRelease(
            taskId = "task-19",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19",
            idempotencyKey = SHARED_KEY, // SAME key → provider idempotent replay
        )
        assertTrue(
            "fork 3 (same key replay) → Advanced (idempotent), got $fork3",
            fork3 is AdvanceDecision.Advanced,
        )
        assertEquals(
            "still exactly one advance (idempotent replay)",
            1,
            h.schedule.advanceCount,
        )
    }

    /**
     * M-AD-19 supplement: TrustedQuotaLedger attempt-keyed idempotency.
     *
     * P1-3 fix: double-committing the same attempt key must count as ONE entry.
     * In production, Room enforces this via a UNIQUE constraint on
     * `TrustedQuotaEntry(taskId, attemptKey)`. This prevents crash-recovery
     * from inflating the quota count.
     */
    @Test
    fun M_AD_19_supplement_quotaLedgerIdempotency_noDuplicateCounting() {
        val h = ConsumerHarness.create()

        // Commit with explicit attempt key
        val first = h.commitQuotaEntry("task-19s", "attempt-1")
        assertTrue("first commit → new entry", first)
        assertEquals("count is 1", 1, h.quotaLedger.countTrustedEntries("task-19s"))

        // Double-commit SAME key → idempotent (count stays 1)
        val dup = h.commitQuotaEntry("task-19s", "attempt-1")
        assertFalse("duplicate commit → rejected", dup)
        assertEquals("count still 1 (idempotent)", 1, h.quotaLedger.countTrustedEntries("task-19s"))

        // Different key → new entry (count goes to 2)
        val second = h.commitQuotaEntry("task-19s", "attempt-2")
        assertTrue("new key → new entry", second)
        assertEquals("count is 2", 2, h.quotaLedger.countTrustedEntries("task-19s"))

        // Verify the coordinator sees the correct count via QuotaReader
        val ctx = h.scheduleContext()
        val notMet = h.coordinator.evaluateAfterRelease(
            taskId = "task-19s",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19s",
            idempotencyKey = "ad19s-k1",
        )
        assertTrue("2/3 via QuotaReader → QuotaNotMet", notMet is AdvanceDecision.QuotaNotMet)

        // Add third unique entry → quota met
        h.commitQuotaEntry("task-19s", "attempt-3")
        val met = h.coordinator.evaluateAfterRelease(
            taskId = "task-19s",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19s",
            idempotencyKey = "ad19s-k2",
        )
        assertTrue("3/3 via QuotaReader → Advanced", met is AdvanceDecision.Advanced)
    }

    /**
     * M-AD-19 supplement: same-key replay where the first fork DID advance.
     * The same key must return the cached receipt, never produce a second advance.
     */
    @Test
    fun M_AD_19_supplement_sameKeyReplayAfterAdvance_idempotent() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-19r", 3)

        // First call: advance
        val first = h.coordinator.evaluateAfterRelease(
            taskId = "task-19r",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19r",
            idempotencyKey = "ad19r-k1",
        )
        assertTrue("first call → Advanced", first is AdvanceDecision.Advanced)
        assertEquals(1, h.schedule.advanceCount)
        val firstReceipt = (first as AdvanceDecision.Advanced).receipt

        // Replay: same key, same everything.
        // The observation override is needed because the schedule state has
        // changed but the idempotent receipt still references the old advance.
        val replayObservation = h.schedule.honestObservation("lease-19r").copy(
            scheduleItemId = firstReceipt.advancedToItemId!!,
            scheduleVersion = firstReceipt.scheduleVersionAfter,
            acceptedIntentHash = firstReceipt.effectiveIntentHash,
            environmentRevision = firstReceipt.effectiveEnvironmentRevision,
        )
        h.overrideNextObservation(replayObservation)

        val replay = h.coordinator.evaluateAfterRelease(
            taskId = "task-19r",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19r",
            idempotencyKey = "ad19r-k1", // SAME key
        )
        assertTrue("replay → Advanced (cached receipt)", replay is AdvanceDecision.Advanced)
        assertEquals("still one advance", 1, h.schedule.advanceCount)
        assertEquals(
            "same receipt returned",
            firstReceipt.receiptDigest,
            (replay as AdvanceDecision.Advanced).receipt.receiptDigest,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    // Positive control: honest non-terminal and terminal advance succeed
    // ════════════════════════════════════════════════════════════════════════

    /** Control: honest non-terminal advance with all four legs matching. */
    @Test
    fun control_honestNonTerminalAdvance_succeeds() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-ctrl", 3)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-ctrl",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-ctrl",
            idempotencyKey = "ctrl-k1",
        )

        assertTrue("honest advance → Advanced, got $decision", decision is AdvanceDecision.Advanced)
        val receipt = (decision as AdvanceDecision.Advanced).receipt
        assertEquals("item-2", receipt.advancedToItemId)
        assertEquals(AdvanceOutcomeV1.ADVANCED.wire, receipt.outcomeWire)
    }

    /** Control: honest terminal (EXHAUSTED) advance with four-leg readback. */
    @Test
    fun control_honestTerminalAdvance_succeeds() {
        val h = ConsumerHarness.create(items = listOf("only-item"))
        val ctx = h.scheduleContext()
        h.commitQuota("task-term", 3)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-term",
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-term",
            idempotencyKey = "term-k1",
        )

        assertTrue(
            "terminal advance → Exhausted, got $decision",
            decision is AdvanceDecision.Exhausted,
        )
        val receipt = (decision as AdvanceDecision.Exhausted).receipt
        assertNull("advancedToItemId is null for EXHAUSTED", receipt.advancedToItemId)
        assertEquals(AdvanceOutcomeV1.EXHAUSTED.wire, receipt.outcomeWire)
    }
}
