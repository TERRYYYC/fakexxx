package name.caiyao.fakegps.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DeferredProcessTerminationTest {
    @Test
    fun `cancelled termination stays harmless even if its callback was already dequeued`() {
        val callbacks = mutableListOf<Runnable>()
        val removed = mutableListOf<Runnable>()
        var terminations = 0
        val termination = DeferredProcessTermination(
            postDelayed = { callback, _ -> callbacks += callback },
            removeCallback = { callback -> removed += callback },
            terminate = { terminations += 1 },
        )

        termination.schedule(500L)
        val staleCallback = callbacks.single()
        termination.cancelPending()
        staleCallback.run()

        assertSame(staleCallback, removed.single())
        assertEquals(0, terminations)
    }

    @Test
    fun `current scheduled termination still executes once`() {
        val callbacks = mutableListOf<Runnable>()
        var terminations = 0
        val termination = DeferredProcessTermination(
            postDelayed = { callback, _ -> callbacks += callback },
            removeCallback = {},
            terminate = { terminations += 1 },
        )

        termination.schedule(500L)
        callbacks.single().run()
        callbacks.single().run()

        assertEquals(1, terminations)
    }

    @Test
    fun `stale callback cannot consume the termination scheduled after a retry`() {
        val callbacks = mutableListOf<Runnable>()
        var terminations = 0
        val termination = DeferredProcessTermination(
            postDelayed = { callback, _ -> callbacks += callback },
            removeCallback = {},
            terminate = { terminations += 1 },
        )

        termination.schedule(500L)
        val staleCallback = callbacks.single()
        termination.cancelPending()
        termination.schedule(500L)
        val currentCallback = callbacks.last()

        staleCallback.run()
        currentCallback.run()

        assertEquals(1, terminations)
    }
}
