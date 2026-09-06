package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.oracle.OracleWireHealth
import name.caiyao.fakegps.oracle.OracleWireSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinderAuthoritativeContinuitySourceTest {

    @Test
    fun `read failure is absence of authoritative proof`() {
        val source = BinderAuthoritativeContinuitySource { error("simulated Binder failure") }

        assertNull(source.snapshot())
    }

    @Test
    fun `valid wire maps every trust field without promotion`() {
        val wire = validWire()

        assertEquals(
            AuthoritativeContinuitySnapshot(
                protocolVersion = wire.protocolVersion,
                bootId = wire.bootId,
                oracleInstanceId = wire.oracleInstanceId,
                sequence = wire.sequence,
                ownerUid = wire.ownerUid,
                ownerPackage = wire.ownerPackage,
                gpsProviderEnabled = wire.gpsProviderEnabled,
                networkProviderEnabled = wire.networkProviderEnabled,
                requiredCoverageMask = wire.requiredCoverageMask,
                installedCoverageMask = wire.installedCoverageMask,
                health = AuthoritativeOracleHealth.HEALTHY,
                qwySemanticDigest = wire.qwySemanticDigest,
                lastCompletedQwyMutationId = wire.lastCompletedQwyMutationId,
            ),
            BinderAuthoritativeContinuitySource { wire }.snapshot(),
        )
    }

    @Test
    fun `non-healthy wire health stays fail closed in domain`() {
        val source = BinderAuthoritativeContinuitySource {
            validWire().copy(health = OracleWireHealth.BUILD_UNATTESTED)
        }

        assertEquals(AuthoritativeOracleHealth.BUILD_UNATTESTED, source.snapshot()?.health)
    }

    private fun validWire() = OracleWireSnapshot(
        protocolVersion = 1,
        bootId = "123e4567-e89b-12d3-a456-426614174000",
        oracleInstanceId = "instance-a",
        sequence = 8L,
        ownerUid = 10_321,
        ownerPackage = "name.caiyao.fakegps",
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        requiredCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = OracleWireHealth.HEALTHY,
        qwySemanticDigest = "semantic-a",
        lastCompletedQwyMutationId = "mutation-7",
    )
}
