package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.oracle.OracleBundleCodec
import name.caiyao.fakegps.oracle.OracleClientRegistry
import name.caiyao.fakegps.oracle.OracleWireHealth
import name.caiyao.fakegps.oracle.OracleWireSnapshot

/** Read-only adapter. Registry absence, Binder failure, and schema failure are all absence. */
class BinderAuthoritativeContinuitySource internal constructor(
    private val readCurrentWireSnapshot: () -> OracleWireSnapshot?,
) : AuthoritativeContinuitySource {
    constructor() : this(::readProcessOracleSnapshot)

    override fun snapshot(): AuthoritativeContinuitySnapshot? = try {
        readCurrentWireSnapshot()?.toAuthoritativeSnapshot()
    } catch (_: Exception) {
        null
    }
}

private fun readProcessOracleSnapshot(): OracleWireSnapshot? {
    val oracle = OracleClientRegistry.process.current() ?: return null
    return OracleBundleCodec.decode(oracle.snapshot())
}

private fun OracleWireSnapshot.toAuthoritativeSnapshot(): AuthoritativeContinuitySnapshot =
    AuthoritativeContinuitySnapshot(
        protocolVersion, bootId, oracleInstanceId, sequence, ownerUid, ownerPackage,
        gpsProviderEnabled, networkProviderEnabled, requiredCoverageMask, installedCoverageMask,
        health.toAuthoritativeHealth(), qwySemanticDigest, lastCompletedQwyMutationId,
    )

private fun OracleWireHealth.toAuthoritativeHealth(): AuthoritativeOracleHealth = when (this) {
    OracleWireHealth.HEALTHY -> AuthoritativeOracleHealth.HEALTHY
    OracleWireHealth.BUILD_UNATTESTED -> AuthoritativeOracleHealth.BUILD_UNATTESTED
    OracleWireHealth.UNSUPPORTED_PLATFORM -> AuthoritativeOracleHealth.HOOKS_INCOMPLETE
    OracleWireHealth.BOOT_ID_UNAVAILABLE -> AuthoritativeOracleHealth.UNINITIALIZED
    OracleWireHealth.HOOKS_INCOMPLETE -> AuthoritativeOracleHealth.HOOKS_INCOMPLETE
    OracleWireHealth.BRIDGE_UNAVAILABLE -> AuthoritativeOracleHealth.SESSION_UNAVAILABLE
    OracleWireHealth.SESSION_UNAVAILABLE -> AuthoritativeOracleHealth.SESSION_UNAVAILABLE
    OracleWireHealth.ENDPOINT_UNAVAILABLE -> AuthoritativeOracleHealth.ENDPOINT_UNAVAILABLE
    OracleWireHealth.CALLBACK_POISONED -> AuthoritativeOracleHealth.SESSION_UNCERTAIN
    OracleWireHealth.INVARIANT_FAILURE -> AuthoritativeOracleHealth.INVARIANT_FAILED
}
