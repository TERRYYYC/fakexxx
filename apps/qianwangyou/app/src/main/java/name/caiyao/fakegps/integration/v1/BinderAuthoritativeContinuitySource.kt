package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.oracle.OracleBundleCodec
import name.caiyao.fakegps.oracle.OracleClientRegistry
import name.caiyao.fakegps.oracle.OracleWireHealth
import name.caiyao.fakegps.oracle.OracleWireSnapshot

/**
 * QWY-process adapter from the private system-server Binder to the domain proof
 * type. Registry absence, Binder failure, and strict Bundle decode rejection
 * are all represented as no snapshot, so callers cannot promote them to FULL.
 */
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
        protocolVersion = protocolVersion,
        bootId = bootId,
        oracleInstanceId = oracleInstanceId,
        sequence = sequence,
        ownerUid = ownerUid,
        ownerPackage = ownerPackage,
        gpsProviderEnabled = gpsProviderEnabled,
        networkProviderEnabled = networkProviderEnabled,
        requiredCoverageMask = requiredCoverageMask,
        installedCoverageMask = installedCoverageMask,
        health = health.toAuthoritativeHealth(),
        qwySemanticDigest = qwySemanticDigest,
        lastCompletedQwyMutationId = lastCompletedQwyMutationId,
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
