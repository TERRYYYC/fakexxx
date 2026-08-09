package name.caiyao.fakegps.probe

internal object HookAcceptanceStateMachine {
    enum class State {
        IDLE,
        PUBLISHED,
        PROBING,
        RESTORING,
        RESTORED,
        ABORTED,
    }

    enum class Event {
        PUBLISHED,
        PROBE_STARTED,
        RESTORE_STARTED,
        RESTORE_COMPLETED,
        ABORTED,
    }

    fun transition(state: State, event: Event): State =
        when (state to event) {
            State.IDLE to Event.PUBLISHED -> State.PUBLISHED
            State.IDLE to Event.ABORTED -> State.ABORTED
            State.PUBLISHED to Event.PROBE_STARTED -> State.PROBING
            State.PUBLISHED to Event.RESTORE_STARTED -> State.RESTORING
            State.PROBING to Event.RESTORE_STARTED -> State.RESTORING
            State.RESTORING to Event.RESTORE_COMPLETED -> State.RESTORED
            else -> throw IllegalStateException("illegal acceptance transition: $state + $event")
        }

    fun requiresRestore(state: State): Boolean =
        state == State.PUBLISHED || state == State.PROBING || state == State.RESTORING
}
