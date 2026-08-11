package name.caiyao.fakegps.ui

import java.util.concurrent.atomic.AtomicBoolean

/** Allows one user-triggered asynchronous mutation to own its state at a time. */
internal class SingleFlightGate {
    private val active = AtomicBoolean(false)

    fun tryStart(): Boolean = active.compareAndSet(false, true)

    fun finish() {
        active.set(false)
    }
}
