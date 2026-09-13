package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首瓦片结果闸（moto g54 冷启动修复，2026-09-13）的语义钉死。
 *
 * 背景：g54 上首瓦片在网络栈就绪前 ~67ms 即刻失败（osmdroid 默认判网在
 * VALIDATING 期返回 false，downloader 静默拒绝，HTTP 根本没发出），旧逻辑
 * 把"第一次失败"粘死成永久 CANVAS 降级。修复 = 启动宽限窗内的失败不上报，
 * 由重绘重试泵自愈；宽限窗外的失败仍是真证据，照旧粘住。
 */
class FirstOutcomeGateTest {

    private class FakeClock {
        var now = 0L
        fun read(): Long = now
    }

    private fun gateWith(clock: FakeClock, graceMs: Long = 10_000L) =
        FirstOutcomeGate(graceMs) { clock.read() }

    @Test
    fun `success inside the grace window reports immediately`() {
        val clock = FakeClock().apply { now = 100L }
        val gate = gateWith(clock)
        clock.now = 300L

        assertTrue("first success must surface right away", gate.shouldReport(success = true))
        assertFalse("subsequent outcomes are deduped", gate.shouldReport(success = true))
        assertFalse(gate.shouldReport(success = false))
    }

    @Test
    fun `failure inside the grace window is swallowed and the gate stays open`() {
        val clock = FakeClock().apply { now = 0L }
        val gate = gateWith(clock)

        clock.now = 67L // g54 实测：MapView 布局后 67ms 即刻失败
        assertFalse(
            "a sub-second failure cannot be a real source problem and must not stick",
            gate.shouldReport(success = false),
        )
        clock.now = 5_000L
        assertFalse("still inside grace: another failure is swallowed too", gate.shouldReport(success = false))

        // 宽限窗内被吞失败后，自愈成功必须能上报（卡片保留、降级永不发生）
        clock.now = 6_000L
        assertTrue(
            "recovery success after a swallowed transient failure must surface",
            gate.shouldReport(success = true),
        )
        assertFalse(gate.shouldReport(success = false))
    }

    @Test
    fun `failure after the grace window reports sticky like before`() {
        val clock = FakeClock().apply { now = 0L }
        val gate = gateWith(clock)

        clock.now = 10_001L // > 10s 宽限窗
        assertTrue(
            "a failure past the grace window is real evidence and must stick the fallback",
            gate.shouldReport(success = false),
        )
        assertFalse("first outcome already reported", gate.shouldReport(success = true))
    }

    @Test
    fun `zero grace restores the old first-failure-sticks semantics`() {
        val clock = FakeClock().apply { now = 0L }
        val gate = gateWith(clock, graceMs = 0L)

        assertTrue(gate.shouldReport(success = false))
        assertFalse(gate.shouldReport(success = true))
    }
}
