package name.caiyao.fakegps.mockprovider

import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderSessionRunnerTest {

    @Test
    fun `blocking provider transaction runs on worker before main completion`() {
        val workerQueue = ArrayDeque<Runnable>()
        val completionQueue = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        val runner = MockProviderSessionRunner(
            worker = Executor(workerQueue::addLast),
            completion = Executor(completionQueue::addLast),
        )

        runner.submit(
            operation = {
                events += "operation"
                MockProviderState.Idle
            },
            onComplete = { result ->
                assertEquals(MockProviderState.Idle, result.getOrThrow())
                events += "completion"
            },
        )

        assertTrue(events.isEmpty())
        workerQueue.removeFirst().run()
        assertEquals(listOf("operation"), events)
        completionQueue.removeFirst().run()
        assertEquals(listOf("operation", "completion"), events)
    }
}
