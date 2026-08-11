package name.caiyao.fakegps.integration.v1.matrix

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_PKG
import name.caiyao.fakegps.integration.v1.support.ProviderHarness.Companion.AUTO_SIGNER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §10 crash row owned by the qwy provider lane.
 * Spec: §6.4 relevant-change list, §6.6 L6; INV-08/09.
 */
class CrashMatrixTest {

    /**
     * M-CR-09: qwy restarts and loses its continuity observation window →
     * revision MUST increase and coverage MUST degrade, so the trust predicate
     * (pre.revision == post.revision && FULL) fails instead of silently
     * passing. Process liveness is not continuity.
     */
    @Test
    fun M_CR_09() {
        val h = ProviderHarness.create()
        h.pair(AUTO_PKG, AUTO_SIGNER)
        h.tracker.markContinuityEstablished()
        val before = h.tracker.snapshot()
        assertEquals(
            "precondition: continuity FULL before the crash",
            ContinuityCoverageV1.FULL.wire,
            before.coverageWire,
        )

        h.restart(cleanlinessProvable = false)

        val after = h.tracker.snapshot()
        assertTrue("revision increased across the lost window", after.revision > before.revision)
        assertNotEquals("coverage degraded", ContinuityCoverageV1.FULL.wire, after.coverageWire)
        assertNotEquals("new owner generation", before.generation, after.generation)
    }
}
