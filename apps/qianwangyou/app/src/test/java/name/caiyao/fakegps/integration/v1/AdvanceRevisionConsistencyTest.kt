package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device-evidence regression (ZY22JHW9M4, 2026-09-06): the §6.7.5 independent
 * post-advance observe() must be able to pass the engine's four-leg equality on
 * the environmentRevision leg. completeAndAdvance used to snapshot the tracker
 * BEFORE its own SCHEDULE_BOUNDARY bump, leaving the live revision exactly one
 * ahead of effectiveEnvironmentRevision — every real post-advance observe then
 * failed the equality (receipt 38 vs observed 39, deterministic; the unit fakes
 * model both sides consistently so CI never saw it).
 *
 * The fix follows release()'s own convention: bump-THEN-receipt, so the receipt
 * describes the post-boundary environment that the observe reads.
 *
 * NOT a ledger row: this pins the §6.7.5 four-leg reachability with the REAL
 * tracker/storage assembly (supplementary lane, same discipline as
 * AdvanceProviderRedTest).
 */
class AdvanceRevisionConsistencyTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        return h
    }

    @Test
    fun advanceReceiptRevision_matchesPostAdvanceObservation() {
        val h = harness()
        val applyReceipt = h.apply(key = "adv-rev-apply")
        h.release(applyReceipt.leaseId, key = "adv-rev-rel")

        val proof = CompletionProofV1(
            scheduleItemId = "item-1",
            trustedSuccessCount = 3,
            quotaRequired = 3,
            ledgerRef = "auto:ledger:advrev:item-1",
            verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
        )
        val bare = CompleteAndAdvanceRequestV1(
            leaseId = applyReceipt.leaseId,
            idempotencyKey = "adv-rev-key",
            requestDigest = "", // computed below via the frozen contract helper
            expectedScheduleId = h.env.scheduleId,
            expectedScheduleVersion = h.env.scheduleVersion,
            expectedCurrentItemId = "item-1",
            completionProof = proof,
            callerProtocolVersion = 1,
        )
        val request = bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))

        val advanceReceipt = h.handler.completeAndAdvance(AUTO_UID, request)

        // The exact observe the engine's four-leg verification issues right after
        // a non-terminal advance: the RELEASED historical lease inside the §6.3.3
        // exception window, with the apply receipt's operationId.
        val observed = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = applyReceipt.leaseId,
                operationId = applyReceipt.operationId,
                expectedIntentHash = advanceReceipt.effectiveIntentHash,
            ),
        )

        assertEquals(
            "post-advance observe must read the receipt's effectiveEnvironmentRevision " +
                "(bump-then-receipt convention, same as release)",
            advanceReceipt.effectiveEnvironmentRevision,
            observed.environmentRevision,
        )
    }
}
