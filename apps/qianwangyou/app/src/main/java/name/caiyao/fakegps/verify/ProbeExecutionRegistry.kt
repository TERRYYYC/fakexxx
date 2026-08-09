package name.caiyao.fakegps.verify

import java.util.concurrent.FutureTask

/** Owns cancellable probe work by request ID so one screen cannot stop another screen's request. */
internal class ProbeExecutionRegistry {
    private val active = mutableMapOf<String, FutureTask<*>>()

    @Synchronized
    fun register(requestId: String, task: FutureTask<*>): Boolean =
        if (active.containsKey(requestId)) false else {
            active[requestId] = task
            true
        }

    @Synchronized
    fun cancel(requestId: String): Boolean {
        val task = active.remove(requestId) ?: return false
        task.cancel(true)
        return true
    }

    @Synchronized
    fun isActive(requestId: String, task: FutureTask<*>): Boolean =
        active[requestId] === task

    @Synchronized
    fun complete(requestId: String, task: FutureTask<*>): Boolean {
        active.remove(requestId, task)
        return active.isEmpty()
    }

    @Synchronized
    fun isIdle(): Boolean = active.isEmpty()

    @Synchronized
    fun cancelAll() {
        val tasks = active.values.toList()
        active.clear()
        tasks.forEach { it.cancel(true) }
    }
}
