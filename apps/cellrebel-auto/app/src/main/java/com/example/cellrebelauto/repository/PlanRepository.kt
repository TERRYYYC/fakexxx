package com.example.cellrebelauto.repository

import androidx.room.withTransaction
import com.example.cellrebelauto.automation.plan.PlanScheduler
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.db.TaskAttemptCount
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.environment.TrustPolicy
import com.example.cellrebelauto.environment.hasStructurallyValidEffectiveCoordinates
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.audit.AutoAuditEvent
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord
import com.example.cellrebelauto.model.plan.AttemptWithTask
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.model.plan.WorklistRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Plan-level repository (O1–O4 data owner). Wraps the plan/task/attempt/session
 * DAOs and hosts the transactional success finalization (INV-3): attempt row +
 * guarded task increment atomically, idempotent against stale expected values.
 * # 计划级仓库：封装计划/任务/尝试/会话 DAO，
 * # 并承载成功收尾事务（INV-3）：尝试行 + 守卫式任务自增原子完成，幂等
 */
class PlanRepository(private val db: AppDatabase) {

    // ---- Reads ----

    suspend fun getPlan(planId: Long): LocationPlan? = db.planDao().getPlanById(planId)

    suspend fun getTasks(planId: Long): List<LocationTask> = db.locationTaskDao().getTasksForPlan(planId)

    suspend fun getTask(taskId: Long): LocationTask? = db.locationTaskDao().getTaskById(taskId)

    // ---- Trusted selection (M-MG-02, Issue #5 Task 4 area 6) ----

    /**
     * Trusted-aware address selection (M-MG-02). The next task to attempt is the first in execution
     * order whose TRUSTED quota — `count(trusted_quota_entries where taskId=…)` — has NOT reached
     * [LocationTask.requiredSuccesses]; NOT the legacy v4 `completedSuccesses` counter, which carries
     * no A+ evidence chain and must not decide selection/completion (INV-05/06, M-MG-02). GREEN wires
     * `AutomationEngine.run()` to this instead of [PlanScheduler.selectNext].
     *
     * GREEN semantics:
     *  1. NORMALIZE: any task whose trusted count has reached required (regardless of status) is
     *     flipped to completed via the trusted SQL — idempotent, the F5-equivalent projection.
     *  2. SELECT: the first ACTIVE task in execution order that is trusted-INCOMPLETE **and**
     *     (counter-incomplete OR not yet attempted by THIS run). The second clause reconciles M-MG-02
     *     with legacy retry semantics: a counter-full/trusted-empty task MUST be re-attempted at least
     *     once per run (never silently skipped on the counter), but a legacy run that already produced
     *     its own attempts stops instead of looping forever — only a trusted mint can complete a task,
     *     and legacy attempts mint nothing (§7.3). In A+ mode the counter never advances, so A+ retry
     *     semantics (INV-2) are unchanged.
     *  3. Fall back to the first PENDING task (a pending counter-full/trusted-empty task is likewise
     *     never skipped — M-MG-01/M-MG-02 migration semantics).
     *
     * The trusted count is read from the REAL projection ([TrustedQuotaDao.trustedCountForTask]) —
     * never a test-supplied map — so a bad impl cannot green it by painting an isolated helper.
     */
    suspend fun selectNextTrustedTask(planId: Long, attemptedTaskIds: Set<Long> = emptySet()): LocationTask? {
        val tasks = db.locationTaskDao().getTasksForPlan(planId)
        // 1. Normalize trusted-complete statuses (idempotent projection flip).
        for (t in tasks) {
            if (t.status != "completed" &&
                db.trustedQuotaDao().trustedCountForTask(t.id) >= t.requiredSuccesses
            ) {
                db.locationTaskDao().completeTaskIfQuotaReached(t.id)
            }
        }
        val refreshed = db.locationTaskDao().getTasksForPlan(planId)
        val ordered = PlanScheduler.executionOrder(refreshed)
        // 2. Active + trusted-incomplete, reconciled with legacy retry semantics.
        val active = ordered.firstOrNull { task ->
            task.status == "active" &&
                db.trustedQuotaDao().trustedCountForTask(task.id) < task.requiredSuccesses &&
                (task.completedSuccesses < task.requiredSuccesses || task.id !in attemptedTaskIds)
        }
        if (active != null) return active
        // 3. Pending fallback.
        return ordered.firstOrNull { it.status == "pending" }
    }

    suspend fun countAttemptsForTask(taskId: Long): Int = db.testAttemptDao().countAttemptsForTask(taskId)

    // # 最近一次终态尝试的 endedAt（缓冲投影，INV-5）
    suspend fun latestTerminalAttemptEndedAt(planId: Long): Long? =
        db.testAttemptDao().getLatestTerminalAttemptEndedAtForPlan(planId)

    // ---- Plan screen reads (observable) ----

    // # 观察最近导入的计划
    fun observeLatestPlan(): Flow<LocationPlan?> = db.planDao().observeLatestPlan()

    // # 观察某计划的任务列表（DAO 已按执行顺序排序，INV-1）
    fun observeTasks(planId: Long): Flow<List<LocationTask>> =
        db.locationTaskDao().observeTasksForPlan(planId)

    // # 观察某计划每个任务的尝试总数
    fun observeAttemptCounts(planId: Long): Flow<List<TaskAttemptCount>> =
        db.testAttemptDao().observeAttemptCountsForPlan(planId)

    // ---- History / export (AC-C3, INV-8) ----

    /**
     * Legacy v2 test_results rows for export (C1). The migration deliberately
     * keeps them; they must not silently vanish from operator surfaces.
     * # v2 遗留 test_results 行（导出用，C1）：迁移故意保留，不得静默消失
     */
    suspend fun getLegacyResultsForExport(): List<com.example.cellrebelauto.model.TestResult> =
        db.testResultDao().getAllResultsForExport()

    // # v2 遗留行（History 页分区展示，C1）
    fun observeLegacyResults(): Flow<List<com.example.cellrebelauto.model.TestResult>> =
        db.testResultDao().getAllResults()

    /**
     * All attempts joined with task plan context, chronological (export).
     * # 全量尝试联接任务计划上下文，按时间升序（导出用）
     */
    suspend fun getAllAttemptsWithTasks(): List<AttemptWithTask> =
        joinAttemptsWithTasks(
            db.testAttemptDao().getAllAttempts(),
            db.locationTaskDao().getAllTasks()
        )

    /**
     * Observable attempts joined with task plan context, newest first (History).
     * # 可观察的尝试联接（History 页，最新在前）
     */
    fun observeAttemptsWithTasks(): Flow<List<AttemptWithTask>> =
        combine(
            db.testAttemptDao().observeAllAttempts(),
            db.locationTaskDao().observeAllTasks()
        ) { attempts, tasks ->
            joinAttemptsWithTasks(attempts, tasks)
        }

    // # plan_row = 任务在其计划执行顺序中的 1 起始序号（INV-1 投影）
    private fun joinAttemptsWithTasks(
        attempts: List<TestAttempt>,
        tasks: List<LocationTask>
    ): List<AttemptWithTask> {
        val planRowByTaskId = tasks.groupBy { it.planId }
            .flatMap { (_, planTasks) ->
                PlanScheduler.executionOrder(planTasks)
                    .mapIndexed { index, task -> task.id to index + 1 }
            }
            .toMap()
        val taskById = tasks.associateBy { it.id }
        return attempts.mapNotNull { attempt ->
            val task = taskById[attempt.taskId] ?: return@mapNotNull null
            AttemptWithTask(
                attempt = attempt,
                planRow = planRowByTaskId[attempt.taskId] ?: 0,
                csvRow = task.csvRow,
                priority = task.priority,
                requiredSuccesses = task.requiredSuccesses
            )
        }
    }

    // ---- Import (atomic, AC-A2) ----

    /**
     * Syncs the buffer into the plan snapshot the engine executes (F6).
     * Allowed only while the plan is unstarted (all tasks pending, no attempts);
     * returns false otherwise (field is then next-plan-only).
     * # 把 buffer 同步进 engine 执行的 plan 快照（F6）：
     * # 仅计划未启动（全部 pending 且无尝试）时生效，否则返回 false
     */
    suspend fun syncBufferIfPlanNotStarted(planId: Long, seconds: Int): Boolean = db.withTransaction {
        val started = db.locationTaskDao().getTasksForPlan(planId).any { it.status != "pending" } ||
            db.testAttemptDao().getAttemptsForPlan(planId).isNotEmpty()
        if (started) return@withTransaction false
        db.planDao().updateGlobalBuffer(planId, seconds)
        true
    }

    /**
     * Persists a validated worklist as plan + tasks in ONE transaction.
     * Callers must run WorklistParser first and pass only Success rows;
     * the unfinished-plan rejection is a UI/policy concern handled before this.
     * # 将已校验的清单原子落库（计划行 + 全部任务行，同一事务）。
     * # 调用方须先过 WorklistParser；未完成计划的拒绝策略在 UI 层处理
     */
    suspend fun importPlan(
        sourceFileName: String,
        globalBufferSeconds: Int,
        rows: List<WorklistRow>,
        importedAt: Long
    ): Long = db.planDao().insertPlanWithTasks(
        LocationPlan(
            sourceFileName = sourceFileName,
            importedAt = importedAt,
            globalBufferSeconds = globalBufferSeconds,
            totalRows = rows.size,
            totalRequiredSuccesses = rows.sumOf { it.requiredSuccesses }
        ),
        rows.map {
            LocationTask(
                planId = 0, // # 由 insertPlanWithTasks 回填
                csvRow = it.csvRow,
                longitude = it.longitude,
                latitude = it.latitude,
                priority = it.priority,
                requiredSuccesses = it.requiredSuccesses
            )
        }
    )

    // ---- Recovery (INV-9 / O4) ----

    suspend fun markNonTerminalInterrupted(nowMs: Long): Int =
        db.testAttemptDao().markNonTerminalInterrupted(nowMs)

    suspend fun markStaleSessionsInterrupted(nowMs: Long): Int =
        db.runSessionDao().markStaleRunningSessionsInterrupted(nowMs)

    // # A+ 恢复后全局 sweep 排除当前 owner session（Sol round-9 P1-4）
    suspend fun markStaleSessionsInterruptedExcept(nowMs: Long, excludeId: Long): Int =
        db.runSessionDao().markStaleSessionsInterruptedExcept(nowMs, excludeId)

    // ---- A+ recovery (R9, §8.2 RECOVERING) ----

    /**
     * A+ crash-recovery candidates: non-terminal attempts that entered the A+ lifecycle
     * (`aplusState` non-null) — their persisted current operation (phase + leaseId) is the ONLY
     * authority the recovery branches on (§7.1: the Attempt owns its 当前 operation; `AutoAuditEvent`
     * is append-only, never a state owner — Sol round-8 P1-3). READ-ONLY seam.
     */
    suspend fun findAPlusRecoverableAttempts(planId: Long): List<TestAttempt> =
        db.testAttemptDao().findAplusRecoverableAttempts(planId)

    /** The active (running) session the recovery supersedes rather than duplicating (Sol round-8 P1-6). */
    suspend fun findActiveRunSession(planId: Long): RunSession? =
        db.runSessionDao().findActiveRunningSession(planId)

    // # A+ 恢复态投影（§8.2）：持久化 RECOVERING/PAUSED/RUNNING
    suspend fun markSessionStatus(sessionId: Long, status: String) =
        db.runSessionDao().updateStatus(sessionId, status)

    // # 可信完成投影（§7.3）：任务完成 = 可信计数达成；GREEN 由 F3 可信 SQL 承载
    suspend fun completeTaskIfQuotaReached(taskId: Long): Int =
        db.locationTaskDao().completeTaskIfQuotaReached(taskId)

    // # A+ 当前操作持久化（§7.1）：阶段 + lease（Sol round-8 P1-3/P1-4）
    suspend fun markAplusState(attemptId: Long, aplusState: String) =
        db.testAttemptDao().markAplusState(attemptId, aplusState)

    /** Atomically mark RECOVERY_REQUIRED with durable typed reason (Sol R2 P1-3). */
    suspend fun markRecoveryRequired(attemptId: Long, reason: String) =
        db.testAttemptDao().markRecoveryRequired(attemptId, reason)

    /**
     * Persist the terminal intent before release. If the process dies after RELEASED but before the
     * terminal projection, recovery can still close with the original typed reason instead of
     * silently downgrading it to interrupted.
     */
    suspend fun markFailureContinuationAndReleasePending(attemptId: Long, reason: String) {
        val updated = db.testAttemptDao().markFailureContinuationAndReleasePending(attemptId, reason)
        check(updated == 1) {
            "FAILURE_RELEASE_BOUNDARY_OWNER_INVALID: attempt=$attemptId"
        }
    }

    /**
     * Publish the RELEASE_RECEIPT audit edge and RELEASED owner checkpoint in one Room transaction.
     * An old crash window may already contain the exact audit while the owner is RELEASE_PENDING;
     * that exact legacy replay is repaired without appending a duplicate. Any divergent/duplicate
     * provenance is an invariant break.
     */
    suspend fun commitReleaseReceiptCheckpoint(attemptId: Long, recordedAt: Long) = db.withTransaction {
        val attempt = db.testAttemptDao().getAttemptById(attemptId)
            ?: throw IllegalStateException("RELEASE_CHECKPOINT_ATTEMPT_MISSING: attempt=$attemptId")
        if (attempt.aplusState != "RELEASE_PENDING") {
            throw IllegalStateException(
                "RELEASE_CHECKPOINT_OWNER_INVALID: attempt=$attemptId phase=${attempt.aplusState}"
            )
        }
        val expectedPayload = "RELEASE_PENDING->CLOSED"
        val existing = db.auditEventDao().forAttempt(attemptId)
            .filter { it.eventType == "RELEASE_RECEIPT" }
        when {
            existing.isEmpty() -> db.auditEventDao().insert(
                AutoAuditEvent(
                    seq = db.auditEventDao().count().toLong() + 1L,
                    attemptId = attemptId,
                    correlationRef = null,
                    eventType = "RELEASE_RECEIPT",
                    payloadDigest = expectedPayload,
                    recordedAt = recordedAt
                )
            )
            existing.size == 1 && existing.single().payloadDigest == expectedPayload -> Unit
            else -> throw IllegalStateException(
                "RELEASE_CHECKPOINT_AUDIT_CONFLICT: attempt=$attemptId count=${existing.size}"
            )
        }
        check(db.testAttemptDao().markReleasedFromPending(attemptId) == 1) {
            "RELEASE_CHECKPOINT_OWNER_UPDATE_FAILED: attempt=$attemptId"
        }
    }

    suspend fun markAplusLease(attemptId: Long, leaseId: String) =
        db.testAttemptDao().markAplusLease(attemptId, leaseId)

    suspend fun getAplusLeaseId(attemptId: Long): String? =
        db.testAttemptDao().getAplusLeaseId(attemptId)

    // ---- R45 (Sol R45 P1-3): the advance CAS anchor (spec §4.3 step 1 — persist before external execution) ----

    suspend fun markAplusAdvanceAnchor(attemptId: Long, scheduleId: String, itemId: String, version: Long) =
        db.testAttemptDao().markAplusAdvanceAnchor(attemptId, scheduleId, itemId, version)

    /** The anchored triple, or null when any leg is absent (fail-closed — never invent a CAS precondition). */
    suspend fun getAplusAdvanceAnchor(attemptId: Long): Triple<String, String, Long>? {
        val a = db.testAttemptDao().getAplusAdvanceAnchor(attemptId)
        return if (a?.aplusAnchorScheduleId == null || a.aplusAnchorItemId == null || a.aplusAnchorVersion == null) null
        else Triple(a.aplusAnchorScheduleId!!, a.aplusAnchorItemId!!, a.aplusAnchorVersion!!)
    }

    /** The apply-receipt operationId (ObserveRequest tuple leg; the post-advance observe is lease-bound). */
    suspend fun getApplyOperationId(attemptId: Long): String? =
        db.testAttemptDao().getOperationIdForIdempotencyKey(
            com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(attemptId)
        )

    // # R37 (Sol R36 P1-2): current execution owner persist/read
    suspend fun markCurrentExecutionId(attemptId: Long, executionId: String) =
        db.testAttemptDao().markCurrentExecutionId(attemptId, executionId)

    suspend fun getCurrentExecutionId(attemptId: Long): String? =
        db.testAttemptDao().getCurrentExecutionId(attemptId)

    // # R37 (Sol R36 P1-1): durable observation + receipt persist/read
    // One immutable storage authority: every engine phase-boundary write converges here. INSERT
    // IGNORE alone would create two truths (live vs durable), so every replay reads the winning raw
    // record back and requires exact payload equality after signed-zero canonicalization.
    suspend fun persistObservation(
        attemptId: Long,
        phase: String,
        snapshot: com.example.cellrebelauto.environment.ObservationSnapshot
    ): com.example.cellrebelauto.environment.ObservationSnapshot = db.withTransaction {
        persistObservationInTransaction(attemptId, phase, snapshot)
    }

    /**
     * Atomically commit the observation carrier and its §8.1 owner phase. A process death can see
     * either the old phase without the row or the new phase with the row, never
     * `ENV_APPLIED + durable PRE` created by two separate commits.
     */
    suspend fun persistObservationAndMarkAplusState(
        attemptId: Long,
        phase: String,
        snapshot: com.example.cellrebelauto.environment.ObservationSnapshot,
        aplusState: String
    ): com.example.cellrebelauto.environment.ObservationSnapshot = db.withTransaction {
        val (requiredOwnerState, requiredNextState) = when (phase) {
            "PRE" -> "ENV_APPLIED" to "PRE_OBSERVED"
            else -> throw IllegalStateException(
                "OBSERVATION_OWNER_PHASE_UNSUPPORTED: attempt=$attemptId phase=$phase"
            )
        }
        val attempt = db.testAttemptDao().getAttemptById(attemptId)
            ?: throw IllegalStateException(
                "OBSERVATION_OWNER_ATTEMPT_MISSING: attempt=$attemptId phase=$phase"
            )
        if (attempt.aplusState != requiredOwnerState || aplusState != requiredNextState) {
            throw IllegalStateException(
                "OBSERVATION_OWNER_STATE_INVALID: attempt=$attemptId phase=$phase " +
                    "owner=${attempt.aplusState} required=$requiredOwnerState next=$aplusState " +
                    "requiredNext=$requiredNextState"
            )
        }
        val durable = persistObservationInTransaction(attemptId, phase, snapshot)
        db.testAttemptDao().markAplusState(attemptId, aplusState)
        durable
    }

    /**
     * Atomically publish the complete decision bundle. `DECIDING` is unreachable unless both the
     * immutable POST carrier and immutable completion receipt are durable in the same transaction.
     * A crash before commit remains `POST_OBSERVE_PENDING`; a crash after commit can re-decide from
     * the complete durable bundle (M-CR-05/M-CR-06).
     */
    suspend fun persistDecisionBundleAndEnterDeciding(
        attemptId: Long,
        postObservation: com.example.cellrebelauto.environment.ObservationSnapshot,
        completionEvidenceWire: Int,
        acceptedIntentHash: String,
        leaseId: String
    ): Pair<
        com.example.cellrebelauto.environment.ObservationSnapshot,
        com.example.cellrebelauto.model.ledger.DurableCompletionReceipt
    > = db.withTransaction {
        requireOwnedExecutionInTransaction(
            attemptId,
            allowedPhases = setOf("POST_OBSERVE_PENDING", "DECIDING")
        )
        db.durableObservationDao().forAttemptPhase(attemptId, "PRE")
            ?: throw IllegalStateException(
                "DECISION_PRE_MISSING: attempt=$attemptId cannot enter DECIDING"
            )
        val durablePost = persistObservationInTransaction(attemptId, "POST", postObservation)
        val durableReceipt = persistCompletionReceiptInTransaction(
            attemptId, completionEvidenceWire, acceptedIntentHash, leaseId
        )
        db.testAttemptDao().markAplusState(attemptId, "DECIDING")
        durablePost to durableReceipt
    }

    private suspend fun persistObservationInTransaction(
        attemptId: Long,
        phase: String,
        snapshot: com.example.cellrebelauto.environment.ObservationSnapshot
    ): com.example.cellrebelauto.environment.ObservationSnapshot {
        require(snapshot.hasStructurallyValidEffectiveCoordinates()) {
            "DURABLE_OBSERVATION_INVALID_COORDINATES: attempt=$attemptId phase=$phase"
        }
        val canonical = snapshot.canonicalSignedZero()
        val candidate = com.example.cellrebelauto.model.ledger.DurableObservationRecord(
            attemptId = attemptId, phase = phase,
            leaseId = canonical.leaseId,
            acceptedIntentHash = canonical.acceptedIntentHash,
            coverage = canonical.coverage,
            verificationLevel = canonical.verificationLevel,
            deliveryMode = canonical.deliveryMode,
            isMock = canonical.isMock,
            scheduleDecision = canonical.scheduleDecision,
            effectiveLat = canonical.effectiveLat,
            effectiveLng = canonical.effectiveLng,
            environmentRevision = canonical.environmentRevision,
            environmentFingerprint = canonical.environmentFingerprint,
            observedAtElapsedRealtimeMs = canonical.observedAtElapsedRealtimeMs,
            observedAtEpochMs = canonical.observedAtEpochMs,
            continuitySinceElapsedRealtimeMs = canonical.continuitySinceElapsedRealtimeMs,
            continuitySinceEpochMs = canonical.continuitySinceEpochMs,
            evidenceRefsJson = org.json.JSONArray(canonical.evidenceRefs).toString(),
            evidenceRefs = canonical.evidenceRefs.joinToString(";")
        )
        db.durableObservationDao().insertIfAbsent(candidate)
        val winner = db.durableObservationDao().forAttemptPhase(attemptId, phase)
            ?: throw IllegalStateException(
                "DURABLE_OBSERVATION_MISSING: no row after insert for attempt=$attemptId phase=$phase"
            )
        if (winner.copy(id = 0).canonicalSignedZero() != candidate) {
            throw IllegalStateException(
                "DURABLE_OBSERVATION_CONFLICT: immutable replay mismatch for " +
                    "attempt=$attemptId phase=$phase"
            )
        }
        return observationSnapshotFromRecord(winner)
    }

    /**
     * SQLite REAL canonicalizes signed zero. No other payload is normalized: null/non-finite/out of
     * range coordinates are rejected before Room so distinct invalid values can never collapse into
     * one SQL NULL replay payload.
     */
    private fun com.example.cellrebelauto.environment.ObservationSnapshot.canonicalSignedZero() = copy(
        effectiveLat = effectiveLat.canonicalSignedZero(),
        effectiveLng = effectiveLng.canonicalSignedZero()
    )

    private fun com.example.cellrebelauto.model.ledger.DurableObservationRecord.canonicalSignedZero() = copy(
        effectiveLat = effectiveLat.canonicalSignedZero(),
        effectiveLng = effectiveLng.canonicalSignedZero()
    )

    private fun Double?.canonicalSignedZero(): Double? = if (this != null && this == 0.0) 0.0 else this

    suspend fun getObservation(attemptId: Long, phase: String): com.example.cellrebelauto.model.ledger.DurableObservationRecord? =
        db.durableObservationDao().forAttemptPhase(attemptId, phase)

    /**
     * R43 GREEN (M-CR-06): reconstruct the §6.4 [ObservationSnapshot] from the durable record —
     * the crash-recovery re-decide reads observations from HERE, never from a live source.
     * `evidenceRefs` is parsed back from the JSON array column (round-trippable).
     */
    suspend fun getObservationSnapshot(attemptId: Long, phase: String): com.example.cellrebelauto.environment.ObservationSnapshot? =
        db.durableObservationDao().forAttemptPhase(attemptId, phase)?.let(::observationSnapshotFromRecord)

    private fun observationSnapshotFromRecord(
        r: com.example.cellrebelauto.model.ledger.DurableObservationRecord
    ): com.example.cellrebelauto.environment.ObservationSnapshot {
        val refs = try {
            val arr = org.json.JSONArray(r.evidenceRefsJson)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            // Legacy semicolon column fallback (pre-JSON rows).
            if (r.evidenceRefs.isBlank()) emptyList() else r.evidenceRefs.split(";")
        }
        return com.example.cellrebelauto.environment.ObservationSnapshot(
            leaseId = r.leaseId,
            acceptedIntentHash = r.acceptedIntentHash,
            coverage = r.coverage,
            verificationLevel = r.verificationLevel,
            deliveryMode = r.deliveryMode,
            isMock = r.isMock,
            scheduleDecision = r.scheduleDecision,
            effectiveLat = r.effectiveLat,
            effectiveLng = r.effectiveLng,
            environmentRevision = r.environmentRevision,
            environmentFingerprint = r.environmentFingerprint,
            observedAtElapsedRealtimeMs = r.observedAtElapsedRealtimeMs,
            observedAtEpochMs = r.observedAtEpochMs,
            continuitySinceEpochMs = r.continuitySinceEpochMs,
            continuitySinceElapsedRealtimeMs = r.continuitySinceElapsedRealtimeMs,
            evidenceRefs = refs
        )
    }

    /** Same immutable replay rule as observations; a conflicting receipt is an invariant break. */
    suspend fun persistCompletionReceipt(
        attemptId: Long,
        wire: Int,
        acceptedIntentHash: String,
        leaseId: String
    ): com.example.cellrebelauto.model.ledger.DurableCompletionReceipt = db.withTransaction {
        persistCompletionReceiptInTransaction(attemptId, wire, acceptedIntentHash, leaseId)
    }

    private suspend fun persistCompletionReceiptInTransaction(
        attemptId: Long,
        wire: Int,
        acceptedIntentHash: String,
        leaseId: String
    ): com.example.cellrebelauto.model.ledger.DurableCompletionReceipt {
        val candidate = com.example.cellrebelauto.model.ledger.DurableCompletionReceipt(
            attemptId = attemptId, completionEvidenceWire = wire,
            acceptedIntentHash = acceptedIntentHash, leaseId = leaseId
        )
        db.durableCompletionReceiptDao().insertIfAbsent(candidate)
        val winner = db.durableCompletionReceiptDao().forAttempt(attemptId)
            ?: throw IllegalStateException(
                "DURABLE_COMPLETION_RECEIPT_MISSING: no row after insert for attempt=$attemptId"
            )
        if (winner.copy(id = 0) != candidate) {
            throw IllegalStateException(
                "DURABLE_COMPLETION_RECEIPT_CONFLICT: immutable replay mismatch for attempt=$attemptId"
            )
        }
        return winner
    }

    suspend fun getCompletionReceipt(attemptId: Long): com.example.cellrebelauto.model.ledger.DurableCompletionReceipt? =
        db.durableCompletionReceiptDao().forAttempt(attemptId)

    /** R43 GREEN (M-CR-06): owner lookup of the current execution row by executionId (never positional). */
    suspend fun getExecutionByExecutionId(executionId: String): com.example.cellrebelauto.model.execution.CellRebelExecution? =
        db.attemptExecutionDao().byExecutionId(executionId)

    /**
     * Persist the immutable §7.1 execution carrier at COMPLETION_OBSERVED. A same-id replay is a
     * no-op only when every stored field agrees; a foreign/different winner fails closed instead of
     * allowing the later decision to evaluate a live payload that lost the storage race.
     */
    suspend fun persistExecutionEvidence(
        executionId: String,
        attemptId: Long,
        completionEvidenceWire: Int,
        evidencePayloadDigest: String,
        startedAtElapsed: Long,
        runningConfirmedAtElapsed: Long,
        completedAtElapsed: Long,
        baselineRunningState: String?,
        runningMarkerText: String?,
        runningDurationMs: Long?,
        webBrowsingScore: Double?,
        videoStreamingScore: Double?,
        roundTimestampsElapsed: String?
    ): CellRebelExecution = db.withTransaction {
        persistExecutionInTransaction(
            CellRebelExecution(
                executionId = executionId,
                attemptId = attemptId,
                completionEvidenceWire = completionEvidenceWire,
                evidencePayloadDigest = evidencePayloadDigest,
                startedAt = startedAtElapsed,
                classifiedAt = completedAtElapsed,
                startedAtElapsed = startedAtElapsed,
                runningConfirmedAtElapsed = runningConfirmedAtElapsed,
                completedAtElapsed = completedAtElapsed,
                baselineRunningState = baselineRunningState,
                runningMarkerText = runningMarkerText,
                runningDurationMs = runningDurationMs,
                webBrowsingScore = webBrowsingScore,
                videoStreamingScore = videoStreamingScore,
                roundTimestampsElapsed = roundTimestampsElapsed
            )
        )
    }

    /**
     * The owner pointer and immutable execution row are a prerequisite of DECIDING. This check is
     * intentionally inside the POST+receipt transaction so a dangling/foreign owner rolls the whole
     * bundle back and leaves POST_OBSERVE_PENDING intact.
     */
    private suspend fun requireOwnedExecutionInTransaction(
        attemptId: Long,
        allowedPhases: Set<String>
    ): CellRebelExecution {
        val attempt = db.testAttemptDao().getAttemptById(attemptId)
            ?: throw IllegalStateException("EXECUTION_OWNER_ATTEMPT_MISSING: attempt=$attemptId")
        if (attempt.aplusState !in allowedPhases) {
            throw IllegalStateException(
                "EXECUTION_OWNER_PHASE_INVALID: attempt=$attemptId phase=${attempt.aplusState} " +
                    "allowed=${allowedPhases.sorted().joinToString("|")}"
            )
        }
        val ownerExecutionId = attempt.currentExecutionId
            ?: throw IllegalStateException("EXECUTION_OWNER_MISSING: attempt=$attemptId")
        val execution = db.attemptExecutionDao().byExecutionId(ownerExecutionId)
            ?: throw IllegalStateException(
                "EXECUTION_OWNER_ROW_MISSING: attempt=$attemptId execution=$ownerExecutionId"
            )
        if (execution.attemptId != attemptId) {
            throw IllegalStateException(
                "EXECUTION_OWNER_FOREIGN: attempt=$attemptId execution=$ownerExecutionId " +
                    "belongsTo=${execution.attemptId}"
            )
        }
        return canonicalExecution(execution)
    }

    /**
     * Re-read the entire immutable DECIDING bundle inside the mint transaction. The repository trust
     * entrypoint must defend its own phase boundary: a caller cannot substitute live PRE/POST or a
     * live receipt, nor call it while the owner is still POST_OBSERVE_PENDING.
     */
    private suspend fun durableDecisionContextInTransaction(
        live: CompletionTrustContext,
        durableExecution: CellRebelExecution
    ): CompletionTrustContext {
        val attemptId = durableExecution.attemptId
        val ownerExecution = requireOwnedExecutionInTransaction(
            attemptId,
            allowedPhases = setOf("DECIDING")
        )
        if (ownerExecution.executionId != durableExecution.executionId) {
            throw IllegalStateException(
                "EXECUTION_OWNER_MISMATCH: attempt=$attemptId " +
                    "owner=${ownerExecution.executionId} candidate=${durableExecution.executionId}"
            )
        }
        val durablePre = db.durableObservationDao().forAttemptPhase(attemptId, "PRE")
            ?.let(::observationSnapshotFromRecord)
            ?: throw IllegalStateException("DECISION_PRE_MISSING: attempt=$attemptId")
        val durablePost = db.durableObservationDao().forAttemptPhase(attemptId, "POST")
            ?.let(::observationSnapshotFromRecord)
            ?: throw IllegalStateException("DECISION_POST_MISSING: attempt=$attemptId")
        val durableReceipt = db.durableCompletionReceiptDao().forAttempt(attemptId)
            ?: throw IllegalStateException("DECISION_RECEIPT_MISSING: attempt=$attemptId")

        if (durablePre != live.preObservation.canonicalSignedZero()) {
            throw IllegalStateException("DECISION_PRE_CONFLICT: attempt=$attemptId")
        }
        if (durablePost != live.postObservation.canonicalSignedZero()) {
            throw IllegalStateException("DECISION_POST_CONFLICT: attempt=$attemptId")
        }
        if (durableReceipt.completionEvidenceWire != live.completionEvidenceWire ||
            durableReceipt.acceptedIntentHash != live.applyReceiptIntentHash ||
            durableReceipt.leaseId != live.applyReceiptLease
        ) {
            throw IllegalStateException("DECISION_RECEIPT_CONFLICT: attempt=$attemptId")
        }
        return live.copy(
            execution = durableExecution,
            completionEvidenceWire = durableReceipt.completionEvidenceWire,
            applyReceiptIntentHash = durableReceipt.acceptedIntentHash,
            applyReceiptLease = durableReceipt.leaseId,
            preObservation = durablePre,
            postObservation = durablePost
        )
    }

    /** INSERT-IGNORE + exact readback equality is the single storage authority for executions. */
    private suspend fun persistExecutionInTransaction(candidate: CellRebelExecution): CellRebelExecution {
        val canonicalCandidate = canonicalExecution(candidate)
        db.attemptExecutionDao().insertIfAbsent(canonicalCandidate)
        val winner = db.attemptExecutionDao().byExecutionId(canonicalCandidate.executionId)
            ?: throw IllegalStateException(
                "DURABLE_EXECUTION_MISSING: execution=${canonicalCandidate.executionId}"
            )
        val canonicalWinner = canonicalExecution(winner)
        if (canonicalWinner.copy(id = 0) != canonicalCandidate.copy(id = 0)) {
            throw IllegalStateException(
                "DURABLE_EXECUTION_CONFLICT: immutable replay mismatch for " +
                    "execution=${canonicalCandidate.executionId}"
            )
        }
        return canonicalWinner
    }

    /** SQLite-safe canonical form: reject non-finite scores; normalize only signed zero. */
    private fun canonicalExecution(execution: CellRebelExecution): CellRebelExecution {
        require(execution.webBrowsingScore == null || execution.webBrowsingScore.isFinite()) {
            "DURABLE_EXECUTION_INVALID_WEB_SCORE: execution=${execution.executionId}"
        }
        require(execution.videoStreamingScore == null || execution.videoStreamingScore.isFinite()) {
            "DURABLE_EXECUTION_INVALID_VIDEO_SCORE: execution=${execution.executionId}"
        }
        fun Double.canonicalSignedZero(): Double = if (this == 0.0) 0.0 else this
        return execution.copy(
            webBrowsingScore = execution.webBrowsingScore?.canonicalSignedZero(),
            videoStreamingScore = execution.videoStreamingScore?.canonicalSignedZero()
        )
    }

    // # 恢复真相载体（Sol round-16 P1-1 / round-18 P1-1）：可信账本 / 未验证记录是 append-only 权威，
    // # 返回 typed entry 以绑定 attempt+task 并检测跨表矛盾，绝不信裸 phase 字符串
    suspend fun getTrustedEntry(attemptId: Long): TrustedQuotaEntry? =
        db.trustedQuotaDao().getByAttempt(attemptId)

    suspend fun getUnverifiedRecord(attemptId: Long): UnverifiedAttemptRecord? =
        db.unverifiedAttemptRecordDao().getByAttempt(attemptId)

    /** R44 (DSF review P1-2): the trusted-count projection for the completeAndAdvance proof. */
    suspend fun trustedCountForTask(taskId: Long): Int =
        db.trustedQuotaDao().trustedCountForTask(taskId)

    // # A+ PASS 终态化（P1-5）：标记 attempt succeeded，successOrdinal 由可信计数投影 1-based（Sol round-9
    // # P1-6：绝不动 legacy completedSuccesses、绝不写 successOrdinal=0）。
    suspend fun finalizeAplusSuccess(
        attemptId: Long,
        taskId: Long,
        endedAt: Long,
        webScore: Double?,
        videoScore: Double?
    ) = closeAplusSuccess(attemptId, taskId, endedAt, webScore, videoScore)

    // ---- Task lifecycle ----

    suspend fun markTaskActive(taskId: Long) = db.locationTaskDao().updateTaskStatus(taskId, "active")

    // # 恢复归一化：满配额但状态未翻转的历史崩溃窗口任务 → completed（F5 兜底）
    suspend fun normalizeQuotaCompletedTasks(): Int =
        db.locationTaskDao().normalizeQuotaCompletedTasks()

    // ---- Attempt lifecycle ----

    suspend fun insertAttempt(attempt: TestAttempt): Long = db.testAttemptDao().insert(attempt)

    // # C2：观察到 RUNNING 的瞬间持久化 starting -> running 迁移（仅自 starting）
    suspend fun markAttemptRunning(attemptId: Long, runningAt: Long) =
        db.testAttemptDao().markRunning(attemptId, runningAt)

    /**
     * Atomic success finalization (INV-3 + F5): guarded task increment, attempt
     * row update, AND quota-reached task completion in ONE Room transaction.
     * Returns false when the expected completedSuccesses is stale (re-run safety).
     * # 原子成功收尾：守卫式自增 + 尝试行更新 + 配额达成即 completed，
     * # 同一事务；期望值过期时返回 false（重跑安全）
     */
    suspend fun finalizeAttemptSuccess(
        attemptId: Long,
        taskId: Long,
        expectedCompletedSuccesses: Int,
        runningObservedAt: Long?,
        endedAt: Long,
        webScore: Double?,
        videoScore: Double?,
        status: String = "succeeded"
    ): Boolean = db.withTransaction {
        val incremented = db.locationTaskDao()
            .incrementSuccessIfCurrent(taskId, expectedCompletedSuccesses)
        if (incremented == 0) return@withTransaction false
        db.testAttemptDao().markSucceeded(
            attemptId = attemptId,
            successOrdinal = expectedCompletedSuccesses + 1,
            runningObservedAt = runningObservedAt,
            endedAt = endedAt,
            webScore = webScore,
            videoScore = videoScore,
            status = status
        )
        // # F5：配额达成 → completed 并入本事务，不留崩溃窗口
        db.locationTaskDao().completeTaskIfQuotaReached(taskId)
        true
    }

    /**
     * Persist a failed attempt with typed reason (INV-4/10); never touches quota.
     * # 持久化失败尝试（带类型化原因），绝不动配额
     */
    suspend fun finalizeAttemptFailure(attemptId: Long, reason: String, endedAt: Long) =
        db.testAttemptDao().markFailed(attemptId, reason, endedAt)

    // # 停止/取消：仅在途尝试仍为非终态时标记 interrupted
    suspend fun markAttemptInterruptedIfNonTerminal(attemptId: Long, nowMs: Long) =
        db.testAttemptDao().markInterruptedIfNonTerminal(attemptId, nowMs)

    /**
     * Recovery-only terminal projection after the exact lease release receipt is durable. The owner
     * phase and terminal attempt row commit together, so recovery never advertises CLOSED with a
     * still-nonterminal attempt (or vice versa).
     */
    suspend fun closeRecoveredAttempt(
        attemptId: Long,
        endedAt: Long,
        failureReason: String?
    ) = db.withTransaction {
        db.testAttemptDao().markAplusState(attemptId, "CLOSED")
        if (failureReason == null) {
            db.testAttemptDao().markInterruptedIfNonTerminal(attemptId, endedAt)
        } else {
            db.testAttemptDao().markFailed(attemptId, failureReason, endedAt)
        }
    }

    /** Close the A+ owner and project trusted success in one transaction. */
    suspend fun closeAplusSuccess(
        attemptId: Long,
        taskId: Long,
        endedAt: Long,
        webScore: Double?,
        videoScore: Double?
    ) = db.withTransaction {
        db.testAttemptDao().markAplusState(attemptId, "CLOSED")
        db.testAttemptDao().markSucceeded(
            attemptId = attemptId,
            successOrdinal = db.trustedQuotaDao().trustedCountForTask(taskId),
            runningObservedAt = null,
            endedAt = endedAt,
            webScore = webScore,
            videoScore = videoScore,
            status = "succeeded"
        )
    }

    /** Close the A+ owner and project a typed failure in one transaction. */
    suspend fun closeAplusFailure(attemptId: Long, reason: String, endedAt: Long) =
        db.withTransaction {
            db.testAttemptDao().markAplusState(attemptId, "CLOSED")
            db.testAttemptDao().markFailed(attemptId, reason, endedAt)
        }

    // ---- Trusted completion persistence (R4-F1, §11.2 / §11.4) ----

    /**
     * Production trust-gated completion entrypoint (Issue #5 R4-F1, §11.2 / §11.4).
     *
     * THE production method that, given a classified completion bound to pre/post observations,
     * persists the [com.example.cellrebelauto.model.execution.CellRebelExecution] evidence row AND
     * mints a [com.example.cellrebelauto.model.ledger.TrustedQuotaEntry] when [TrustPolicy] admits it
     * (§8.1 DECIDING → QUOTA_COMMITTED). Today NO production path persists a CellRebelExecution or
     * mints trusted quota — completion goes through the legacy counter in [finalizeAttemptSuccess],
     * which a digest-only / false-oracle impl can green while the A+ evidence chain is lost (§11.1
     * legacy hold-out; `AttemptExecutionDao.insert` has zero production call sites).
     *
     * SKELETON (pre-freeze, §11.4 — GREEN body frozen pending contract-v1 freeze #3):
     *  (1) persists ONLY digest + the three §6.4.2 elapsed clocks, DROPPING the §7.1 evidence detail
     *      (baseline state / marker text / RUNNING duration / both scores / per-round timestamps);
      *  (2) evaluates [TrustPolicy] for the returned decision but MINTS NOTHING — even on PASS.
      *
      * GREEN (contract v1 frozen): (1) persists the FULL §7.1 evidence detail verbatim from
      * [CompletionTrustContext.execution]; (2) evaluates [TrustPolicy]; on PASS mints EXACTLY ONE
      * [TrustedQuotaEntry] in the same transaction — with `taskId` resolved by a REAL DB lookup
      * (attempt → task, never a constant), `evidenceDigest` bound to the execution's payload digest,
      * and `committedAt` bound to the injected monotonic commit clock; on FAIL writes the exact
      * [UnverifiedAttemptRecord] carrier (typed reason + digest, never a synthesized discard).
      *
      * Drives the REAL Room `cellrebel_executions` + `trusted_quota_entries` tables — no isolated helper.
      *
      * # 生产信任收尾入口（GREEN）：全 §7.1 证据持久化；PASS 铸币（taskId 真查库、committedAt=注入时钟）；FAIL 写未验证记录
      */
    suspend fun recordTrustedCompletion(ctx: CompletionTrustContext): TrustDecision =
        recordTrustedCompletion(ctx, 0L) // deprecated one-arg: test-only fallback; production must use two-arg

    /**
     * R38 (Sol R37 P2-4): canonical production entrypoint with injected monotonic commit clock.
     * Both the one-arg overload and AutomationEngine delegate through HERE.
     * The committedAt of the minted TrustedQuotaEntry MUST bind this caller-injected clock,
     * never a default constant or execution.completedAtElapsed.
     */
    suspend fun recordTrustedCompletion(ctx: CompletionTrustContext, commitClockMs: Long): TrustDecision = db.withTransaction {
        // (1) Persist/read back the FULL §7.1 carrier. INSERT IGNORE is only an idempotency primitive:
        // exact readback equality decides whether the live candidate is the same immutable execution.
        val durableExecution = persistExecutionInTransaction(ctx.execution)
        // Attribution: resolve attemptId → taskId by REAL DB lookup (never a constant, R5-F1).
        // An UNRESOLVABLE attempt cannot be attributed to any task — fail-closed: NO mint, NO
        // unverified record (neither can be bound to a nonexistent attempt); the persisted
        // execution row itself is the audit trail. Decision stays FAIL in that case.
        val attempt = db.testAttemptDao().getAttemptById(durableExecution.attemptId)
        val durableCtx = if (attempt == null) {
            ctx.copy(execution = durableExecution)
        } else {
            durableDecisionContextInTransaction(ctx, durableExecution)
        }
        // (2) Evaluate only the complete durable winner bundle, never a live payload that may have
        // lost an execution/observation/receipt race.
        val decision = TrustPolicy().evaluate(durableCtx)
        val existingTrusted = attempt?.let {
            db.trustedQuotaDao().getByAttempt(durableExecution.attemptId)
        }
        val existingUnverified = attempt?.let {
            db.unverifiedAttemptRecordDao().getByAttempt(durableExecution.attemptId)
        }
        if (existingTrusted != null && existingUnverified != null) {
            throw IllegalStateException(
                "DECISION_CARRIER_CONFLICT: attempt=${durableExecution.attemptId} has both carriers"
            )
        }
        if (decision == TrustDecision.PASS && existingUnverified != null) {
            throw IllegalStateException(
                "DECISION_CARRIER_CONFLICT: trusted decision for attempt=${durableExecution.attemptId} " +
                    "contradicts durable unverified carrier"
            )
        }
        if (decision == TrustDecision.FAIL && existingTrusted != null) {
            throw IllegalStateException(
                "DECISION_CARRIER_CONFLICT: unverified decision for attempt=${durableExecution.attemptId} " +
                    "contradicts durable trusted carrier"
            )
        }
        if (decision == TrustDecision.PASS && attempt != null) {
            // Recovery-safe (P1-2 Sol Issue #19/#20): the DECIDING crash window (ledger committed
            // but phase string not yet persisted as QUOTA_COMMITTED) causes redecideDecidingAttempt
            // to re-invoke this method. If the prior mint survived the crash, insertIfAbsent is a
            // no-op — the trusted count projection is idempotent (it counts existing rows).
            // Using plain insert() here would ABORT the entire recovery transaction and leave the
            // attempt stuck at DECIDING forever.
            //
            // Sol R2 P1-2: after IGNORE conflict, read back existing row and verify immutable field
            // equality. A corrupted row (wrong taskId/evidenceDigest) must fail-closed, never be
            // silently accepted. The IGNORE itself is correct (prevents ABORT), but we must validate
            // that the existing row is the SAME mint, not a corrupted/different one.
            val inserted = db.trustedQuotaDao().insertIfAbsent(
                TrustedQuotaEntry(
                    attemptId = durableExecution.attemptId,
                    taskId = attempt.taskId,
                    evidenceDigest = durableExecution.evidencePayloadDigest,
                    committedAt = commitClockMs
                )
            )
            if (inserted == -1L) {
                // IGNORE fired: row already exists. Read back and verify immutable fields match.
                val existing = db.trustedQuotaDao().getByAttempt(durableExecution.attemptId)
                if (existing == null || existing.taskId != attempt.taskId ||
                    existing.evidenceDigest != durableExecution.evidencePayloadDigest) {
                    // Immutable field mismatch: a corrupted row exists. Fail-closed.
                    throw IllegalStateException(
                        "TRUSTED_LEDGER_CORRUPTION: existing TrustedQuotaEntry for attempt " +
                        "${durableExecution.attemptId} has mismatched immutable fields " +
                        "(taskId=${existing?.taskId} vs ${attempt.taskId}, " +
                        "evidenceDigest=${existing?.evidenceDigest} vs ${durableExecution.evidencePayloadDigest})"
                    )
                }
                // Fields match: legitimate DECIDING crash window re-mint. No-op, proceed.
            }
        } else if (decision == TrustDecision.FAIL && attempt != null) {
            // FAIL for a REAL attempt: the exact unverified carrier (typed reason + evidence digest).
            val candidate = UnverifiedAttemptRecord(
                attemptId = durableExecution.attemptId,
                reason = "UNTRUSTED",
                evidenceDigest = durableExecution.evidencePayloadDigest
            )
            db.unverifiedAttemptRecordDao().insert(candidate)
            val winner = db.unverifiedAttemptRecordDao().getByAttempt(durableExecution.attemptId)
                ?: throw IllegalStateException(
                    "UNVERIFIED_CARRIER_MISSING: attempt=${durableExecution.attemptId}"
                )
            if (winner.copy(id = 0) != candidate) {
                throw IllegalStateException(
                    "UNVERIFIED_CARRIER_CONFLICT: immutable replay mismatch for " +
                        "attempt=${durableExecution.attemptId}"
                )
            }
        }
        if (attempt == null) return@withTransaction TrustDecision.FAIL
        decision
    }

    // ---- Session lifecycle ----

    suspend fun createSession(planId: Long, startedAt: Long): Long =
        db.runSessionDao().insert(
            RunSession(startedAt = startedAt, planId = planId, configSnapshot = "plan:$planId")
        )

    suspend fun finishSession(sessionId: Long, status: String, endedAt: Long, totalCycles: Int) =
        db.runSessionDao().finish(sessionId, endedAt, status, totalCycles)
}
