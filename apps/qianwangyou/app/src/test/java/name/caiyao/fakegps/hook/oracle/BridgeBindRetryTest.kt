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

    /**
     * #195 review (Medium)：预算耗尽后 onBindingDied 触发的 rebind 曾无预算且失败静默。框架
     * binding death 交给安装器的是全新一代，rebind 前 resetBudget()——只重置预算，不动代际
     * 守卫（代际归安装器管），rebind 的失败重新可调度、可日志，二次死路能再打一条放弃日志。
     */
    @Test
    fun `binding death after give-up hands the rebind a fresh budget that retries and logs again`() {
        val env = FakeEnvironment()
        val retry = BridgeBindRetry(env)

        repeat(BridgeBindRetry.RETRY_DELAYS_MS.size + 1) { retry.onRegistrationFailed("first generation") }
        assertEquals(1, env.logs.count { it.contains("gave up") })

        // onBindingDied → installer resetBudget() → rebind。
        retry.resetBudget()
        assertEquals(0, retry.retriesUsed())

        retry.onRegistrationFailed("rebind rejected")
        assertEquals("the rebind's first failure schedules a backoff from 1s again",
            listOf(1_000L), env.scheduledDelaysMs.takeLast(1))
        assertEquals("no premature second give-up line while the fresh budget lasts",
            1, env.logs.count { it.contains("gave up") })

        repeat(BridgeBindRetry.RETRY_DELAYS_MS.size) { retry.onRegistrationFailed("rebind session") }
        assertEquals("a second dead end logs its own give-up line", 2, env.logs.count { it.contains("gave up") })
        assertTrue("second give-up names the rebind session as the last failure",
            env.logs.last { it.contains("gave up") }.contains("rebind session"))
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
