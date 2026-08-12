package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import name.caiyao.fakegps.integration.v1.DurableIdempotencyStore
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.OTHER_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SUPPLEMENTARY RED LANE — provider-side completeAndAdvance (§6.7).
 *
 * NOT ledger rows and NOT counted as ledger coverage. As of exact HEAD
 * 590ab58 (spec v1.39) the FIRST provider-owned advance rows exist and are
 * bound in matrix/AdvanceMatrixTest.kt (M_AD_12 lease gate wire 7, M_AD_13
 * frozen-order first-hit). The REMAINING provider semantics below (CAS gates,
 * pointer+receipt single transaction, idempotent receipt refetch, exhausted
 * duality, ordering facets) stay supplemental: Opus5 allocates their IDs in
 * one batch — do NOT self-assign IDs (ID drift), and do NOT count this file
 * toward the ledger (deleting it would still leave the verifier green).
 *
 * Judgment order is now FROZEN as §6.7.4b:
 *   proof → idempotency → schedule(14/15/16) → lease(7) → mutation
 * with two pinned rationales: idempotent replay precedes the schedule gate
 * (after a successful advance the replayed expectedCurrentItemId is
 * necessarily expired — schedule-first would collapse M-AD-02 into M-AD-04
 * and shoot down legal replays), and the schedule gate precedes the lease
 * gate (a stale/exhausted schedule makes lease state irrelevant; lease-first
 * sends Auto through a wasted release-retry into the same terminal).
 *
 * RECORDED SHAPE, NO SELF-ASSIGNED ID (dsf F-3, advisory on af06993):
 * M_AD_12 covers only the SAME-caller active lease; advance while a
 * FOREIGN-caller / device-level unconverged lease exists (the INV-16
 * dimension) has no case yet — M-AD-14 candidate or an M_AD_12 extension.
 * dsf's findings already sit with the Sol review lane; the ID arrives with
 * Opus5's batch allocation. Not re-delivered from here, not numbered here.
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

    /**
     * §6.7.3 receipt digest recompute — delegated to the contract's OWN helper
     * (dsf F-8 on af06993): the previous local field assembly was a second copy
     * of the receipt framing, and v1.38 froze exactly one framing helper
     * because a drifted copy does not fail loudly, it just computes a
     * different digest.
     */
    private fun recomputeReceiptDigest(request: CompleteAndAdvanceRequestV1, receipt: AdvanceReceiptV1): String =
        CanonicalAdvanceReceiptDigestV1.compute(
            receipt = receipt,
            requestDigest = request.requestDigest,
            idempotencyKey = request.idempotencyKey,
        )

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

    /**
     * Requesting an advance AFTER exhaustion is the caller error →
     * SCHEDULE_EXHAUSTED wire 16 (M-AD-11 provider half). The owner RESTARTS
     * between the exhausting advance and the further request: M-AD-10 retains
     * the current item, so "already exhausted" needs its own DURABLE
     * discriminator — an implementation keeping it in memory answers the
     * post-restart request as if item-3 were still completable (dsf F-2
     * same-class sweep: exhausted-bit persistence was unpinned).
     */
    @Test
    fun advance_afterExhausted_scheduleExhausted() {
        val h = harness()
        h.env.currentItemId = "item-3"
        val leaseId = earnAndRelease(h, key = "adv-exh-apply")
        h.handler.completeAndAdvance(
            AUTO_UID,
            request(h, leaseId, "adv-k7", expectedItemId = "item-3", completionProof = proof(h, "item-3")),
        )

        h.restart(cleanlinessProvable = true)

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_EXHAUSTED) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k7-again", expectedItemId = "item-3", completionProof = proof(h, "item-3")),
            )
        }
        assertEquals("current item retained across restart", "item-3", h.env.currentItemId)
    }

    /**
     * §6.7.5 crash convergence (dsf F-2 item 3): a write fault between the
     * pointer write and the receipt write crashes the operation mid-flight.
     * Because both writes are REQUIRED to share one durable transaction, the
     * post-restart world has exactly ONE legal terminal shape per key: the
     * replay either finds the committed receipt (advance happened, exactly
     * once) or re-executes cleanly (nothing committed) — never a torn state
     * (pointer moved without a retrievable receipt, or a receipt that
     * re-advances). Harness rollback semantics make a two-transaction
     * implementation observably torn here.
     */
    @Test
    fun advance_crashBetweenWrites_convergesToSingleLegalState() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-crash")

        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }
        try {
            h.handler.completeAndAdvance(AUTO_UID, req)
            fail("injected write fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // SimulatedWriteCrash (or a transport wrapper) — NOT a typed
            // ContractException: a crash is not a business answer.
        }
        h.kv.failOnWrite = null

        h.restart(cleanlinessProvable = true)

        // Replay converges to exactly one advance...
        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("item-2", replay.advancedToItemId)
        assertEquals("item-2", h.env.currentItemId)

        // ...and is stable: replaying again returns the same durable receipt.
        val replay2 = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals(replay, replay2)
        assertEquals("no third pointer move ever happened", "item-2", h.env.currentItemId)
    }

    // ------------------------------------------- §6.7.4b ordering facets
    // (the lease gate row itself is ledger-bound: AdvanceMatrixTest::M_AD_12)

    /**
     * §6.7.4b facet — FLIPPED for the v1.42 frozen order (Terra PR#22 P1-2 /
     * dsf F-7.1). The order is now:
     *   safety(+recompute digest) → idempotency → proof → schedule → lease → mutation
     * so idempotency is judged BEFORE proof, the reverse of what this row
     * asserted under v1.39.
     *
     * A reused key carrying a now-BLANK proof is a DIFFERENT payload, so its
     * recomputed digest differs from the stored one. The safety gate (step 1)
     * still passes because the request() helper builds a digest that binds its
     * own blank-proof fields (self-consistent, not forged), so step 1 has
     * nothing to reject. Step 2 then finds the same key with a different
     * recomputed digest → IDEMPOTENCY_CONFLICT(12). A proof-first implementation
     * would instead reach the proof gate and answer REQUEST_INVALID(13); seeing
     * 12 here is the evidence that idempotency is judged first.
     */
    @Test
    fun advance_idempotencyGate_precedesProof() {
        val h = harness()
        val leaseId = earnAndRelease(h)
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-k8"))

        val blankProof = CompletionProofV1(
            scheduleItemId = "",
            trustedSuccessCount = 0,
            quotaRequired = 0,
            ledgerRef = "",
            verifiedAtElapsedRealtimeMs = h.clock.elapsedRealtimeMs(),
        )
        expectContractFailure(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "adv-k8", expectedItemId = "item-2", completionProof = blankProof),
            )
        }
        assertEquals("no second advance", 1, h.env.advanceCount)
    }

    /**
     * §6.7.4b facet: same-key + same-digest replay is judged before BOTH the
     * schedule gate and the lease gate — after a successful advance the
     * replayed expectedCurrentItemId is necessarily expired AND the caller may
     * already hold the next item's lease; the stored receipt must still come
     * back. Failing this collapses M-AD-02 into M-AD-04 and turns every
     * crash-recovery replay during the next apply into a spurious conflict.
     */
    @Test
    fun advance_replaySameKey_precedesScheduleAndLeaseGates() {
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
     * §6.7.4b facet: for a FRESH request the schedule gate is judged BEFORE
     * the lease gate — stale item reports wire 14, not LEASE_CONFLICT, even
     * while a lease is active. (The canonical stale-VERSION + active-lease
     * combination is the ledger-bound AdvanceMatrixTest::M_AD_13.)
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

    // -------------------------------------- §6.7.4b step 1: recompute requestDigest
    // (Terra PR#22 P1-2 / dsf F-7.3)

    /**
     * §6.7.4b step 1: the provider MUST recompute requestDigest from the
     * RECEIVED fields and compare it to the caller-supplied value; a mismatch
     * is REQUEST_INVALID(13) BEFORE idempotency ever runs. The caller-supplied
     * digest is untrusted input — apply()/release() already recompute their own
     * digests, advance() was the one path that trusted the wire. Without
     * recompute, an attacker mutates any payload field but keeps the old
     * (key, digest) pair, hits the stored entry, and gets back a receipt bound
     * to a DIFFERENT request — the idempotency key decays from "identity of one
     * call" into "a claim ticket for any stored result".
     */
    @Test
    fun advance_forgedRequestDigest_requestInvalid() {
        val h = harness()
        val leaseId = earnAndRelease(h)

        // Fields are legal, but requestDigest does not bind them (a value the
        // provider never authored from these fields).
        val forged = request(h, leaseId, "adv-forge").copy(
            requestDigest = "forged-digest-not-bound-to-these-fields",
        )
        expectContractFailure(ContractErrorCodeV1.REQUEST_INVALID) {
            h.handler.completeAndAdvance(AUTO_UID, forged)
        }
        assertEquals("forged request never advanced the pointer", 0, h.env.advanceCount)
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
    }

    // ------------------------------------- §6.7.4b step 5: device-global lease gate
    // Supplemental (NO ledger id — same discipline as this file's header).
    // dsf F-3's (a) branch: a lease held by ANOTHER caller, or an EXPIRED (not
    // yet converged) lease, must block advance. The DISTINCT open question — how
    // completeAndAdvance treats a leaseId ARGUMENT owned by a foreign caller — is
    // §20.1 KB-6 / §6.7.4a "unfrozen" and is deliberately NOT decided here.

    /**
     * Terra PR#22 P1-4: an EXPIRED-but-not-RELEASED lease is still a blocking
     * device lease (§8.4 / INV-28: EXPIRED never auto-releases; TTL is not a
     * bypass of INV-21). The frozen step-5 gate is "ANY non-RELEASED /
     * unconverged lease → LEASE_CONFLICT(7)" with NO exemption for EXPIRED. The
     * pre-fix handler exempted EXPIRED and let advance swap the device
     * environment under a lease that had merely timed out.
     */
    @Test
    fun advance_expiredForeignLease_leaseConflict() {
        val h = harness()
        h.pair(OTHER_PKG, OTHER_SIGNER)

        // AUTO earns then releases → device clean; AUTO keeps the historical ref.
        val leaseId = earnAndRelease(h)
        // A DIFFERENT caller holds a lease, then lets it lapse past its deadline.
        h.apply(uid = OTHER_UID, key = "other-apply", intent = h.intent(attemptId = "o1"))
        h.clock.advance(600_001) // past the 10-minute default deadline → EXPIRED

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-exp"))
        }
        assertEquals("expired lease still blocks advance", 0, h.env.advanceCount)
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
    }

    /**
     * §6.7.4a: the lease gate is DEVICE-GLOBAL, not "this caller's". A lease
     * held by ANOTHER caller blocks advance just the same — otherwise caller B
     * holds a lease while caller A advances and swaps the device environment out
     * from under B. Passes on the pre-fix handler via its generic non-RELEASED
     * branch; kept as a regression guard (the twin of the EXPIRED hole above) so
     * a future refactor to a caller-scoped gate fails loudly.
     */
    @Test
    fun advance_foreignActiveLease_deviceGlobalLeaseConflict() {
        val h = harness()
        h.pair(OTHER_PKG, OTHER_SIGNER)

        val leaseId = earnAndRelease(h)
        h.apply(uid = OTHER_UID, key = "other-apply", intent = h.intent(attemptId = "o1"))

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-foreign"))
        }
        assertEquals("no advance under a foreign active lease", 0, h.env.advanceCount)
    }

    // ----------------------------- §6.7.4b step 5+6: gate and mutation are atomic
    // (Terra PR#22 P1-3)

    /**
     * Terra PR#22 P1-3: the schedule/lease gate and the pointer mutation must
     * live in ONE serialized transaction. The pre-fix handler evaluated the
     * gates OUTSIDE the mutation transaction, so two concurrent advances could
     * BOTH pass the schedule gate (each sees item-1) and BOTH mutate — the
     * double advance the compare-and-advance contract exists to make
     * impossible.
     *
     * Real threads, one shared harness: exactly one advance may win; the other
     * must observe the moved pointer and fail SCHEDULE_ITEM_MISMATCH(14). The
     * hard invariant is advanceCount == 1 — never 2. Repeated to keep the race
     * live. (Mirrors the accepted ConcurrencyMatrixTest M-CC-03/04 real-thread
     * one-winner shape.)
     */
    @Test
    fun advance_concurrentAdvances_serializedNoDoubleAdvance() {
        repeat(40) {
            val h = harness()
            val leaseId = earnAndRelease(h)
            // Two DISTINCT keys, both a legal advance from item-1 — no idempotency
            // replay can mask the race; each is a fresh compare-and-advance.
            val reqA = request(h, leaseId, "adv-race-a")
            val reqB = request(h, leaseId, "adv-race-b")

            val outcomes = Collections.synchronizedList(mutableListOf<Any>())
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            listOf(reqA, reqB).forEach { req ->
                Thread {
                    start.await()
                    try {
                        outcomes.add(h.handler.completeAndAdvance(AUTO_UID, req))
                    } catch (t: Throwable) {
                        outcomes.add(t)
                    } finally {
                        done.countDown()
                    }
                }.start()
            }
            start.countDown()
            assertTrue("race finished", done.await(30, TimeUnit.SECONDS))

            assertEquals("pointer advanced exactly once, never twice", 1, h.env.advanceCount)
            assertEquals("item-2", h.env.currentItemId)
            val failures = outcomes.filterIsInstance<ContractException>()
            assertEquals("exactly one loser", 1, failures.size)
            assertEquals(
                "loser saw the already-moved pointer",
                ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH,
                failures.single().code,
            )
        }
    }

    // ------------------- §6.7.5 CROSS-BOUNDARY atomicity (Terra PR#22 round 2)
    // The shared-store harness cannot represent these torn states at all: the
    // fake env and the provider share one transaction buffer, so the fake is
    // silently STRONGER than production (same class as the AndroidDurableKv
    // finding). Both tests below run on createWithExternalEnvStore(), where the
    // qwy pointer lives in its OWN store — the real topology.

    /**
     * Terra round-2 P1, the forbidden direction: "pointer 已变 / receipt 写失败".
     * The provider's receipt write fails; the qwy pointer must NOT have moved.
     * §6.7.5: no receipt ⇒ no advance — an implementation that mutates the
     * external pointer before its own commit point produces a pointer that
     * moved with no retrievable receipt, and the same-key retry cannot recover
     * (it re-executes against a schedule that already advanced → wire 14 — the
     * legal replay is dead).
     */
    @Test
    fun advance_externalStore_receiptWriteFails_pointerNeverMoved() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-xb-crash")

        // Fail the provider-side receipt persist. The env's own store is a
        // DIFFERENT kv, so nothing the provider buffers can mask a premature
        // external mutation.
        h.kv.failOnWrite = { namespace, _ ->
            namespace == DurableIdempotencyStore.RECEIPT_NAMESPACE
        }
        try {
            h.handler.completeAndAdvance(AUTO_UID, req)
            fail("injected receipt write fault must surface")
        } catch (expected: RuntimeException) {
            // crash, not a business answer
        }
        h.kv.failOnWrite = null

        // THE assertion of this row: no receipt ⇒ the external pointer did not move.
        assertEquals("no receipt ⇒ no advance (§6.7.5)", "item-1", h.env.currentItemId)
        assertEquals("external pointer untouched", 0, h.env.advanceCount)

        // And the failed attempt is fully recoverable: restart, same-key retry
        // executes cleanly exactly once.
        h.restart(cleanlinessProvable = true)
        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("item-2", replay.advancedToItemId)
        assertEquals("item-2", h.env.currentItemId)
        assertEquals("exactly one advance ever", 1, h.env.advanceCount)
        assertEquals("stable replay", replay, h.handler.completeAndAdvance(AUTO_UID, req))
    }

    /**
     * The recoverable window of a single-commit protocol: receipt committed,
     * crash BEFORE the external pointer apply. The committed receipt IS the
     * advance (single commit point); recovery must roll the pointer FORWARD at
     * owner startup — §6.7.5 "重启后只能观察到「已推进」或「未推进」" means the
     * post-recovery world has the pointer matching the receipt BEFORE any
     * caller-visible request runs, and the same-key replay returns the original
     * durable receipt without a second mutation.
     */
    @Test
    fun advance_externalStore_crashAfterCommitBeforePointerApply_rollsForward() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-xb-rollfwd")

        // Crash exactly at the external mutation (after the provider's commit
        // point in a receipt-first protocol; before anything in a mutate-first
        // one — the post-restart assertions below tell the two apart).
        h.env.failNextAdvancePointer = true
        try {
            h.handler.completeAndAdvance(AUTO_UID, req)
            fail("injected pointer-apply fault must surface")
        } catch (expected: RuntimeException) {
            // crash, not a business answer
        }

        h.restart(cleanlinessProvable = true)

        // Roll-forward happened at startup, BEFORE any request: the committed
        // advance is already reflected in the external store.
        assertEquals(
            "owner startup completed the committed advance (roll-forward)",
            "item-2",
            h.env.currentItemId,
        )

        // The replay returns the ORIGINAL durable receipt — and is stable.
        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("item-2", replay.advancedToItemId)
        assertEquals(AdvanceOutcomeV1.ADVANCED.wire, replay.outcomeWire)
        assertEquals("receipt digest binds the original request",
            recomputeReceiptDigest(req, replay), replay.receiptDigest)
        assertEquals("stable second replay", replay, h.handler.completeAndAdvance(AUTO_UID, req))
        assertEquals("pointer moved exactly once across crash + recovery",
            1, h.env.advanceCount)
    }

    // ---------- §6.7.5 the fence must cover ALL entry points (Terra round 3)
    // The single-commit protocol only closes the gap if EVERY entry point that
    // touches lease or pointer is serialized through the pointer change and
    // settles a pending advance before serving. Fencing only concurrent
    // advances (round-2 first cut) left two holes.

    /**
     * Terra round-3 P1(a): the commit→external-apply window. An advance has
     * committed its receipt + pending slot but has NOT yet moved the external
     * pointer. A concurrent apply() must NOT be able to grant a device lease in
     * that window — otherwise the advance then changes the pointer under an
     * active lease, exactly the §6.7.4b device-global race the protocol claims
     * to close. apply() must be fenced through the pointer change, not merely
     * through the provider commit.
     */
    @Test
    fun advance_commitToApplyWindow_concurrentApply_cannotGrantLeaseBeforePointerMoves() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.pair(OTHER_PKG, OTHER_SIGNER)
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-window")

        val applyLandedWhilePointerStale = AtomicBoolean(false)
        val applyDone = CountDownLatch(1)

        h.env.beforeAdvancePointer = {
            // We are past the provider commit, before the pointer moves (still item-1).
            val racer = Thread {
                try {
                    h.apply(uid = OTHER_UID, key = "window-apply", intent = h.intent(attemptId = "w"))
                    // If apply COMPLETED here while the pointer is still item-1,
                    // it granted a lease inside the forbidden window.
                    if (h.env.currentItemId == "item-1") applyLandedWhilePointerStale.set(true)
                } catch (_: Throwable) {
                    // typed rejection is fine — it just did not land in the window
                } finally {
                    applyDone.countDown()
                }
            }
            racer.start()
            // Give the racing apply a real chance. A fenced entry blocks on the
            // owner lock and cannot finish inside this callback.
            applyDone.await(500, TimeUnit.MILLISECONDS)
        }

        h.handler.completeAndAdvance(AUTO_UID, req)
        assertTrue("racing apply eventually finished", applyDone.await(5, TimeUnit.SECONDS))

        assertFalse(
            "a concurrent apply must not grant a lease while the advance pointer change is still pending",
            applyLandedWhilePointerStale.get(),
        )
        assertEquals("advance completed the pointer move", "item-2", h.env.currentItemId)
    }

    /**
     * Terra round-3 P1(b): live-process (no restart) recovery. The external
     * pointer apply throws but the process SURVIVES — recovery must not depend
     * on onOwnerProcessStart(). A same-key replay (or any read-side entry) must
     * settle the committed advance before serving, so it never returns a
     * receipt that says "advanced" while the external pointer is still stale.
     */
    @Test
    fun advance_pointerApplyThrows_liveProcess_replaySettlesPointer_noDivergence() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        val leaseId = earnAndRelease(h)
        val req = request(h, leaseId, "adv-live-crash")

        // Pointer apply throws; the handler instance is NOT recreated.
        h.env.failNextAdvancePointer = true
        try {
            h.handler.completeAndAdvance(AUTO_UID, req)
            fail("pointer-apply fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // crash, not a business answer
        }
        // Receipt is committed (advance exists) but the external pointer is stale.
        assertEquals("pointer not yet applied", "item-1", h.env.currentItemId)

        // Same-key replay on the SAME live handler: it must settle the pointer
        // BEFORE returning the durable receipt — no receipt/pointer divergence.
        val replay = h.handler.completeAndAdvance(AUTO_UID, req)
        assertEquals("replay returns the durable receipt", "item-2", replay.advancedToItemId)
        assertEquals(
            "live replay settled the external pointer before serving",
            "item-2", h.env.currentItemId,
        )
        assertEquals("pointer moved exactly once", 1, h.env.advanceCount)
    }

    /**
     * Terra round-3 "worse" case: after a live-process pointer-apply failure, a
     * DIFFERENT idempotency key resending the SAME item-1 completion must NOT
     * mint a second item-1→item-2 receipt over the single pending slot (which
     * would leave one pointer move but two successful receipts). The owner fence
     * settles the pending advance BEFORE the new key runs, so the pointer is
     * already item-2 and the new key's schedule gate fails
     * SCHEDULE_ITEM_MISMATCH(14). Sensitivity: with settlePendingAdvance()
     * removed from the fence, the new key sees a stale item-1 pointer, passes
     * every gate, and commits a second receipt — this asserts it cannot.
     */
    @Test
    fun advance_pointerApplyThrows_liveProcess_newKeySameCompletion_noSecondReceipt() {
        val h = ProviderHarness.createWithExternalEnvStore()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        val leaseId = earnAndRelease(h)

        // K1 commits its receipt, then the external apply throws; handler survives.
        h.env.failNextAdvancePointer = true
        try {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-nk-k1"))
            fail("pointer-apply fault must surface, not be swallowed")
        } catch (expected: RuntimeException) {
            // crash, not a business answer
        }
        assertEquals("pointer not yet applied", "item-1", h.env.currentItemId)

        // A DIFFERENT key with the same item-1 completion: the fenced entry
        // settles the pending advance first (pointer → item-2), so this new key
        // now mismatches the schedule instead of minting a second receipt.
        expectContractFailure(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-nk-k2-NEW"))
        }
        assertEquals("pending settled before serving the new key", "item-2", h.env.currentItemId)
        assertEquals("pointer moved exactly once — no second advance", 1, h.env.advanceCount)

        // The ONE durable receipt is K1's; it replays cleanly with no extra move.
        val k1Replay = h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "adv-nk-k1"))
        assertEquals("item-2", k1Replay.advancedToItemId)
        assertEquals("still exactly one advance across the whole sequence", 1, h.env.advanceCount)
    }
}
