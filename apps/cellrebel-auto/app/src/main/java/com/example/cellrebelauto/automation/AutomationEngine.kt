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
    // R42 (Sol R41 P2): dedicated monotonic commit clock for TrustedQuotaEntry.committedAt only.
    // nowMs stays wall/epoch (session/attempt/cooldown UI depend on it); this seam binds the ledger
    // commit timestamp to a monotonic domain (elapsedRealtime-style) per the TrustedQuotaEntry contract.
    private val commitClockMs: () -> Long = { nowMs() },
    // R44 (Sol GREEN-review-3 F3): dedicated monotonic ELAPSED clock sourcing the §7.1 execution
    // evidence *Elapsed columns. Provider observations are stamped in the elapsedRealtime domain, so
    // persisting wall-clock values there (AttemptOutcome.startedAt/endedAt are epoch) makes every
    // §6.4.2 bracketing/RUN-floor comparison wall-vs-uptime — never satisfiable in production. The
    // spec forbids reusing AttemptOutcome.startedAt for these columns.
    private val elapsedClockMs: () -> Long = { nowMs() },
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

            // R44 (DSF review P1-2): DISCOVER — §6.1's capability handshake at run start. The
            // provider must advertise the frozen protocol version before any A+ attempt runs; an
            // unavailable/discover-failed provider fail-closes the plan BEFORE the first apply.
            recoveryCoordinator?.let { coord ->
                val capabilities = coord.executorBackend().discover()
                if (capabilities == null ||
                    capabilities.protocolVersion != io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
                ) {
                    aplusPause("provider discover failed or protocol incompatible (v1 required)")
                    return@coroutineScope
                }
            }

            // ==================== Step 2+: plan loop ====================
            // # M-MG-02 GREEN：选择走 trusted 投影（count(trusted) >= required 才算完成），绝不读
            // # legacy counter 判定完成/跳过。attemptedThisRun 调和 legacy 重试语义：counter-full/
            // # trusted-empty 任务本 run 至少重试一次（绝不静默跳过），但已由本 run 产出尝试的
            // # legacy 停止（只有 trusted 铸币能完成任务，legacy 尝试不铸币，§7.3）。
            val attemptedThisRun = mutableSetOf<Long>()
            var tasks = planRepository.getTasks(planId)
            while (isActive && !PlanScheduler.isPlanComplete(tasks)) {
                val task = planRepository.selectNextTrustedTask(planId, attemptedThisRun) ?: break
                attemptedThisRun.add(task.id)
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
                // R44 (Sol GREEN-review-3 F3): the §7.1 execution evidence binds the MONOTONIC elapsed
                // domain — captured from the elapsed seam at the same lifecycle moment as the wall stamp.
                val attemptStartedAtElapsed = elapsedClockMs()
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
                    // # intent 身份由持久 attempt owner 态重算（INV-23，Sol round-8 P1-2）。R44 (Sol
                    // # GREEN-review-3 F2)：intent 只构造一次——digest 与 Binder request 共用同一对象，绝不各自重算。
                    val applyIntent = APlusOperationIdentity.intent(
                        runSessionId, attemptId, planId, task.id, startedAt, startedAt + testTimeoutMs
                    )
                    val intentDigest = APlusOperationIdentity.requestDigest(applyIntent)
                    // R45 (Sol R45 P1-3 / spec §4.3 step 1): ANCHOR — the advance CAS triple
                    // (expectedScheduleId, expectedCurrentItemId, expectedScheduleVersion) is taken
                    // from a discover() projection group NOW and persisted to the attempt's durable
                    // owner row BEFORE any external execution starts (§4.3: 先落库，再启动外部执行).
                    // completeAndAdvance later replays this triple BYTE-FOR-BYTE and must never
                    // re-discover at advance time (§6.7.3 v1.72 / M-AD-28: a refreshed precondition
                    // would push a previous generation's completion onto a NEW schedule).
                    val anchorDiscover = aplusCoord.executorBackend()?.discover()
                    val anchorProjection = anchorDiscover?.takeIf {
                        it.currentScheduleId != null && it.currentItemId != null && it.scheduleVersion != null
                    }
                    if (anchorProjection == null) {
                        // v1.55 group invariant: a partial-null projection is illegal — fail-closed
                        // with NO external execution (§6.7.5 applies the same rule at readback).
                        planRepository.finalizeAttemptFailure(attemptId, FailureReason.UNTRUSTED.name, nowMs())
                        aplusPause("discover projection incomplete for attempt $attemptId — cannot anchor the advance CAS triple")
                        return@coroutineScope
                    }
                    planRepository.markAplusAdvanceAnchor(
                        attemptId, anchorProjection.currentScheduleId!!, anchorProjection.currentItemId!!, anchorProjection.scheduleVersion!!
                    )
                    // R44 (DSF review P1-2): PREFLIGHT — the provider's schedule decision for THIS
                    // intent is consulted BEFORE the apply (§6.7): a DENIED/WAIT_UNTIL preflight
                    // fail-closes the attempt BEFORE any external apply is dispatched. This is the
                    // production consumer of executor.preflight().
                    // R45 (Sol R45 P1-2): preflight is MANDATORY — null is the unified fail-closed
                    // result of an unbound transport / validator failure / illegal response, and it
                    // must block the apply exactly like a DENIED schedule. The previous null-pass-
                    // through let an unapproved/unreachable provider change the device environment.
                    val preflight = aplusCoord.executorBackend()?.preflight(
                        applyIntent, APlusOperationIdentity.applyIdempotencyKey(attemptId), intentDigest
                    )
                    if (preflight == null || preflight.scheduleDecisionWire !=
                        io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.ALLOWED_NOW.wire
                    ) {
                        // §6.7: not allowed now (or unavailable) ⇒ typed failure, no apply, no quota.
                        val why = if (preflight == null) "unavailable (fail-closed)" else "decision=${preflight.scheduleDecisionWire}"
                        planRepository.finalizeAttemptFailure(attemptId, FailureReason.UNTRUSTED.name, nowMs())
                        aplusPause("preflight denied schedule for attempt $attemptId ($why)")
                        return@coroutineScope
                    }
                    // # P1-3：持久化当前操作（§7.1 Attempt 拥有当前 operation）。
                    planRepository.markAplusState(attemptId, "APPLY_PENDING")
                    aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.BEGIN_APPLY) ?: aplusState
                    // # P1-2（Sol round-9）：正路径 apply 是 provider 驱动——dispatchApply → ApplyOutcome.leaseId
                    // # 持久化 lease（绝不凭空发明）；fail-closed executor 无 lease → PAUSED。
                    val applyOutcome = aplusCoord.dispatchApply(
                        attemptId, applyIntent, APlusOperationIdentity.applyIdempotencyKey(attemptId), intentDigest, nowMs()
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
                    val preObservation = aplusEvidenceSrc.acquirePreObservation(attemptId, runSessionId)
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
                    // R37 (Sol R36 P1-1): persist pre-observation to durable storage BEFORE CellRebel start
                    // so crash recovery re-decides from durable data, not a stale live source.
                    planRepository.persistObservation(attemptId, "PRE", preObservation)
                    aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.START_CELLREBEL) ?: aplusState
                    planRepository.markAplusState(attemptId, "CELLREBEL_START_PENDING")
                    // R37 (Sol R36 P1-2): persist current executionId BEFORE external start (§8.1 START).
                    val currentExecId = "exec-${attemptId}-${nowMs()}"
                    planRepository.markCurrentExecutionId(attemptId, currentExecId)
                    updateState(AutomationState.LAUNCHING_CELLREBEL)
                    var runningConfirmedAtElapsed = 0L
                    val outcome = cellRebelRunner.runTest(startedAt, testTimeoutMs) { runningAt ->
                        // R44 (Sol GREEN-review-3 F3): RUNNING confirmed in the elapsed domain too.
                        runningConfirmedAtElapsed = elapsedClockMs()
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
                            // R43 (Sol GREEN-review-2 F3): the §7.1 execution evidence row is persisted AT
                            // the COMPLETION_OBSERVED phase boundary — BEFORE the post-observe call and long
                            // before the trusted transaction. A crash between here and DECIDING therefore
                            // finds the durable execution evidence in the DB (M-CR-06's precondition);
                            // previously the row was only inserted inside recordTrustedCompletion, leaving
                            // the DECIDING→ledger crash window evidence-less in production.
                            val completedAtElapsed = elapsedClockMs()
                            planRepository.persistExecutionEvidence(
                                executionId = currentExecId,
                                attemptId = attemptId,
                                completionEvidenceWire = 1, // classified VERIFIED_NEW_COMPLETION (§8.6.2) by the source contract
                                evidencePayloadDigest = "exec-evidence-$attemptId-$currentExecId",
                                // R44 (Sol GREEN-review-3 F3): elapsed-domain stamps from the monotonic
                                // seam — NEVER AttemptOutcome.startedAt/endedAt (wall/epoch; the spec
                                // forbids reusing them, and only the elapsed domain is comparable to
                                // the provider observations in §6.4.2 bracketing).
                                startedAtElapsed = attemptStartedAtElapsed,
                                runningConfirmedAtElapsed = runningConfirmedAtElapsed,
                                completedAtElapsed = completedAtElapsed,
                                baselineRunningState = "IDLE",
                                runningMarkerText = "RUNNING",
                                runningDurationMs = completedAtElapsed - runningConfirmedAtElapsed,
                                webBrowsingScore = outcome.webScore,
                                videoStreamingScore = outcome.videoScore,
                                roundTimestampsElapsed = "$attemptStartedAtElapsed;$completedAtElapsed"
                            )
                            // §8.1: COMPLETION_OBSERVED → POST_OBSERVE_PENDING is persisted BEFORE the post-observe
                            // call (Sol round-23 P1-1): a crash in the post-observe call must recover as
                            // POST_OBSERVE_PENDING, not CELLREBEL_RUNNING.
                            planRepository.markAplusState(attemptId, "POST_OBSERVE_PENDING")
                            val postObservation = aplusEvidenceSrc.acquirePostObservation(attemptId, runSessionId)
                            if (postObservation == null) {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.OBSERVATION_UNTRUSTED) ?: aplusState
                                if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = FailureReason.UNTRUSTED.name, endedAt = outcome.endedAt, webScore = null, videoScore = null)) {
                                    return@coroutineScope
                                }
                                aplusPause("post-observation unavailable for attempt $attemptId")
                                return@coroutineScope
                            }
                            aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.POST_OBSERVATION_OK) ?: aplusState
                            // R37 (Sol R36 P1-1): persist post-observation to durable storage BEFORE DECIDING.
                            planRepository.persistObservation(attemptId, "POST", postObservation)
                            // §8.1: POST_OBSERVATION_OK → DECIDING is persisted right after the observation succeeds
                            // (Sol round-23 P1-1).
                            planRepository.markAplusState(attemptId, "DECIDING")
                            // # DECIDE：ctx 由持久 intent（目标坐标、本地重算 hash）+ 后端 artifact 组装（INV-23）
                            val evidence = aplusEvidenceSrc.acquireCompletionEvidence(attemptId, runSessionId)
                            if (evidence == null) {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.OBSERVATION_UNTRUSTED) ?: aplusState
                                if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = FailureReason.UNTRUSTED.name, endedAt = outcome.endedAt, webScore = null, videoScore = null)) {
                                    return@coroutineScope
                                }
                                aplusPause("completion evidence unavailable for attempt $attemptId")
                                return@coroutineScope
                            }
                            // R37 (Sol R36 P1-1): persist completion receipt to durable storage BEFORE trust decision.
                            planRepository.persistCompletionReceipt(
                                attemptId, evidence.completionEvidenceWire,
                                evidence.applyReceiptIntentHash, evidence.applyReceiptLease
                            )
                            val trustCtx = CompletionTrustContext(
                                // R43 GREEN (Sol R38 P1-2 phantom owner): the persisted execution row
                                // carries the SAME Auto-local executionId generated at START_CELLREBEL
                                // (currentExecId) — the owner pointer and the evidence row are one identity.
                                execution = evidence.execution.copy(attemptId = attemptId, executionId = currentExecId),
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
                            val decision = planRepository.recordTrustedCompletion(trustCtx, commitClockMs())
                            val trusted = decision == TrustDecision.PASS
                            if (trusted) {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.TRUST_POLICY_PASS) ?: aplusState
                                // # §7.3：完成 = 可信计数投影（trusted-only SQL 是 F3 GREEN）；legacy 计数绝不动（P1-3）
                                planRepository.completeTaskIfQuotaReached(task.id)
                                // # P1-1 (Sol round-15): persist the authoritative phase — M-CR-07 crash
                                // # (after the ledger commit) must recover as QUOTA_COMMITTED, not DECIDING.
                                planRepository.markAplusState(attemptId, "QUOTA_COMMITTED")
                                val quotaReached = planRepository.trustedCountForTaskPublic(task.id) >= task.requiredSuccesses
                                // R45 (Sol R45 P1-4 / §6.7.4a frozen order): RELEASE FIRST. The apply
                                // lease binds the CURRENT item's environment; advancing while holding
                                // it swaps the environment under an ACTIVE lease — the exact shape
                                // ENVIRONMENT_DRIFT exists to catch, self-inflicted. A compliant
                                // provider must answer LEASE_CONFLICT; we never send that shape.
                                if (!aplusReleaseLease(attemptId)) {
                                    return@coroutineScope
                                }
                                if (quotaReached) {
                                    // R46 (Sol R46 P1-1): the normal path and the ADVANCE_* crash
                                    // recovery share ONE replay+verify routine so a mid-advance crash
                                    // resumes through the IDENTICAL durable-request replay (same
                                    // idempotency key + canonical digest) and the same four-leg
                                    // verification — the provider's idempotency returns the stored
                                    // receipt for the same key, never a second advance.
                                    if (!replayAdvanceAndVerify(attemptId, task.id, applyOutcome.operationId, intentDigest)) {
                                        return@coroutineScope
                                    }
                                }
                                if (!aplusFinalize(attemptId, task.id, endedAt = outcome.endedAt, webScore = outcome.webScore, videoScore = outcome.videoScore)) {
                                    return@coroutineScope
                                }
                                updateState(AutomationState.SUCCEEDED)
                            } else {
                                aplusState = attemptDriver?.driveTransition(attemptId, aplusState, AttemptEvent.TRUST_POLICY_FAIL) ?: aplusState
                                // # UNVERIFIED_RECORDED：未验证记录由 recordTrustedCompletion 的 GREEN 写（P2），
                                // # skeleton 不写（RED）；legacy 计数绝不动。持久化 phase 供恢复投影（Sol round-15 P1-1）。
                                planRepository.markAplusState(attemptId, "UNVERIFIED_RECORDED")
                                updateState(AutomationState.FAILED)
                                _lastFailure.value = LastFailureInfo(attemptOrdinal, FailureReason.UNTRUSTED.name)
                                // # P1-5：release BEFORE terminalize（lease-bound + durable，P1-4），然后终态化 attempt。
                                if (!aplusReleaseAndFinalize(attemptId, task.id, success = false, reason = FailureReason.UNTRUSTED.name, endedAt = outcome.endedAt, webScore = outcome.webScore, videoScore = outcome.videoScore)) {
                                    return@coroutineScope
                                }
                                log("A+ attempt $attemptOrdinal decided=$decision (state $aplusState)")
                                // # fail-closed（P1-3/P1-5）：trust-fail = 安全失败（§8.2 STOPPED），持久 PAUSED，
                                // # 绝不静默重试、绝不动 legacy 计数；也终结骨架恒 FAIL 时的无限重试。
                                aplusPause("trust decision FAIL for attempt $attemptId — UNVERIFIED_RECORDED, no quota, no legacy counter")
                                return@coroutineScope
                            }
                            log("A+ attempt $attemptOrdinal decided=$decision (state $aplusState)")
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
        // ==================== DECIDING: re-decide from DURABLE DB carriers (M-CR-06, R43 GREEN) ====================
        // The §8.1 TRUST_POLICY_PASS transaction crashed before commit. At DECIDING the durable
        // execution evidence + pre/post observations + completion receipt ALL persist in DB (the
        // normal path persisted them at their phase boundaries); only the ledger mint is missing.
        // Recovery re-assembles CompletionTrustContext from the DURABLE data (never a live source
        // re-acquiring stale observations) and re-runs recordTrustedCompletion with the commit clock.
        if (crashed.aplusState == "DECIDING") {
            if (!redecideDecidingAttempt(crashed)) {
                // Missing durable evidence ⇒ fail-closed: no mint, fall through to the release
                // convergence; the terminal projection marks the attempt interrupted (never succeeded).
                log("A+ recovery: DECIDING attempt ${crashed.id} lacks durable evidence — fail-closed, no re-mint")
            }
        }

        // ==================== Mid-observation phases: re-acquire the missing phase evidence ====================
        // M-CR-03/04/05 (GREEN): a PRE_OBSERVED / CELLREBEL_START_PENDING / POST_OBSERVE_PENDING crash
        // re-preobserves / classifies / post-observes from the live evidence source; it is NEVER
        // collapsed to interrupted.
        //  - SUCCESS: the re-acquired evidence is PERSISTED to the durable carrier and the phase
        //    ADVANCES (PRE_OBSERVED → CELLREBEL_START_PENDING; POST_OBSERVE_PENDING → DECIDING via
        //    the re-decide below; classification re-persists the execution evidence). The recovered
        //    attempt continues its lifecycle — the re-acquisition result is never discarded
        //    (Sol GREEN-review P1-2).
        //  - FAILURE (source null): the attempt is finalized UNTRUSTED (typed failure) — still never
        //    interrupted — AND the release MUST converge durably before continuing; a non-durable
        //    release is a fail-closed PAUSE (the lease is unresolved — §8.1 RELEASE_INCOMPLETE).
        when (crashed.aplusState) {
            // ---- R46 (Sol R46 P1-1): mid-advance crash recovery. The trusted mint is durable
            // (QUOTA_COMMITTED preceded the advance) and the lease was already RELEASED (release
            // precedes advance). Recovery REPLAYS the same durable advance request — identical
            // idempotency key + canonical digest (the digest preimage excludes the audit
            // timestamp, so the rebuilt request is byte-identical) — and re-runs the four-leg
            // verification + receipt-digest binding. The provider's idempotency returns the
            // STORED receipt for the same key; a crash never pushes a second advance.
            "ADVANCE_PENDING", "ADVANCE_OBSERVING", "ADVANCE_STATE_READBACK" -> {
                if (planRepository.getTrustedEntry(crashed.id) == null) {
                    // The advance only ever runs quota-reached — a missing mint here is an
                    // invariant break, fail-closed (never advance without the ledger authority).
                    planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
                    aplusPause("ADVANCE_* crash without a durable trusted mint for attempt ${crashed.id} — invariant break")
                    return false
                }
                // INV-23: the intent digest recompute from the persisted attempt owner state —
                // the same inputs the original apply digested.
                val intentDigest = APlusOperationIdentity.requestDigest(
                    APlusOperationIdentity.intent(
                        crashed.runSessionId, crashed.id, planId, crashed.taskId, crashed.startedAt, crashed.startedAt + testTimeoutMs
                    )
                )
                if (!replayAdvanceAndVerify(crashed.id, crashed.taskId, null, intentDigest)) {
                    return false
                }
                planRepository.markAplusState(crashed.id, "CLOSED")
                planRepository.finalizeAplusSuccess(crashed.id, crashed.taskId, nowMs(), null, null)
                log("A+ recovery: ADVANCE_* attempt ${crashed.id} replayed + independently verified — closed trusted")
                return true
            }
            "PRE_OBSERVED", "CELLREBEL_START_PENDING", "CELLREBEL_RUNNING", "POST_OBSERVE_PENDING" -> {
                val src = completionEvidenceSource
                if (src == null) {
                    aplusPause("no evidence source to re-acquire ${crashed.aplusState} attempt ${crashed.id}")
                    return false
                }
                val acquired: Boolean = when (crashed.aplusState) {
                    "PRE_OBSERVED" -> {
                        val pre = src.acquirePreObservation(crashed.id, crashed.runSessionId)
                        if (pre != null) {
                            // Persist the re-acquired pre-observation durably, then advance the phase
                            // (the next phase START_CELLREBEL re-runs the external start).
                            planRepository.persistObservation(crashed.id, "PRE", pre)
                            attemptDriver?.driveTransition(crashed.id, AttemptState.PRE_OBSERVED, AttemptEvent.START_CELLREBEL)
                            planRepository.markAplusState(crashed.id, "CELLREBEL_START_PENDING")
                            log("A+ recovery: PRE_OBSERVED attempt ${crashed.id} re-preobserved + advanced to CELLREBEL_START_PENDING")
                            return true
                        } else false
                    }
                    "POST_OBSERVE_PENDING" -> {
                        val post = src.acquirePostObservation(crashed.id, crashed.runSessionId)
                        if (post != null) {
                            // Persist the re-acquired post-observation durably, then advance into
                            // DECIDING and run the trust decision over the durable bundle.
                            planRepository.persistObservation(crashed.id, "POST", post)
                            attemptDriver?.driveTransition(crashed.id, AttemptState.POST_OBSERVE_PENDING, AttemptEvent.POST_OBSERVATION_OK)
                            planRepository.markAplusState(crashed.id, "DECIDING")
                            redecideDecidingAttempt(crashed)
                            log("A+ recovery: POST_OBSERVE_PENDING attempt ${crashed.id} re-observed + advanced into DECIDING")
                            return true
                        } else false
                    }
                    else -> {
                        // CELLREBEL_START_PENDING / CELLREBEL_RUNNING: re-classify the completion
                        // evidence, persist the receipt durably, and advance to POST_OBSERVE_PENDING
                        // (the owner pointer already exists from START; the next phase re-observes).
                        val evidence = src.acquireCompletionEvidence(crashed.id, crashed.runSessionId)
                        if (evidence != null) {
                            planRepository.persistCompletionReceipt(
                                crashed.id, evidence.completionEvidenceWire,
                                evidence.applyReceiptIntentHash, evidence.applyReceiptLease
                            )
                            // R44 (Sol GREEN-review-3 F3): persist the §7.1 EXECUTION EVIDENCE at the
                            // same boundary, bound to the persisted OWNER execution pointer. The later
                            // DECIDING re-decide reads exactly this owner row — a recovery that only
                            // lands the receipt leaves the durable bundle incomplete and can never
                            // re-run the trust decision (production write chain, never hand-seeded).
                            val ownerExecId = planRepository.getCurrentExecutionId(crashed.id)
                            if (ownerExecId != null) {
                                val execEvidence = evidence.execution
                                planRepository.persistExecutionEvidence(
                                    executionId = ownerExecId,
                                    attemptId = crashed.id,
                                    completionEvidenceWire = evidence.completionEvidenceWire,
                                    evidencePayloadDigest = execEvidence.evidencePayloadDigest,
                                    startedAtElapsed = execEvidence.startedAtElapsed,
                                    runningConfirmedAtElapsed = execEvidence.runningConfirmedAtElapsed,
                                    completedAtElapsed = execEvidence.completedAtElapsed,
                                    baselineRunningState = execEvidence.baselineRunningState,
                                    runningMarkerText = execEvidence.runningMarkerText,
                                    runningDurationMs = execEvidence.runningDurationMs,
                                    webBrowsingScore = execEvidence.webBrowsingScore,
                                    videoStreamingScore = execEvidence.videoStreamingScore,
                                    roundTimestampsElapsed = execEvidence.roundTimestampsElapsed
                                )
                            }
                            attemptDriver?.driveTransition(crashed.id, AttemptState.CELLREBEL_RUNNING, AttemptEvent.COMPLETION_OBSERVED)
                            planRepository.markAplusState(crashed.id, "POST_OBSERVE_PENDING")
                            log("A+ recovery: ${crashed.aplusState} attempt ${crashed.id} re-classified + advanced to POST_OBSERVE_PENDING")
                            return true
                        } else false
                    }
                }
                if (!acquired) {
                    // Fail-closed: the phase evidence cannot be re-acquired ⇒ UNTRUSTED typed failure.
                    // The release MUST be durable before the plan continues (Sol GREEN-review P1-2:
                    // a non-durable release is an unresolved lease — PAUSE, never silent advance).
                    val leaseForUntrusted = planRepository.getAplusLeaseId(crashed.id)
                    if (leaseForUntrusted != null) {
                        val releaseReceipt = coordinator.releaseLease(
                            crashed.id,
                            APlusOperationIdentity.releaseIdempotencyKey(crashed.id),
                            leaseForUntrusted,
                            APlusOperationIdentity.releaseDigest(leaseForUntrusted),
                            nowMs()
                        )
                        if (releaseReceipt == null) {
                            // RELEASE_INCOMPLETE (§8.1): the lease is unresolved — persist the phase
                            // and PAUSE; never finalize-continue on a dangling lease.
                            attemptDriver?.driveTransition(crashed.id, AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_INCOMPLETE)
                            planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
                            aplusPause("release not durable for UNTRUSTED attempt ${crashed.id} — lease unresolved (§8.1 RELEASE_INCOMPLETE)")
                            return false
                        }
                    }
                    planRepository.finalizeAttemptFailure(crashed.id, FailureReason.UNTRUSTED.name, nowMs())
                    planRepository.markAplusState(crashed.id, "UNVERIFIED_RECORDED")
                    log("A+ recovery: ${crashed.aplusState} attempt ${crashed.id} re-acquisition failed — UNTRUSTED, never interrupted")
                    return true
                }
            }
        }

        // APPLY_PENDING = apply dispatched, receipt/lease not yet durable → reconcile the apply (idempotent
        // replay) to obtain the lease, which comes BACK from the apply — never pre-seeded (Sol round-9 P1-3).
        if (crashed.aplusState == "APPLY_PENDING") {
            val applyKey = APlusOperationIdentity.applyIdempotencyKey(crashed.id)
            // R44 (Sol GREEN-review-3 F2): the SAME intent object feeds the digest and the wire
            // request — all inputs from the durable owner state (attempt row + plan config).
            val applyIntent = APlusOperationIdentity.intent(
                crashed.runSessionId, crashed.id, planId, crashed.taskId, crashed.startedAt, crashed.startedAt + testTimeoutMs
            )
            val intentDigest = APlusOperationIdentity.requestDigest(applyIntent)
            val result = coordinator.reconcile(crashed.id, applyIntent, applyKey, intentDigest, nowMs())
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

    /**
     * R43 GREEN (M-CR-06): re-decide a DECIDING crash from the DURABLE DB carriers. The §8.1
     * TRUST_POLICY_PASS transaction crashed before commit; the durable execution evidence (located
     * by the persisted currentExecutionId OWNER — never positionally, and the row must belong to
     * this attempt), the pre/post observations, and the completion receipt were all persisted at
     * their phase boundaries. Re-assemble the CompletionTrustContext from the durable data — never
     * a live source re-acquiring stale observations — and re-run recordTrustedCompletion with the
     * engine's monotonic commit clock.
     *
     * @return true when the durable bundle was complete and the re-decision ran (PASS minted or
     *   FAIL wrote the unverified carrier); false when any durable carrier is missing (fail-closed:
     *   NO mint — the caller falls through to the release convergence + interrupted projection).
     */
    /** The persisted lease for an attempt (never invented; null when absent). */
    private suspend fun leaseIdForAttempt(attemptId: Long): String =
        planRepository.getAplusLeaseId(attemptId) ?: ""

    private suspend fun PlanRepository.trustedCountForTaskPublic(taskId: Long): Int =
        trustedCountForTask(taskId)

    private suspend fun redecideDecidingAttempt(crashed: TestAttempt): Boolean {
        // Owner lookup (Sol R35 P1-2): the CURRENT execution is the row the owner pointer names.
        val ownerExecId = planRepository.getCurrentExecutionId(crashed.id) ?: return false
        val execution = planRepository.getExecutionByExecutionId(ownerExecId) ?: return false
        // P1-2 binding: the owner row must belong to THIS attempt — a foreign/dangling owner is fail-closed.
        if (execution.attemptId != crashed.id) return false
        val pre = planRepository.getObservationSnapshot(crashed.id, "PRE") ?: return false
        val post = planRepository.getObservationSnapshot(crashed.id, "POST") ?: return false
        val receipt = planRepository.getCompletionReceipt(crashed.id) ?: return false

        // INV-23: the owner recompute from the persisted attempt intent (ids + session + plan/task
        // refs + window — R44, Sol GREEN-review-3 F2: identical inputs on every path).
        val intentDigest = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                crashed.runSessionId, crashed.id, planId, crashed.taskId, crashed.startedAt, crashed.startedAt + testTimeoutMs
            )
        )
        val trustCtx = CompletionTrustContext(
            execution = execution,
            // The receipt's wire is the provider's verification claim; TrustPolicy additionally
            // requires the execution row to agree (wire disagreement fails closed, Sol R39).
            completionEvidenceWire = receipt.completionEvidenceWire,
            applyReceiptIntentHash = receipt.acceptedIntentHash,
            locallyRecomputedIntentHash = intentDigest,
            applyReceiptLease = receipt.leaseId,
            targetLat = crashed.latitude,
            targetLng = crashed.longitude,
            locationToleranceMeters = 1.0,
            preObservation = pre,
            postObservation = post
        )
        val decision = planRepository.recordTrustedCompletion(trustCtx, commitClockMs())
        if (decision == TrustDecision.PASS) {
            attemptDriver?.driveTransition(crashed.id, AttemptState.DECIDING, AttemptEvent.TRUST_POLICY_PASS)
            // §7.3: completion is the trusted-count projection.
            planRepository.completeTaskIfQuotaReached(crashed.taskId)
            planRepository.markAplusState(crashed.id, "QUOTA_COMMITTED")
            log("A+ recovery: re-decided attempt ${crashed.id} = PASS (trusted entry minted)")
        } else {
            attemptDriver?.driveTransition(crashed.id, AttemptState.DECIDING, AttemptEvent.TRUST_POLICY_FAIL)
            planRepository.markAplusState(crashed.id, "UNVERIFIED_RECORDED")
            log("A+ recovery: re-decided attempt ${crashed.id} = FAIL (unverified record written)")
        }
        return true
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
            trusted != null && trusted.taskId == crashed.taskId -> {
                // Group 1 (Sol closure verdict Issue #19): re-compute quota from the durable
                // trusted ledger and dispatch the external advance if the task's quota is
                // reached AND the advance anchor is available (persisted when the attempt
                // opened). The normal path (line 551) gates advance on quotaReached; recovery
                // must do the same — a missing advance here means the schedule never moves
                // after a QUOTA_COMMITTED/RELEASED crash with quota reached.
                //
                // P1-1 fix (Sol closure review R2): a missing TASK is always an invariant break
                // (the task row is immutable after plan import). A missing ANCHOR is an invariant
                // break only for QUOTA_COMMITTED/RELEASED (the lifecycle persists it before trust
                // evaluation); for DECIDING crashes (where the trusted entry survived but the anchor
                // was never set), anchor=null is legitimate — skip advance, finalize succeeded.
                val task = planRepository.getTask(crashed.taskId)
                if (task == null) {
                    planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
                    aplusPause("trusted recovery: task ${crashed.taskId} not found for attempt ${crashed.id} — invariant break")
                    return false
                }
                val trustedCount = planRepository.trustedCountForTaskPublic(crashed.taskId)
                val anchor = planRepository.getAplusAdvanceAnchor(crashed.id)
                if (trustedCount >= task.requiredSuccesses && anchor != null) {
                    val intentDigest = APlusOperationIdentity.requestDigest(
                        APlusOperationIdentity.intent(
                            crashed.runSessionId, crashed.id, planId, crashed.taskId,
                            crashed.startedAt, crashed.startedAt + testTimeoutMs
                        )
                    )
                    if (!replayAdvanceAndVerify(crashed.id, crashed.taskId, null, intentDigest)) {
                        return false
                    }
                } else if (trustedCount >= task.requiredSuccesses && anchor == null) {
                    // Anchor null + quota reached: invariant break for QUOTA_COMMITTED/RELEASED
                    // (anchor MUST exist by that point); but for DECIDING crashes (redecide
                    // fallthrough), it's legitimate — advance was never dispatched.
                    val anchorRequiredPhases = setOf("QUOTA_COMMITTED", "RELEASED")
                    if (crashed.aplusState in anchorRequiredPhases) {
                        planRepository.markAplusState(crashed.id, "RECOVERY_REQUIRED")
                        aplusPause("trusted recovery: anchor missing for ${crashed.aplusState} attempt ${crashed.id} — invariant break")
                        return false
                    }
                    // DECIDING or other phases: anchor never set → skip advance, just finalize.
                }
                planRepository.finalizeAplusSuccess(crashed.id, crashed.taskId, nowMs(), null, null)
            }
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
        // Sol R2 P1-1: remove scheduleAdvanced gate from recovery. The gate was designed for
        // the NORMAL path ("should we take the next address?") — its TrustedQuotaAcquirer checks
        // endedAt==null (set by finalizeAplusSuccess) AND trustedCount < requiredSuccesses (false
        // when quota is met). Both conditions are structurally false after recovery finalizes AND
        // when quota is met. In production composition, recovery ALWAYS PAUSEs regardless of
        // outcome. Recovery has its own verification (replayAdvanceAndVerify validates receipt +
        // observe + digest); once terminal truth is projected and the attempt CLOSED, the engine
        // resumes directly. The normal-path gate applies to normal-path advance decisions only.
        planRepository.markAplusState(crashed.id, "CLOSED")
        log("A+ recovery: attempt ${crashed.id} terminal truth projected — resuming plan")
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
        planRepository.markAplusState(attemptId, "RELEASED")
        planRepository.markAplusState(attemptId, "CLOSED")
        if (success) {
            planRepository.finalizeAplusSuccess(attemptId, taskId, endedAt, webScore, videoScore)
        } else {
            planRepository.finalizeAttemptFailure(attemptId, reason ?: FailureReason.UNTRUSTED.name, endedAt)
        }
        return true
    }

    /**
     * R46 (Sol R46 P1-1/P1-2): the SINGLE advance replay+verify routine shared by the normal
     * quota-reached path and the ADVANCE_* crash recovery. Rebuilds the advance request from
     * DURABLE state only (the attempt-open anchor triple + the trusted projection + the persisted
     * lease) — the rebuild is byte-identical to the original request (the digest preimage excludes
     * verifiedAtElapsedRealtimeMs), so an idempotent provider returns the STORED receipt for the
     * same (key, digest) and a crash never pushes a second advance. Returns false = fail-closed
     * (RECOVERY_REQUIRED + pause); true = the advance is proven by independent verification.
     *
     * R46 (Sol R46 P1-2): the receipt's receiptDigest is RECOMPUTED
     * (CanonicalAdvanceReceiptDigestV1) before anything in it is trusted — a receipt that does
     * not bind the request it answers is not evidence of anything.
     */
    private suspend fun replayAdvanceAndVerify(
        attemptId: Long,
        taskId: Long,
        verbatimOperationId: String?,
        intentDigest: String
    ): Boolean {
        val coordinator = recoveryCoordinator ?: run {
            aplusPause("no coordinator to advance attempt $attemptId")
            return false
        }
        val task = planRepository.getTask(taskId)
        if (task == null) {
            planRepository.markAplusState(attemptId, "RECOVERY_REQUIRED")
            aplusPause("task $taskId missing for attempt $attemptId — cannot build the advance proof")
            return false
        }
        // R45 (Sol R45 P1-3): the CAS triple is REPLAYED byte-for-byte from the attempt-open
        // anchor — never re-discovered here — and requestDigest is the canonical ADVANCE framing
        // (CanonicalAdvanceDigestV1), NOT the apply intent digest.
        val anchor = planRepository.getAplusAdvanceAnchor(attemptId)
        if (anchor == null) {
            planRepository.markAplusState(attemptId, "RECOVERY_REQUIRED")
            aplusPause("advance anchor missing for attempt $attemptId — cannot build the CAS request (fail-closed)")
            return false
        }
        // §6.7.4a: the lease field is the HISTORICAL reference to where the quota was earned
        // (the release already made it RELEASED before the advance was first dispatched).
        val advanceLease = leaseIdForAttempt(attemptId)
        val baseAdvanceRequest = io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1(
            leaseId = advanceLease,
            idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
            requestDigest = "",
            expectedScheduleId = anchor.first,
            expectedScheduleVersion = anchor.third,
            expectedCurrentItemId = anchor.second,
            completionProof = io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1(
                scheduleItemId = anchor.second,
                trustedSuccessCount = planRepository.trustedCountForTaskPublic(taskId),
                quotaRequired = task.requiredSuccesses,
                ledgerRef = "ledger-$attemptId",
                verifiedAtElapsedRealtimeMs = commitClockMs()
            ),
            callerProtocolVersion = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
        )
        val advanceRequest = baseAdvanceRequest.copy(
            requestDigest = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1.compute(baseAdvanceRequest)
        )
        planRepository.markAplusState(attemptId, "ADVANCE_PENDING")
        val advanceReceipt = coordinator.executorBackend().completeAndAdvance(advanceRequest, intentDigest)
        if (advanceReceipt == null) {
            // Fail-closed: the provider could not prove the advance — the quota is committed
            // locally but the schedule did NOT move. Pause for operator visibility (§6.7.3).
            planRepository.markAplusState(attemptId, "RECOVERY_REQUIRED")
            aplusPause("completeAndAdvance not proven for attempt $attemptId — schedule did not advance")
            return false
        }
        // R46 (Sol R46 P1-2): recompute the receipt digest — it must bind THIS request's
        // (requestDigest, idempotencyKey) together with the outcome the provider claims.
        val expectedReceiptDigest = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1.compute(
            advanceReceipt, advanceRequest.requestDigest, advanceRequest.idempotencyKey
        )
        if (advanceReceipt.receiptDigest != expectedReceiptDigest) {
            planRepository.markAplusState(attemptId, "RECOVERY_REQUIRED")
            aplusPause("advance receipt digest mismatch for attempt $attemptId — the receipt does not bind this request")
            return false
        }
        // R45 (Sol R45 P1-5 / §6.7.5): the receipt is the provider's SELF-DESCRIPTION, not proof
        // the environment moved. Independent verification is mandatory — non-terminal: observe()
        // four legs; terminal (exhausted): a fresh discover() readback with the v1.55 non-null
        // group precondition then its own four legs.
        val exhausted = advanceReceipt.advancedToItemId == null
        if (!exhausted) {
            planRepository.markAplusState(attemptId, "ADVANCE_OBSERVING")
            // The observe tuple's operationId: the VERBATIM apply receipt first (the engine owns
            // this attempt's apply result on the normal path), the durable Room receipt as the
            // recovery-shaped fallback.
            val operationId = verbatimOperationId ?: planRepository.getApplyOperationId(attemptId)
            if (operationId == null) {
                planRepository.markAplusState(attemptId, "RECOVERY_REQUIRED")
                aplusPause("apply operationId missing for attempt $attemptId — cannot observe the new environment")
                return false
            }
            val observed = coordinator.executorBackend().observe(
                advanceLease, operationId, advanceReceipt.effectiveIntentHash
            )
            // P1-3 (Sol Issue #19/#20 R2): typed reason identifies WHICH leg failed independently.
            // Each leg is verified separately so the failure reason names the exact mismatch —
            // a generic "four-leg" message hides which verification surface is broken.
            val mismatchLeg: String? = when {
                observed == null -> "OBSERVE_NULL"
                observed.scheduleItemId != advanceReceipt.advancedToItemId -> "scheduleItemId"
                observed.scheduleVersion != advanceReceipt.scheduleVersionAfter -> "scheduleVersion"
                observed.acceptedIntentHash != advanceReceipt.effectiveIntentHash -> "acceptedIntentHash"
                observed.environmentRevision != advanceReceipt.effectiveEnvironmentRevision -> "environmentRevision"
                else -> null
            }
            if (mismatchLeg != null) {
                planRepository.markRecoveryRequired(attemptId, "OBSERVED_TUPLE_MISMATCH:$mismatchLeg")
                aplusPause("post-advance observe mismatch for attempt $attemptId — leg $mismatchLeg does not match receipt (OBSERVED_TUPLE_MISMATCH)")
                return false
            }
        } else {
            planRepository.markAplusState(attemptId, "ADVANCE_STATE_READBACK")
            val readback = coordinator.executorBackend().discover()
            // P1-3 (Sol Issue #19/#20 R2): typed reason for exhausted readback mismatch.
            val readbackMismatchLeg: String? = when {
                readback == null -> "READBACK_NULL"
                readback.currentScheduleId == null -> "currentScheduleId_null"
                readback.currentItemId == null -> "currentItemId_null"
                readback.scheduleVersion == null -> "scheduleVersion_null"
                readback.exhausted == null -> "exhausted_null"
                readback.currentScheduleId != anchor.first -> "currentScheduleId"
                readback.currentItemId != advanceReceipt.advancedFromItemId -> "currentItemId"
                readback.scheduleVersion != advanceReceipt.scheduleVersionAfter -> "scheduleVersion"
                readback.exhausted != true -> "exhausted"
                else -> null
            }
            if (readbackMismatchLeg != null) {
                planRepository.markRecoveryRequired(attemptId, "READBACK_TUPLE_MISMATCH:$readbackMismatchLeg")
                aplusPause("exhausted readback mismatch for attempt $attemptId — leg $readbackMismatchLeg (READBACK_TUPLE_MISMATCH)")
                return false
            }
        }
        return true
    }

    /**
     * R45 (Sol R45 P1-4 / §6.7.4a): the release leg ALONE — BEGIN_RELEASE → RELEASE_PENDING →
     * durable release receipt → RELEASED. Returns false (RECOVERY_REQUIRED + pause) when the
     * release cannot be proven; the quota-reached caller advances + verifies BETWEEN the
     * RELEASED and CLOSED phases (the frozen order: release FIRST, then advance).
     */
    private suspend fun aplusReleaseLease(attemptId: Long): Boolean {
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
        planRepository.markAplusState(attemptId, "RELEASED")
        return true
    }

    /** R45: the finalize leg alone — CLOSED + the trusted-success terminal projection. */
    private suspend fun aplusFinalize(
        attemptId: Long,
        taskId: Long,
        endedAt: Long,
        webScore: Double?,
        videoScore: Double?
    ): Boolean {
        planRepository.markAplusState(attemptId, "CLOSED")
        planRepository.finalizeAplusSuccess(attemptId, taskId, endedAt, webScore, videoScore)
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
