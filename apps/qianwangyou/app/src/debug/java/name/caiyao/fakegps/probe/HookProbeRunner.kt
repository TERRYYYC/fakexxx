package name.caiyao.fakegps.probe

import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Keeps HookProbe's blocking framework waits away from the activity main thread.
 *
 * Completion is dispatched through the activity-owned executor so lifecycle transitions and
 * result publication stay serialized on the main thread. Closing the runner suppresses any
 * completion that was queued before the activity was destroyed.
 */
internal class HookProbeRunner(
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val callbackExecutor: Executor,
) {
    @Volatile
    private var closed = false

    fun <T> submit(
        task: () -> T,
        completion: (Result<T>) -> Unit = {},
    ) {
        if (closed) return
        try {
            worker.execute {
                dispatch(runCatching(task), completion)
            }
        } catch (failure: RejectedExecutionException) {
            dispatch(Result.failure(failure), completion)
        }
    }

    fun close() {
        closed = true
        worker.shutdownNow()
    }

    private fun <T> dispatch(
        result: Result<T>,
        completion: (Result<T>) -> Unit,
    ) {
        if (closed) return
        callbackExecutor.execute {
            if (!closed) {
                completion(result)
            }
        }
    }
}
