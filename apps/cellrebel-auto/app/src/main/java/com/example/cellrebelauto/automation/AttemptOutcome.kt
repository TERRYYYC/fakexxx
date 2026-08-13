package com.example.cellrebelauto.automation

/**
 * Typed outcome of one handler operation (AC-B1, replaces nullable TestScores + Unit).
 * # 处理器操作的类型化结果：成功/失败都有明确结构，失败必须带原因
 */

// # 类型化失败原因（终态 schema）
enum class FailureReason {
    FAKE_GPS_NOT_ACTIVE, FOREGROUND_SWITCH_FAILED, NO_RUNNING_EVIDENCE,
    CELLREBEL_TIMEOUT, SCORE_PARSE_FAILED, CANCELLED, INTERRUPTED,
    // # F1：Start 交互前屏幕上已存在 RUNNING —— 属于上一次运行，拒绝归属
    PRE_EXISTING_RUN,
    // # A+（§8.1 UNVERIFIED_RECORDED）：完成证据未过 §6.4 信任判定，写独立未验证记录，绝不计可信配额
    UNTRUSTED
}

/**
 * Result of a CellRebel test attempt.
 * # 一次 CellRebel 测试尝试的结果
 */
sealed interface AttemptOutcome {
    val startedAt: Long
    val endedAt: Long

    data class Success(
        val webScore: Double,
        val videoScore: Double,
        // # 观察到 RUNNING 的时间戳（AC-B2 审计）
        val runningObservedAt: Long,
        override val startedAt: Long,
        override val endedAt: Long
    ) : AttemptOutcome

    data class Failure(
        val reason: FailureReason,
        val detail: String?,
        override val startedAt: Long,
        override val endedAt: Long
    ) : AttemptOutcome
}
