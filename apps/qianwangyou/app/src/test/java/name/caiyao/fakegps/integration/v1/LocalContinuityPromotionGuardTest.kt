package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Issue #66: an application-side apply is not an authoritative history oracle.
 *
 * Until a complete system-server source is independently proven, the ordinary
 * provider path must not promote its own locally tracked continuity to FULL.
 */
class LocalContinuityPromotionGuardTest {

    @Test
    fun ordinaryApplyThenObserve_doesNotSelfCertifyFullContinuity() {
        val harness = ProviderHarness.create()
        harness.pair()
        val receipt = harness.apply(key = "local-continuity-apply")

        val observation = harness.handler.observe(
            AUTO_UID,
            ObserveRequestV1(
                leaseId = receipt.leaseId,
                operationId = "local-continuity-observe",
                expectedIntentHash = receipt.acceptedIntentHash,
            ),
        )

        assertNotEquals(
            "ordinary app-local apply cannot prove uninterrupted history",
            ContinuityCoverageV1.FULL.wire,
            observation.continuityCoverageWire,
        )
    }
}
