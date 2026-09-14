package name.caiyao.fakegps.hook.oracle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #194：phase-600 桥注册失败的重试预算——退避节奏、预算耗尽后的最终放弃、成功后重置。 */
class BridgeBindRetryTest {

    private class FakeEnvironment : BridgeBindRetry.Environment {
        val scheduledDelaysMs = mutableListOf<Long>()
        val logs = mutableListOf<String>()

        override fun scheduleRebind(delayMs: Long) {
            scheduledDelaysMs += delayMs
        }

        override fun log(message: String) {
            logs += message
        }
    }

    @Test
    fun `backoff escalates 1s 5s 30s 30s then the budget is spent`() {
        val env = FakeEnvironment()
        val retry = BridgeBindRetry(env)

        repeat(BridgeBindRetry.RETRY_DELAYS_MS.size) { attempt ->
            retry.onRegistrationFailed("failure-$attempt")
        }
        assertEquals(listOf(1_000L, 5_000L, 30_000L, 30_000L), env.scheduledDelaysMs)
        assertEquals(0, env.logs.count { it.contains("gave up") })

        env.scheduledDelaysMs.clear()
        retry.onRegistrationFailed("failure-final")
        retry.onRegistrationFailed("failure-after-give-up")

        assertTrue("no rebind may be scheduled after give-up", env.scheduledDelaysMs.isEmpty())
        assertEquals("final give-up logs exactly once, then stays silent", 1, env.logs.count { it.contains("gave up") })
    }

    @Test
    fun `success resets the budget so the next bridge generation starts fresh`() {
        val env = FakeEnvironment()
        val retry = BridgeBindRetry(env)

        retry.onRegistrationFailed("bind rejected")
        retry.onRegistrationFailed("null binding")
        assertEquals(listOf(1_000L, 5_000L), env.scheduledDelaysMs)

        retry.onRegistrationSucceeded()
        assertEquals(0, retry.retriesUsed())

        retry.onRegistrationFailed("never connected")
        assertEquals("fresh budget restarts at 1s", 1_000L, env.scheduledDelaysMs.last())
    }

    @Test
    fun `give-up marker clears on success so a later dead end logs again`() {
        val env = FakeEnvironment()
        val retry = BridgeBindRetry(env)

        repeat(BridgeBindRetry.RETRY_DELAYS_MS.size + 1) { retry.onRegistrationFailed("first session") }
        assertEquals(1, env.logs.count { it.contains("gave up") })

        retry.onRegistrationSucceeded()
        repeat(BridgeBindRetry.RETRY_DELAYS_MS.size + 1) { retry.onRegistrationFailed("second session") }
        assertEquals(2, env.logs.count { it.contains("gave up") })
    }

    @Test
    fun `failure reason and attempt progress are preserved in the log line`() {
        val env = FakeEnvironment()
        val retry = BridgeBindRetry(env)

        retry.onRegistrationFailed("phase-600 bridge bind rejected")

        assertEquals(1, env.logs.size)
        assertTrue(env.logs.single().contains("phase-600 bridge bind rejected"))
        assertTrue(env.logs.single().contains("1/4"))
    }
}
