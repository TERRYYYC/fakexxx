package name.caiyao.fakegps.mockprovider

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayServicesTaskAwaiterTest {

    @Test
    fun `failed task exposes the platform cause instead of an execution wrapper`() {
        val denied = SecurityException("not selected as mock location app")
        val failure = runCatching {
            PlayServicesTaskAwaiter(timeout = 1, unit = TimeUnit.SECONDS)
                .await(Tasks.forException<Void>(denied))
        }.exceptionOrNull()

        assertSame(denied, failure)
    }

    @Test
    fun `unfinished task fails within the transaction timeout`() {
        val task = TaskCompletionSource<Void>().task
        val failure = runCatching {
            PlayServicesTaskAwaiter(timeout = 1, unit = TimeUnit.MILLISECONDS).await(task)
        }.exceptionOrNull()

        assertTrue(failure is TimeoutException)
    }
}
