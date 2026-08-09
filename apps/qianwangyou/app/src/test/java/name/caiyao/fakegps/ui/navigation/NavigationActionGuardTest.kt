package name.caiyao.fakegps.ui.navigation

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationActionGuardTest {

    @Test
    fun resumedDestination_acceptsTheNavigationAction() {
        val guard = NavigationActionGuard()
        var invoked = false

        val accepted = guard.submit(Lifecycle.State.RESUMED) {
            invoked = true
        }

        assertTrue(accepted)
        assertTrue(invoked)
    }

    @Test
    fun consecutiveResumedSubmissions_acceptOnlyTheFirstAction() {
        val guard = NavigationActionGuard()
        val invocations = mutableListOf<String>()

        assertTrue(guard.submit(Lifecycle.State.RESUMED) { invocations += "first" })
        assertFalse(guard.submit(Lifecycle.State.RESUMED) { invocations += "second" })

        assertEquals(listOf("first"), invocations)
    }

    @Test
    fun enteringDestination_defersTheFirstActionUntilResumed() {
        val guard = NavigationActionGuard()
        var invoked = false

        val accepted = guard.submit(Lifecycle.State.STARTED) {
            invoked = true
        }

        assertTrue(accepted)
        assertFalse(invoked)

        assertTrue(guard.onStateChanged(Lifecycle.State.RESUMED))
        assertTrue(invoked)
    }

    @Test
    fun enteringDestination_acceptsOnlyOnePendingAction() {
        val guard = NavigationActionGuard()
        val invocations = mutableListOf<String>()

        assertTrue(guard.submit(Lifecycle.State.STARTED) { invocations += "first" })
        assertFalse(guard.submit(Lifecycle.State.STARTED) { invocations += "second" })
        guard.onStateChanged(Lifecycle.State.RESUMED)

        assertEquals(listOf("first"), invocations)
    }

    @Test
    fun destinationLeavingDuringTransition_discardsASecondNavigationAction() {
        val guard = NavigationActionGuard()
        val invocations = mutableListOf<String>()

        assertTrue(guard.submit(Lifecycle.State.RESUMED) { invocations += "first" })
        assertFalse(guard.submit(Lifecycle.State.STARTED) { invocations += "second" })
        guard.onStateChanged(Lifecycle.State.CREATED)
        guard.onStateChanged(Lifecycle.State.DESTROYED)

        assertEquals(listOf("first"), invocations)
        assertFalse(guard.onStateChanged(Lifecycle.State.RESUMED))
    }

    @Test
    fun destinationReturningToResumed_acceptsANewNavigationAction() {
        val guard = NavigationActionGuard()
        val invocations = mutableListOf<String>()

        assertTrue(guard.submit(Lifecycle.State.RESUMED) { invocations += "first" })
        guard.onStateChanged(Lifecycle.State.STARTED)
        guard.onStateChanged(Lifecycle.State.CREATED)
        guard.onStateChanged(Lifecycle.State.STARTED)
        assertFalse(guard.onStateChanged(Lifecycle.State.RESUMED))
        assertTrue(guard.submit(Lifecycle.State.RESUMED) { invocations += "second" })

        assertEquals(listOf("first", "second"), invocations)
    }

    @Test
    fun resumedActionFailure_restoresReadyState() {
        val guard = NavigationActionGuard()
        val expected = IllegalArgumentException("invalid route")

        val actual = assertThrows(IllegalArgumentException::class.java) {
            guard.submit(Lifecycle.State.RESUMED) { throw expected }
        }

        assertSame(expected, actual)
        var invoked = false
        assertTrue(guard.submit(Lifecycle.State.RESUMED) { invoked = true })
        assertTrue(invoked)
    }

    @Test
    fun deferredActionFailure_restoresReadyState() {
        val guard = NavigationActionGuard()
        val expected = IllegalStateException("destination disappeared")

        assertTrue(guard.submit(Lifecycle.State.STARTED) { throw expected })
        val actual = assertThrows(IllegalStateException::class.java) {
            guard.onStateChanged(Lifecycle.State.RESUMED)
        }

        assertSame(expected, actual)
        var invoked = false
        assertTrue(guard.submit(Lifecycle.State.RESUMED) { invoked = true })
        assertTrue(invoked)
    }

    @Test
    fun rejectedCompositeNavigation_executesNoPartialStep() {
        val guard = NavigationActionGuard()
        var popped = false
        var navigated = false

        guard.submit(Lifecycle.State.STARTED) {
            popped = true
            navigated = true
        }
        guard.onStateChanged(Lifecycle.State.CREATED)

        assertFalse(popped)
        assertFalse(navigated)
    }
}
