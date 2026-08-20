package com.example.cellrebelauto.matrix

import com.example.cellrebelauto.automation.advance.AdvanceCoordinator
import com.example.cellrebelauto.automation.advance.AdvanceDecision
import com.example.cellrebelauto.automation.advance.ProviderGateway
import com.example.cellrebelauto.automation.advance.ScheduleContext
import com.example.cellrebelauto.support.ConsumerHarness
import com.example.cellrebelauto.support.CrashSimulation
import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 advance rows owned by the Auto consumer lane — M-AD-14 through M-AD-19.
 *
 * All coordinator calls are `suspend`, so every test runs inside `runBlocking`.
 * The suspend interfaces match the production signatures (Room DAO, AIDL).
 *
 * Constraint: semantic only. No v1 AIDL method set / DTO field·order·type /
 * wire value changes.
 */
class AdvanceMatrixTest {

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-14: Quota committed but NOT met → no advance
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun M_AD_14_quotaNotMet_noAdvance() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        // (a) Zero quota → QuotaNotMet
        val decision0 = h.coordinator.evaluateAfterRelease(
            taskId = "task-14", requiredSuccesses = 3,
            scheduleContext = ctx, leaseId = "lease-14a", idempotencyKey = "ad14-k0",
        )
        assertTrue("zero quota → QuotaNotMet", decision0 is AdvanceDecision.QuotaNotMet)
        assertEquals("no advance", 0, h.schedule.advanceCount)

        // (b) 1/3 → QuotaNotMet
        h.commitQuota("task-14", 1)
        val decision1 = h.coordinator.evaluateAfterRelease(
            taskId = "task-14", requiredSuccesses = 3,
            scheduleContext = ctx, leaseId = "lease-14b", idempotencyKey = "ad14-k1",
        )
        assertTrue("1/3 → QuotaNotMet", decision1 is AdvanceDecision.QuotaNotMet)
        assertEquals("still no advance", 0, h.schedule.advanceCount)

        // (c) 3/3 boundary → Advanced
        h.commitQuota("task-14", 2)
        val decisionMet = h.coordinator.evaluateAfterRelease(
            taskId = "task-14", requiredSuccesses = 3,
            scheduleContext = ctx, leaseId = "lease-14c", idempotencyKey = "ad14-k2",
        )
        assertTrue("3/3 → Advanced", decisionMet is AdvanceDecision.Advanced)
        assertEquals("advance happened", 1, h.schedule.advanceCount)
        assertEquals("item-2", h.schedule.currentItemId)
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-15: Crash between quota commit and met determination
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun M_AD_15_crashBetweenQuotaWriteAndMetDetermination_recoveryReReads() = runBlocking {
        val h = ConsumerHarness.create()

        // Phase 1: durable quota write, then crash
        h.commitQuota("task-15", 2)
        // "CRASH" — TrustedQuotaLedger retains entries

        // Phase 2: recovery — coordinator re-reads from durable store
        val ctx = h.scheduleContext()
        val afterCrash = h.coordinator.evaluateAfterRelease(
            taskId = "task-15", requiredSuccesses = 3,
            scheduleContext = ctx, leaseId = "lease-15", idempotencyKey = "ad15-k1",
        )
        assertTrue("recovery reads 2/3 → QuotaNotMet", afterCrash is AdvanceDecision.QuotaNotMet)
        assertEquals("no advance", 0, h.schedule.advanceCount)

        // Phase 3: more entries, re-evaluate
        h.commitQuota("task-15", 1) // now 3/3
        val afterThird = h.coordinator.evaluateAfterRelease(
            taskId = "task-15", requiredSuccesses = 3,
            scheduleContext = ctx, leaseId = "lease-15", idempotencyKey = "ad15-k2",
        )
        assertTrue("re-read 3/3 → Advanced", afterThird is AdvanceDecision.Advanced)
        assertEquals("advance happened", 1, h.schedule.advanceCount)
    }

    @Test
    fun M_AD_15_supplement_crashDuringProviderCall_idempotentReplay() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-15s", 3)

        // Gateway that crashes after provider commit on first call
        val crashGateway = object : ProviderGateway {
            private var firstCall = true
            override suspend fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
                val receipt = h.schedule.advance(request)
                if (firstCall) { firstCall = false; throw CrashSimulation("crash after provider commit") }
                return receipt
            }
            override suspend fun observe(leaseId: String, context: ScheduleContext) =
                h.schedule.honestObservation(leaseId)
            override suspend fun discover() = h.schedule.honestSnapshot()
        }
        val crashCoordinator = AdvanceCoordinator(crashGateway, h.quotaLedger)

        // First call: crashes
        var crashed = false
        try {
            crashCoordinator.evaluateAfterRelease(
                taskId = "task-15s", requiredSuccesses = 3, scheduleContext = ctx,
                leaseId = "lease-15s", idempotencyKey = "ad15-sk1",
            )
        } catch (e: CrashSimulation) { crashed = true }
        assertTrue("crash propagated", crashed)
        assertEquals("provider advanced", 1, h.schedule.advanceCount)

        // Recovery: same key → idempotent
        val recovered = crashCoordinator.evaluateAfterRelease(
            taskId = "task-15s", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-15s", idempotencyKey = "ad15-sk1",
        )
        assertTrue("recovery → Advanced", recovered is AdvanceDecision.Advanced)
        assertEquals("still one advance", 1, h.schedule.advanceCount)
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-16: EXHAUSTED receipt digest mismatch
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun M_AD_16_exhaustedReceiptDigestMismatch_recoveryRequired() = runBlocking {
        val h = ConsumerHarness.create(items = listOf("item-1"))
        val ctx = h.scheduleContext()
        h.commitQuota("task-16", 3)
        h.corruptNextReceiptDigest()

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-16", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-16", idempotencyKey = "ad16-k1",
        )
        assertTrue("corrupt EXHAUSTED → RecoveryRequired", decision is AdvanceDecision.RecoveryRequired)
        assertTrue((decision as AdvanceDecision.RecoveryRequired).reason.contains("ADVANCE_DIGEST_MISMATCH"))
    }

    @Test
    fun M_AD_16_control_honestExhaustedReceiptDigest_accepted() = runBlocking {
        val h = ConsumerHarness.create(items = listOf("item-1"))
        val ctx = h.scheduleContext()
        h.commitQuota("task-16c", 3)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-16c", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-16c", idempotencyKey = "ad16-ck1",
        )
        assertTrue("honest EXHAUSTED → Exhausted", decision is AdvanceDecision.Exhausted)
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-17: Non-terminal observe intentHash mismatch
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun M_AD_17_nonTerminalObserve_intentHashMismatch_recoveryRequired() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-17", 3)

        val fakeObs = h.schedule.honestObservation("lease-17").copy(
            scheduleItemId = "item-2", scheduleVersion = 2L,
            acceptedIntentHash = "WRONG-intent-hash", environmentRevision = 200L,
        )
        h.overrideNextObservation(fakeObs)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-17", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-17", idempotencyKey = "ad17-k1",
        )
        assertTrue("intentHash mismatch → RecoveryRequired", decision is AdvanceDecision.RecoveryRequired)
        val r = decision as AdvanceDecision.RecoveryRequired
        assertTrue(r.reason.contains("OBSERVED_TUPLE_MISMATCH"))
        assertTrue(r.reason.contains("acceptedIntentHash"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-18: Non-terminal observe environmentRevision mismatch
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun M_AD_18_nonTerminalObserve_revisionMismatch_recoveryRequired() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-18", 3)

        val fakeObs = h.schedule.honestObservation("lease-18").copy(
            scheduleItemId = "item-2", scheduleVersion = 2L,
            acceptedIntentHash = "intent-hash-item-2", environmentRevision = 999L,
        )
        h.overrideNextObservation(fakeObs)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-18", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-18", idempotencyKey = "ad18-k1",
        )
        assertTrue("revision mismatch → RecoveryRequired", decision is AdvanceDecision.RecoveryRequired)
        val r = decision as AdvanceDecision.RecoveryRequired
        assertTrue(r.reason.contains("environmentRevision"))
    }

    @Test
    fun M_AD_18_supplement_versionLegMismatch_recoveryRequired() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-18s", 3)

        val fakeObs = h.schedule.honestObservation("lease-18s").copy(
            scheduleItemId = "item-2", scheduleVersion = 999L,
            acceptedIntentHash = "intent-hash-item-2", environmentRevision = 200L,
        )
        h.overrideNextObservation(fakeObs)

        val decision = h.coordinator.evaluateAfterRelease(
            taskId = "task-18s", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-18s", idempotencyKey = "ad18-sk1",
        )
        assertTrue("version mismatch → RecoveryRequired", decision is AdvanceDecision.RecoveryRequired)
        assertTrue((decision as AdvanceDecision.RecoveryRequired).reason.contains("scheduleVersion"))
    }

    // ════════════════════════════════════════════════════════════════════════
    // M-AD-19: Cross-fork same-key replay
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun M_AD_19_crossForkSameKeyReplay_idempotent_noDoubleAdvance() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        val SHARED_KEY = "ad19-shared-key"

        h.commitQuota("task-19", 2)
        val fork1 = h.coordinator.evaluateAfterRelease(
            taskId = "task-19", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-19", idempotencyKey = SHARED_KEY,
        )
        assertTrue("fork 1 (2/3) → QuotaNotMet", fork1 is AdvanceDecision.QuotaNotMet)
        assertEquals(0, h.schedule.advanceCount)

        h.commitQuota("task-19", 1)
        val fork2 = h.coordinator.evaluateAfterRelease(
            taskId = "task-19", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-19", idempotencyKey = SHARED_KEY,
        )
        assertTrue("fork 2 (3/3, same key) → Advanced", fork2 is AdvanceDecision.Advanced)
        assertEquals(1, h.schedule.advanceCount)

        val fork3 = h.coordinator.evaluateAfterRelease(
            taskId = "task-19", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-19", idempotencyKey = SHARED_KEY,
        )
        assertTrue("fork 3 (same key replay) → Advanced", fork3 is AdvanceDecision.Advanced)
        assertEquals("idempotent", 1, h.schedule.advanceCount)
    }

    @Test
    fun M_AD_19_supplement_quotaLedgerIdempotency_noDuplicateCounting() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()

        val first = h.commitQuotaEntry("task-19s", "attempt-1")
        assertTrue("first → new", first)
        assertEquals(1, h.quotaLedger.countTrustedEntries("task-19s"))

        val dup = h.commitQuotaEntry("task-19s", "attempt-1")
        assertFalse("dup → rejected", dup)
        assertEquals("still 1", 1, h.quotaLedger.countTrustedEntries("task-19s"))

        h.commitQuotaEntry("task-19s", "attempt-2")
        h.commitQuotaEntry("task-19s", "attempt-3")
        assertEquals(3, h.quotaLedger.countTrustedEntries("task-19s"))

        val met = h.coordinator.evaluateAfterRelease(
            taskId = "task-19s", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-19s", idempotencyKey = "ad19s-k1",
        )
        assertTrue("3/3 → Advanced", met is AdvanceDecision.Advanced)
    }

    @Test
    fun M_AD_19_supplement_sameKeyReplayAfterAdvance_idempotent() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-19r", 3)

        val first = h.coordinator.evaluateAfterRelease(
            taskId = "task-19r", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-19r", idempotencyKey = "ad19r-k1",
        )
        assertTrue("first → Advanced", first is AdvanceDecision.Advanced)
        assertEquals(1, h.schedule.advanceCount)
        val firstReceipt = (first as AdvanceDecision.Advanced).receipt

        val replayObs = h.schedule.honestObservation("lease-19r").copy(
            scheduleItemId = firstReceipt.advancedToItemId!!,
            scheduleVersion = firstReceipt.scheduleVersionAfter,
            acceptedIntentHash = firstReceipt.effectiveIntentHash,
            environmentRevision = firstReceipt.effectiveEnvironmentRevision,
        )
        h.overrideNextObservation(replayObs)

        val replay = h.coordinator.evaluateAfterRelease(
            taskId = "task-19r", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-19r", idempotencyKey = "ad19r-k1",
        )
        assertTrue("replay → Advanced", replay is AdvanceDecision.Advanced)
        assertEquals("still one", 1, h.schedule.advanceCount)
        assertEquals(firstReceipt.receiptDigest, (replay as AdvanceDecision.Advanced).receipt.receiptDigest)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Positive controls
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun control_honestNonTerminalAdvance_succeeds() = runBlocking {
        val h = ConsumerHarness.create()
        val ctx = h.scheduleContext()
        h.commitQuota("task-ctrl", 3)

        val d = h.coordinator.evaluateAfterRelease(
            taskId = "task-ctrl", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-ctrl", idempotencyKey = "ctrl-k1",
        )
        assertTrue("honest → Advanced", d is AdvanceDecision.Advanced)
        assertEquals("item-2", (d as AdvanceDecision.Advanced).receipt.advancedToItemId)
    }

    @Test
    fun control_honestTerminalAdvance_succeeds() = runBlocking {
        val h = ConsumerHarness.create(items = listOf("only-item"))
        val ctx = h.scheduleContext()
        h.commitQuota("task-term", 3)

        val d = h.coordinator.evaluateAfterRelease(
            taskId = "task-term", requiredSuccesses = 3, scheduleContext = ctx,
            leaseId = "lease-term", idempotencyKey = "term-k1",
        )
        assertTrue("terminal → Exhausted", d is AdvanceDecision.Exhausted)
        assertNull((d as AdvanceDecision.Exhausted).receipt.advancedToItemId)
    }
}
