package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.RequestDigests
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §10 advance rows owned by the qwy provider lane — the FIRST provider-owned
 * advance ledger rows (assigned at exact HEAD 590ab58, spec v1.39).
 * Spec: §6.7.4a (lease gate, exact wire ruling) / §6.7.4b (frozen judgment
 * order: proof → idempotency → schedule(14/15/16) → lease(7) → mutation).
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
    ): CompleteAndAdvanceRequestV1 {
        val completionProof = proof(h, expectedItemId)
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
     * schedule(14/15/16) BEFORE lease(7), so the answer is exactly
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
     * M-AD-21 (spec v1.54 §6.7.4b intra-step ordering 16→14→15):
     * When the schedule is EXHAUSTED and the caller carries a stale expectedCurrentItemId
     * (e.g. pointing at an earlier item after the pointer retained on the last item),
     * the answer must be SCHEDULE_EXHAUSTED(16), not SCHEDULE_ITEM_MISMATCH(14).
     *
     * The frozen intra-step ordering is 16→14→15: exhausted is checked BEFORE item
     * mismatch. Before this reorder the code checked 14→15→16, so a stale item on
     * an exhausted schedule would incorrectly return 14 instead of the terminal 16.
     *
     * Rationale: "already exhausted" is a terminal state — telling the caller "item
     * mismatch" invites a useless resync+retry cycle when retry is structurally
     * impossible. The frozen order gives the terminal answer first.
     */
    @Test
    fun M_AD_21_exhaustedWithStaleItem_returnsSixteenNotFourteen() {
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
