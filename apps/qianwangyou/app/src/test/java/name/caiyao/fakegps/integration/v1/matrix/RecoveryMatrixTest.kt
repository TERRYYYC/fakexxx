package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * §10 recovery row owned by the qwy provider lane.
 * Spec: §6.4 (mock-location AppOp/owner change is a relevant change); INV-08.
 */
class RecoveryMatrixTest {

    /**
     * M-RC-03: an external app steals the mock-location owner and hands it
     * back. Post state equals pre state — the revision must change anyway;
     * "looks the same afterwards" is exactly the false trust INV-08 forbids.
     * The provider wiring must forward owner-change events into the revision
     * owner (an unwired listener leaves pre == post and this test red).
     */
    @Test
    fun M_RC_03() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        val receipt = h.apply(key = "rc03-a")

        val pre = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(receipt.leaseId, "op-rc03-pre", receipt.acceptedIntentHash),
        )

        h.env.hijackAndRestoreMockOwner()

        val post = h.handler.observe(
            AUTO_UID,
            ObserveRequestV1(receipt.leaseId, "op-rc03-post", receipt.acceptedIntentHash),
        )

        assertNotEquals(
            "owner hijack+restore must move the revision even though state matches",
            pre.environmentRevision,
            post.environmentRevision,
        )
    }
}
