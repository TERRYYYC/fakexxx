package com.example.cellrebelauto.automation

import android.util.Log
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.automation.aplus.AttemptState
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.automation.plan.PlanScheduler
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ReconcileResult
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.ScheduleAdvanceState
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Snapshot of the task currently being attempted (Run page status card).
 * # 当前正在尝试的任务快照（Run 页状态卡）
 */
data class EngineTaskSnapshot(
    val csvRow: Int,
    val priority: Int,
    val latitude: Double,
    val longitude: Double,
    val completedSuccesses: Int,
    val requiredSuccesses: Int,
    val attemptOrdinal: Int
)

/**
 * Scheduler cooldown projection (Run page cooldown card). Emitted once when
 * the buffer wait starts; the UI counts down locally from startedAtMs.
 * # scheduler 缓冲倒计时投影：等待开始时发射一次，UI 基于 startedAtMs 本地倒数
 */
data class CooldownInfo(
    val startedAtMs: Long,
    val remainingMs: Long,
    val totalMs: Long,
    // # 倒计时结束后的去向：同点重试 / 前进下一点
    val nextAction: String
)

/**
 * Most recent failed attempt (Run page last-failure line, INV-10 visible).
 * # 最近一次失败尝试（Run 页 last failure 行）
 */
data class LastFailureInfo(
    val attemptOrdinal: Int,
    val reason: String
)

/**
 * Plan-driven automation orchestrator (F001). Executes the imported location
 * worklist in deterministic order, counting only verified CellRebel successes
 * toward per-location quotas.
 *
 * Run loop:
 *   1. Recovery sweep FIRST — leftover non-terminal attempts → interrupted,
 *      stale `running` sessions → interrupted (INV-9, O3/O4)
 *   2. selectNext (active-unfinished first, then priority ASC / csvRow ASC)
 *   3. BufferGate wait from persisted last-terminal endedAt (INV-5, after
 *      BOTH success and failure)
 *   4. Fake GPS setLocation (skippable, F003) — failure → typed failed attempt,
 *      NO quota consumed (INV-10); GPS settle after confirmed activation (F3);
 *      CellRebel stage OFF → ok_gps_only terminal counting quota (F003)
 *   5. runTest — Success finalized in ONE Room transaction (attempt row +
 *      guarded task increment, INV-3); Failure persisted with typed reason (INV-4)
 *   6. quota met → task completed; all complete → session completed
 *
 * # 计划驱动的自动化编排器（F001）：恢复清扫优先 → 确定性选任务 →
 * # 缓冲门禁 → GPS 稳定/落点（失败即停、不占配额）→ 已验证测试 →
 * # 单事务成功收尾 / 类型化失败记录 → 配额完成推进
 */
class AutomationEngine(
    private val planId: Long,
    private val planRepository: PlanRepository,
    private val cellRebelRunner: CellRebelRunner,
    private val gpsSetter: GpsLocationSetter,
    private val bufferGate: BufferGate,
    private val testTimeoutMs: Long,
    private val gpsSettleMs: Long,
    // # F003：阶段开关快照提供者，每次 attempt 重新读取（AC-F3-5）
    private val stageToggles: suspend () -> StageToggles = { StageToggles() },
    // # 仅用于 returnToSelf（MIUI 中转）；测试不传
    private val bridge: AccessibilityBridge? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val delayMs: suspend (Long) -> Unit = { delay(it) },
    // # R6-F4（§11.7）：§8.1 状态机的生产驱动入口。GREEN 在 attempt 生命周期各 §8.1 步驱动它，
    // # 使状态机迁移都落到持久审计流。RED seam：默认 null（既有行为不变）；测试传真实 driver。
    // # 不可为 val 默认非空——engine 不持有 db，driver 由构造方（APlusComposition / 测试）注入。
    private val attemptDriver: APlusAttemptDriver? = null,
    // # R8-F2（Sol round-7）：A+ 崩溃恢复协调器。生产经组合根 APlusComposition 由 backend 构造注入；
    // # 默认 null = 纯 legacy（pre-freeze 生产现状）。非 null 且 completionEvidenceSource 非 null 时
    // # engine 进入 A+ 模式：恢复段同键 reconcile + release 收敛 + schedule 门；正路径走 §3.1 生命周期。
    private val recoveryCoordinator: RecoveryCoordinator? = null,
    // # R8-F1（Sol round-7 P1-2）：A+ 证据获取 seam（观察/分类/回执 artifact）。目标坐标与本地重算
    // # hash 不由它提供——ctx 由持久 attempt intent 组装（INV-23）。默认 null = legacy。
    private val completionEvidenceSource: APlusEvidenceSource? = null
) {
    companion object {
        private const val TAG = "AutoEngine"
        // # 单步操作失败后的最大重试次数
        private const val MAX_STEP_RETRIES = 3
    }

    // # 当前状态
    private val _state = MutableStateFlow(AutomationState.IDLE)
    val state: StateFlow<AutomationState> = _state

    // # 已执行的尝试数
    private val _cycleCount = MutableStateFlow(0)
    val cycleCount: StateFlow<Int> = _cycleCount

    // # 日志列表（保留最近 200 条）
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs

    // # 当前任务快照（Run 页状态卡）
    private val _currentTask = MutableStateFlow<EngineTaskSnapshot?>(null)
    val currentTask: StateFlow<EngineTaskSnapshot?> = _currentTask

    // # scheduler 缓冲倒计时投影（Run 页 cooldown 卡）
    private val _cooldown = MutableStateFlow<CooldownInfo?>(null)
    val cooldown: StateFlow<CooldownInfo?> = _cooldown

    // # 最近一次失败（Run 页 last failure 行）
    private val _lastFailure = MutableStateFlow<LastFailureInfo?>(null)
    val lastFailure: StateFlow<LastFailureInfo?> = _lastFailure

    private var runSessionId: Long = 0
    // # 在途尝试（停止/取消时标记 interrupted）
    private var currentAttemptId: Long? = null

    /**
     * Runs the full plan loop. Call from a coroutine scope; cancelling the
     * coroutine cleanly stops the automation (in-flight attempt → interrupted).
     *
     * # 运行完整的计划循环。取消协程即优雅停止（在途尝试标记 interrupted）
     */
    suspend fun run() = coroutineScope {
        try {
            // ==================== Step 0a: A+ reconcile BEFORE the blind sweep (§8.2 RECOVERING) ====
            // # R9（Sol round-8 P1-3/P1-4/P1-6）：恢复分支于持久的"当前操作"（aplusState），身份从 owner
            // # 态重算（APlusOperationIdentity），release 是 lease-bound + durable（typed receipt）；崩溃
            // # owner session 被 REUSE（RECOVERING → RUNNING/PAUSED），绝不 mint 第二个 active run。
            val aplusCoordinator = recoveryCoordinator
            val aplusEvidence = completionEvidenceSource
            if (aplusCoordinator != null && aplusEvidence != null) {
                // # P1-6：复用现有 running session（supersede），不新建第二个 active run。
                val existingSession = planRepository.findActiveRunSession(planId)
                if (existingSession != null) {
                    runSessionId = existingSession.id
                    planRepository.markSessionStatus(runSessionId, "recovering")
                    updateState(AutomationState.RECOVERING)
                    for (crashed in planRepository.findAPlusRecoverableAttempts(planId)) {
                        if (!recoverCrashedAttempt(crashed, aplusCoordinator)) {
                            return@coroutineScope
                        }
                    }
                    planRepository.markSessionStatus(runSessionId, "running")
                }
            }

            // ==================== Step 0: recovery sweep FIRST (INV-9, F3R1-3) ====================
            // # 清扫永远最先跑：即使随后 both-OFF guard 拒绝启动，
            // # 崩溃残留也必须被终态化
            val sweptAttempts = planRepository.markNonTerminalInterrupted(nowMs())
            // # P1-4（Sol round-9）：全局 sweep 排除当前 owner session（A+ 恢复刚把它标回 running），
            // # 不得把刚恢复的 owner 也打断。
            val sweptSessions = if (runSessionId != 0L) {
                planRepository.markStaleSessionsInterruptedExcept(nowMs(), runSessionId)
            } else {
                planRepository.markStaleSessionsInterrupted(nowMs())
            }
            if (sweptAttempts > 0 || sweptSessions > 0) {
                log("Recovery sweep: $sweptAttempts attempt(s) + $sweptSessions session(s) marked interrupted")
            }
            // # F5 兜底：满配额但状态未翻转的历史崩溃窗口任务 → completed
            val normalized = planRepository.normalizeQuotaCompletedTasks()
            if (normalized > 0) {
                log("Recovery sweep: $normalized quota-full task(s) normalized to completed")
            }

            // ==================== Step 1: both-stages-off guard (AC-F3-4) ====================
            // # 双关 = 配置错误（KD-F3-3）：明确拒绝，不创建会话
            val initialToggles = stageToggles()
            if (!initialToggles.locationStageEnabled && !initialToggles.testStageEnabled) {
                log("ERROR: both stages are OFF — nothing would be executed. Enable at least one stage.")
                updateState(AutomationState.ERROR)
                return@coroutineScope
            }

            val plan = planRepository.getPlan(planId)
            if (plan == null) {
                log("ERROR: plan #$planId not found")
                updateState(AutomationState.ERROR)
                return@coroutineScope
            }

            // # A+ 模式下 session 已在恢复段创建（recovering→running）；legacy 在此创建
            if (runSessionId == 0L) {
                runSessionId = planRepository.createSession(planId, nowMs())
            }
            _cycleCount.value = 0
            log("=== Plan run started (plan #$planId, session #$runSessionId) ===")

            // ==================== Step 2+: plan loop ====================
            var tasks = planRepository.getTasks(planId)
            while (isActive && !PlanScheduler.isPlanComplete(tasks)) {
                val task = PlanScheduler.selectNext(tasks) ?: break
                ensureActive()

                // # 选中时是否为 pending（cooldown 卡的下一步去向据此投影）
                val advancingToNewTask = task.status == "pending"
                // # 新选中的 pending 任务 → active
                if (advancingToNewTask) {
                    planRepository.markTaskActive(task.id)
                }
                log("--- Location csvRow=${task.csvRow} (${task.latitude},${task.longitude}) " +
                    "success ${task.completedSuccesses}/${task.requiredSuccesses} ---")

                // # 缓冲门禁（INV-5）：成功和失败后都要等（从持久化 endedAt 投影）
                val lastEndedAt = planRepository.latestTerminalAttemptEndedAt(planId)
                val remainingMs = bufferGate.remainingMs(lastEndedAt)
                if (remainingMs > 0) {
                    updateState(AutomationState.COOLDOWN)
                    _cooldown.value = CooldownInfo(
                        startedAtMs = nowMs(),
                        remainingMs = remainingMs,
                        totalMs = bufferGate.bufferSeconds * 1000L,
                        nextAction = if (advancingToNewTask)
                            "advance to next location"
                        else
                            "retry same location"
                    )
                    log("Buffer gate: waiting ${remainingMs / 1000}s before next attempt")
                    delayMs(remainingMs)
                    _cooldown.value = null
                    ensureActive()
                }

                // # F003：每次 attempt 重新读取开关快照（AC-F3-5 中途切换下个 attempt 生效）
                val toggles = stageToggles()
                // # F3R1-1：运行中双关 = 配置错误，fail-closed——不创建 attempt、
                // # 不涨配额，session 以 error 终态收尾（AC-F3-4/KD-F3-3）
                if (!toggles.locationStageEnabled && !toggles.testStageEnabled) {
                    log("ERROR: both stages turned OFF mid-plan — failing closed, no new attempt")
                    updateState(AutomationState.ERROR)
                    planRepository.finishSession(runSessionId, "error", nowMs(), _cycleCount.value)
                    return@coroutineScope
                }
                // # INV-F3-1：跳过必记录（双关已在启动时拒绝，至多一个标记）
                val stageNotes = when {
                    !toggles.locationStageEnabled -> "gps_skipped"
                    !toggles.testStageEnabled -> "test_skipped"
                    else -> null
                }

                // # 创建尝试行（starting），ordinal = 任务内计数 + 1
                val startedAt = nowMs()
                val attemptOrdinal = planRepository.countAttemptsForTask(task.id) + 1
                val attemptId = planRepository.insertAttempt(
                    TestAttempt(
                        taskId = task.id,
                        runSessionId = runSessionId,
                        attemptOrdinal = attemptOrdinal,
                        successOrdinal = null,
                        startedAt = startedAt,
                        runningObservedAt = null,
                        endedAt = null,
                        status = "starting",
                        failureReason = null,
                        webBrowsingScore = null,
                        videoStreamingScore = null,
                        latitude = task.latitude,
                        longitude = task.longitude,
                        stageNotes = stageNotes
                    )
                )
                currentAttemptId = attemptId
                _cycleCount.value = _cycleCount.value + 1
                _currentTask.value = EngineTaskSnapshot(
                    csvRow = task.csvRow,
                    priority = task.priority,
                    latitude = task.latitude,
                    longitude = task.longitude,
                    completedSuccesses = task.completedSuccesses,
                    requiredSuccesses = task.requiredSuccesses,
                    attemptOrdinal = attemptOrdinal
                )

                // ==================== A+ lifecycle（R9；§3.1 typed steps / §8.1 表） ====================
                // # R9（Sol round-8 P1-3/P1-4/P1-5）：A+ 模式持久化当前操作（aplusState + aplusLeaseId），
                // # release 在 terminalize 之前、lease-bound + durable；trust-fail → unverified + legacy-zero；
                // # PASS → 终态化 attempt（绝不动 legacy 计数）。apply/release 外部调用是 GREEN，pre-freeze
                // # 只驱动 §8.1 迁移 + 持久化 owner 态，判定路径（recordTrustedCompletion）保持可达。
                val aplusCoord = recoveryCoordinator
                val aplusEvidenceSrc = completionEvidenceSource
                if (aplusCoord != null && aplusEvidenceSrc != null) {
                    var aplusState = AttemptState.CREATED
                    // # intent 身份由持久 attempt intent 重算（目标坐标 + attempt id，INV-23，Sol round-8 P1-2）。
                    val intentDigest = APlusOperationIdentity.requestDigest(task.latitude, task.longitude, attemptId, runSessionId)
                    // # P1-3：持久化当前操作（§7.1 Attempt 拥有当前 operation）。
                    planRepository.markAplusState(attemptId, "APPLY_PENDING")
                    aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.BEGIN_APPLY) ?: aplusState
                    // # P1-2（Sol round-9）：正路径 apply 是 provider 驱动——dispatchApply → ApplyOutcome.leaseId
                    // # 持久化 lease（绝不凭空发明）；fail-closed executor 无 lease → PAUSED。
                    val applyOutcome = aplusCoord.dispatchApply(
                        attemptId, APlusOperationIdentity.applyIdempotencyKey(attemptId), intentDigest, nowMs()
                    )
                    val leaseId = applyOutcome.leaseId
                    if (leaseId == null) {
                        aplusPause("apply did not acquire a lease for attempt $attemptId")
                        return@coroutineScope
                    }
                    planRepository.markAplusLease(attemptId, leaseId)
                    aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.APPLY_RECEIPT) ?: aplusState
                    planRepository.markAplusState(attemptId, "ENV_APPLIED")
                    // # OBSERVE_PRE（§8.1 禁止：没 observe 就启动 CellRebel）
                    val preObservation = aplusEvidenceSrc.acquirePreObservation(attemptId)
                    if (preObservation == null) {
                        aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.OBSERVATION_UNTRUSTED) ?: aplusState
                        if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = FailureReason.UNTRUSTED.name, endedAt = nowMs(), webScore = null, videoScore = null)) {
                            return@coroutineScope
                        }
                        aplusPause("pre-observation unavailable for attempt $attemptId")
                        return@coroutineScope
                    }
                    aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.PRE_OBSERVATION_OK) ?: aplusState
                    planRepository.markAplusState(attemptId, "PRE_OBSERVED")
                    aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.START_CELLREBEL) ?: aplusState
                    planRepository.markAplusState(attemptId, "CELLREBEL_START_PENDING")
                    updateState(AutomationState.LAUNCHING_CELLREBEL)
                    val outcome = cellRebelRunner.runTest(startedAt, testTimeoutMs) { runningAt ->
                        planRepository.markAttemptRunning(attemptId, runningAt)
                        aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.NEW_RUN_OBSERVED) ?: aplusState
                        planRepository.markAplusState(attemptId, "CELLREBEL_RUNNING")
                    }
                    ensureActive()
                    when (outcome) {
                        is AttemptOutcome.Failure -> {
                            aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.TIMEOUT_INTERRUPTED) ?: aplusState
                            aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.RECONCILE) ?: aplusState
                            if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = outcome.reason.name, endedAt = outcome.endedAt, webScore = null, videoScore = null)) {
                                return@coroutineScope
                            }
                            updateState(AutomationState.FAILED)
                            _lastFailure.value = LastFailureInfo(attemptOrdinal, outcome.reason.name)
                            log("A+ attempt $attemptOrdinal failed: ${outcome.reason} — typed failure, no quota")
                            // # fail-closed：非 PASS 一律停跑（pre-freeze 骨架恒 FAIL，避免无限重试）
                            aplusPause("typed failure for attempt $attemptId — no quota, no legacy counter")
                            return@coroutineScope
                        }
                        is AttemptOutcome.Success -> {
                            aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.COMPLETION_OBSERVED) ?: aplusState
                            // §8.1: COMPLETION_OBSERVED → POST_OBSERVE_PENDING is persisted BEFORE the post-observe
                            // call (Sol round-23 P1-1): a crash in the post-observe call must recover as
                            // POST_OBSERVE_PENDING, not CELLREBEL_RUNNING.
                            planRepository.markAplusState(attemptId, "POST_OBSERVE_PENDING")
                            val postObservation = aplusEvidenceSrc.acquirePostObservation(attemptId)
                            if (postObservation == null) {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.OBSERVATION_UNTRUSTED) ?: aplusState
                                if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = FailureReason.UNTRUSTED.name, endedAt = outcome.endedAt, webScore = null, videoScore = null)) {
                                    return@coroutineScope
                                }
                                aplusPause("post-observation unavailable for attempt $attemptId")
                                return@coroutineScope
                            }
                            aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.POST_OBSERVATION_OK) ?: aplusState
                            // §8.1: POST_OBSERVATION_OK → DECIDING is persisted right after the observation succeeds
                            // (Sol round-23 P1-1).
                            planRepository.markAplusState(attemptId, "DECIDING")
                            // # DECIDE：ctx 由持久 intent（目标坐标、本地重算 hash）+ 后端 artifact 组装（INV-23）
                            val evidence = aplusEvidenceSrc.acquireCompletionEvidence(attemptId)
                            if (evidence == null) {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.OBSERVATION_UNTRUSTED) ?: aplusState
                                if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = FailureReason.UNTRUSTED.name, endedAt = outcome.endedAt, webScore = null, videoScore = null)) {
                                    return@coroutineScope
                                }
                                aplusPause("completion evidence unavailable for attempt $attemptId")
                                return@coroutineScope
                            }
                            val trustCtx = CompletionTrustContext(
                                execution = evidence.execution.copy(attemptId = attemptId),
                                completionEvidenceWire = evidence.completionEvidenceWire,
                                applyReceiptIntentHash = evidence.applyReceiptIntentHash,
                                locallyRecomputedIntentHash = intentDigest,
                                applyReceiptLease = evidence.applyReceiptLease,
                                targetLat = task.latitude,
                                targetLng = task.longitude,
                                locationToleranceMeters = 1.0,
                                preObservation = preObservation,
                                postObservation = postObservation
                            )
                            val decision = planRepository.recordTrustedCompletion(trustCtx)
                            val trusted = decision == TrustDecision.PASS
                            if (trusted) {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.TRUST_POLICY_PASS) ?: aplusState
                                // # §7.3：完成 = 可信计数投影（trusted-only SQL 是 F3 GREEN）；legacy 计数绝不动（P1-3）
                                planRepository.completeTaskIfQuotaReached(task.id)
                                // # P1-1 (Sol round-15): persist the authoritative phase — M-CR-07 crash
                                // # (after the ledger commit) must recover as QUOTA_COMMITTED, not DECIDING.
                                planRepository.markAplusState(attemptId, "QUOTA_COMMITTED")
                                updateState(AutomationState.SUCCEEDED)
                            } else {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.TRUST_POLICY_FAIL) ?: aplusState
                                // # UNVERIFIED_RECORDED：未验证记录由 recordTrustedCompletion 的 GREEN 写（P2），
                                // # skeleton 不写（RED）；legacy 计数绝不动。持久化 phase 供恢复投影（Sol round-15 P1-1）。
                                planRepository.markAplusState(attemptId, "UNVERIFIED_RECORDED")
                                updateState(AutomationState.FAILED)
                                _lastFailure.value = LastFailureInfo(attemptOrdinal, FailureReason.UNTRUSTED.name)
                            }
                            // # P1-5：release BEFORE terminalize（lease-bound + durable，P1-4），然后终态化 attempt。
                            if (!aplusReleaseAndFinalize(attemptId, task.id, success = trusted, reason = if (trusted) null else FailureReason.UNTRUSTED.name, endedAt = outcome.endedAt, webScore = outcome.webScore, videoScore = outcome.videoScore)) {
                                return@coroutineScope
                            }
                            log("A+ attempt $attemptOrdinal decided=$decision (state $aplusState)")
                            if (!trusted) {
                                // # fail-closed（P1-3/P1-5）：trust-fail = 安全失败（§8.2 STOPPED），持久 PAUSED，
                                // # 绝不静默重试、绝不动 legacy 计数；也终结骨架恒 FAIL 时的无限重试。
                                aplusPause("trust decision FAIL for attempt $attemptId — UNVERIFIED_RECORDED, no quota, no legacy counter")
                                return@coroutineScope
                            }
                        }
                    }
                    currentAttemptId = null
                    tasks = planRepository.getTasks(planId)
                    continue
                }

                // ==================== Location stage（AC-F3-2：OFF 则整段跳过） ====================
                if (toggles.locationStageEnabled) {
                    // # Fake GPS（失败即停，INV-10）
                    updateState(AutomationState.LAUNCHING_FAKE_GPS)
                    val gpsOutcome = gpsSetter.setLocation(task.latitude, task.longitude)
                    ensureActive()
                    if (gpsOutcome is GpsOutcome.Failed) {
                        log("GPS failed: ${gpsOutcome.reason} — typed failed attempt, no quota consumed")
                        planRepository.finalizeAttemptFailure(attemptId, gpsOutcome.reason.name, nowMs())
                        updateState(AutomationState.FAILED)
                        _lastFailure.value = LastFailureInfo(attemptOrdinal, gpsOutcome.reason.name)
                        currentAttemptId = null
                        returnToSelf()
                        tasks = planRepository.getTasks(planId)
                        continue
                    }

                    // # GPS 稳定等待（F3）：锚在新坐标激活确认之后、CellRebel 启动之前，
                    // # 旅程顺序 = Setting GPS → GPS settling → Testing
                    if (gpsSettleMs > 0) {
                        updateState(AutomationState.WAITING_INTERVAL)
                        log("Waiting ${gpsSettleMs / 1000}s for GPS to settle...")
                        delayMs(gpsSettleMs)
                        ensureActive()
                    }
                } else {
                    log("Location stage OFF — skipping Fake GPS entirely (gps_skipped)")
                }

                // ==================== Test stage OFF：GPS 验证即终态（AC-F3-3） ====================
                if (!toggles.testStageEnabled) {
                    // # KD-F3-2：ok_gps_only 计配额；同事务守卫式收尾（INV-3 语义不变）
                    log("CellRebel stage OFF — GPS-verified attempt terminates as ok_gps_only")
                    planRepository.finalizeAttemptSuccess(
                        attemptId = attemptId,
                        taskId = task.id,
                        expectedCompletedSuccesses = task.completedSuccesses,
                        runningObservedAt = null,
                        endedAt = nowMs(),
                        webScore = null,
                        videoScore = null,
                        status = "ok_gps_only"
                    )
                    val updated = planRepository.getTask(task.id)
                    if (updated != null) {
                        _currentTask.value = _currentTask.value
                            ?.copy(completedSuccesses = updated.completedSuccesses)
                        if (updated.status == "completed") {
                            log("Location csvRow=${task.csvRow} quota complete ✔")
                        }
                    }
                    updateState(AutomationState.SUCCEEDED)
                    log("Attempt $attemptOrdinal ok_gps_only (test_skipped)")
                    currentAttemptId = null
                    returnToSelf()
                    tasks = planRepository.getTasks(planId)
                    continue
                }

                // ==================== CellRebel verified attempt ====================
                returnToSelf()
                updateState(AutomationState.LAUNCHING_CELLREBEL)
                val outcome = cellRebelRunner.runTest(startedAt, testTimeoutMs) { runningAt ->
                    // # C2：观察到 RUNNING 的瞬间持久化 starting -> running 迁移（spec O3）
                    planRepository.markAttemptRunning(attemptId, runningAt)
                }
                ensureActive()
                returnToSelf()

                // ==================== Finalize ====================
                updateState(AutomationState.PROCESSING)
                when (outcome) {
                    is AttemptOutcome.Success -> {
                        // # INV-3：单事务收尾（尝试行 + 守卫式自增）
                        val finalized = planRepository.finalizeAttemptSuccess(
                            attemptId = attemptId,
                            taskId = task.id,
                            expectedCompletedSuccesses = task.completedSuccesses,
                            runningObservedAt = outcome.runningObservedAt,
                            endedAt = outcome.endedAt,
                            webScore = outcome.webScore,
                            videoScore = outcome.videoScore
                        )
                        if (!finalized) {
                            log("WARNING: finalize skipped (stale expected count) — idempotent guard held")
                        }
                        val updated = planRepository.getTask(task.id)
                        if (updated != null) {
                            // # 刷新 Run 页状态卡上的成功计数
                            _currentTask.value = _currentTask.value
                                ?.copy(completedSuccesses = updated.completedSuccesses)
                            // # F5：配额达成 → completed 已在 finalize 事务内完成
                            if (updated.status == "completed") {
                                log("Location csvRow=${task.csvRow} quota complete ✔")
                            }
                        }
                        updateState(AutomationState.SUCCEEDED)
                        log("Attempt $attemptOrdinal succeeded: Web=${outcome.webScore}, Video=${outcome.videoScore}")
                    }
                    is AttemptOutcome.Failure -> {
                        // # INV-4：失败持久化，不计入配额
                        planRepository.finalizeAttemptFailure(attemptId, outcome.reason.name, outcome.endedAt)
                        updateState(AutomationState.FAILED)
                        _lastFailure.value = LastFailureInfo(attemptOrdinal, outcome.reason.name)
                        log("Attempt $attemptOrdinal failed: ${outcome.reason} (${outcome.detail ?: "no detail"})")
                    }
                }
                currentAttemptId = null
                tasks = planRepository.getTasks(planId)
            }

            // # 只有计划投影真正 complete 才算成功完成（F5：selectNext == null
            // # 但投影未完成 = 状态不一致，绝不记 completed）
            tasks = planRepository.getTasks(planId)
            if (PlanScheduler.isPlanComplete(tasks)) {
                updateState(AutomationState.DONE)
                planRepository.finishSession(runSessionId, "completed", nowMs(), _cycleCount.value)
                log("=== Plan completed: ${_cycleCount.value} attempts ===")
            } else {
                updateState(AutomationState.ERROR)
                planRepository.finishSession(runSessionId, "error", nowMs(), _cycleCount.value)
                log("=== ERROR: no selectable task but plan projection is NOT complete ===")
            }

        } catch (e: CancellationException) {
            // # 停止/取消：legacy 在途尝试标记 interrupted；A+ 模式绝不盲目标记 terminal——cancel 后可能
            // # 仍持有未收敛 lease，必须保持 recoverable 由下次恢复 reconcile（Sol round-9 addendum）。
            // # withContext(NonCancellable) 保证 paused 持久化在取消上下文中仍完成（Sol round-10 P1-5）。
            _cooldown.value = null
            if (recoveryCoordinator != null) {
                withContext(NonCancellable) {
                    aplusPause("cancelled with an unresolved A+ lease — recoverable, not terminalized")
                }
            } else {
                currentAttemptId?.let { planRepository.markAttemptInterruptedIfNonTerminal(it, nowMs()) }
                updateState(AutomationState.IDLE)
                if (runSessionId != 0L) {
                    planRepository.finishSession(runSessionId, "stopped", nowMs(), _cycleCount.value)
                }
            }
            log("=== Automation stopped by user ===")
            throw e // # 重新抛出以正确传播取消

        } catch (e: Exception) {
            // # 不可恢复的错误：legacy 在飞 attempt 终态化（F7 不留孤儿）；A+ 模式保持 recoverable。
            if (recoveryCoordinator != null) {
                withContext(NonCancellable) {
                    aplusPause("exception with an unresolved A+ lease: ${e.message}")
                }
            } else {
                currentAttemptId?.let { attemptId ->
                    runCatching {
                        planRepository.markAttemptInterruptedIfNonTerminal(attemptId, nowMs())
                    }.onFailure { Log.w(TAG, "failed to terminalize in-flight attempt $attemptId", it) }
                }
                currentAttemptId = null
                updateState(AutomationState.ERROR)
                if (runSessionId != 0L) {
                    planRepository.finishSession(runSessionId, "error", nowMs(), _cycleCount.value)
                }
            }
            log("=== Automation ERROR: ${e.message} ===")
            Log.e(TAG, "Automation failed", e)
        }
    }

    /**
     * Executes [block] with retry logic. Returns true if successful.
     * # 带重试逻辑执行代码块。成功返回 true。
     */
    private suspend fun retryWithFallback(
        stepName: String,
        maxRetries: Int = MAX_STEP_RETRIES,
        block: suspend () -> Unit
    ): Boolean {
        for (attempt in 1..maxRetries) {
            try {
                block()
                return true
            } catch (e: CancellationException) {
                throw e // # 不拦截取消异常
            } catch (e: Exception) {
                log("RETRY: $stepName failed (attempt $attempt/$maxRetries): ${e.message}")
                Log.w(TAG, "$stepName attempt $attempt failed", e)
                if (attempt < maxRetries) {
                    delayMs(2000L * attempt) // # 递增延迟重试
                }
            }
        }
        log("FAILED: $stepName after $maxRetries attempts")
        return false
    }

    /**
     * Returns to our own app via Recent Apps.
     * MIUI allows startActivity for third-party apps only when our app
     * is genuinely in the foreground, so we use this as a "hub" between phases.
     * No-op when no bridge is attached (unit tests).
     *
     * # 通过最近任务切换回自己的 app。
     * # MIUI 只有在自己 app 真正在前台时才放行 startActivity，
     * # 所以每次切换第三方 app 之间都要回到自己作为中转。
     * # 无桥接（单测）时为空操作
     */
    private suspend fun returnToSelf() {
        val bridge = bridge ?: return
        val selfPkg = bridge.getServicePackageName()
        if (bridge.getCurrentPackage() == selfPkg) {
            log("Already at our app")
            return
        }

        log("Returning to our app via Recent Apps...")
        val selfLabel = bridge.getSelfAppLabel()
        bridge.openRecents()
        delay(1500)

        var tapped = false
        for (attempt in 1..3) {
            val root = bridge.getRootNode()
            if (root == null) { delay(500); continue }

            val card = NodeFinder.findByText(root, selfLabel)
                ?: NodeFinder.findByContentDescription(root, selfLabel)
            if (card != null) {
                log("Found '$selfLabel' in recents, tapping...")
                bridge.clickNode(card)
                delay(300)
                val (cx, cy) = bridge.getNodeCenter(card)
                bridge.dispatchTap(cx, cy)
                tapped = true
                break
            }

            if (attempt == 1) {
                val texts = NodeFinder.flatten(root)
                    .mapNotNull { it.text?.toString() }
                    .take(20).joinToString(" | ")
                log("[DIAG] Recents texts: [$texts]")
            }
            delay(500)
        }

        if (!tapped) {
            log("WARNING: '$selfLabel' not found in recents, closing recents")
            bridge.goBack()
            delay(500)
        }

        // # 等待自己的 app 出现在前台
        val returned = withTimeoutOrNull(10_000L) {
            while (bridge.getCurrentPackage() != selfPkg) {
                delay(500)
            }
            true
        }
        if (returned == true) {
            log("Our app is foreground")
        } else {
            log("WARNING: returnToSelf timed out (foreground=${bridge.getCurrentPackage()})")
        }
        delay(500)
    }

    /**
     * Durable A+ PAUSED（§8.2）：恢复/正路径任一 durable 步骤 fail-closed 时，持久停跑并保留现场
     * 证据，绝不盲扫、绝不取下一任务（Sol round-7）。标记 session 为 paused，返回后调用方退出 run。
     * # A+ 持久 PAUSED：fail-closed 停跑，标记 session paused，保留现场证据
     */
    private suspend fun aplusPause(message: String) {
        updateState(AutomationState.PAUSED)
        if (runSessionId != 0L) {
            planRepository.markSessionStatus(runSessionId, "paused")
        }
        log("ERROR: $message")
    }

    /**
     * Recover ONE crashed A+ attempt, branching on its PERSISTED current operation (Sol round-8 P1-3 /
     * round-9 P1-4 full phase). Only `APPLY_PENDING` re-reconciles the apply (its receipt may not be
     * durable yet); every LATER state (ENV_APPLIED … DECIDING / QUOTA_COMMITTED / UNVERIFIED_RECORDED /
     * RELEASE_PENDING) has a durable lease and is release-converged, NEVER re-applied — so a crash after
     * a trusted mint (DECIDING) is not re-applied and its minted attempt is not clobbered as interrupted.
     *
     * @return true to continue the plan; false = fail-closed PAUSED (caller returns).
     */
    private suspend fun recoverCrashedAttempt(crashed: TestAttempt, coordinator: RecoveryCoordinator): Boolean {
        // APPLY_PENDING = apply dispatched, receipt/lease not yet durable → reconcile the apply (idempotent
        // replay) to obtain the lease, which comes BACK from the apply — never pre-seeded (Sol round-9 P1-3).
        if (crashed.aplusState == "APPLY_PENDING") {
            val applyKey = APlusOperationIdentity.applyIdempotencyKey(crashed.id)
            val intentDigest = APlusOperationIdentity.requestDigest(crashed.latitude, crashed.longitude, crashed.id, crashed.runSessionId)
            val result = coordinator.reconcile(crashed.id, applyKey, intentDigest, nowMs())
            val leaseId = when (result) {
                is ReconcileResult.AdvancedToRelease -> result.leaseId
                is ReconcileResult.ReplayedApply -> result.leaseId
                else -> null
            }
            if (leaseId == null) {
                aplusPause("reconcile of attempt ${crashed.id} = $result (§8.2: 证据不足走 PAUSED)")
                return false
            }
            attemptDriver?.driveTransition(crashed.id, AttemptState.APPLY_PENDING, AttemptEvent.CRASH_RECOVER)
            planRepository.markAplusLease(crashed.id, leaseId)
        }
        // Every in-flight state: converge the release (lease-bound + durable), then the schedule gate.
        val leaseId = planRepository.getAplusLeaseId(crashed.id)
        if (leaseId == null) {
            aplusPause("no durable leaseId to release for recovered attempt ${crashed.id}")
            return false
        }
        val receipt = coordinator.releaseLease(
            crashed.id,
            APlusOperationIdentity.releaseIdempotencyKey(crashed.id),
            leaseId,
            APlusOperationIdentity.releaseDigest(leaseId),
            nowMs()
        )
        if (receipt == null) {
            // RELEASE_INCOMPLETE → RECOVERY_REQUIRED (§8.1): the release failed, the lease is unresolved —
            // persist the phase, never silently advance (Sol round-13 P1-4).
            attemptDriver?.driveTransition(crashed.id, AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_INCOMPLETE)
            planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
            aplusPause("release receipt not durable for recovered attempt ${crashed.id}")
            return false
        }
        attemptDriver?.driveTransition(crashed.id, AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_RECEIPT)
        return advanceAfterRelease(crashed, coordinator)
    }

    /** After a durable release receipt, project the terminal truth from the append-only carrier, then gate resume. */
    private suspend fun advanceAfterRelease(crashed: TestAttempt, coordinator: RecoveryCoordinator): Boolean {
        // Terminal truth projection FIRST, sourced from the append-only carrier (Sol round-16/18 P1-1/2):
        // the trusted ledger / unverified record is the durable authority — never the bare phase string.
        val trusted = planRepository.getTrustedEntry(crashed.id)
        val unverified = planRepository.getUnverifiedRecord(crashed.id)
        // Conflicting append-only truths (both a trusted mint AND an unverified record) are a fail-closed
        // invariant break (§8.1 PASS/FAIL are mutually exclusive), never silently promoted to trusted.
        if (trusted != null && unverified != null) {
            planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
            aplusPause("conflicting trusted + unverified carriers for attempt ${crashed.id}")
            return false
        }
        when {
            trusted != null && trusted.taskId == crashed.taskId ->
                planRepository.finalizeAplusSuccess(crashed.id, crashed.taskId, nowMs(), null, null)
            trusted != null && trusted.taskId != crashed.taskId -> {
                // A wrong-task carrier violates §7.1 attempt+task binding → fail-closed, never succeeded.
                planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
                aplusPause("trusted carrier taskId mismatch for attempt ${crashed.id} (${trusted.taskId} != ${crashed.taskId})")
                return false
            }
            unverified != null ->
                planRepository.finalizeAttemptFailure(crashed.id, FailureReason.UNTRUSTED.name, nowMs())
            else -> planRepository.markAttemptInterruptedIfNonTerminal(crashed.id, nowMs())
        }
        planRepository.markAplusState(crashed.id, "CLOSED")
        // The schedule gate decides whether to resume (take the next address) — never the terminal truth.
        val advance = coordinator.scheduleAdvanced(
            crashed.id, APlusOperationIdentity.applyIdempotencyKey(crashed.id), nowMs()
        )
        if (advance != ScheduleAdvanceState.ADVANCED) {
            aplusPause("schedule-advance gate held for recovered attempt ${crashed.id}")
            return false
        }
        log("A+ recovery: attempt ${crashed.id} released + gate advanced — resuming plan")
        return true
    }

    /**
     * Normal-path release + terminalize (§8.1 RELEASE_PENDING → RELEASE_RECEIPT → CLOSED). Sol round-8
     * P1-5: the release happens BEFORE the terminalize, and the release is lease-bound + durable (P1-4) —
     * a crash between release and terminalize leaves the attempt recoverable with the lease already
     * released, never the reverse (terminalized + live lease).
     *
     * Pre-freeze the apply is GREEN (no persisted lease) → nothing to release; a seeded lease (recovery /
     * GREEN apply) MUST yield a durable typed receipt or the engine fails closed.
     *
     * @return true to continue; false = fail-closed PAUSED (caller returns).
     */
    private suspend fun aplusReleaseAndFinalize(
        attemptId: Long,
        taskId: Long,
        success: Boolean,
        reason: String?,
        endedAt: Long,
        webScore: Double?,
        videoScore: Double?
    ): Boolean {
        attemptDriver?.driveTransition(attemptId, AttemptState.DECIDING, AttemptEvent.BEGIN_RELEASE)
        planRepository.markAplusState(attemptId, "RELEASE_PENDING")
        // # P1-2/P1-4: release is provider-driven + lease-bound. The lease is always present (dispatchApply
        // # acquired + persisted it); a missing lease is a structural defect → fail-closed.
        val leaseId = planRepository.getAplusLeaseId(attemptId)
        if (leaseId == null) {
            aplusPause("no durable leaseId to release for attempt $attemptId")
            return false
        }
        val receipt = recoveryCoordinator?.releaseLease(
            attemptId,
            APlusOperationIdentity.releaseIdempotencyKey(attemptId),
            leaseId,
            APlusOperationIdentity.releaseDigest(leaseId),
            nowMs()
        )
        if (receipt == null) {
            // RELEASE_INCOMPLETE → RECOVERY_REQUIRED (§8.1): the release failed, the lease is unresolved —
            // persist the phase, never silently advance (Sol round-13 P1-4).
            attemptDriver?.driveTransition(attemptId, AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_INCOMPLETE)
            planRepository.markAplusState(attemptId, "RECOVERY_REQUIRED")
            aplusPause("release receipt not durable for attempt $attemptId")
            return false
        }
        attemptDriver?.driveTransition(attemptId, AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_RECEIPT)
        planRepository.markAplusState(attemptId, "CLOSED")
        if (success) {
            planRepository.finalizeAplusSuccess(attemptId, taskId, endedAt, webScore, videoScore)
        } else {
            planRepository.finalizeAttemptFailure(attemptId, reason ?: FailureReason.UNTRUSTED.name, endedAt)
        }
        return true
    }

    private fun updateState(newState: AutomationState) {
        val old = _state.value
        _state.value = newState
        if (old != newState) {
            Log.d(TAG, "State: $old → $newState")
        }
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        _logs.value = (_logs.value + entry).takeLast(200)
        Log.d(TAG, message)
    }
}
