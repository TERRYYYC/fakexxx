package name.caiyao.fakegps.verify

/** Identity of one verification attempt. Both values must match before a result is accepted. */
data class ProbeRequest(
    val requestId: String,
    val fingerprint: String,
)

enum class ProbeFailure {
    NOT_SCOPED,
    TIMEOUT,
    PAYLOAD_MISMATCH,
    MALFORMED_RESULT,
    START_FAILED,
    INTERNAL_ERROR,
}

object ProbeResultCorrelation {
    fun matches(request: ProbeRequest, observation: ProbeObservationEnvelope): Boolean =
        request.requestId == observation.requestId &&
            request.fingerprint == observation.fingerprint
}

sealed interface VerificationRequestState {
    data object Idle : VerificationRequestState
    data class Starting(val request: ProbeRequest) : VerificationRequestState
    data class Delivered(
        val request: ProbeRequest,
        val observation: ProbeObservationEnvelope,
    ) : VerificationRequestState
    data class Failed(
        val request: ProbeRequest,
        val failure: ProbeFailure,
    ) : VerificationRequestState
}

/** Pure correlation gate shared by the Android client and JVM tests. */
object VerificationRequestCoordinator {

    fun start(request: ProbeRequest): VerificationRequestState =
        VerificationRequestState.Starting(request)

    fun accept(
        current: VerificationRequestState,
        observation: ProbeObservationEnvelope,
    ): VerificationRequestState {
        val active = (current as? VerificationRequestState.Starting)?.request ?: return current
        if (active.requestId != observation.requestId ||
            active.fingerprint != observation.fingerprint
        ) {
            return current
        }
        return VerificationRequestState.Delivered(active, observation)
    }

    fun fail(
        current: VerificationRequestState,
        request: ProbeRequest,
        failure: ProbeFailure,
    ): VerificationRequestState {
        val active = (current as? VerificationRequestState.Starting)?.request ?: return current
        if (active != request) return current
        return VerificationRequestState.Failed(active, failure)
    }
}
