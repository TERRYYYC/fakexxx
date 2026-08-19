package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.ContractOperation
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §10 advance rows owned by the qwy provider lane — the FIRST provider-owned
 * advance ledger rows (assigned at exact HEAD 590ab58, spec v1.39).
 * Spec: §6.7.4a (lease gate, exact wire ruling) / §6.7.4b (frozen judgment
 * order: idempotency BEFORE proof (v1.42) → schedule gates in the v1.54
 * intra-step order 16→14→15 → lease(7) → mutation; attribution gate not yet
 * on this tree — lands with the KB-5 lane). An earlier revision of this
 * header read "proof → idempotency → schedule(14/15/16)" — both halves stale
 * since v1.42/v1.54; corrected rather than silently inherited.
 */
class AdvanceMatrixTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        return h
    }

    private fun proof(h: ProviderHarness, itemId: String) = CompletionProofV1(
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
        expectedScheduleId: String = h.env.scheduleId,
    ): CompleteAndAdvanceRequestV1 {
        val completionProof = proof(h, expectedItemId)
        val bare = CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = key,
            requestDigest = "", // computed below via the frozen contract helper
            expectedScheduleId = expectedScheduleId,
            expectedScheduleVersion = expectedVersion,
            expectedCurrentItemId = expectedItemId,
            completionProof = completionProof,
            callerProtocolVersion = 1,
        )
        return bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
    }

    /**
     * M-AD-12: advance while the caller still holds an ACTIVE lease (release
     * was skipped) → exact `LEASE_CONFLICT` wire 7, no advance, pointer
     * untouched; after release the same request bytes must succeed.
     *
     * Wire is pinned per the §6.7.4a v1.39 ruling and its exclusions:
     *  - NOT REQUEST_INVALID(13): the bytes are legal — release then resend the
     *    same bytes and it succeeds, while 13 means "retry is useless";
     *  - NOT STALE_LEASE(8): request.leaseId is BY DESIGN a RELEASED historical
     *    attribution ref (§6.7.4a) — "stale" is its normal shape, not an error;
     *  - NOT ENVIRONMENT_DRIFT(9): 9 reports drift observed AFTER the fact;
     *    this gate PREVENTS drift being created under an active lease.
     * §6.3.3's wire-7 definition was widened in the same revision to cover the
     * caller's OWN active lease — asserting "any typed failure" here would
     * accept a wrong wire and is not evidence.
     */
    @Test
    fun M_AD_12() {
        val h = harness()
        val active = h.apply(key = "ad12-apply") // deliberately NOT released

        expectContractFailure(ContractErrorCodeV1.LEASE_CONFLICT) {
            h.handler.completeAndAdvance(AUTO_UID, request(h, active.leaseId, "ad12-k1"))
        }
        assertEquals("no advance", 0, h.env.advanceCount)
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)

        // Release, then the SAME request bytes succeed — proving 13 would have
        // been the wrong classification (retry is not useless).
        h.release(active.leaseId, key = "ad12-rel")
        val receipt = h.handler.completeAndAdvance(AUTO_UID, request(h, active.leaseId, "ad12-k1"))
        assertEquals("item-2", receipt.advancedToItemId)
        assertEquals(1, h.env.advanceCount)
    }

    /**
     * M-AD-13: multiple preconditions violated at once — the canonical case:
     * the caller BOTH still holds an active lease AND carries an expired
     * `expectedScheduleVersion`. §6.7.4b's frozen order judges
     * schedule gates (16→14→15 since the v1.54 intra-step reorder) BEFORE
     * lease(7), so the answer is exactly
     * `SCHEDULE_VERSION_STALE` — an implementation that picks gates in an
     * arbitrary order (returning 7 here) fails this row. Rationale frozen in
     * v1.39: with the schedule gone stale there may be nothing to complete, so
     * sending Auto through a release-retry cycle first is pure waste.
     */
    @Test
    fun M_AD_13() {
        val h = harness()
        val active = h.apply(key = "ad13-apply") // NOT released → lease gate also violated
        val staleVersion = h.env.scheduleVersion
        h.env.scheduleVersion += 1 // plan edited meanwhile → schedule gate violated too

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_VERSION_STALE) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, active.leaseId, "ad13-k1", expectedVersion = staleVersion),
            )
        }
        assertEquals("no advance under any violated gate", 0, h.env.advanceCount)
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
    }

    /**
     * M-AD-11(b) (renamed from M_AD_21 per Terra evidence-binding correction
     * `0001786752664520`): single-threaded exhausted schedule + stale non-null
     * expectedCurrentItemId → wire 16, per the v1.54 §6.7.4b intra-step
     * ordering 16→14→15 (exhausted checked before item mismatch).
     *
     * Name/reality note: this is NOT M-AD-21's concurrent exactly-once
     * evidence — M-AD-21 needs two distinct keys racing (winner + loser +
     * advanceCount==1). The non-final half lives in
     * AdvanceProviderRedTest::advance_concurrentAdvances_serializedNoDoubleAdvance;
     * the final-item half (winner EXHAUSTED receipt, loser exactly 16) lives
     * in Opus's `2c70eea` row-16 test on feat/kb5-kb6-advance-predicates.
     * This row pins the judgment ORDER that makes those answers reachable.
     *
     * Rationale: "already exhausted" is a terminal state — telling the caller
     * "item mismatch" invites a useless resync+retry cycle when retry is
     * structurally impossible. The frozen order gives the terminal answer first.
     */
    @Test
    fun M_AD_11b_exhaustedWithStaleItem_returnsSixteenNotFourteen() {
        val h = harness()
        val leaseId = h.apply(key = "ad21-apply").leaseId
        h.release(leaseId, key = "ad21-rel")

        // Advance through all items to exhaust the schedule.
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "ad21-a1", "item-1"))
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "ad21-a2", "item-2"))
        h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "ad21-a3", "item-3"))

        // Schedule is now exhausted; pointer retained on item-3 (M-AD-10).
        // Caller sends expectedCurrentItemId = "item-2" (stale — they think item-2 is current).
        expectContractFailure(ContractErrorCodeV1.SCHEDULE_EXHAUSTED) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "ad21-stale", expectedItemId = "item-2"),
            )
        }
    }

    /**
     * M-AD-26 provider half (v1.72): a request whose expectedScheduleId names a
     * DIFFERENT schedule fails the identity leg FIRST — exact wire 17
     * SCHEDULE_IDENTITY_MISMATCH, before 16/14/15. The identity leg is the
     * premise of the other schedule gates: item/version answers about another
     * schedule would mislead the caller's recovery.
     *
     * Pointer untouched, no advance, no receipt minted.
     */
    @Test
    fun M_AD_26_wrongScheduleId_identityMismatch_wire17_pointerUntouched() {
        val h = harness()
        val leaseId = h.apply(key = "ad26-apply").leaseId
        h.release(leaseId, key = "ad26-rel")

        expectContractFailure(ContractErrorCodeV1.SCHEDULE_IDENTITY_MISMATCH) {
            h.handler.completeAndAdvance(
                AUTO_UID,
                request(h, leaseId, "ad26-k1", expectedScheduleId = "wrong-schedule"),
            )
        }
        assertEquals("no advance on identity mismatch", 0, h.env.advanceCount)
        assertEquals("pointer untouched", "item-1", h.env.currentItemId)
        assertNull(
            "no receipt minted for a wrong-schedule request",
            h.idempotency.find(AUTO_PKG, ContractOperation.ADVANCE, "ad26-k1"),
        )
    }

    /**
     * Spec v1.56: receipt.scheduleVersionAfter = expectedScheduleVersion + 1 for
     * BOTH terminal (EXHAUSTED) and non-terminal (ADVANCED) advance. The fake
     * environment bumps version on every advancePointer (production parity),
     * and this asserts the committed receipt carries V+1 directly — the
     * receipt is what Auto re-syncs from, so a pre-advance version there would
     * make the next advance's version gate (wire 15) reject a legitimate retry.
     */
    @Test
    fun M_AD_22_receiptVersionAfterIsExpectedPlusOne() {
        val h = harness()
        val leaseId = h.apply(key = "ad22-apply").leaseId
        h.release(leaseId, key = "ad22-rel")

        val v0 = h.env.scheduleVersion

        // Non-terminal advance: item-1 → item-2
        val advanced = h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "ad22-a1", "item-1"))
        assertEquals("ADVANCED receipt carries expected version + 1", v0 + 1, advanced.scheduleVersionAfter)
        assertEquals("environment version actually bumped", v0 + 1, h.env.scheduleVersion)

        // Second non-terminal advance: item-2 → item-3
        val advanced2 = h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "ad22-a2", "item-2"))
        assertEquals("second ADVANCED receipt carries expected version + 1", v0 + 2, advanced2.scheduleVersionAfter)

        // Terminal advance: item-3 = last → EXHAUSTED
        val terminal = h.handler.completeAndAdvance(AUTO_UID, request(h, leaseId, "ad22-a3", "item-3"))
        assertEquals("EXHAUSTED receipt carries expected version + 1", v0 + 3, terminal.scheduleVersionAfter)
        assertEquals("environment version bumped again", v0 + 3, h.env.scheduleVersion)
    }
}
