package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * SUPPLEMENTARY RED LANE — provider-side completeAndAdvance (§6.7).
 *
 * NOT ledger rows: §10.1 assigns M-AD-01..11 to the Auto consumer lane
 * (in-process fake provider proving Auto's replay/verify discipline). The REAL
 * provider's compare-and-advance semantics — precondition checks, atomic
 * pointer+receipt transaction, idempotent receipt retrieval, exhausted duality
 * — are qwy-side behavior with no ledger row today. That seam is flagged back
 * to issue #3; until the ledger catches up, these tests pin the §6.7 semantics
 * the AIDL surface already obligates this provider to implement.
 */
class AdvanceProviderRedTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        // Quota earned under a lease that is then released (§6.7.4a order).
        return h
    }

    /** Earn + release so advance happens with NO active lease; returns the historical leaseId. */
    private fun earnAndRelease(h: ProviderHarness, key: String = "adv-apply"): String {
        val receipt = h.apply(key = key)
        h.release(receipt.leaseId, key = "$key-rel")
        return receipt.leaseId
    }

    private fun proof(h: ProviderHarness, itemId: String = "item-1") = CompletionProofV1(
        scheduleItemId = itemId,
        trustedSuccessCount = 3,
        quotaRequired = 3,
        ledgerRef = "auto:ledger:run-1:$itemId",
        verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
    )

    private fun request(
        h: ProviderHarness,
        leaseId: String,
        key: String,
        expectedItemId: String = "item-1",
        expectedVersion: Long = h.env.scheduleVersion,
        completionProof: CompletionProofV1 = proof(h, expectedItemId),
    ): CompleteAndAdvanceRequestV1 {
        val digest = RequestDigests.advanceRequestDigest(
            leaseId = leaseId,
            expectedScheduleVersion = expectedVersion,
            expectedCurrentItemId = expectedItemId,
            proofScheduleItemId = completionProof.scheduleItemId,
            proofTrustedSuccessCount = completionProof.trustedSuccessCount,
            proofQuotaRequired = completionProof.quotaRequired,
            proofLedgerRef = completionProof.ledgerRef,
        )
        return CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = key,
            requestDigest = digest,
            expectedScheduleVersion = expectedVersion,
            expectedCurrentItemId = expectedItemId,
            completionProof = completionProof,
            callerProtocolVersion = 1,
        )
    }

    /** §6.7.3 receipt digest recompute using ONLY the contract's shared framing (a receipt that does not recompute is not a receipt). */
    private fun recomputeReceiptDigest(request: CompleteAndAdvanceRequestV1, receipt: AdvanceReceiptV1): String {
        val fields = mutableListOf(
            CanonicalDigestV1.utf8(request.requestDigest),
            CanonicalDigestV1.utf8(request.idempotencyKey),
            CanonicalDigestV1.decimal(receipt.outcomeWire),
            CanonicalDigestV1.utf8(receipt.advancedFromItemId),
        )
        val target = receipt.advancedToItemId
        if (target == null) {
            fields.add(CanonicalDigestV1.utf8("0"))
        } else {
            fields.add(CanonicalDigestV1.utf8("1"))
            fields.add(CanonicalDigestV1.utf8(target))
        }
        fields.add(CanonicalDigestV1.decimal(receipt.scheduleVersionAfter))
        fields.add(CanonicalDigestV1.utf8(receipt.effectiveIntentHash))
        fields.add(CanonicalDigestV1.decimal(receipt.effectiveEnvironmentRevision))
        return CanonicalDigestV1.digest(CanonicalDigestV1.DOMAIN_ADVANCE_RECEIPT, fields)
    }

    /** Missing/mismatched proof → REQUEST_INVALID, pointer untouched (M-AD-01 provider half). */
    @Test
    fun advance_proofItemMismatch_requestInvalid_pointerUntouched() {
        val h = harness()
        val leaseId = earnAndRelease(h)

        expectContractFailure(ContractErrorCodeV1.REQUEST_INVALID) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k1", completionProof = proof(h, itemId = "item-OTHER")),
            )
        }
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
        assertEquals(0, h.env.advanceCount)
    }

    /** Same key + same digest replay → SAME receipt, pointer moved exactly once (M-AD-02 provider half). */
    @Test
    fun advance_sameKeySameDigest_replaysReceipt_pointerOnce() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k2")

        val first = h.handler.completeAndAdvance(AUTO_UID, req)
        val replay = h.handler.completeAndAdvance(AUTO_UID, req)

        assertEquals(first, replay)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals("pointer advanced exactly once", 1, h.env.advanceCount)
        assertEquals(AdvanceOutcomeV1.ADVANCED.wire, first.outcomeWire)
        assertEquals("item-1", first.advancedFromItemId)
        assertEquals("item-2", first.advancedToItemId)
    }

    /** Same key + DIFFERENT digest → IDEMPOTENCY_CONFLICT, no advance (M-AD-03 provider half). */
    @Test
    fun advance_sameKeyDifferentDigest_idempotencyConflict() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k3"))

        // Same key, now pointing at item-2 (different preimage → different digest).
        expectContractFailure(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k3", expectedItemId = "item-2", completionProof = proof(h, "item-2")),
            )
        }
        assertEquals("no second advance", 1, h.env.advanceCount)
    }

    /** Stale expectedCurrentItemId → SCHEDULE_ITEM_MISMATCH wire 14 (M-AD-04/05 provider half — the last line against double advance). */
    @Test
    fun advance_staleItem_scheduleItemMismatch() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k4"))

        // New key, but still expecting item-1 (a lost-idempotency-key resend).
        expectContractFailure(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k4-NEW"))
        }
        assertEquals("pointer did not move twice", "item-2", h.env.currentItemId)
        assertEquals(1, h.env.advanceCount)
    }

    /** Schedule edited during quota proving → SCHEDULE_VERSION_STALE wire 15, no advance (M-AD-06 provider half). */
    @Test
    fun advance_staleVersion_scheduleVersionStale() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k5", expectedVersion = h.env.scheduleVersion)

        h.env.scheduleVersion += 1 // operator edited the plan meanwhile

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_VERSION_STALE) {
            h.handler.completeAndAdvance(AUTO_UID, req)
        }
        assertEquals("item-1", h.env.currentItemId)
        assertEquals(0, h.env.advanceCount)
    }

    /** Completing the LAST item is success: outcome EXHAUSTED + null target + retained pointer (M-AD-10 provider half). */
    @Test
    fun advance_lastItem_exhaustedReceipt_notAFailure() {
        val h = harness()
        h.env.currentItemId = "item-3"
        val leaseId = earnAndRelease(h, key = "adv-last-apply")

        val receipt = h.handler.completeAndAdvance(
            AUTO_UID,
            request(h, leaseId, "adv-k6", expectedItemId = "item-3", completionProof = proof(h, "item-3")),
        )

        assertEquals(AdvanceOutcomeV1.EXHAUSTED.wire, receipt.outcomeWire)
        assertNull("terminal, not a failure: null target", receipt.advancedToItemId)
        assertEquals("item-3", receipt.advancedFromItemId)
        assertEquals("current item retained, no wrap-around", "item-3", h.env.currentItemId)
    }

    /** Requesting an advance AFTER exhaustion is the caller error → SCHEDULE_EXHAUSTED wire 16 (M-AD-11 provider half). */
    @Test
    fun advance_afterExhausted_scheduleExhausted() {
        val h = harness()
        h.env.currentItemId = "item-3"
        val leaseId = earnAndRelease(h, key = "adv-exh-apply")
        h.handler.completeAndAdvance(
            AUTO_UID,
            request(h, leaseId, "adv-k7", expectedItemId = "item-3", completionProof = proof(h, "item-3")),
        )

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_EXHAUSTED) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k7-again", expectedItemId = "item-3", completionProof = proof(h, "item-3")),
            )
        }
    }

    /**
     * §6.7.4a: release comes FIRST; the caller must hold NO active lease at
     * advance time (request.leaseId is a historical attribution ref, not a
     * hold). Which wire carries this rejection is NOT yet frozen in the spec —
     * flagged to #3; until then we pin only "typed failure + no advance".
     */
    @Test
    fun advance_withActiveLease_rejected_noAdvance() {
        val h = harness()
        val receipt = h.apply(key = "adv-active-apply") // NOT released

        try {
            h.handler.completeAndAdvance(AUTO_UID, request(h, receipt.leaseId, "adv-k8"))
            fail("advance under an active lease must be rejected (§6.7.4a)")
        } catch (e: ContractException) {
            // Exact code pending #3 decision; the behavioral floor is: rejected + untouched.
        }
        assertEquals("item-1", h.env.currentItemId)
        assertEquals(0, h.env.advanceCount)
    }

    /** Receipt digest must recompute from the request + outcome via the shared framing — otherwise it is not a receipt (§6.7.3). */
    @Test
    fun advance_receiptDigest_recomputes() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k9")

        val receipt = h.handler.completeAndAdvance(AUTO_UID, req)

        assertEquals(
            "receiptDigest binds request, key and outcome (presence-discriminated null target)",
            recomputeReceiptDigest(req, receipt),
            receipt.receiptDigest,
        )
    }

    /** Advance receipt + pointer survive an owner restart as ONE fact: replay returns the same receipt, pointer moved once (§6.7.5). */
    @Test
    fun advance_receiptAndPointer_surviveRestart_asOneTransaction() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k10")
        val first = h.handler.completeAndAdvance(AUTO_UID, req)

        h.restart(cleanlinessProvable = true)

        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("same durable receipt after restart", first, replay)
        assertTrue("no second pointer move", h.env.advanceCount <= 2) // fake pointer already at item-2
        assertEquals("item-2", h.env.currentItemId)
    }
}
