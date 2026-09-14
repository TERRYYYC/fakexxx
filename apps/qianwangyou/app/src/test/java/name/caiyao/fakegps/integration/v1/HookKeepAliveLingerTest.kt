package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.RecordingDiagnosticLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #198 迭代二（mi14 三周期证据，issues/198#issuecomment-5666574320）：首版在
 * lease 收敛的瞬间 stopService，而实测 PowerKeeper 在 FGS 移除后 3.0–3.8s 即冻
 * 结无 FGS 的 QWY；引擎 attempt 间隙（BufferGate/globalBufferSeconds，默认 10s）
 * 必然整个落在冻结窗内 → 下一次 attempt 的第一个 binder 调用（discover）黑洞
 * （"return black caller"，无自动 THAW）→ typed PAUSED。0 次干净边界跨越。
 *
 * 修复语义：lease 压力收敛后保活【有界 linger】——下游（FGS）保持拉起状态
 * [LingeringKeepAlive.DEFAULT_LINGER_MILLIS]，期间到来的新 engage 取消定时器并
 * 继续；linger 到期才真正 disengage。plan 真结束时保活仍会在 linger 上限内退出
 * （不常驻）。
 *
 * 每条用例钉住 linger 状态机的一个边界：
 *  - 收敛不立即 disengage，linger 到期才 disengage
 *  - 间隙内（< linger）新 lease → 无保活窗口、无多余 disengage
 *  - 新 engage 重置：disarm，下一次收敛重新起满额 linger
 *  - 提前/陈旧的定时器触发按剩余时间重挂，绝不提前 disengage（也是"不常驻"
 *    的另一半：不精确的定时器最终仍会触发退出）
 *  - 重复收敛信号重新起满额 linger
 *  - 红线：linger 延迟路径跑在 handler 的 try/catch 之外，下游失败必须自己
 *    降级为 diagnostics，绝不能炸进定时器线程（= 进程崩溃）
 *  - IDLE 时的 false 原样透传（与首版 stopService no-op 行为对齐）
 */
class HookKeepAliveLingerTest {

    // ---- test doubles ----

    private class RecordingDownstream : LeaseKeepAliveSignal {
        val events = mutableListOf<Boolean>()

        /** 设置后，收到匹配的压力值即抛异常（模拟坏掉的 Android wiring）。 */
        var failOn: Boolean? = null

        override fun onLeasePressure(hasBlockingLease: Boolean) {
            if (hasBlockingLease == failOn) {
                throw IllegalStateException("FGS start not allowed (simulated)")
            }
            events += hasBlockingLease
        }
    }

    /** 本类与 binding/Robolectric 用例共用：可手动触发的 linger 定时器假件。 */
    internal class FakeLingerTimer : LingeringKeepAlive.LingerTimer {        var armedDelayMs: Long? = null
            private set
        private var onExpiry: (() -> Unit)? = null

        override fun arm(delayMs: Long, onExpiry: () -> Unit) {
            armedDelayMs = delayMs
            this.onExpiry = onExpiry
        }

        override fun disarm() {
            armedDelayMs = null
            onExpiry = null
        }

        /** 生产入口的镜像：一次性定时器到点触发挂起的回调（触发即解除挂起）。 */
        fun fire() {
            val callback = onExpiry ?: return
            armedDelayMs = null
            onExpiry = null
            callback()
        }
    }

    // ---- fixture ----

    private class Fixture {
        val clock = FakeMonotonicClock()
        val timer = FakeLingerTimer()
        val downstream = RecordingDownstream()
        val diagnostics = RecordingDiagnosticLog()
        val policy = LingeringKeepAlive(
            downstream = downstream,
            clock = clock,
            lingerTimer = timer,
            diagnostics = diagnostics,
        )
    }

    private fun fixture() = Fixture()

    // ---- tests ----

    @Test
    fun `a converged release waits out the linger before disengaging`() {
        val f = fixture()

        f.policy.onLeasePressure(true)
        f.policy.onLeasePressure(false)

        assertEquals(
            "收敛瞬间不得 disengage —— 这正是迭代一钉死的确定性黑洞形态",
            listOf(true),
            f.downstream.events,
        )

        f.clock.advance(LingeringKeepAlive.DEFAULT_LINGER_MILLIS)
        f.timer.fire()

        assertEquals(
            "linger 到期才真正 disengage（plan 真结束时保活仍会退出，不常驻）",
            listOf(true, false),
            f.downstream.events,
        )
        assertNull("退出后不得残留挂起的定时器", f.timer.armedDelayMs)
    }

    @Test
    fun `a next lease arriving inside the linger window leaves no keep-alive gap`() {
        val f = fixture()

        f.policy.onLeasePressure(true) // attempt 1
        f.policy.onLeasePressure(false) // attempt 1 release 收敛
        f.clock.advance(10_000L) // BufferGate 默认 10s 间隙（< linger）
        f.policy.onLeasePressure(true) // attempt 2 apply

        assertEquals(
            "间隙 < linger：不得出现任何 disengage（无保活窗口即冻结黑洞）",
            listOf(true, true),
            f.downstream.events,
        )
        assertNull("新 engage 必须取消挂起的 linger 定时器", f.timer.armedDelayMs)

        // 陈旧触发（定时器已被 disarm）也不得产生多余 disengage
        f.timer.fire()
        assertEquals(listOf(true, true), f.downstream.events)
    }

    @Test
    fun `a new engage resets the linger so the window starts over`() {
        val f = fixture()

        f.policy.onLeasePressure(true)
        f.policy.onLeasePressure(false) // linger 窗口 1 起点
        f.clock.advance(25_000L)
        f.policy.onLeasePressure(true) // 间隙内新 lease → disarm + 继续
        f.policy.onLeasePressure(false) // 新收敛 → 重新起满额 linger
        f.clock.advance(25_000L) // 距第二个收敛仅 25s
        f.timer.fire()

        assertEquals(
            "重置后窗口未满，不得 disengage（第二个 true 是新 engage 本身的透传）",
            listOf(true, true),
            f.downstream.events,
        )
        assertNotNull("窗口未满必须仍有挂起的定时器（不常驻的保证）", f.timer.armedDelayMs)

        f.clock.advance(5_000L) // 第二个收敛后满 30s
        f.timer.fire()

        assertEquals(listOf(true, true, false), f.downstream.events)
    }

    @Test
    fun `an early timer fire re-arms the remainder instead of disengaging`() {
        val f = fixture()

        f.policy.onLeasePressure(true)
        f.policy.onLeasePressure(false)
        f.clock.advance(LingeringKeepAlive.DEFAULT_LINGER_MILLIS - 1L)
        f.timer.fire() // 不精确定时器提前 1ms 触发

        assertEquals("提前触发不得 disengage", listOf(true), f.downstream.events)
        assertEquals(
            "提前触发按剩余时间重挂 —— 否则要么常驻、要么提前断保活",
            1L,
            f.timer.armedDelayMs,
        )

        f.clock.advance(1L)
        f.timer.fire()

        assertEquals(listOf(true, false), f.downstream.events)
    }

    @Test
    fun `repeated convergence signals restart the full linger`() {
        val f = fixture()

        f.policy.onLeasePressure(true)
        f.policy.onLeasePressure(false) // 窗口 1
        f.clock.advance(20_000L)
        f.policy.onLeasePressure(false) // 幂等 replay / 二次收敛信号：窗口重启
        f.clock.advance(10_000L) // 距窗口 1 满额，但距窗口 2 仅 10s
        f.timer.fire()

        assertEquals(listOf(true), f.downstream.events)

        f.clock.advance(20_000L)
        f.timer.fire()

        assertEquals(listOf(true, false), f.downstream.events)
    }

    @Test
    fun `a downstream failure on the delayed path degrades to diagnostics and never throws`() {
        val f = fixture()

        f.policy.onLeasePressure(true)
        f.downstream.failOn = false
        f.policy.onLeasePressure(false)
        f.clock.advance(LingeringKeepAlive.DEFAULT_LINGER_MILLIS)

        try {
            f.timer.fire()
        } catch (failure: Throwable) {
            throw AssertionError("linger 延迟路径抛异常 = 炸进定时器线程（红线）", failure)
        }

        assertEquals("下游失败不得吞掉 disengage 语义本身", listOf(true), f.downstream.events)

        // 相位必须前移到 IDLE：后续收敛信号回到透传（而不是卡死在 LINGERING 重挂窗口）
        f.downstream.failOn = null
        f.policy.onLeasePressure(false)
        assertEquals(listOf(true, false), f.downstream.events)

        assertTrue(
            "失败必须降级为 diagnostics 行",
            f.diagnostics.lines.any { it.contains("#198 keep-alive linger") },
        )
    }

    @Test
    fun `an idle false passes through unchanged`() {
        val f = fixture()

        f.policy.onLeasePressure(false)

        assertEquals(
            "IDLE 下的收敛信号原样透传（与首版 stopService no-op 行为对齐）",
            listOf(false),
            f.downstream.events,
        )
    }
}
