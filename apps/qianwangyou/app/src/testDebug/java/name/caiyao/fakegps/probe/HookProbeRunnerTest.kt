package name.caiyao.fakegps.probe

import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HookProbeRunnerTest {
    @Test
    fun blockingProbeRunsOnWorkerAndCompletionReturnsThroughCallerExecutor() {
        val caller = Thread.currentThread()
        val callbackQueue = LinkedBlockingQueue<Runnable>()
        val workerThread = AtomicReference<Thread>()
        val completed = AtomicBoolean(false)
        val runner = HookProbeRunner(
            worker = Executors.newSingleThreadExecutor(),
            callbackExecutor = Executor(callbackQueue::add),
        )

        runner.submit(
            task = {
                workerThread.set(Thread.currentThread())
                "report"
            },
            completion = {
                assertEquals("report", it.getOrThrow())
                completed.set(true)
            },
        )

        val completion = callbackQueue.poll(2, TimeUnit.SECONDS)
        assertNotSame(caller, workerThread.get())
        assertFalse(completed.get())
        completion.run()
        assertTrue(completed.get())
        runner.close()
    }

    @Test
    fun closeSuppressesCompletionAlreadyQueuedForLifecycleOwner() {
        val callbackQueue = LinkedBlockingQueue<Runnable>()
        val completed = AtomicBoolean(false)
        val runner = HookProbeRunner(
            worker = Executors.newSingleThreadExecutor(),
            callbackExecutor = Executor(callbackQueue::add),
        )

        runner.submit(task = { "report" }) { completed.set(true) }
        val completion = callbackQueue.poll(2, TimeUnit.SECONDS)

        runner.close()
        completion.run()
        assertFalse(completed.get())
    }
}
