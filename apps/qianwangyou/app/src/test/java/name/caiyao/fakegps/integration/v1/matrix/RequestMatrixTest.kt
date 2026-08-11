package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import name.caiyao.fakegps.integration.v1.support.expectContractFailure
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * §10 request row owned by the qwy provider lane (§6.3.3 wire 13; INV-04).
 *
 * Structural illegality is a CALLER error and must be diagnosable as one —
 * falling through to INTERNAL_FAILURE would disguise it as a provider fault.
 */
class RequestMatrixTest {

    /** M-RQ-01: empty required ref / out-of-range coordinate / deadline ≤ notBefore → typed REQUEST_INVALID. */
    @Test
    fun M_RQ_01() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)

        // Empty required ref.
        expectContractFailure(ContractErrorCodeV1.REQUEST_INVALID) {
            h.apply(key = "rq01-a", intent = h.intent(profileRef = ""))
        }

        // Coordinate out of range.
        expectContractFailure(ContractErrorCodeV1.REQUEST_INVALID) {
            h.apply(key = "rq01-b", intent = h.intent(latitude = 95.0))
        }

        // deadline <= notBefore.
        expectContractFailure(ContractErrorCodeV1.REQUEST_INVALID) {
            val bad = h.intent().let {
                it.copy(notBeforeEpochMs = it.deadlineEpochMs + 1_000L)
            }
            h.apply(key = "rq01-c", intent = bad)
        }

        assertEquals("no structurally invalid request touched the environment", 0, h.env.applyCount)
    }
}
