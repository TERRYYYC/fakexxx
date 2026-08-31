package name.caiyao.fakegps.integration.v1

/** Android-free process lifetime token; a Binder-backed adapter may wrap IBinder here. */
fun interface QwySemanticClientDeathToken {
    fun isAlive(): Boolean
}

fun interface QwySemanticClientDeathTokenFactory {
    fun create(): QwySemanticClientDeathToken
}

/**
 * Production adapter point for IAuthoritativeContinuityOracle. Implementations
 * unwrap their own death-token type; this contract never exposes Android APIs.
 */
interface QwySemanticMutationEndpoint {
    fun registerCurrentSession(
        semanticDigest: String,
        clientDeathToken: QwySemanticClientDeathToken,
    )

    fun beginMutation(
        mutationId: String,
        beforeDigest: String,
        clientDeathToken: QwySemanticClientDeathToken,
    ): Long

    fun finishMutation(
        token: Long,
        changed: Boolean,
        uncertain: Boolean,
        afterDigest: String?,
    )
}

fun interface QwySemanticMutationEndpointProvider {
    fun current(): QwySemanticMutationEndpoint?
}

enum class QwySemanticMutationFailure {
    INVALID_INPUT,
    ENDPOINT_UNAVAILABLE,
    ENDPOINT_CHANGED,
    CLIENT_DEATH_TOKEN_FAILED,
    CLIENT_DIED,
    REGISTRATION_FAILED,
    SESSION_UNAVAILABLE,
    DIGEST_MISMATCH,
    BEGIN_FAILED,
    OPERATION_FAILED,
    FINISH_FAILED,
    OUTCOME_UNPROVEN,
    EXPLICIT_UNCERTAIN,
}

sealed interface QwySemanticSessionRegistration {
    data class Registered(val semanticDigest: String) : QwySemanticSessionRegistration
    data class Failed(val reason: QwySemanticMutationFailure) : QwySemanticSessionRegistration
}

/** The local mutation reports what it can prove after its durable/local commit. */
sealed interface QwySemanticMutationWork<out T> {
    data class Changed<T>(val value: T, val afterDigest: String) : QwySemanticMutationWork<T>
    data class ProvedNoOp<T>(val value: T, val afterDigest: String) : QwySemanticMutationWork<T>
    data class Uncertain(val afterDigest: String? = null) : QwySemanticMutationWork<Nothing>
}

/** Only [Changed] and [ProvedNoOp] carry a value callers may continue with. */
sealed interface QwySemanticMutationResult<out T> {
    data class Changed<T>(val value: T, val afterDigest: String) : QwySemanticMutationResult<T>
    data class ProvedNoOp<T>(val value: T, val afterDigest: String) : QwySemanticMutationResult<T>
    data class Uncertain(val reason: QwySemanticMutationFailure) :
        QwySemanticMutationResult<Nothing>
}

/**
 * Serial process-side bracket for QWY semantic state. A trusted result exists
 * only when the same live endpoint and client-death token survive register,
 * begin, local work, and finish.
 */
class QwySemanticMutationCoordinator(
    private val endpointProvider: QwySemanticMutationEndpointProvider,
    private val clientDeathTokenFactory: QwySemanticClientDeathTokenFactory,
) {
    private val currentThreadMutationDepth = ThreadLocal.withInitial { 0 }

    private data class Session(
        val endpoint: QwySemanticMutationEndpoint,
        val deathToken: QwySemanticClientDeathToken,
        val semanticDigest: String,
    )

    private var session: Session? = null

    /** Read-only lane selection check; no session is created implicitly. */
    @Synchronized
    fun isReadyFor(semanticDigest: String): Boolean {
        val active = session ?: return false
        if (semanticDigest.isBlank() || active.semanticDigest != semanticDigest) return false
        if (!isAlive(active.deathToken)) return false
        return currentEndpoint() === active.endpoint
    }

    /**
     * True only while this coordinator is executing local work on this thread.
     * Central writers use it to join a handler-owned outer bracket instead of
     * creating a second mutation ID for the same durable publication.
     */
    fun isMutationInFlightOnCurrentThread(): Boolean =
        (currentThreadMutationDepth.get() ?: 0) > 0

    @Synchronized
    fun registerCurrentSession(semanticDigest: String): QwySemanticSessionRegistration {
        session = null
        if (semanticDigest.isBlank()) {
            return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.INVALID_INPUT,
            )
        }

        val endpoint = currentEndpoint()
            ?: return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            )
        val deathToken = try {
            clientDeathTokenFactory.create()
        } catch (_: Exception) {
            return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.CLIENT_DEATH_TOKEN_FAILED,
            )
        }
        if (!isAlive(deathToken)) {
            return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.CLIENT_DIED,
            )
        }

        try {
            endpoint.registerCurrentSession(semanticDigest, deathToken)
        } catch (_: Exception) {
            return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.REGISTRATION_FAILED,
            )
        }

        if (!isAlive(deathToken)) {
            return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.CLIENT_DIED,
            )
        }
        val currentAfterRegistration = currentEndpoint()
            ?: return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            )
        if (currentAfterRegistration !== endpoint) {
            return QwySemanticSessionRegistration.Failed(
                QwySemanticMutationFailure.ENDPOINT_CHANGED,
            )
        }

        session = Session(endpoint, deathToken, semanticDigest)
        return QwySemanticSessionRegistration.Registered(semanticDigest)
    }

    @Synchronized
    fun <T> runMutation(
        mutationId: String,
        beforeDigest: String,
        operation: () -> QwySemanticMutationWork<T>,
    ): QwySemanticMutationResult<T> {
        if (mutationId.isBlank() || beforeDigest.isBlank()) {
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.INVALID_INPUT,
            )
        }
        val active = session
            ?: return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.SESSION_UNAVAILABLE,
            )
        if (beforeDigest != active.semanticDigest) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.DIGEST_MISMATCH,
            )
        }

        val endpoint = currentEndpoint()
        if (endpoint == null) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            )
        }
        if (endpoint !== active.endpoint) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.ENDPOINT_CHANGED,
            )
        }
        if (!isAlive(active.deathToken)) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.CLIENT_DIED,
            )
        }

        val remoteToken = try {
            endpoint.beginMutation(mutationId, beforeDigest, active.deathToken)
        } catch (_: Exception) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.BEGIN_FAILED,
            )
        }
        if (remoteToken <= 0L) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.BEGIN_FAILED,
            )
        }
        if (!isAlive(active.deathToken)) {
            return finishUncertain(
                active,
                remoteToken,
                afterDigest = null,
                reason = QwySemanticMutationFailure.CLIENT_DIED,
            )
        }

        val work = try {
            currentThreadMutationDepth.set((currentThreadMutationDepth.get() ?: 0) + 1)
            try {
                operation()
            } finally {
                val remaining = (currentThreadMutationDepth.get() ?: 1) - 1
                if (remaining == 0) currentThreadMutationDepth.remove()
                else currentThreadMutationDepth.set(remaining)
            }
        } catch (_: Exception) {
            return finishUncertain(
                active,
                remoteToken,
                afterDigest = null,
                reason = QwySemanticMutationFailure.OPERATION_FAILED,
            )
        }

        val endpointAfterWork = currentEndpoint()
        if (endpointAfterWork == null) {
            return finishUncertain(
                active,
                remoteToken,
                afterDigest = null,
                reason = QwySemanticMutationFailure.ENDPOINT_UNAVAILABLE,
            )
        }
        if (endpointAfterWork !== active.endpoint) {
            return finishUncertain(
                active,
                remoteToken,
                afterDigest = null,
                reason = QwySemanticMutationFailure.ENDPOINT_CHANGED,
            )
        }
        if (!isAlive(active.deathToken)) {
            return finishUncertain(
                active,
                remoteToken,
                afterDigest = null,
                reason = QwySemanticMutationFailure.CLIENT_DIED,
            )
        }

        return when (work) {
            is QwySemanticMutationWork.Changed -> {
                if (work.afterDigest.isBlank()) {
                    finishUncertain(
                        active,
                        remoteToken,
                        afterDigest = null,
                        reason = QwySemanticMutationFailure.OUTCOME_UNPROVEN,
                    )
                } else {
                    finishChanged(active, remoteToken, work)
                }
            }

            is QwySemanticMutationWork.ProvedNoOp -> {
                if (work.afterDigest != beforeDigest || work.afterDigest.isBlank()) {
                    finishUncertain(
                        active,
                        remoteToken,
                        afterDigest = work.afterDigest.takeIf { it.isNotBlank() },
                        reason = QwySemanticMutationFailure.OUTCOME_UNPROVEN,
                    )
                } else {
                    finishNoOp(active, remoteToken, work)
                }
            }

            is QwySemanticMutationWork.Uncertain -> finishUncertain(
                active,
                remoteToken,
                afterDigest = work.afterDigest,
                reason = QwySemanticMutationFailure.EXPLICIT_UNCERTAIN,
            )
        }
    }

    private fun <T> finishChanged(
        active: Session,
        remoteToken: Long,
        work: QwySemanticMutationWork.Changed<T>,
    ): QwySemanticMutationResult<T> {
        try {
            active.endpoint.finishMutation(
                remoteToken,
                changed = true,
                uncertain = false,
                afterDigest = work.afterDigest,
            )
        } catch (_: Exception) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.FINISH_FAILED,
            )
        }
        if (!authoritySurvived(active)) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.CLIENT_DIED,
            )
        }
        session = active.copy(semanticDigest = work.afterDigest)
        return QwySemanticMutationResult.Changed(work.value, work.afterDigest)
    }

    private fun <T> finishNoOp(
        active: Session,
        remoteToken: Long,
        work: QwySemanticMutationWork.ProvedNoOp<T>,
    ): QwySemanticMutationResult<T> {
        try {
            active.endpoint.finishMutation(
                remoteToken,
                changed = false,
                uncertain = false,
                afterDigest = work.afterDigest,
            )
        } catch (_: Exception) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.FINISH_FAILED,
            )
        }
        if (!authoritySurvived(active)) {
            session = null
            return QwySemanticMutationResult.Uncertain(
                QwySemanticMutationFailure.CLIENT_DIED,
            )
        }
        session = active.copy(semanticDigest = work.afterDigest)
        return QwySemanticMutationResult.ProvedNoOp(work.value, work.afterDigest)
    }

    private fun finishUncertain(
        active: Session,
        remoteToken: Long,
        afterDigest: String?,
        reason: QwySemanticMutationFailure,
    ): QwySemanticMutationResult.Uncertain {
        session = null
        try {
            active.endpoint.finishMutation(
                remoteToken,
                changed = false,
                uncertain = true,
                afterDigest = afterDigest,
            )
        } catch (_: Exception) {
            // The original ambiguity remains the stronger diagnosis. Either
            // way, no trusted value or live local session escapes this method.
        }
        return QwySemanticMutationResult.Uncertain(reason)
    }

    private fun authoritySurvived(active: Session): Boolean =
        isAlive(active.deathToken) && currentEndpoint() === active.endpoint

    private fun currentEndpoint(): QwySemanticMutationEndpoint? = try {
        endpointProvider.current()
    } catch (_: Exception) {
        null
    }

    private fun isAlive(token: QwySemanticClientDeathToken): Boolean = try {
        token.isAlive()
    } catch (_: Exception) {
        false
    }
}
