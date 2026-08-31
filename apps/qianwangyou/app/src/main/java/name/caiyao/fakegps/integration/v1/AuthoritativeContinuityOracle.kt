package name.caiyao.fakegps.integration.v1

/** Internal protocol constants for the system-server continuity oracle. */
object AuthoritativeContinuityProtocol {
    const val VERSION = 1
}

/**
 * Independently attestable mutation surfaces required by protocol v1.
 *
 * Wrapper, API-35 Access Checking delegate, and direct lifecycle removal paths
 * deliberately have separate bits. Resolving one is not evidence for another.
 */
object AuthoritativeCoverageMask {
    const val APP_OPS_CHECKING_SERVICE_WRAPPER = 1L shl 0
    const val APP_OPS_ACCESS_CHECKING_DELEGATE = 1L shl 1
    const val APP_OPS_LIFECYCLE_REMOVAL = 1L shl 2
    const val LOCATION_PROVIDER_STATE = 1L shl 3
    const val LOCATION_EFFECTIVE_ENABLED = 1L shl 4
    const val QWY_SERVICE_GENERATION = 1L shl 5
    const val QWY_SEMANTIC_MUTATION = 1L shl 6
    const val BRIDGE_SESSION = 1L shl 7
    const val BUILD_ATTESTATION = 1L shl 8

    const val REQUIRED_V1 =
        APP_OPS_CHECKING_SERVICE_WRAPPER or
            APP_OPS_ACCESS_CHECKING_DELEGATE or
            APP_OPS_LIFECYCLE_REMOVAL or
            LOCATION_PROVIDER_STATE or
            LOCATION_EFFECTIVE_ENABLED or
            QWY_SERVICE_GENERATION or
            QWY_SEMANTIC_MUTATION or
            BRIDGE_SESSION or
            BUILD_ATTESTATION
}

/** Only [HEALTHY] may participate in a complete continuity proof. */
enum class AuthoritativeOracleHealth(val wire: Int) {
    HEALTHY(0),
    UNINITIALIZED(1),
    BUILD_UNATTESTED(2),
    HOOKS_INCOMPLETE(3),
    SESSION_UNAVAILABLE(4),
    SESSION_UNCERTAIN(5),
    ENDPOINT_UNAVAILABLE(6),
    INVARIANT_FAILED(7),
}

/** Endpoint state refreshed while the journal sequence is odd. */
data class AuthoritativeContinuityState(
    val ownerUid: Int?,
    val ownerPackage: String?,
    val gpsProviderEnabled: Boolean,
    val networkProviderEnabled: Boolean,
    val installedCoverageMask: Long,
    val health: AuthoritativeOracleHealth,
    val qwySemanticDigest: String?,
    val lastCompletedQwyMutationId: String?,
)

/** Immutable synchronous PRE/POST value carried by the internal Binder. */
data class AuthoritativeContinuitySnapshot(
    val protocolVersion: Int,
    val bootId: String,
    val oracleInstanceId: String,
    val sequence: Long,
    val ownerUid: Int?,
    val ownerPackage: String?,
    val gpsProviderEnabled: Boolean,
    val networkProviderEnabled: Boolean,
    val requiredCoverageMask: Long,
    val installedCoverageMask: Long,
    val health: AuthoritativeOracleHealth,
    val qwySemanticDigest: String?,
    val lastCompletedQwyMutationId: String?,
)

fun interface AuthoritativeContinuitySource {
    fun snapshot(): AuthoritativeContinuitySnapshot?
}

data class AuthoritativeObservationWindow(
    val pre: AuthoritativeContinuitySnapshot?,
    val post: AuthoritativeContinuitySnapshot?,
    val windowStartElapsedRealtimeMs: Long,
)

/** Result classes needed by durable reconciliation without weakening failure modes. */
enum class AuthoritativeWindowVerdict {
    VALID,
    BOOT_OR_INSTANCE_CHANGED,
    SEQUENCE_REGRESSION,
    MUTATING_OR_CHANGED,
    UNHEALTHY,
}

/**
 * Checks one endpoint snapshot. This does not prove history; callers still need
 * [classifyAuthoritativeWindow] over two synchronous reads.
 */
fun AuthoritativeContinuitySnapshot.isStableCompleteFor(
    expectedPackage: String,
    expectedUid: Int,
): Boolean {
    if (protocolVersion != AuthoritativeContinuityProtocol.VERSION) return false
    if (bootId.isBlank() || oracleInstanceId.isBlank()) return false
    if (sequence < 0L || sequence and 1L != 0L) return false
    if (health != AuthoritativeOracleHealth.HEALTHY) return false
    if (requiredCoverageMask != AuthoritativeCoverageMask.REQUIRED_V1) return false
    if (installedCoverageMask != requiredCoverageMask) return false
    if (ownerUid != expectedUid || ownerPackage != expectedPackage) return false
    if (!gpsProviderEnabled || !networkProviderEnabled) return false
    if (qwySemanticDigest.isNullOrBlank()) return false
    if (lastCompletedQwyMutationId != null && lastCompletedQwyMutationId.isBlank()) return false
    return true
}

/**
 * Pure PRE/POST classifier. The ordering is intentional: identity changes and
 * same-instance rollback need distinct durable treatment, while any odd or
 * advanced sequence means the read interval itself was not continuous.
 */
fun classifyAuthoritativeWindow(
    pre: AuthoritativeContinuitySnapshot?,
    post: AuthoritativeContinuitySnapshot?,
    expectedPackage: String,
    expectedUid: Int,
): AuthoritativeWindowVerdict {
    if (pre == null || post == null) return AuthoritativeWindowVerdict.UNHEALTHY
    if (!pre.hasWellFormedIdentityAndSequence() || !post.hasWellFormedIdentityAndSequence()) {
        return AuthoritativeWindowVerdict.UNHEALTHY
    }
    if (pre.bootId != post.bootId || pre.oracleInstanceId != post.oracleInstanceId) {
        return AuthoritativeWindowVerdict.BOOT_OR_INSTANCE_CHANGED
    }
    if (post.sequence < pre.sequence) {
        return AuthoritativeWindowVerdict.SEQUENCE_REGRESSION
    }
    if (pre.sequence and 1L != 0L || post.sequence and 1L != 0L || pre.sequence != post.sequence) {
        return AuthoritativeWindowVerdict.MUTATING_OR_CHANGED
    }
    if (!pre.isStableCompleteFor(expectedPackage, expectedUid) ||
        !post.isStableCompleteFor(expectedPackage, expectedUid)
    ) {
        return AuthoritativeWindowVerdict.UNHEALTHY
    }
    if (pre.qwySemanticDigest != post.qwySemanticDigest ||
        pre.lastCompletedQwyMutationId != post.lastCompletedQwyMutationId
    ) {
        return AuthoritativeWindowVerdict.MUTATING_OR_CHANGED
    }
    if (pre.requiredCoverageMask != post.requiredCoverageMask ||
        pre.installedCoverageMask != post.installedCoverageMask ||
        pre.health != post.health ||
        pre.ownerUid != post.ownerUid ||
        pre.ownerPackage != post.ownerPackage ||
        pre.gpsProviderEnabled != post.gpsProviderEnabled ||
        pre.networkProviderEnabled != post.networkProviderEnabled
    ) {
        return AuthoritativeWindowVerdict.UNHEALTHY
    }
    return AuthoritativeWindowVerdict.VALID
}

private fun AuthoritativeContinuitySnapshot.hasWellFormedIdentityAndSequence(): Boolean =
    protocolVersion == AuthoritativeContinuityProtocol.VERSION &&
        bootId.isNotBlank() &&
        oracleInstanceId.isNotBlank() &&
        sequence >= 0L

enum class AuthoritativeMutationOutcome {
    CHANGED,
    PROVED_NO_OP,
    UNCERTAIN,
}

class AuthoritativeMutationToken internal constructor(
    internal val oracleInstanceId: String,
    internal val id: Long,
)

/**
 * Synchronized odd/even seqlock journal owned by one system-server module
 * instance. Overlapping tokens share one odd interval; only the final exit can
 * publish a stable value.
 */
class AuthoritativeContinuityOracle(
    private val bootId: String,
    private val oracleInstanceId: String,
    private val requiredCoverageMask: Long = AuthoritativeCoverageMask.REQUIRED_V1,
    initialState: AuthoritativeContinuityState,
) : AuthoritativeContinuitySource {
    private var sequence = 0L
    private var state = initialState
    private var stickyHealth: AuthoritativeOracleHealth? = null
    private var nextTokenId = 0L
    private val activeTokenIds = linkedSetOf<Long>()

    private var outerStableSequence: Long? = null
    private var outerInitialState: AuthoritativeContinuityState? = null
    private var outerChanged = false
    private var outerUncertain = false

    @Synchronized
    override fun snapshot(): AuthoritativeContinuitySnapshot = snapshotLocked()

    @Synchronized
    fun beginMutation(): AuthoritativeMutationToken {
        if (nextTokenId == Long.MAX_VALUE ||
            (activeTokenIds.isEmpty() && sequence > Long.MAX_VALUE - 2L)
        ) {
            poisonInvariant()
            sequence = Long.MAX_VALUE
            throw IllegalStateException("authoritative continuity sequence overflow")
        }

        if (activeTokenIds.isEmpty()) {
            if (sequence < 0L || sequence and 1L != 0L) {
                poisonInvariant()
                throw IllegalStateException("authoritative continuity journal is not stable")
            }
            outerStableSequence = sequence
            outerInitialState = state
            outerChanged = false
            outerUncertain = false
            sequence += 1L
        } else if (sequence and 1L == 0L) {
            poisonInvariant()
            throw IllegalStateException("active mutation tokens require an odd sequence")
        }

        val token = AuthoritativeMutationToken(oracleInstanceId, nextTokenId++)
        activeTokenIds += token.id
        return token
    }

    @Synchronized
    fun finishMutation(
        token: AuthoritativeMutationToken,
        outcome: AuthoritativeMutationOutcome,
        state: AuthoritativeContinuityState? = null,
    ): AuthoritativeContinuitySnapshot {
        if (token.oracleInstanceId != oracleInstanceId || !activeTokenIds.remove(token.id)) {
            poisonInvariant()
            advanceForInvalidFinish()
            throw IllegalArgumentException("mutation token is not active for this oracle instance")
        }

        if (state != null) this.state = state
        when (outcome) {
            AuthoritativeMutationOutcome.CHANGED -> outerChanged = true
            AuthoritativeMutationOutcome.PROVED_NO_OP -> Unit
            AuthoritativeMutationOutcome.UNCERTAIN -> {
                outerUncertain = true
                poisonSession()
            }
        }

        if (activeTokenIds.isNotEmpty()) return snapshotLocked()

        val stableSequence = outerStableSequence
        val initialState = outerInitialState
        if (stableSequence == null || initialState == null || sequence and 1L == 0L) {
            poisonInvariant()
            advanceForInvalidFinish()
            clearOuterMutation()
            return snapshotLocked()
        }

        val endpointChangedWithoutReportedChange = !outerChanged && !outerUncertain &&
            this.state != initialState
        if (endpointChangedWithoutReportedChange) poisonInvariant()

        sequence = if (outerChanged || outerUncertain || endpointChangedWithoutReportedChange) {
            stableSequence + 2L
        } else {
            stableSequence
        }
        clearOuterMutation()
        return snapshotLocked()
    }

    /**
     * Explicit owner-session reconciliation after an uncertain client death or
     * finish. Registration is itself a changed generation interval; only that
     * explicit baseline may clear SESSION_UNCERTAIN.
     */
    @Synchronized
    fun registerRecoveredSession(
        recoveredState: AuthoritativeContinuityState,
    ): AuthoritativeContinuitySnapshot {
        require(recoveredState.health == AuthoritativeOracleHealth.HEALTHY) {
            "recovered authoritative session must provide healthy state"
        }
        val token = beginMutation()
        finishMutation(token, AuthoritativeMutationOutcome.CHANGED, recoveredState)
        if (stickyHealth == AuthoritativeOracleHealth.SESSION_UNCERTAIN) {
            stickyHealth = null
        }
        return snapshotLocked()
    }

    private fun snapshotLocked(): AuthoritativeContinuitySnapshot =
        AuthoritativeContinuitySnapshot(
            protocolVersion = AuthoritativeContinuityProtocol.VERSION,
            bootId = bootId,
            oracleInstanceId = oracleInstanceId,
            sequence = sequence,
            ownerUid = state.ownerUid,
            ownerPackage = state.ownerPackage,
            gpsProviderEnabled = state.gpsProviderEnabled,
            networkProviderEnabled = state.networkProviderEnabled,
            requiredCoverageMask = requiredCoverageMask,
            installedCoverageMask = state.installedCoverageMask,
            health = stickyHealth ?: state.health,
            qwySemanticDigest = state.qwySemanticDigest,
            lastCompletedQwyMutationId = state.lastCompletedQwyMutationId,
        )

    private fun poisonSession() {
        if (stickyHealth != AuthoritativeOracleHealth.INVARIANT_FAILED) {
            stickyHealth = AuthoritativeOracleHealth.SESSION_UNCERTAIN
        }
    }

    private fun poisonInvariant() {
        stickyHealth = AuthoritativeOracleHealth.INVARIANT_FAILED
    }

    private fun advanceForInvalidFinish() {
        sequence = when {
            sequence < 0L -> Long.MAX_VALUE
            sequence == Long.MAX_VALUE -> Long.MAX_VALUE
            sequence and 1L != 0L -> sequence + 1L
            sequence <= Long.MAX_VALUE - 2L -> sequence + 2L
            else -> Long.MAX_VALUE
        }
    }

    private fun clearOuterMutation() {
        outerStableSequence = null
        outerInitialState = null
        outerChanged = false
        outerUncertain = false
    }
}
