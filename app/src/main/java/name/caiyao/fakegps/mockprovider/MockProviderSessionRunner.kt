package name.caiyao.fakegps.mockprovider

import java.util.concurrent.Executor

/** Runs bounded blocking provider transactions off main, then returns their result to main. */
class MockProviderSessionRunner(
    private val worker: Executor,
    private val completion: Executor,
) {
    fun submit(
        operation: () -> MockProviderState,
        onComplete: (Result<MockProviderState>) -> Unit,
    ) {
        worker.execute {
            val result = runCatching(operation)
            completion.execute { onComplete(result) }
        }
    }
}
