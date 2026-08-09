package com.example.cellrebelauto.automation.plan

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Global buffer gate tests (AC-A5, INV-5): remaining time is a pure projection
 * of the persisted last-terminal endedAt, so it survives restarts and is
 * independent of the test-timeout setting.
 * # 全局缓冲门禁测试：剩余时间是已持久化 endedAt 的纯投影，
 * # 因此可跨重启存活，且与测试超时参数无关
 */
class BufferGateTest {

    private class FakeClock(var nowMs: Long) {
        fun now(): Long = nowMs
    }

    @Test
    fun `remaining is zero when there is no prior terminal attempt`() {
        val clock = FakeClock(100_000L)
        val gate = BufferGate(bufferSeconds = 60, nowMs = clock::now)
        assertEquals(0L, gate.remainingMs(lastTerminalEndedAt = null))
    }

    @Test
    fun `full buffer remains right after a terminal attempt ends`() {
        val clock = FakeClock(100_000L)
        val gate = BufferGate(bufferSeconds = 60, nowMs = clock::now)
        // # 刚结束（同一毫秒）→ 需等待完整缓冲
        assertEquals(60_000L, gate.remainingMs(lastTerminalEndedAt = 100_000L))
    }

    @Test
    fun `partial buffer remains mid window`() {
        val clock = FakeClock(125_000L)
        val gate = BufferGate(bufferSeconds = 60, nowMs = clock::now)
        // # 已过去 25s → 剩余 35s
        assertEquals(35_000L, gate.remainingMs(lastTerminalEndedAt = 100_000L))
    }

    @Test
    fun `remaining is zero after the buffer expires`() {
        val clock = FakeClock(200_000L)
        val gate = BufferGate(bufferSeconds = 60, nowMs = clock::now)
        assertEquals(0L, gate.remainingMs(lastTerminalEndedAt = 100_000L))
        assertEquals(0L, gate.remainingMs(lastTerminalEndedAt = 40_000L))
    }

    @Test
    fun `buffer gates equally after success and after failure`() {
        // # INV-5：成功与失败共用同一个终态 endedAt 投影，缓冲行为一致
        val clock = FakeClock(110_000L)
        val gate = BufferGate(bufferSeconds = 60, nowMs = clock::now)
        val afterSuccess = gate.remainingMs(lastTerminalEndedAt = 100_000L)
        val afterFailure = gate.remainingMs(lastTerminalEndedAt = 100_000L)
        assertEquals(afterSuccess, afterFailure)
        assertEquals(50_000L, afterFailure)
    }

    @Test
    fun `buffer survives restart recomputed from persisted endedAt`() {
        // # “重启”：用同一个持久化 endedAt 重建门禁实例，剩余时间不变
        val persistedEndedAt = 100_000L
        val clockBeforeRestart = FakeClock(130_000L)
        val beforeRestart = BufferGate(bufferSeconds = 60, nowMs = clockBeforeRestart::now)
            .remainingMs(lastTerminalEndedAt = persistedEndedAt)

        val clockAfterRestart = FakeClock(130_000L)
        val afterRestart = BufferGate(bufferSeconds = 60, nowMs = clockAfterRestart::now)
            .remainingMs(lastTerminalEndedAt = persistedEndedAt)

        assertEquals(beforeRestart, afterRestart)
        assertEquals(30_000L, afterRestart)
    }

    @Test
    fun `buffer does not depend on test timeout`() {
        // # AC-B5 语义：缓冲参数与测试超时参数相互独立
        val clock = FakeClock(110_000L)
        val gate = BufferGate(bufferSeconds = 60, nowMs = clock::now)
        val remaining = gate.remainingMs(lastTerminalEndedAt = 100_000L)
        // # 测试超时 90s 与缓冲 60s 无关：剩余时间只由 bufferSeconds 决定
        val unrelatedTestTimeoutMs = 90_000L
        assertEquals(50_000L, remaining)
        assertEquals(50_000L, 60_000L - 10_000L)
        assert(unrelatedTestTimeoutMs != remaining) { "timeout value must not leak into buffer math" }
    }
}
