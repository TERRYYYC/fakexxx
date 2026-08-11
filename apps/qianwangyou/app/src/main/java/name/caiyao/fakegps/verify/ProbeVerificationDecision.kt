package name.caiyao.fakegps.verify

sealed interface ProbeUiStatus {
    data object NotRequested : ProbeUiStatus
    data object Starting : ProbeUiStatus
    data object Verified : ProbeUiStatus
    data class Failed(val failure: ProbeFailure) : ProbeUiStatus
}

/** The only bridge from request state into VerificationEngine inputs. */
data class ProbeVerificationDecision(
    val scope: ObservationScope,
    val observed: Map<String, String>,
    val notes: List<String>,
    val cellCount: Int,
    val status: ProbeUiStatus,
    val failure: ProbeFailure?,
) {
    companion object {
        fun resolve(state: VerificationRequestState): ProbeVerificationDecision = when (state) {
            VerificationRequestState.Idle -> baseline(ProbeUiStatus.NotRequested)
            is VerificationRequestState.Starting -> baseline(ProbeUiStatus.Starting)
            is VerificationRequestState.Failed -> baseline(
                ProbeUiStatus.Failed(state.failure),
                state.failure,
            )
            is VerificationRequestState.Delivered -> ProbeVerificationDecision(
                scope = ObservationScope.HOOK_PROBE,
                observed = state.observation.values,
                notes = state.observation.notes,
                cellCount = state.observation.cellCount,
                status = ProbeUiStatus.Verified,
                failure = null,
            )
        }

        private fun baseline(
            status: ProbeUiStatus,
            failure: ProbeFailure? = null,
        ) = ProbeVerificationDecision(
            scope = ObservationScope.REAL_BASELINE,
            observed = emptyMap(),
            notes = emptyList(),
            cellCount = 0,
            status = status,
            failure = failure,
        )
    }
}
