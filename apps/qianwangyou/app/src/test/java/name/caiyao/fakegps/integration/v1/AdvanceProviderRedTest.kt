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
import org.junit.Test

/**
 * SUPPLEMENTARY RED LANE — provider-side completeAndAdvance (§6.7).
 *
 * NOT ledger rows and NOT counted as ledger coverage: §10.1 today assigns
 * M-AD-01..11 to the Auto consumer lane. Provider-side M-AD row IDs are being
 * added by Opus5 in the contract PR (routed via #3 after Sol's verification of
 * d21102d); ONLY once those IDs + precedence land in a canonical HEAD do these
 * methods get bound row-by-row to exact IDs and enter the derived counts.
 * Until that binding, deleting this file would still leave the verifier
 * green — this coverage is real but VIRTUAL to the gate (Sol's warning).
 *
 * Precedence pinned here (Sol-verified ordering, awaiting canonical freeze):
 *   1. same-key + same-digest replay short-circuits FIRST (returns the stored
 *      receipt even if the caller now holds a new active lease);
 *   2. for a fresh request: schedule preconditions (§6.7.4 wire 14/15/16)
 *      are judged BEFORE the active-lease gate;
 *   3. the active-lease gate (§6.7.4a, ruling direction: exact
 *      LEASE_CONFLICT(7), no new wire) sits AFTER the preconditions and
 *      BEFORE any mutation — a rejected advance never moves the pointer.
 */
class AdvanceProviderRedTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
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

    // ---------------------------------------------------------------- proof

    /** MISSING proof (blank required refs) → REQUEST_INVALID via wire 13's frozen trigger, pointer untouched (M-AD-01 "无 proof" half). */
    @Test
    fun advance_proofMissing_requestInvalid_pointerUntouched() {
        val h = harness()
        val leaseId = earnAndRelease(h)

        val blankProof = CompletionProofV1(
            scheduleItemId = "",
            trustedSuccessCount = 0,
            quotaRequired = 0,
            ledgerRef = "",
            verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
        )
        expectContractFailure(ContractErrorCodeV1.REQUEST_INVALID) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k0", completionProof = blankProof),
            )
        }
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
        assertEquals(0, h.env.advanceCount)
    }

    /** Proof present but pointing at a NON-current item → REQUEST_INVALID, pointer untouched (M-AD-01 mismatch half). */
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

    // ---------------------------------------------------------- idempotency

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

    // -------------------------------------------------------- preconditions

    /**
     * M-AD-04 provider half — LOST idempotency key: after a successful advance,
     * the same completion is resent under a NEW key, still expecting item-1.
     * The stale expectedCurrentItemId is the LAST line against double advance
     * and must trip wire 14 without moving the pointer again.
     */
    @Test
    fun advance_lostKeyResend_scheduleItemMismatch_noDoubleAdvance() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k4"))

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k4-NEW"))
        }
        assertEquals("pointer did not move twice", "item-2", h.env.currentItemId)
        assertEquals(1, h.env.advanceCount)
    }

    /**
     * M-AD-05 provider half — WRONG item from the start: no prior advance, the
     * request simply expects a non-current item. Independent of the lost-key
     * shape above: here nothing was ever advanced, so a merged test could pass
     * for the wrong reason.
     */
    @Test
    fun advance_wrongItemExpectation_scheduleItemMismatch_pointerUntouched() {
        val h = harness()
        val leaseId = earnAndRelease(h)

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k4b", expectedItemId = "item-2", completionProof = proof(h, "item-2")),
            )
        }
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
        assertEquals(0, h.env.advanceCount)
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

    // ----------------------------------------------------------- exhaustion

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

    // ---------------------------------------------- lease gate + precedence

    /**
     * §6.7.4a active-lease gate: preconditions pass, but the caller still holds
     * an active lease → rejected with LEASE_CONFLICT(7) and NO mutation.
     * Code pins the Sol-verified #3 ruling direction (exact LEASE_CONFLICT, no
     * new wire); re-confirm when the canonical HEAD freezes the mapping.
     */
    @Test
    fun advance_withActiveLease_leaseConflict_noAdvance() {
        val h = harness()
        val receipt = h.apply(key = "adv-active-apply") // NOT released

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, receipt.leaseId, "adv-k8"))
        }
        assertEquals("item-1", h.env.currentItemId)
        assertEquals("mutation never happened", 0, h.env.advanceCount)
    }

    /**
     * Precedence 1: same-key + same-digest replay is judged BEFORE the
     * active-lease gate — a completed advance stays retrievable even after the
     * caller acquired the next item's lease. Failing this turns every
     * crash-recovery replay during the next apply into a spurious conflict.
     */
    @Test
    fun advance_replaySameKey_precedesActiveLeaseGate() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k9")
        val first = h.handler.completeAndAdvance(AUTO_UID, req)

        // Next item's lease is now ACTIVE.
        h.apply(key = "adv-next-apply", intent = h.intent(attemptId = "att-2"))

        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("stored receipt returned despite active lease", first, replay)
        assertEquals("no second advance", 1, h.env.advanceCount)
    }

    /**
     * Precedence 2: for a FRESH request, schedule preconditions are judged
     * BEFORE the active-lease gate — stale item reports wire 14, not
     * LEASE_CONFLICT, even while a lease is active. (Gate order: replay →
     * preconditions → lease gate → mutation.)
     */
    @Test
    fun advance_freshRequest_preconditionBeatsLeaseGate() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k10"))

        // Next item's lease ACTIVE + fresh request still expecting item-1.
        h.apply(key = "adv-next-apply2", intent = h.intent(attemptId = "att-3"))

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k10-NEW"))
        }
        assertEquals("item-2", h.env.currentItemId)
        assertEquals(1, h.env.advanceCount)
    }

    // -------------------------------------------------------------- receipt

    /** Receipt digest must recompute from the request + outcome via the shared framing — otherwise it is not a receipt (§6.7.3). */
    @Test
    fun advance_receiptDigest_recomputes() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k11")

        val receipt = h.handler.completeAndAdvance(AUTO_UID, req)

        assertEquals(
            "receiptDigest binds request, key and outcome (presence-discriminated null target)",
            recomputeReceiptDigest(req, receipt),
            receipt.receiptDigest,
        )
    }

    /**
     * §6.7.5: advance receipt + pointer survive an owner restart as ONE fact —
     * the replay returns the durable receipt and the pointer moved EXACTLY
     * once. `== 1` is the whole point: a `<= 2` tolerance would admit exactly
     * the duplicate advance idempotency exists to prevent (Sol SR on d21102d).
     */
    @Test
    fun advance_receiptAndPointer_surviveRestart_asOneTransaction() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-k12")
        val first = h.handler.completeAndAdvance(AUTO_UID, req)

        h.restart(cleanlinessProvable = true)

        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("same durable receipt after restart", first, replay)
        assertEquals("pointer advanced exactly once across the restart", 1, h.env.advanceCount)
        assertEquals("item-2", h.env.currentItemId)
    }
}
