package com.example.cellrebelauto.ui.dashboard

import com.example.cellrebelauto.model.AutomationState

/**
 * The four EXISTING entries the dashboard's action buttons may invoke. There
 * is deliberately no sixth path: RESUME re-enters startOrResumePlan (INV-9,
 * the engine's recovery sweep makes it idempotent), the OPEN_* actions are the
 * same navigation the footer buttons use, RESET_PLAN is the #12 entry with its
 * in-transaction guard. No bypass state machine is created here.
 *
 * # 建议动作只允许既有入口：Resume / Provider 页 / Plan 页 / #12 重置；禁止旁路
 */
enum class DashboardAction {
    NONE,
    RESUME,
    OPEN_PROVIDERS,
    OPEN_PLAN,
    RESET_PLAN,
}

/**
 * One human-readable line for a held terminal: WHAT happened + WHAT to do.
 * # 暂停/终态的人话解释：发生了什么 + 建议动作
 */
data class PauseExplanation(
    val headline: String,
    val detail: String,
    val action: DashboardAction,
    val actionLabel: String? = null,
) {
    companion object {
        fun idle(headline: String, detail: String) =
            PauseExplanation(headline, detail, DashboardAction.NONE)
    }
}

/**
 * T7 P1.1 — the pause-reason → human-language mapping (pure, exhaustive).
 *
 * The engine already classifies everything: typed terminal states
 * ([AutomationState.SERVICE_RECYCLED] from the #15 lifecycle publisher,
 * [AutomationState.PAUSED] from the §8.2 fail-closed path), typed failure
 * reasons on the last-failure line, and ERROR log lines that carry the
 * EXHAUSTED / trust-gate / ANCHOR_MISMATCH (P1.3 CoordinateGuard) causes. This
 * mapping folds those three inputs into ONE operator-readable explanation.
 * Every AutomationState value and every FailureReason value must map — the
 * oracle iterates both enums, so a new value without a translation goes red.
 *
 * # 人话原因映射（纯函数、穷尽既有枚举）：状态+最近失败+最近 ERROR 日志 → 文案+建议动作
 */
object PauseReasonExplainer {

    /** Log fragments the engine's pause paths actually emit (applusPause / #15 / plan not found). */
    private const val FRAGMENT_EXHAUSTED = "EXHAUSTED"
    private const val FRAGMENT_TRUST_REJECTED = "trust gate rejected"
    private const val FRAGMENT_ANCHOR_MISMATCH = "ANCHOR_MISMATCH"
    private const val FRAGMENT_DISCOVER_FAILED = "provider discover failed or protocol incompatible"
    private const val FRAGMENT_OWNERSHIP = "ownership is inactive or ambiguous"
    private const val FRAGMENT_RECONCILE = "reconcile of attempt"

    /**
     * Explains the engine state. `lastFailureReason` is the raw FailureReason
     * name (or a typed prefix like ANCHOR_MISMATCH) from the last-failure
     * projection; `latestErrorLog` is the newest ERROR-prefixed line of the
     * rolling log. Never throws, never returns a blank line.
     */
    fun explain(
        state: AutomationState,
        lastFailureReason: String? = null,
        latestErrorLog: String? = null,
    ): PauseExplanation {
        // The #15 lifecycle terminal outranks everything: the engine host is
        // gone, so any "still running" reading is false and Resume is THE action.
        if (state == AutomationState.SERVICE_RECYCLED) {
            return PauseExplanation(
                headline = "无障碍服务被系统回收，引擎已停止",
                detail = "系统/OEM 回收了无障碍服务（force-stop、uiautomator dump 等都会触发）。" +
                    "点 Resume 重开计划，进度会从可信账本继续。",
                action = DashboardAction.RESUME,
                actionLabel = "Resume 重开",
            )
        }
        if (state == AutomationState.PAUSED || state == AutomationState.ERROR) {
            return explainHeld(state, lastFailureReason, latestErrorLog)
        }
        if (state == AutomationState.COOLDOWN) {
            return PauseExplanation.idle(
                headline = "缓冲等待中",
                detail = "两次尝试之间的调度缓冲倒计时（成功与失败后都会等待），结束后自动继续。"
            )
        }
        if (state == AutomationState.DONE) {
            return PauseExplanation.idle(
                headline = "计划已完成",
                detail = "所有位置的配额都已可信达成。重跑请用 Plan 页的重置入口。"
            )
        }
        if (state == AutomationState.FAILED || state == AutomationState.SUCCEEDED) {
            return PauseExplanation.idle(
                headline = if (state == AutomationState.SUCCEEDED) "本次尝试成功" else "本次尝试失败",
                detail = "尝试已收尾入账" +
                    (lastFailureReason?.let { "（最近失败：" + failureReasonText(it) + "）" } ?: "") +
                    "，调度器将在缓冲后继续。"
            )
        }
        // IDLE + every RUNNING-phase state: visible, no action offered.
        return PauseExplanation.idle(
            headline = state.displayName,
            detail = if (state == AutomationState.IDLE) "引擎空闲——从 Plan 页或下方按钮启动计划。"
            else "正在执行：${state.displayName}"
        )
    }

    /** PAUSED / ERROR: the log fragment decides the copy; order is most-specific first. */
    private fun explainHeld(
        state: AutomationState,
        lastFailureReason: String?,
        latestErrorLog: String?,
    ): PauseExplanation {
        val log = latestErrorLog ?: ""
        return when {
            log.contains(FRAGMENT_ANCHOR_MISMATCH) || lastFailureReason?.startsWith("ANCHOR_MISMATCH") == true ->
                PauseExplanation(
                    headline = "档案/计划错位，已暂停",
                    detail = "配额入账前的坐标校验发现：provider 实际生效坐标与本行计划坐标不符" +
                        "（典型原因：计划行数与档案数不一致，整体错位一行）。请到 Plan 页核对计划行数与档案坐标后重置计划。",
                    action = DashboardAction.OPEN_PLAN,
                    actionLabel = "去核对计划",
                )
            log.contains(FRAGMENT_TRUST_REJECTED) ->
                PauseExplanation(
                    headline = "Provider 信任被拒，已暂停",
                    detail = "引擎的信任门拒绝了 provider 的签名（被撤销或签名轮转）。" +
                        "到 Provider 管理页重新批准当前签名后 Resume。",
                    action = DashboardAction.OPEN_PROVIDERS,
                    actionLabel = "去 Provider 管理重批",
                )
            log.contains(FRAGMENT_EXHAUSTED) ->
                PauseExplanation(
                    headline = "日程耗尽，已暂停",
                    detail = "provider 的日程已全部完成（EXHAUSTED），本地剩余任务保持 pending。" +
                        "去千网游重开日程，或用 Plan 页的重置入口重建计划后重新开始。",
                    action = DashboardAction.RESET_PLAN,
                    actionLabel = "重置计划",
                )
            log.contains(FRAGMENT_OWNERSHIP) ->
                PauseExplanation(
                    headline = "恢复归属不明，已暂停",
                    detail = "检测到归属不清的 A+ 恢复现场（多处 owner 或会话冲突），已安全停止。" +
                        "确认无重复启动后 Resume；必要时用 Plan 页重置。",
                    action = DashboardAction.RESUME,
                    actionLabel = "Resume 重开",
                )
            log.contains(FRAGMENT_RECONCILE) ->
                PauseExplanation(
                    headline = "恢复证据不足，已暂停",
                    detail = "被中断尝试的完成证据未达 §8.2 判定标准，现场已保留、未计入配额。" +
                        "可在确认千网游侧状态后 Resume 重新收敛。",
                    action = DashboardAction.RESUME,
                    actionLabel = "Resume 重开",
                )
            log.contains(FRAGMENT_DISCOVER_FAILED) ->
                PauseExplanation(
                    headline = "链路传输失败，已暂停",
                    detail = "与 provider 的契约通道握手失败或协议不兼容。检查千网游(QWY)是否存活、" +
                        "Vector/LSPosed 模块是否启用，恢复通道后 Resume。",
                    action = DashboardAction.NONE,
                )
            lastFailureReason != null ->
                PauseExplanation(
                    headline = if (state == AutomationState.PAUSED) "引擎已暂停" else "引擎报错停止",
                    detail = "最近失败：" + failureReasonText(lastFailureReason) +
                        "。处理后点 Resume，进度从可信账本继续。",
                    action = DashboardAction.RESUME,
                    actionLabel = "Resume 重开",
                )
            else ->
                PauseExplanation(
                    headline = if (state == AutomationState.PAUSED) "引擎已暂停" else "引擎报错停止",
                    detail = "具体原因见下方日志。处理后点 Resume 继续；进度从可信账本继续。",
                    action = DashboardAction.RESUME,
                    actionLabel = "Resume 重开",
                )
        }
    }

    /**
     * Human text for a typed failure reason. Exhaustive over FailureReason by
     * oracle; unknown strings fall through with the raw code preserved so a
     * future reason is still diagnosable.
     */
    fun failureReasonText(reason: String?): String = when {
        reason == null -> "无失败记录"
        reason.startsWith("ANCHOR_MISMATCH") -> "档案/计划错位（坐标校验拦截，未计配额）"
        else -> when (reason) {
            "FAKE_GPS_NOT_ACTIVE" -> "Fake GPS 未激活（启动后未确认到 Stop 按钮）"
            "FOREGROUND_SWITCH_FAILED" -> "前台切换失败（目标应用没能带到前台）"
            "NO_RUNNING_EVIDENCE" -> "未观察到测试运行证据"
            "CELLREBEL_TIMEOUT" -> "CellRebel 测试超时"
            "SCORE_PARSE_FAILED" -> "分数解析失败"
            "CANCELLED" -> "被取消（停止/替换计划）"
            "INTERRUPTED" -> "被中断（进程/服务中断后恢复清扫）"
            "PRE_EXISTING_RUN" -> "启动前已有上次运行的 RUNNING 残留，拒绝归属"
            "UNTRUSTED" -> "完成证据未过信任判定（不计入配额）"
            "EXHAUSTED" -> "日程耗尽"
            else -> "未知原因（$reason）"
        }
    }
}
