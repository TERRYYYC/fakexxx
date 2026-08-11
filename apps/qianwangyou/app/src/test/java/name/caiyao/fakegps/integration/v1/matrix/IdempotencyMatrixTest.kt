package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import name.caiyao.fakegps.integration.v1.RequestDigests
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_UID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * §10 idempotency rows owned by the qwy provider lane (§6.3.4; INV-13).
 */
class IdempotencyMatrixTest {

    /**
     * M-ID-02: retrying the SAME request with a fresh operationId must not
     * conflict — operationId is excluded from the §6.3.4 preimage precisely so
     * recovery retries stay legal.
     */
    @Test
    fun M_ID_02() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        val receipt = h.apply(key = "id02-a")

        val first = h.handler.release(
            AUTO_UID,
            ReleaseRequestV1(receipt.leaseId, "op-1", "id02-rel"),
        )
        val retry = h.handler.release(
            AUTO_UID,
            ReleaseRequestV1(receipt.leaseId, "op-2-retry", "id02-rel"),
        )

        assertEquals("same receipt content on retry", first.leaseId, retry.leaseId)
        assertEquals(first.releasedAtEpochMs, retry.releasedAtEpochMs)
        assertEquals(first.environmentRevision, retry.environmentRevision)
        assertEquals(first.releaseComplete, retry.releaseComplete)
        assertEquals("cleanup ran once, not twice", 1, h.env.cleanupCount)
    }

    /**
     * M-ID-03: domain separation — an apply preimage and a release preimage
     * with byte-identical field sequences must still produce different digests
     * (§6.3.4 frozen domains "fakexxx.contract.v1.apply" / ".release").
     */
    @Test
    fun M_ID_03() {
        // Same single free-string field on both operations.
        val collidingValue = "a".repeat(64)

        val applyDigest = RequestDigests.applyDigest(acceptedIntentHash = collidingValue)
        val releaseDigest = RequestDigests.releaseDigest(leaseId = collidingValue)

        assertNotEquals("domain tag must separate identical field bytes", applyDigest, releaseDigest)
        assertEquals("apply digest is stable", applyDigest, RequestDigests.applyDigest(collidingValue))
        assertEquals("release digest is stable", releaseDigest, RequestDigests.releaseDigest(collidingValue))
    }
}
