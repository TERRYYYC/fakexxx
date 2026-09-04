package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.CellRebelScreenState
import com.example.cellrebelauto.automation.cellrebel.CellRebelStateDetector
import com.example.cellrebelauto.automation.cellrebel.ScreenNode
import com.example.cellrebelauto.automation.cellrebel.flatten

/**
 * Testable seam between the attempt lifecycle and the real device.
 * The Android implementation lives in CellRebelHandler; tests use fakes.
 * # 尝试生命周期与真实设备之间的可测试接缝：
 * # Android 实现在 CellRebelHandler 中，测试用假驱动
 */
interface CellRebelDriver {
    /** # 当前屏幕树根节点（无法获取时为 null） */
    suspend fun snapshot(): ScreenNode?

    /** # 对 Start 按钮执行 ACTION_CLICK */
    suspend fun clickStart(): Boolean

    /** # AC-B3 兜底：对 Start 按钮中心坐标点按 */
    suspend fun dispatchStartTap(): Boolean
}

/**
 * Engine-facing seam for running one verified CellRebel attempt.
 * Implemented by CellRebelHandler (device) and fakes (tests).
 * # 面向引擎的执行一次已验证 CellRebel 尝试的接缝：
 * # 设备上由 CellRebelHandler 实现，测试用假实现
 */
interface CellRebelRunner {
    /**
     * @param onStartInteraction fired once, immediately after the first Start
     * interaction was successfully dispatched. The caller samples its
     * monotonic clock at this boundary for §6.4.2 execution bracketing.
     * # 首次 Start 交互成功派发后立即触发；调用方在此边界采样单调时钟
     *
     * @param onRunningObserved C2: fired the instant RUNNING evidence is
     * observed, so the caller can persist the starting -> running transition
     * immediately (spec O3 state table). Default: no-op.
     * # 观察到 RUNNING 的瞬间触发，调用方据此立即持久化 running 迁移
     */
    suspend fun runTest(
        startedAt: Long,
        testTimeoutMs: Long,
        onStartInteraction: suspend () -> Unit,
        onRunningObserved: suspend (runningAtMs: Long) -> Unit = {}
    ): AttemptOutcome
}

/**
 * Verified CellRebel attempt lifecycle (AC-B2/B3/B5, F1 freshness):
 *
 *   0. pre-click baseline: READY/COMPLETED must be observed first; a RUNNING
 *      seen before this attempt's Start interaction belongs to a previous run
 *      and is rejected as PRE_EXISTING_RUN (freshness attribution, F1)
 *   1. click Start (ACTION_CLICK); false result (button not found) → immediate
 *      coordinate-tap fallback
 *   2. wait for RUNNING evidence; if none within ~3s of the click, ONE
 *      coordinate-tap fallback; still none within a bounded share of the
 *      timeout → Failure(NO_RUNNING_EVIDENCE)
 *   3. wait for stable completion: classify == COMPLETED with IDENTICAL scores
 *      across 2 consecutive polls (~1.5s apart); markers gone + Start enabled
 *      are part of COMPLETED by definition
 *   4. timeout → Failure(CELLREBEL_TIMEOUT); markers gone but scores
 *      unparseable (persisting) → Failure(SCORE_PARSE_FAILED)
 *
 * Identical before/after score values are VALID (INV-7) — scores are never
 * compared against previous attempts, only between consecutive polls of the
 * SAME attempt.
 *
 * Clock and delay are injected so tests run with virtual time.
 *
 * # 已验证的 CellRebel 尝试生命周期：
 * # 先观察到 RUNNING，再要求完成态分数连续两次轮询完全一致才采纳；
 * # 分数绝不与上一次尝试比较（INV-7）；时钟与延迟可注入，测试用虚拟时间
 */
class CellRebelAttemptFlow(
    private val detector: CellRebelStateDetector = CellRebelStateDetector(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) {
    companion object {
        // # 轮询间隔（完成稳定性要求的两轮间隔 ~1.5s）
        private const val POLL_INTERVAL_MS = 1500L
        // # AC-B3：首次 ACTION_CLICK 后观察不到 running 证据的兜底窗口
        private const val START_FALLBACK_WINDOW_MS = 3000L
        // # 等待 RUNNING 证据的预算占总超时的份额（有界）
        private const val RUNNING_EVIDENCE_TIMEOUT_SHARE = 3
        // # 连续多少次"标记消失但分数不可解析"才判 SCORE_PARSE_FAILED（防瞬态误判）
        private const val PARSE_FAILURE_CONFIRM_POLLS = 2
    }

    /**
     * Runs the lifecycle from the moment the test screen is shown.
     * The timeout budget anchors HERE (lifecycle entry) — [startedAt] is the
     * engine-side audit timestamp only and never shrinks the budget (F2).
     * # 从测试页已展示开始执行生命周期（启动/导航由调用方负责）。
     * # 超时预算锚在进入本生命周期的此刻；startedAt 仅作审计时间戳（F2）
     */
    suspend fun run(
        driver: CellRebelDriver,
        startedAt: Long,
        testTimeoutMs: Long,
        onStartInteraction: suspend () -> Unit = {},
        onRunningObserved: suspend (runningAtMs: Long) -> Unit = {}
    ): AttemptOutcome {
        // # 预算锚点：进入验证生命周期的时刻（审计 startedAt 不动）
        val anchoredAt = nowMs()
        val deadline = anchoredAt + testTimeoutMs
        val runningEvidenceDeadline = anchoredAt + testTimeoutMs / RUNNING_EVIDENCE_TIMEOUT_SHARE

        // # 第 0 步：pre-click 基线（F1，新鲜 READY/COMPLETED → RUNNING → COMPLETED）
        // # 一次观察只能归属本次调用执行的动作：Start 交互前已存在的 RUNNING
        // # 属于上一次运行（如恢复残留），绝不算本次 attempt 的证据
        var baselineSeen = false
        while (nowMs() < minOf(deadline, runningEvidenceDeadline)) {
            val nodes = driver.snapshot()?.flatten()
            if (nodes != null) {
                when (detector.classify(nodes)) {
                    CellRebelScreenState.RUNNING -> return AttemptOutcome.Failure(
                        reason = FailureReason.PRE_EXISTING_RUN,
                        detail = "RUNNING observed before this attempt's Start interaction (stale run)",
                        startedAt = startedAt,
                        endedAt = nowMs()
                    )
                    CellRebelScreenState.READY, CellRebelScreenState.COMPLETED -> {
                        baselineSeen = true
                        break
                    }
                    CellRebelScreenState.UNKNOWN -> {} // # 屏幕仍在加载，继续等基线
                }
            }
            if (baselineSeen) break
            delayMs(POLL_INTERVAL_MS)
        }
        if (!baselineSeen) {
            return AttemptOutcome.Failure(
                reason = FailureReason.NO_RUNNING_EVIDENCE,
                detail = "Test screen never reached a startable state (no READY/COMPLETED baseline)",
                startedAt = startedAt,
                endedAt = nowMs()
            )
        }

        // # 第 1 步：ACTION_CLICK 启动测试；返回 false（未找到按钮）→ 立即坐标兜底（AC-B3）
        var startInteractionReported = false
        suspend fun reportStartInteraction() {
            if (!startInteractionReported) {
                startInteractionReported = true
                onStartInteraction()
            }
        }

        var fallbackTapDone = false
        if (driver.clickStart()) {
            reportStartInteraction()
        } else {
            fallbackTapDone = true
            if (driver.dispatchStartTap()) reportStartInteraction()
        }
        val clickedAt = nowMs()

        // # 第 2 步：等待 RUNNING 证据（必要时坐标点按兜底）
        var runningObservedAt: Long? = null
        while (nowMs() < minOf(deadline, runningEvidenceDeadline)) {
            // # 兜底窗口已过仍无 running 证据 → 坐标点按一次（AC-B3）
            if (!fallbackTapDone && nowMs() - clickedAt >= START_FALLBACK_WINDOW_MS) {
                fallbackTapDone = true
                if (driver.dispatchStartTap()) reportStartInteraction()
            }
            val nodes = driver.snapshot()?.flatten()
            if (nodes != null && detector.classify(nodes) == CellRebelScreenState.RUNNING) {
                runningObservedAt = nowMs()
                // # C2：观察到即上报，调用方立即持久化 starting -> running 迁移
                onRunningObserved(runningObservedAt)
                break
            }
            delayMs(POLL_INTERVAL_MS)
        }
        if (runningObservedAt == null) {
            return AttemptOutcome.Failure(
                reason = FailureReason.NO_RUNNING_EVIDENCE,
                detail = "No RUNNING evidence within ${testTimeoutMs / RUNNING_EVIDENCE_TIMEOUT_SHARE}ms of Start",
                startedAt = startedAt,
                endedAt = nowMs()
            )
        }

        // # 第 3 步：等待稳定完成（连续两次轮询分数完全一致）
        var lastScores: Pair<Double, Double>? = null
        var unparseablePolls = 0
        while (nowMs() < deadline) {
            val nodes = driver.snapshot()?.flatten()
            if (nodes != null) {
                when (detector.classify(nodes)) {
                    CellRebelScreenState.COMPLETED -> {
                        val scores = detector.extractScores(nodes)!!
                        val current = scores.webBrowsingScore to scores.videoStreamingScore
                        if (current == lastScores) {
                            return AttemptOutcome.Success(
                                webScore = scores.webBrowsingScore,
                                videoScore = scores.videoStreamingScore,
                                runningObservedAt = runningObservedAt,
                                startedAt = startedAt,
                                endedAt = nowMs()
                            )
                        }
                        lastScores = current
                        unparseablePolls = 0
                    }
                    CellRebelScreenState.RUNNING -> {
                        // # 仍在处理：重置稳定性与解析失败计数
                        lastScores = null
                        unparseablePolls = 0
                    }
                    else -> {
                        // # 标记消失但未进入完成态；分数区在却持续不可解析 → SCORE_PARSE_FAILED
                        if (detector.hasScoreLabels(nodes) && detector.extractScores(nodes) == null) {
                            unparseablePolls++
                            if (unparseablePolls >= PARSE_FAILURE_CONFIRM_POLLS) {
                                return AttemptOutcome.Failure(
                                    reason = FailureReason.SCORE_PARSE_FAILED,
                                    detail = "Score labels present but values unparseable after markers cleared",
                                    startedAt = startedAt,
                                    endedAt = nowMs()
                                )
                            }
                        }
                    }
                }
            }
            delayMs(POLL_INTERVAL_MS)
        }

        return AttemptOutcome.Failure(
            reason = FailureReason.CELLREBEL_TIMEOUT,
            detail = "No stable completion within ${testTimeoutMs}ms",
            startedAt = startedAt,
            endedAt = nowMs()
        )
    }
}
