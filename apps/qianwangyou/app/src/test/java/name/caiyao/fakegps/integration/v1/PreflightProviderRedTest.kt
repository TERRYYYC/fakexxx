package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-17 RED LANE — preflight achievable verification level honesty.
 *
 * F-14's second face: Handler:247 stamped a constant VERIFIED into the apply
 * receipt (fixed in #41 by consuming the controller's computed level); the
 * SAME constant is stamped into preflight's achievable claim at Handler:113.
 * Both survived three review rounds, one earnedScheduleRef-axis audit and a
 * green CI — because nothing consumed the preflight field, and no test drove
 * a capability-blocked state through preflight. This lane exists so the NEXT
 * constant cannot ride a green CI.
 *
 * Semantic pinned here (frozen with the fix): achievableVerificationLevelWire
 * is the provider's CAPABILITY CEILING given what is knowable before an apply
 * — the exact preconditions applyEnvironment() itself gates on (gateway
 * availability, current schedule item, qwy-owned coordinates for it). Any
 * known blocker → NONE. It is NOT a prediction of the apply outcome: the
 * publish result is measured truth, reported by the apply receipt and
 * observe(), never by preflight.
 *
 * NOT ledger rows (same discipline as ApplyReleaseProviderRedTest /
 * AdvanceProviderRedTest: no self-assigned matrix IDs).
 */
class PreflightProviderRedTest {

    private fun harness(): ProviderHarness {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        return h
    }

    private fun preflight(h: ProviderHarness) = h.handler.preflight(
        AUTO_UID,
        PreflightRequestV1(
            intent = h.intent(),
            idempotencyKey = "pre-k1",
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        ),
    )

    /**
     * Green path: capability present (current item has qwy-owned
     * coordinates, gateway constructible) → the ceiling IS verified-level;
     * preflight may claim it.
     */
    @Test
    fun preflight_claims_verified_when_capability_present() {
        val h = harness()
        val report = preflight(h)
        assertEquals(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            report.achievableVerificationLevelWire,
        )
    }

    /**
     * F-17 RED: the current schedule item has no qwy-owned coordinates —
     * applyEnvironment() would throw before publishing anything. Preflight
     * must not claim VERIFIED over a state its own apply cannot back.
     */
    @Test
    fun preflight_does_not_claim_verified_when_item_has_no_coordinates() {
        val h = harness()
        h.env.itemCoordinates.clear()
        val report = preflight(h)
        assertEquals(
            VerificationLevelV1.NONE.wire,
            report.achievableVerificationLevelWire,
        )
    }

    /**
     * F-17 RED: no current schedule item at all (KB-8's coordinate owner is
     * absent) — same unreachable-apply state, different leg.
     */
    @Test
    fun preflight_does_not_claim_verified_with_no_current_item() {
        val h = harness()
        h.env.currentItemId = null
        val report = preflight(h)
        assertEquals(
            VerificationLevelV1.NONE.wire,
            report.achievableVerificationLevelWire,
        )
    }
}
