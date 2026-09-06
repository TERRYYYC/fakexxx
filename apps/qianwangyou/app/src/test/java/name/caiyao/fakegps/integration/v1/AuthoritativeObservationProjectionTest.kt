package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthoritativeObservationProjectionTest {

    @Test
    fun `only a stable PRE POST source matching the whole local projection yields FULL`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-window")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        val digest = QwyObservedSemanticDigest.compute(
            ownerGeneration = h.tracker.generation,
            effective = h.env.observeEffective(),
            schedule = h.env.scheduleSnapshot(),
        )
        val source = AuthoritativeContinuitySource { snapshot(digest, sequence = 8L) }
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = source,
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
        )

        val observed = observer.observe(
            lease,
            ObserveRequestV1(receipt.leaseId, "authoritative-window-observe", receipt.acceptedIntentHash),
        )

        assertEquals(ContinuityCoverageV1.FULL.wire, observed.continuityCoverageWire)
    }

    @Test
    fun `changed sequence or mismatched local digest remains NONE`() {
        val h = ProviderHarness.create()
        h.pair()
        val receipt = h.apply(key = "authoritative-window-changed")
        val lease = checkNotNull(h.leases.get(receipt.leaseId))
        var call = 0
        val observer = EnvironmentObserver(
            tracker = h.tracker,
            environment = h.env,
            clock = h.clock,
            audit = h.audit,
            authoritativeSource = AuthoritativeContinuitySource {
                call += 1
                snapshot("wrong-local-digest", sequence = if (call == 1) 8L else 10L)
            },
            expectedOracleOwnerPackage = "name.caiyao.fakegps",
            expectedOracleOwnerUid = 10_321,
        )

        val observed = observer.observe(
            lease,
            ObserveRequestV1(receipt.leaseId, "authoritative-window-changed-observe", receipt.acceptedIntentHash),
        )

        assertEquals(ContinuityCoverageV1.NONE.wire, observed.continuityCoverageWire)
    }

    private fun snapshot(digest: String, sequence: Long) = AuthoritativeContinuitySnapshot(
        protocolVersion = 1,
        bootId = "123e4567-e89b-12d3-a456-426614174000",
        oracleInstanceId = "oracle-a",
        sequence = sequence,
        ownerUid = 10_321,
        ownerPackage = "name.caiyao.fakegps",
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = AuthoritativeOracleHealth.HEALTHY,
        qwySemanticDigest = digest,
        lastCompletedQwyMutationId = null,
    )
}
