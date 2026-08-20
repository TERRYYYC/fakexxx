package com.example.cellrebelauto.automation.advance

import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1

/**
 * Auto consumer's advance protocol coordinator (§8.1 advance edges).
 *
 * **Production binding**: [AutomationEngine] calls [evaluateAfterRelease] at the
 * point where `finalizeAttemptSuccess()` marks a task "completed" (quota met).
 * The coordinator reads quota DURABLY via [QuotaReader] (Room in production,
 * [TrustedQuotaLedger] in tests) — this is what M-AD-15 enforces: after a crash,
 * recovery re-reads from the durable store, never a stale snapshot.
 *
 * All interfaces are `suspend` because the production implementations use Room
 * (async) and AIDL ContentResolver (async). Tests use in-memory suspend stubs.
 *
 * Owns the three decisions the spec places on the AUTO side of the advance:
 *
 *  1. **Quota gate** (§8.1 RELEASE_RECEIPT → ADVANCE_PENDING vs CLOSED):
 *     only `count(TrustedQuotaEntry where taskId) >= requiredSuccesses` may
 *     advance. Refs #19 AC-1, M-AD-14.
 *
 *  2. **Receipt digest verification** (§6.7.3 / §8.1 ADVANCE_PENDING edges):
 *     both ADVANCED and EXHAUSTED outcomes require this; the exhausted path
 *     must NOT bypass it. Refs #19 AC-2, M-AD-16.
 *
 *  3. **Post-advance independent verification** (§6.7.5 / §8.1 observing edges):
 *     - NON-TERMINAL: `observe()` → four-leg tuple. Refs #19 AC-3, M-AD-17/18.
 *     - TERMINAL: `discover()` → group pre-check → four-leg readback.
 */
class AdvanceCoordinator(
    private val provider: ProviderGateway,
    private val quotaReader: QuotaReader,
) {

    /**
     * Evaluate the advance decision after a successful release.
     *
     * The coordinator reads quota count DURABLY from [quotaReader] — never from
     * a caller-supplied snapshot. This is the M-AD-15 enforcement: after a crash
     * between quota write and this evaluation, recovery re-enters here and the
     * coordinator re-reads from the durable store.
     */
    suspend fun evaluateAfterRelease(
        taskId: String,
        requiredSuccesses: Int,
        scheduleContext: ScheduleContext,
        leaseId: String,
        idempotencyKey: String,
    ): AdvanceDecision {
        // ── Step 1: Quota gate (§8.1 RELEASE_RECEIPT edges) ──
        val quotaCount = quotaReader.countTrustedEntries(taskId)
        if (quotaCount < requiredSuccesses) {
            return AdvanceDecision.QuotaNotMet
        }

        // ── Step 2: Build proof + request ──
        val proof = CompletionProofV1(
            scheduleItemId = scheduleContext.currentItemId,
            trustedSuccessCount = quotaCount,
            quotaRequired = requiredSuccesses,
            ledgerRef = scheduleContext.ledgerRef,
            verifiedAtElapsedRealtimeMs = scheduleContext.verifiedAtElapsedRealtimeMs,
        )
        val bareRequest = CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = idempotencyKey,
            requestDigest = "",
            expectedScheduleId = scheduleContext.scheduleId,
            expectedScheduleVersion = scheduleContext.scheduleVersion,
            expectedCurrentItemId = scheduleContext.currentItemId,
            completionProof = proof,
            callerProtocolVersion = 1,
        )
        val requestDigest = CanonicalAdvanceDigestV1.compute(bareRequest)
        val request = bareRequest.copy(requestDigest = requestDigest)

        // ── Step 3: Call provider ──
        val receipt = provider.completeAndAdvance(request)

        // ── Step 4: Receipt digest verification (§6.7.3, M-AD-08 / M-AD-16) ──
        if (!CanonicalAdvanceReceiptDigestV1.verify(receipt, requestDigest, idempotencyKey)) {
            return AdvanceDecision.RecoveryRequired(
                reason = "ADVANCE_DIGEST_MISMATCH",
                receipt = receipt,
            )
        }

        // ── Step 5: Outcome dispatch ──
        val outcome = AdvanceOutcomeV1.fromWire(receipt.outcomeWire)
            ?: return AdvanceDecision.RecoveryRequired(
                reason = "UNKNOWN_OUTCOME_WIRE(${receipt.outcomeWire})",
                receipt = receipt,
            )

        return when (outcome) {
            AdvanceOutcomeV1.ADVANCED -> verifyNonTerminal(receipt, leaseId, scheduleContext)
            AdvanceOutcomeV1.EXHAUSTED -> verifyTerminal(receipt, scheduleContext)
        }
    }

    private suspend fun verifyNonTerminal(
        receipt: AdvanceReceiptV1,
        leaseId: String,
        scheduleContext: ScheduleContext,
    ): AdvanceDecision {
        val observation = provider.observe(leaseId, scheduleContext)
        val mismatches = mutableListOf<String>()
        if (observation.scheduleItemId != receipt.advancedToItemId)
            mismatches += "scheduleItemId(observed=${observation.scheduleItemId}, expected=${receipt.advancedToItemId})"
        if (observation.scheduleVersion != receipt.scheduleVersionAfter)
            mismatches += "scheduleVersion(observed=${observation.scheduleVersion}, expected=${receipt.scheduleVersionAfter})"
        if (observation.acceptedIntentHash != receipt.effectiveIntentHash)
            mismatches += "acceptedIntentHash(observed=${observation.acceptedIntentHash}, expected=${receipt.effectiveIntentHash})"
        if (observation.environmentRevision != receipt.effectiveEnvironmentRevision)
            mismatches += "environmentRevision(observed=${observation.environmentRevision}, expected=${receipt.effectiveEnvironmentRevision})"

        if (mismatches.isNotEmpty()) {
            return AdvanceDecision.RecoveryRequired(
                reason = "OBSERVED_TUPLE_MISMATCH: ${mismatches.joinToString("; ")}",
                receipt = receipt,
            )
        }
        return AdvanceDecision.Advanced(receipt)
    }

    private suspend fun verifyTerminal(
        receipt: AdvanceReceiptV1,
        scheduleContext: ScheduleContext,
    ): AdvanceDecision {
        val snapshot = provider.discover()

        // Group pre-check (v1.55 invariant)
        if (snapshot.currentScheduleId == null || snapshot.currentItemId == null ||
            snapshot.scheduleVersion == null || snapshot.exhausted == null
        ) {
            return AdvanceDecision.RecoveryRequired(
                reason = "EXHAUSTED_STATE_MISMATCH: partial-null projection group " +
                    "(currentScheduleId=${snapshot.currentScheduleId}, currentItemId=${snapshot.currentItemId}, " +
                    "scheduleVersion=${snapshot.scheduleVersion}, exhausted=${snapshot.exhausted})",
                receipt = receipt,
            )
        }

        val mismatches = mutableListOf<String>()
        if (snapshot.currentScheduleId != scheduleContext.scheduleId)
            mismatches += "currentScheduleId(readback=${snapshot.currentScheduleId}, expected=${scheduleContext.scheduleId})"
        if (snapshot.currentItemId != receipt.advancedFromItemId)
            mismatches += "currentItemId(readback=${snapshot.currentItemId}, expected=${receipt.advancedFromItemId})"
        if (snapshot.scheduleVersion != receipt.scheduleVersionAfter)
            mismatches += "scheduleVersion(readback=${snapshot.scheduleVersion}, expected=${receipt.scheduleVersionAfter})"
        if (snapshot.exhausted != true)
            mismatches += "exhausted(readback=${snapshot.exhausted}, expected=true)"

        if (mismatches.isNotEmpty()) {
            return AdvanceDecision.RecoveryRequired(
                reason = "EXHAUSTED_STATE_MISMATCH: ${mismatches.joinToString("; ")}",
                receipt = receipt,
            )
        }
        return AdvanceDecision.Exhausted(receipt)
    }
}

/** Advance protocol outcomes. */
sealed class AdvanceDecision {
    object QuotaNotMet : AdvanceDecision()
    data class Advanced(val receipt: AdvanceReceiptV1) : AdvanceDecision()
    data class Exhausted(val receipt: AdvanceReceiptV1) : AdvanceDecision()
    data class RecoveryRequired(val reason: String, val receipt: AdvanceReceiptV1? = null) : AdvanceDecision()
}

/**
 * Durable quota reader. Production: Room DAO. Tests: [TrustedQuotaLedger].
 * Suspend because Room DAOs are async.
 */
interface QuotaReader {
    suspend fun countTrustedEntries(taskId: String): Int
}

/** Schedule projection captured at attempt open time (§6.7.3 v1.72). */
data class ScheduleContext(
    val scheduleId: String,
    val currentItemId: String,
    val scheduleVersion: Long,
    val ledgerRef: String,
    val verifiedAtElapsedRealtimeMs: Long,
)

/**
 * Gateway to the provider's advance-related operations.
 * Suspend because production uses AIDL ContentResolver (async).
 */
interface ProviderGateway {
    suspend fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1
    suspend fun observe(leaseId: String, context: ScheduleContext): EnvironmentObservationV1
    suspend fun discover(): CapabilitySnapshotV1
}
