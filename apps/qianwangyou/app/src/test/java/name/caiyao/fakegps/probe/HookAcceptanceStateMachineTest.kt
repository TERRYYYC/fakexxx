package name.caiyao.fakegps.probe

import name.caiyao.fakegps.probe.HookAcceptanceStateMachine.Event
import name.caiyao.fakegps.probe.HookAcceptanceStateMachine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HookAcceptanceStateMachineTest {

    @Test
    fun successfulRunTraversesPublishedProbeAndRestoreStates() {
        var state = State.IDLE

        state = HookAcceptanceStateMachine.transition(state, Event.PUBLISHED)
        assertEquals(State.PUBLISHED, state)
        assertTrue(HookAcceptanceStateMachine.requiresRestore(state))

        state = HookAcceptanceStateMachine.transition(state, Event.PROBE_STARTED)
        assertEquals(State.PROBING, state)
        assertTrue(HookAcceptanceStateMachine.requiresRestore(state))

        state = HookAcceptanceStateMachine.transition(state, Event.RESTORE_STARTED)
        assertEquals(State.RESTORING, state)
        state = HookAcceptanceStateMachine.transition(state, Event.RESTORE_COMPLETED)
        assertEquals(State.RESTORED, state)
        assertFalse(HookAcceptanceStateMachine.requiresRestore(state))
    }

    @Test
    fun invalidPayloadAbortsWithoutClaimingRestore() {
        val state = HookAcceptanceStateMachine.transition(State.IDLE, Event.ABORTED)

        assertEquals(State.ABORTED, state)
        assertFalse(HookAcceptanceStateMachine.requiresRestore(state))
    }

    @Test
    fun publishedAndProbingFailuresStillRequireRestore() {
        assertTrue(HookAcceptanceStateMachine.requiresRestore(State.PUBLISHED))
        assertTrue(HookAcceptanceStateMachine.requiresRestore(State.PROBING))
        assertTrue(HookAcceptanceStateMachine.requiresRestore(State.RESTORING))
    }

    @Test
    fun illegalTransitionsFailClosed() {
        assertThrows(IllegalStateException::class.java) {
            HookAcceptanceStateMachine.transition(State.IDLE, Event.PROBE_STARTED)
        }
        assertThrows(IllegalStateException::class.java) {
            HookAcceptanceStateMachine.transition(State.RESTORED, Event.PUBLISHED)
        }
    }
}
