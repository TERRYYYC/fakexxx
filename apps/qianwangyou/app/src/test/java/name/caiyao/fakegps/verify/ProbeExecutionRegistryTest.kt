package name.caiyao.fakegps.verify

import java.util.concurrent.FutureTask
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeExecutionRegistryTest {
    @Test
    fun `cancelling an old request preserves newer work`() {
        val registry = ProbeExecutionRegistry()
        val old = task()
        val current = task()

        assertTrue(registry.register("old", old))
        assertTrue(registry.register("current", current))
        assertTrue(registry.cancel("old"))

        assertTrue(old.isCancelled)
        assertFalse(registry.complete("old", old))
        assertTrue(registry.isActive("current", current))
        assertFalse(registry.isIdle())
        assertTrue(registry.complete("current", current))
    }

    @Test
    fun `unknown cancellation cannot consume the active request`() {
        val registry = ProbeExecutionRegistry()
        val current = task()

        assertTrue(registry.register("current", current))
        assertFalse(registry.cancel("old"))

        assertTrue(registry.isActive("current", current))
        assertFalse(current.isCancelled)
    }

    @Test
    fun `duplicate request id cannot replace its owner`() {
        val registry = ProbeExecutionRegistry()
        val first = task()
        val duplicate = task()

        assertTrue(registry.register("request", first))
        assertFalse(registry.register("request", duplicate))

        assertTrue(registry.isActive("request", first))
        assertFalse(duplicate.isCancelled)
    }

    @Test
    fun `destroying the service cancels every registered execution`() {
        val registry = ProbeExecutionRegistry()
        val first = task()
        val second = task()
        registry.register("first", first)
        registry.register("second", second)

        registry.cancelAll()

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertTrue(registry.isIdle())
    }

    private fun task(): FutureTask<Unit> = FutureTask({}, Unit)
}
