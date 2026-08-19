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
 * Owns the three decisions the spec places on the AUTO side of the advance:
 *
 *  1. **Quota gate** (§8.1 RELEASE_RECEIPT → ADVANCE_PENDING vs CLOSED):
 *     only `count(TrustedQuotaEntry where taskId) >= requiredSuccesses` may
 *     advance. A single committed entry does NOT mean "quota met" — that
 *     conflation would advance a `requiredSuccesses = 3` task after the first
 *     attempt (v1.46 defect). Refs #19 AC-1, M-AD-14.
 *
 *  2. **Receipt digest verification** (§6.7.3 / §8.1 ADVANCE_PENDING edges):
 *     a receipt whose `receiptDigest` does not recompute is not a "weaker
 *     receipt" — it is not a receipt. Both ADVANCED and EXHAUSTED outcomes
 *     require this; the exhausted path must NOT bypass it (v1.46 defect fix).
 *     Refs #19 AC-2, M-AD-16.
 *
 *  3. **Post-advance independent verification** (§6.7.5 / §8.1 observing edges):
 *     - NON-TERMINAL: `observe()` → four-leg tuple must match (item, version,
 *       intentHash, revision). Refs #19 AC-3, M-AD-17, M-AD-18.
 *     - TERMINAL: `discover()` → group pre-check (all-non-null) → four-leg
 *       readback (scheduleId, item, version, exhausted). Refs M-AD-23.
 *
 * The coordinator is stateless and pure — it delegates persistence to the caller
 * so that the transactional boundaries (INV-3) are not duplicated here.
 *
 * # 坐标系设计备注
 * 本协调器不持有状态：入参是当前 attempt 的上下文快照，出参是决策 + 证据。
 * 持久化（attempt 行更新、配额账本）由调用方在同一事务内完成——把事务拆到两个
 * owner 里会让 INV-3 的原子性保证变成两个半保证。
 */
class AdvanceCoordinator(
    private val provider: ProviderGateway,
) {

    /**
     * Evaluate the advance decision after a successful release.
     *
     * @param quotaCount current `count(TrustedQuotaEntry where taskId)`
     * @param requiredSuccesses the task's quota threshold
     * @param scheduleContext the persisted schedule projection from attempt open time (§6.7.3 v1.72)
     * @param leaseId the RELEASED historical lease this attempt earned under
     * @param idempotencyKey advance idempotency key (persisted before the call)
     * @return the decision and evidence, never throws for protocol-level outcomes
     */
    fun evaluateAfterRelease(
        quotaCount: Int,
        requiredSuccesses: Int,
        scheduleContext: ScheduleContext,
        leaseId: String,
        idempotencyKey: String,
    ): AdvanceDecision {
        // ── Step 1: Quota gate (§8.1 RELEASE_RECEIPT edges) ──
        // M-AD-14: quota committed but NOT met → close, no advance
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
            requestDigest = "", // computed below
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
        // Both ADVANCED and EXHAUSTED outcomes require this — the exhausted path
        // must NOT bypass it. "A receipt whose digest does not recompute is not a
        // weaker receipt — it is not a receipt."
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
            AdvanceOutcomeV1.ADVANCED -> verifyNonTerminal(
                receipt = receipt,
                leaseId = leaseId,
                scheduleContext = scheduleContext,
            )

            AdvanceOutcomeV1.EXHAUSTED -> verifyTerminal(
                receipt = receipt,
                scheduleContext = scheduleContext,
            )
        }
    }

    /**
     * Non-terminal advance verification via `observe()` (§6.7.5).
     *
     * Four-leg tuple (v1.68):
     *   observation.scheduleItemId     == receipt.advancedToItemId
     *   observation.scheduleVersion    == receipt.scheduleVersionAfter
     *   observation.acceptedIntentHash == receipt.effectiveIntentHash
     *   observation.environmentRevision == receipt.effectiveEnvironmentRevision
     */
    private fun verifyNonTerminal(
        receipt: AdvanceReceiptV1,
        leaseId: String,
        scheduleContext: ScheduleContext,
    ): AdvanceDecision {
        val observation = provider.observe(leaseId, scheduleContext)

        // Four-leg comparison — each leg is checked individually so the typed
        // reason can name which leg failed (§8.1 OBSERVED_TUPLE_MISMATCH).
        val mismatches = mutableListOf<String>()
        if (observation.scheduleItemId != receipt.advancedToItemId) {
            mismatches += "scheduleItemId(observed=${observation.scheduleItemId}, " +
                "expected=${receipt.advancedToItemId})"
        }
        if (observation.scheduleVersion != receipt.scheduleVersionAfter) {
            mismatches += "scheduleVersion(observed=${observation.scheduleVersion}, " +
                "expected=${receipt.scheduleVersionAfter})"
        }
        if (observation.acceptedIntentHash != receipt.effectiveIntentHash) {
            mismatches += "acceptedIntentHash(observed=${observation.acceptedIntentHash}, " +
                "expected=${receipt.effectiveIntentHash})"
        }
        if (observation.environmentRevision != receipt.effectiveEnvironmentRevision) {
            mismatches += "environmentRevision(observed=${observation.environmentRevision}, " +
                "expected=${receipt.effectiveEnvironmentRevision})"
        }

        if (mismatches.isNotEmpty()) {
            return AdvanceDecision.RecoveryRequired(
                reason = "OBSERVED_TUPLE_MISMATCH: ${mismatches.joinToString("; ")}",
                receipt = receipt,
            )
        }

        return AdvanceDecision.Advanced(receipt)
    }

    /**
     * Terminal (EXHAUSTED) advance verification via `discover()` (§6.7.5 v1.58/v1.68).
     *
     * NOT via observe(): advancedToItemId is null for EXHAUSTED, while
     * EnvironmentObservationV1.scheduleItemId is non-null — that leg can never
     * hold, so observe() is structurally inapplicable for terminal advances.
     *
     * NOT via preflight(): PreflightReportV1 carries no currentScheduleId and
     * names its field scheduleItemId — it cannot establish schedule identity.
     *
     * Steps:
     *   0. Group pre-check: all four projection fields non-null (v1.55 invariant)
     *   1. Four-leg readback (v1.68):
     *        currentScheduleId  == the schedule this advance targeted
     *        currentItemId      == receipt.advancedFromItemId
     *        scheduleVersion    == receipt.scheduleVersionAfter
     *        exhausted          == true
     */
    private fun verifyTerminal(
        receipt: AdvanceReceiptV1,
        scheduleContext: ScheduleContext,
    ): AdvanceDecision {
        val snapshot = provider.discover()

        // ── Group pre-check (v1.55 invariant, M-AD-27) ──
        if (snapshot.currentScheduleId == null ||
            snapshot.currentItemId == null ||
            snapshot.scheduleVersion == null ||
            snapshot.exhausted == null
        ) {
            return AdvanceDecision.RecoveryRequired(
                reason = "EXHAUSTED_STATE_MISMATCH: partial-null projection group " +
                    "(currentScheduleId=${snapshot.currentScheduleId}, " +
                    "currentItemId=${snapshot.currentItemId}, " +
                    "scheduleVersion=${snapshot.scheduleVersion}, " +
                    "exhausted=${snapshot.exhausted})",
                receipt = receipt,
            )
        }

        // ── Four-leg readback ──
        val mismatches = mutableListOf<String>()
        if (snapshot.currentScheduleId != scheduleContext.scheduleId) {
            mismatches += "currentScheduleId(readback=${snapshot.currentScheduleId}, " +
                "expected=${scheduleContext.scheduleId})"
        }
        if (snapshot.currentItemId != receipt.advancedFromItemId) {
            mismatches += "currentItemId(readback=${snapshot.currentItemId}, " +
                "expected=${receipt.advancedFromItemId})"
        }
        if (snapshot.scheduleVersion != receipt.scheduleVersionAfter) {
            mismatches += "scheduleVersion(readback=${snapshot.scheduleVersion}, " +
                "expected=${receipt.scheduleVersionAfter})"
        }
        if (snapshot.exhausted != true) {
            mismatches += "exhausted(readback=${snapshot.exhausted}, expected=true)"
        }

        if (mismatches.isNotEmpty()) {
            return AdvanceDecision.RecoveryRequired(
                reason = "EXHAUSTED_STATE_MISMATCH: ${mismatches.joinToString("; ")}",
                receipt = receipt,
            )
        }

        return AdvanceDecision.Exhausted(receipt)
    }
}

/**
 * Advance protocol outcomes. Each variant carries the evidence the caller needs
 * to persist the state transition.
 */
sealed class AdvanceDecision {

    /** §8.1 RELEASE_RECEIPT(未达标) → CLOSED: quota not met, no advance issued. */
    object QuotaNotMet : AdvanceDecision()

    /** §8.1 ADVANCE_OBSERVING → CLOSED: non-terminal advance independently verified. */
    data class Advanced(val receipt: AdvanceReceiptV1) : AdvanceDecision()

    /** §8.1 ADVANCE_STATE_READBACK → CLOSED: terminal advance independently verified. */
    data class Exhausted(val receipt: AdvanceReceiptV1) : AdvanceDecision()

    /** §8.1 → RECOVERY_REQUIRED: typed reason names the failing leg. */
    data class RecoveryRequired(
        val reason: String,
        val receipt: AdvanceReceiptV1? = null,
    ) : AdvanceDecision()
}

/**
 * Schedule projection captured at attempt open time (§6.7.3 v1.72).
 *
 * These values are persisted when the attempt opens and replayed VERBATIM into
 * the advance request. Reading fresh values just before advance is the defect
 * the identity leg exists to stop — it answers "which schedule is effective now",
 * not "which schedule did this completion belong to" (M-AD-28).
 */
data class ScheduleContext(
    val scheduleId: String,
    val currentItemId: String,
    val scheduleVersion: Long,
    val ledgerRef: String,
    val verifiedAtElapsedRealtimeMs: Long,
)

/**
 * Gateway to the provider's advance-related operations.
 *
 * In production this wraps the AIDL ContentResolver calls; in tests the
 * [ConsumerHarness] implements it with controllable fakes.
 */
interface ProviderGateway {
    /** §6.7 completeAndAdvance. May throw ContractException for typed errors. */
    fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1

    /** §6.7.5 post-advance observe (non-terminal only). */
    fun observe(leaseId: String, context: ScheduleContext): EnvironmentObservationV1

    /** §6.7.5 post-advance discover (terminal only, NOT preflight). */
    fun discover(): CapabilitySnapshotV1
}
