package name.caiyao.fakegps.ui.navigation

import androidx.lifecycle.Lifecycle

/**
 * Serializes navigation for one back-stack entry across its enter/exit transition.
 *
 * A click received while the entry is entering is retained until RESUMED. The same STARTED state
 * on an outgoing entry is rejected while its first navigation remains in flight. The in-flight
 * token is acquired before invoking NavController, so even two callbacks that both still observe
 * RESUMED cannot mutate the stack twice. It resets only if this entry later returns to RESUMED.
 */
internal class NavigationActionGuard {
    private var active = true
    private var navigationInFlight = false
    private var pendingAction: (() -> Unit)? = null

    fun submit(state: Lifecycle.State, action: () -> Unit): Boolean {
        if (!active || navigationInFlight) return false
        if (state == Lifecycle.State.RESUMED) {
            val pending = pendingAction
            if (pending != null) {
                pendingAction = null
                runInFlight(pending)
                return false
            }
            runInFlight(action)
            return true
        }
        if (state == Lifecycle.State.STARTED && pendingAction == null) {
            pendingAction = action
            return true
        }
        return false
    }

    fun onStateChanged(state: Lifecycle.State): Boolean {
        if (!active) return false
        if (state == Lifecycle.State.RESUMED) {
            if (navigationInFlight) {
                navigationInFlight = false
                return false
            }
            val pending = pendingAction ?: return false
            pendingAction = null
            runInFlight(pending)
            return true
        }
        if (!state.isAtLeast(Lifecycle.State.STARTED)) pendingAction = null
        return false
    }

    private fun runInFlight(action: () -> Unit) {
        navigationInFlight = true
        try {
            action()
        } catch (failure: Throwable) {
            navigationInFlight = false
            throw failure
        }
    }

    fun dispose() {
        active = false
        navigationInFlight = false
        pendingAction = null
    }
}
