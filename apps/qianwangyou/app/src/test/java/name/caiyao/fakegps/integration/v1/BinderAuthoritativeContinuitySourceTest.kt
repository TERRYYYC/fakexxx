package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.oracle.OracleBundleCodec
import name.caiyao.fakegps.oracle.OracleClientRegistry
import name.caiyao.fakegps.oracle.OracleDeathLink
import name.caiyao.fakegps.oracle.OracleRegistration
import name.caiyao.fakegps.oracle.OracleWireHealth
import name.caiyao.fakegps.oracle.OracleWireSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BinderAuthoritativeContinuitySourceTest {

    @Test
    fun `missing registry authority is absence of proof`() {
        val registry = OracleClientRegistry<FakeBinder>()
        val source = sourceFor(registry)

        assertNull(source.snapshot())
    }

    @Test
    fun `Binder snapshot failure is absence of proof`() {
        val registry = registryWith(FakeBinder { error("simulated Binder failure") })
        val source = sourceFor(registry)

        assertNull(source.snapshot())
    }

    @Test
    fun `strict codec rejection is absence of proof`() {
        val malformedFields = OracleBundleCodec.encodeFields(validWire()).toMutableMap().apply {
            remove(OracleBundleCodec.KEY_SEQUENCE)
        }
        val registry = registryWith(
            FakeBinder { OracleBundleCodec.decodeFields(malformedFields) },
        )

        assertNull(sourceFor(registry).snapshot())
    }

    @Test
    fun `every wire health has an explicit fail-closed domain mapping`() {
        val mappings = mapOf(
            OracleWireHealth.HEALTHY to AuthoritativeOracleHealth.HEALTHY,
            OracleWireHealth.BUILD_UNATTESTED to AuthoritativeOracleHealth.BUILD_UNATTESTED,
            OracleWireHealth.UNSUPPORTED_PLATFORM to AuthoritativeOracleHealth.HOOKS_INCOMPLETE,
            OracleWireHealth.BOOT_ID_UNAVAILABLE to AuthoritativeOracleHealth.UNINITIALIZED,
            OracleWireHealth.HOOKS_INCOMPLETE to AuthoritativeOracleHealth.HOOKS_INCOMPLETE,
            OracleWireHealth.BRIDGE_UNAVAILABLE to AuthoritativeOracleHealth.SESSION_UNAVAILABLE,
            OracleWireHealth.SESSION_UNAVAILABLE to AuthoritativeOracleHealth.SESSION_UNAVAILABLE,
            OracleWireHealth.ENDPOINT_UNAVAILABLE to AuthoritativeOracleHealth.ENDPOINT_UNAVAILABLE,
            OracleWireHealth.CALLBACK_POISONED to AuthoritativeOracleHealth.SESSION_UNCERTAIN,
            OracleWireHealth.INVARIANT_FAILURE to AuthoritativeOracleHealth.INVARIANT_FAILED,
        )
        assertEquals(OracleWireHealth.entries.toSet(), mappings.keys)

        mappings.forEach { (wireHealth, expectedHealth) ->
            val source = BinderAuthoritativeContinuitySource {
                validWire().copy(health = wireHealth)
            }
            assertEquals(wireHealth.name, expectedHealth, source.snapshot()?.health)
        }
    }

    @Test
    fun `valid wire snapshot maps every field without promotion or rewriting`() {
        val wire = validWire()
        val registry = registryWith(FakeBinder { wire })

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
            sourceFor(registry).snapshot(),
        )
    }

    private fun sourceFor(
        registry: OracleClientRegistry<FakeBinder>,
    ): BinderAuthoritativeContinuitySource = BinderAuthoritativeContinuitySource {
        registry.current()?.snapshot()
    }

    private fun registryWith(binder: FakeBinder): OracleClientRegistry<FakeBinder> =
        OracleClientRegistry<FakeBinder>().apply {
            register(
                callingUid = 1_000,
                registration = OracleRegistration(binder, NoOpDeathLink),
            )
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

    private fun interface FakeBinder {
        fun snapshot(): OracleWireSnapshot?
    }

    private object NoOpDeathLink : OracleDeathLink {
        override fun link(onDeath: () -> Unit) = Unit
    }
}
