package com.example.cellrebelauto.automation.plan

/**
 * Global inter-attempt buffer gate (AC-A5, INV-5). Pure projection from the
 * persisted last-terminal endedAt — no in-memory timer, so it survives
 * process restarts. Independent of the test-timeout setting (AC-B5).
 * # 全局尝试间缓冲门禁。已持久化 endedAt 的纯投影——无内存计时器，
 * # 可跨进程重启存活；与测试超时设置相互独立
 */
class BufferGate(
    val bufferSeconds: Int,
    val nowMs: () -> Long
) {
    /**
     * Remaining wait in milliseconds; 0 when no prior terminal attempt or the
     * buffer has already expired.
     * # 剩余等待毫秒数；无历史终态尝试或缓冲已过期时为 0
     */
    fun remainingMs(lastTerminalEndedAt: Long?): Long {
        if (lastTerminalEndedAt == null) return 0L
        val elapsed = nowMs() - lastTerminalEndedAt
        return maxOf(0L, bufferSeconds * 1000L - elapsed)
    }
}
