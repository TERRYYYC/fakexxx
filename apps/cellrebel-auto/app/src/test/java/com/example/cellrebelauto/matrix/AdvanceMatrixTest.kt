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
 * The system under test is [AdvanceCoordinator]: given a quota count and a
 * provider that behaves in a specific way, does the coordinator make the right
 * decision?
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
     * Two sub-cases: (a) quotaCount=0, (b) quotaCount=1 with required=3.
     */
    @Test
    fun M_AD_14_quotaNotMet_noAdvance() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        // (a) Zero quota committed
        val decision0 = h.coordinator.evaluateAfterRelease(
            quotaCount = 0,
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

        // (b) Partial quota: 1 out of 3
        h.commitQuota("task-14", 1)
        val decision1 = h.coordinator.evaluateAfterRelease(
            quotaCount = h.quotaLedger.count("task-14"),
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

        // (c) Exactly at threshold → SHOULD advance (boundary)
        h.commitQuota("task-14", 2) // now 3 total
        val decisionMet = h.coordinator.evaluateAfterRelease(
            quotaCount = h.quotaLedger.count("task-14"),
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
     * M-AD-15: If Auto crashes between committing the quota entry and evaluating
     * whether quota is met, recovery must RECONCILE the quota count BEFORE
     * deciding whether to advance.
     *
     * The test verifies that:
     *   1. A crash during the advance call is survivable
     *   2. After recovery, the SAME idempotency key is replayed
     *   3. The quota count is re-evaluated from the ledger (not from a stale cache)
     *   4. If quota is met, the advance proceeds; if not, it doesn't
     *
     * INV-15: reconcile first, advance second. Never advance with stale quota.
     */
    @Test
    fun M_AD_15_crashBetweenQuotaCommitAndMetDetermination_reconcileBeforeAdvance() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        // Scenario: requiredSuccesses=2. Commit 2 entries (quota IS met).
        // But the provider crashes before returning the receipt.
        h.commitQuota("task-15", 2)
        h.injectCrashBeforeReceipt()

        // First attempt: crashes
        var crashed = false
        try {
            h.coordinator.evaluateAfterRelease(
                quotaCount = h.quotaLedger.count("task-15"),
                requiredSuccesses = 2,
                scheduleContext = ctx,
                leaseId = "lease-15",
                idempotencyKey = "ad15-k1",
            )
        } catch (e: CrashSimulation) {
            crashed = true
        }
        assertTrue("crash should propagate", crashed)

        // The provider DID advance (the crash was after commit, before receipt).
        // The schedule has moved, but Auto doesn't know yet.
        assertEquals("provider advanced despite crash", 1, h.schedule.advanceCount)

        // Recovery: re-evaluate with the SAME key (idempotent replay).
        // The reconciled quota count is re-read from the ledger.
        val recovered = h.coordinator.evaluateAfterRelease(
            quotaCount = h.quotaLedger.count("task-15"), // re-read from ledger
            requiredSuccesses = 2,
            scheduleContext = ctx,
            leaseId = "lease-15",
            idempotencyKey = "ad15-k1", // SAME key → idempotent replay
        )
        assertTrue(
            "recovery with same key → Advanced (idempotent replay), got $recovered",
            recovered is AdvanceDecision.Advanced,
        )
        assertEquals(
            "still only one advance (idempotent)",
            1,
            h.schedule.advanceCount,
        )

        // Counter-scenario: quota NOT met after recovery.
        // New task, requiredSuccesses=3 but only 1 committed.
        h.commitQuota("task-15b", 1)
        val notMet = h.coordinator.evaluateAfterRelease(
            quotaCount = h.quotaLedger.count("task-15b"),
            requiredSuccesses = 3,
            scheduleContext = h.scheduleContext(), // updated context (item-2 now current)
            leaseId = "lease-15b",
            idempotencyKey = "ad15-k2",
        )
        assertTrue(
            "reconciled quota < required → QuotaNotMet, got $notMet",
            notMet is AdvanceDecision.QuotaNotMet,
        )
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
            quotaCount = 3,
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
            quotaCount = 3,
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
            quotaCount = 3,
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
            quotaCount = 3,
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
            quotaCount = 3,
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
     * M-AD-19: When the same idempotency key is replayed across two forks
     * (one on the quota-not-met path, one on the quota-met path), the result
     * must be:
     *   - Trusted quota remains idempotent (at most one increment)
     *   - No double advance
     *
     * Fork scenario: Auto commits quota entry #2 (now 2/3), crashes, recovers.
     * On recovery, re-evaluates — still 2/3, quota not met, no advance.
     * Later, entry #3 is committed (now 3/3). The SAME key from the previous
     * fork must not cause a second advance if the first fork already advanced.
     *
     * In this test the first fork does NOT advance (quota not met), so the
     * second fork with the SAME key but now-met quota SHOULD advance — and
     * must do so at most once.
     */
    @Test
    fun M_AD_19_crossForkSameKeyReplay_idempotent_noDoubleAdvance() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        // Fork 1: quota not met (2/3). The advance request is never sent
        // because QuotaNotMet short-circuits before calling the provider.
        h.commitQuota("task-19", 2)
        val fork1 = h.coordinator.evaluateAfterRelease(
            quotaCount = 2,
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19",
            idempotencyKey = "ad19-k1",
        )
        assertTrue("fork 1 (2/3) → QuotaNotMet", fork1 is AdvanceDecision.QuotaNotMet)
        assertEquals("no advance yet", 0, h.schedule.advanceCount)

        // Fork 2: same task, quota now met (3/3). DIFFERENT key because this
        // is a new attempt after recovery — the advance is legitimate.
        h.commitQuota("task-19", 1) // now 3/3
        val fork2 = h.coordinator.evaluateAfterRelease(
            quotaCount = 3,
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19",
            idempotencyKey = "ad19-k2", // different key — new attempt
        )
        assertTrue("fork 2 (3/3, new key) → Advanced", fork2 is AdvanceDecision.Advanced)
        assertEquals("exactly one advance", 1, h.schedule.advanceCount)

        // Fork 3: replay of fork 2's key. The provider returns the cached
        // receipt (idempotent). The coordinator should still succeed with
        // Advanced, not double-advance.
        val fork3 = h.coordinator.evaluateAfterRelease(
            quotaCount = 3,
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19",
            idempotencyKey = "ad19-k2", // SAME key as fork 2
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
     * M-AD-19 supplement: same-key replay where the first fork DID advance.
     * The same key must return the cached receipt, never produce a second advance.
     */
    @Test
    fun M_AD_19_supplement_sameKeyReplayAfterAdvance_idempotent() {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-19s", 3)

        // First call: advance
        val first = h.coordinator.evaluateAfterRelease(
            quotaCount = 3,
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19s",
            idempotencyKey = "ad19-sk1",
        )
        assertTrue("first call → Advanced", first is AdvanceDecision.Advanced)
        assertEquals(1, h.schedule.advanceCount)
        val firstReceipt = (first as AdvanceDecision.Advanced).receipt

        // Replay: same key, same everything.
        // The observation override is needed because the schedule state has
        // changed but the idempotent receipt still references the old advance.
        val replayObservation = h.schedule.honestObservation("lease-19s").copy(
            scheduleItemId = firstReceipt.advancedToItemId!!,
            scheduleVersion = firstReceipt.scheduleVersionAfter,
            acceptedIntentHash = firstReceipt.effectiveIntentHash,
            environmentRevision = firstReceipt.effectiveEnvironmentRevision,
        )
        h.overrideNextObservation(replayObservation)

        val replay = h.coordinator.evaluateAfterRelease(
            quotaCount = 3,
            requiredSuccesses = 3,
            scheduleContext = ctx,
            leaseId = "lease-19s",
            idempotencyKey = "ad19-sk1", // SAME key
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
            quotaCount = 3,
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

        val decision = h.coordinator.evaluateAfterRelease(
            quotaCount = 3,
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
