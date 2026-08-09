package name.caiyao.fakegps.mockprovider

import com.google.android.gms.tasks.Task
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PlayServicesTaskAwaiter(
    private val timeout: Long = DEFAULT_TIMEOUT_SECONDS,
    private val unit: TimeUnit = TimeUnit.SECONDS,
) {
    fun await(task: Task<Void>) {
        val completed = CountDownLatch(1)
        var taskFailure: Exception? = null
        task.addOnCompleteListener(DIRECT_EXECUTOR) { result ->
            taskFailure = result.exception
            completed.countDown()
        }
        if (!completed.await(timeout, unit)) {
            throw TimeoutException("Google Play services location task timed out")
        }
        taskFailure?.let { throw it }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 5L
        val DIRECT_EXECUTOR = Executor(Runnable::run)
    }
}
