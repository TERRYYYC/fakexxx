package com.example.cellrebelauto.repository

import androidx.room.withTransaction
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.aplus.AttemptState
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.automation.aplus.AttemptTransitions
import com.example.cellrebelauto.automation.aplus.ReleaseReceiptRoute
import com.example.cellrebelauto.automation.plan.PlanScheduler
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.db.TaskAttemptCount
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.TrustDecision
import com.example.cellrebelauto.environment.TrustPolicy
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.audit.AutoAuditEvent
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord
import com.example.cellrebelauto.model.plan.AttemptWithTask
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.model.plan.WorklistRow
import com.example.cellrebelauto.recovery.AdvanceReplayCarrierRow
import com.example.cellrebelauto.recovery.AdvanceReceiptRow
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import com.example.cellrebelauto.recovery.ProviderReleaseHandoff
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

sealed interface LegacyReleaseRecovery {
    data object NotLegacy : LegacyReleaseRecovery
    data class Ready(val state: AttemptState) : LegacyReleaseRecovery
    data class Rejected(val reason: String) : LegacyReleaseRecovery
}

/** Local classification only, never a capability to advance or dispatch provider work. */
sealed interface LegacyReleaseValidation {
    data object NotLegacy : LegacyReleaseValidation
    data class Valid(val reconcileLegacyRelease: Boolean) : LegacyReleaseValidation
    data class Rejected(val reason: String) : LegacyReleaseValidation
}

/**
 * Plan-level repository (O1–O4 data owner). Wraps the plan/task/attempt/session
 * DAOs and hosts the transactional success finalization (INV-3): attempt row +
 * guarded task increment atomically, idempotent against stale expected values.
 * # 计划级仓库：封装计划/任务/尝试/会话 DAO，
 * # 并承载成功收尾事务（INV-3）：尝试行 + 守卫式任务自增原子完成，幂等
 */
class PlanRepository(private val db: AppDatabase) {

    /**
     * A derived, one-shot #97 proof. Durable session/attempt/receipt rows remain the truth owners;
     * this value only binds the exact snapshot that [confirmSupersedingImport] must re-read.
     */
    data class SupersessionStopProof(
        val requestId: String,
        val planId: Long,
        val sessionId: Long,
        val sessionStartedAt: Long,
        val evidenceDigest: String
    )

    sealed interface SupersessionStopVerification {
        data class Verified(val proof: SupersessionStopProof) : SupersessionStopVerification
        data class NeedsConvergence(val reason: String) : SupersessionStopVerification
        data class Blocked(val reason: String) : SupersessionStopVerification
        data object StalePlan : SupersessionStopVerification
    }

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

    // # 观察任务 + 每任务可信成功计数（§7.3 进度 UI 投影）
    fun observeTasksWithTrustedCounts(planId: Long): Flow<List<com.example.cellrebelauto.db.TaskWithTrustedCount>> =
        db.locationTaskDao().observeTasksForPlanWithTrustedCounts(planId)

    // # 观察某计划每个任务的尝试总数
    fun observeAttemptCounts(planId: Long): Flow<List<TaskAttemptCount>> =
        db.testAttemptDao().observeAttemptCountsForPlan(planId)

    // #12：观察某计划 RECOVERY_REQUIRED 死尝试数（Plan 页重置入口可见性投影）
    fun observeRecoveryRequiredCount(planId: Long): Flow<Int> =
        db.testAttemptDao().observeRecoveryRequiredForPlan(planId)

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

    /** The only durable path that replaces an unfinished plan after explicit UI confirmation. */
    sealed interface SupersedingImportResult {
        data class Imported(val planId: Long) : SupersedingImportResult
        data class ActiveSession(val sessionId: Long) : SupersedingImportResult
        data class StopVerificationRequired(val sessionId: Long) : SupersedingImportResult
        data object StaleStopProof : SupersedingImportResult
        data object StalePlan : SupersedingImportResult
    }

    /** Verifies the complete durable attempt shape before stopping a plan for replacement. */
    suspend fun verifyAndStopForSupersession(
        requestId: String,
        expectedPlanId: Long,
        expectedSessionId: Long,
        stoppedAt: Long
    ): SupersessionStopVerification = db.withTransaction {
        if (requestId.isBlank()) {
            return@withTransaction SupersessionStopVerification.Blocked("REQUEST_ID_MISSING")
        }
        if (db.planDao().getSelectablePlanById(expectedPlanId) == null) {
            return@withTransaction SupersessionStopVerification.StalePlan
        }
        val session = db.runSessionDao().getById(expectedSessionId)
            ?: return@withTransaction SupersessionStopVerification.Blocked("SESSION_MISSING")
        if (session.planId != expectedPlanId) {
            return@withTransaction SupersessionStopVerification.Blocked("SESSION_PLAN_MISMATCH")
        }
        val attempts = db.testAttemptDao().getAttemptsForPlan(expectedPlanId)
        val classifications = attempts.map { classifySupersessionStopAttempt(it) }
        classifications.filterIsInstance<StopAttemptEvidence.Blocked>().firstOrNull()?.let {
            return@withTransaction SupersessionStopVerification.Blocked(it.reason)
        }
        classifications.filterIsInstance<StopAttemptEvidence.NeedsConvergence>().firstOrNull()?.let {
            return@withTransaction SupersessionStopVerification.NeedsConvergence(it.reason)
        }
        classifications.filterIsInstance<StopAttemptEvidence.Safe>().filter { it.interrupt }.forEach {
            db.testAttemptDao().markInterruptedIfNonTerminal(it.attemptId, stoppedAt)
        }
        if (session.status in setOf("starting", "running", "recovering", "paused")) {
            check(db.runSessionDao().stopForSupersession(expectedSessionId, stoppedAt) == 1) {
                "supersession stop lost its session owner"
            }
        } else if (session.status != "stopped") {
            return@withTransaction SupersessionStopVerification.Blocked("SESSION_NOT_STOPPABLE:${session.status}")
        }
        val stopped = requireNotNull(db.runSessionDao().getById(expectedSessionId))
        val digest = requireNotNull(
            readSupersessionStopEvidenceDigest(expectedPlanId, stopped, requestId)
        )
        SupersessionStopVerification.Verified(
            SupersessionStopProof(
                requestId = requestId,
                planId = expectedPlanId,
                sessionId = stopped.id,
                sessionStartedAt = stopped.startedAt,
                evidenceDigest = digest
            )
        )
    }

    private sealed interface StopAttemptEvidence {
        data class Safe(val attemptId: Long, val interrupt: Boolean, val line: String) : StopAttemptEvidence
        data class NeedsConvergence(val reason: String) : StopAttemptEvidence
        data class Blocked(val reason: String) : StopAttemptEvidence
    }

    /**
     * Classifies only durable Auto-owned rows. A generic terminal projection or bare CLOSED phase
     * never proves an external effect safe; lease, release, advance and decision carriers must form
     * one exact terminal shape. CREATED is locally interruptible only before every effect carrier.
     */
    private suspend fun classifySupersessionStopAttempt(
        attempt: com.example.cellrebelauto.model.plan.TestAttempt
    ): StopAttemptEvidence {
        val apply = db.operationReceiptDao().byKey(APlusOperationIdentity.applyIdempotencyKey(attempt.id))
        val releaseKey = APlusOperationIdentity.releaseIdempotencyKey(attempt.id)
        val releaseByKey = db.releaseReceiptDao().byKey(releaseKey)
        val releaseByLease = attempt.aplusLeaseId?.let { db.releaseReceiptDao().byLease(it) }
        val carrier = db.advanceReplayCarrierDao().byAttempt(attempt.id)
        val advanceReceipt = db.advanceReceiptDao().byAttempt(attempt.id)
        val trusted = db.trustedQuotaDao().getByAttempt(attempt.id)
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(attempt.id)
        val audits = db.auditEventDao().forAttempt(attempt.id)
        val executions = db.attemptExecutionDao().forAttempt(attempt.id)
        val locallyInterruptible = attempt.status in setOf("starting", "running")

        if (attempt.aplusState == null) {
            if (attempt.aplusLeaseId != null || apply != null || releaseByKey != null || carrier != null ||
                advanceReceipt != null || trusted != null || unverified != null ||
                executions.isNotEmpty() || audits.isNotEmpty()
            ) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_EXTERNAL_OWNER_WITHOUT_APLUS_STATE")
            }
            if (!locallyInterruptible && attempt.endedAt == null) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_LOCAL_PROJECTION_INCOMPLETE")
            }
            return StopAttemptEvidence.Safe(
                attempt.id,
                interrupt = locallyInterruptible,
                line = "${attempt.id}|${attempt.taskId}|${attempt.runSessionId}|legacy-local|" +
                    "${attempt.status}|${attempt.endedAt}"
            )
        }

        val noEffectCreated = attempt.aplusState == AttemptState.CREATED.name &&
            attempt.aplusLeaseId == null && attempt.currentExecutionId == null && apply == null &&
            releaseByKey == null && carrier == null && advanceReceipt == null && trusted == null &&
            unverified == null && executions.isEmpty() && audits.isEmpty()
        if (noEffectCreated) {
            if (!locallyInterruptible && attempt.endedAt == null) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_CREATED_PROJECTION_INCOMPLETE")
            }
            return StopAttemptEvidence.Safe(
                attempt.id,
                interrupt = locallyInterruptible,
                line = "${attempt.id}|${attempt.taskId}|${attempt.runSessionId}|created-no-effect|" +
                    "${attempt.status}|${attempt.endedAt}|${attempt.aplusAnchorScheduleId}|" +
                    "${attempt.aplusAnchorItemId}|${attempt.aplusAnchorVersion}"
            )
        }
        if (attempt.aplusState == AttemptState.CREATED.name) {
            return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_CREATED_EXTERNAL_EFFECT_UNKNOWN")
        }
        if (attempt.aplusState != AttemptState.CLOSED.name) {
            return StopAttemptEvidence.NeedsConvergence(
                "ATTEMPT_${attempt.id}_UNRESOLVED_APLUS:${attempt.aplusState}"
            )
        }
        if (attempt.status in setOf("starting", "running") || attempt.endedAt == null) {
            return StopAttemptEvidence.NeedsConvergence("ATTEMPT_${attempt.id}_CLOSED_PROJECTION_NOT_TERMINAL")
        }
        if (trusted != null && (unverified != null || trusted.taskId != attempt.taskId)) {
            return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_DECISION_CARRIER_CONFLICT")
        }

        val lease = attempt.aplusLeaseId
        if (lease == null) {
            if (attempt.currentExecutionId != null || apply != null || releaseByKey != null ||
                carrier != null || advanceReceipt != null || trusted != null || unverified != null ||
                executions.isNotEmpty() || audits.isNotEmpty()
            ) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_CLOSED_EXTERNAL_EFFECT_UNKNOWN")
            }
        } else {
            if (releaseByKey == null || releaseByLease == null || releaseByKey != releaseByLease ||
                db.releaseReceiptDao().countForLease(lease) != 1 || releaseByKey.leaseId != lease ||
                releaseByKey.releaseDigest != APlusOperationIdentity.releaseDigest(lease) ||
                releaseByKey.resultOutcome != "RELEASED"
            ) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_RELEASE_AUTHORITY_INVALID")
            }
            if (trusted == null && unverified == null) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_DECISION_CARRIER_MISSING")
            }
            if (apply != null && (apply.resultOutcome != "APPLIED" || apply.leaseId != lease)) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_APPLY_AUTHORITY_INVALID")
            }
        }

        if (unverified != null && (carrier != null || advanceReceipt != null)) {
            return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_UNVERIFIED_ADVANCE_CONFLICT")
        }
        var trustedTask: LocationTask? = null
        var trustedOrdinal: Int? = null
        if (trusted != null) {
            val task = db.locationTaskDao().getTaskById(attempt.taskId)
                ?: return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_TRUSTED_TASK_MISSING")
            val entries = db.trustedQuotaDao().entriesForTask(attempt.taskId)
            val ordinal = entries.indexOfFirst { it.attemptId == attempt.id } + 1
            if (ordinal <= 0) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_TRUSTED_LEDGER_INDEX_MISSING")
            }
            if (ordinal > task.requiredSuccesses) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_TRUSTED_QUOTA_OVERFLOW")
            }
            trustedTask = task
            trustedOrdinal = ordinal
            if (ordinal == task.requiredSuccesses && carrier == null) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_ADVANCE_REQUIRED_AT_QUOTA")
            }
            if (ordinal < task.requiredSuccesses && (carrier != null || advanceReceipt != null)) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_ADVANCE_UNEXPECTED_BELOW_QUOTA")
            }
        }

        if (advanceReceipt != null && carrier == null) {
            return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_ADVANCE_RECEIPT_WITHOUT_CARRIER")
        }
        if (carrier != null) {
            val request = carrier.toAdvanceRequest()
            if (CanonicalAdvanceDigestV1.compute(request) != request.requestDigest ||
                carrier.releaseIdempotencyKey != releaseKey || carrier.releaseLeaseId != lease ||
                carrier.releaseDigest != releaseByKey?.releaseDigest || request.leaseId != lease ||
                request.idempotencyKey != APlusOperationIdentity.applyIdempotencyKey(attempt.id) ||
                request.expectedScheduleId != attempt.aplusAnchorScheduleId ||
                request.expectedCurrentItemId != attempt.aplusAnchorItemId ||
                request.expectedScheduleVersion != attempt.aplusAnchorVersion ||
                request.completionProof.scheduleItemId != attempt.aplusAnchorItemId ||
                request.completionProof.quotaRequired != trustedTask?.requiredSuccesses ||
                request.completionProof.trustedSuccessCount != trustedOrdinal ||
                request.completionProof.ledgerRef != "ledger-${attempt.id}" ||
                request.callerProtocolVersion != io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
            ) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_ADVANCE_CARRIER_INVALID")
            }
            if (advanceReceipt == null) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_ADVANCE_NOT_VERIFIED")
            }
            val receipt = advanceReceipt.toAdvanceReceipt()
            if (advanceReceipt.idempotencyKey != request.idempotencyKey ||
                advanceReceipt.requestDigest != request.requestDigest ||
                !CanonicalAdvanceReceiptDigestV1.verify(
                    receipt,
                    advanceReceipt.requestDigest,
                    advanceReceipt.idempotencyKey
                )
            ) {
                return StopAttemptEvidence.Blocked("ATTEMPT_${attempt.id}_ADVANCE_RECEIPT_INVALID")
            }
        }

        val line = buildString {
            append(attempt.id).append('|').append(attempt.taskId).append('|')
            append(attempt.runSessionId).append('|').append(attempt.status).append('|')
            append(attempt.endedAt).append('|').append(attempt.aplusState).append('|')
            append(lease).append('|').append(apply).append('|').append(releaseByKey).append('|')
            append(carrier).append('|').append(advanceReceipt).append('|')
            append(trusted).append('|').append(unverified).append('|')
            append(executions.joinToString(";")).append('|').append(audits.joinToString(";"))
        }
        return StopAttemptEvidence.Safe(attempt.id, interrupt = false, line = line)
    }

    private suspend fun readSupersessionStopEvidenceDigest(
        planId: Long,
        session: RunSession,
        requestId: String
    ): String? {
        if (session.planId != planId || session.status != "stopped" || session.endedAt == null) return null
        val attempts = db.testAttemptDao().getAttemptsForPlan(planId)
        val evidence = attempts.map { classifySupersessionStopAttempt(it) }
        if (evidence.any {
                it is StopAttemptEvidence.Blocked || it is StopAttemptEvidence.NeedsConvergence
            }
        ) return null
        val taskLines = db.locationTaskDao().getTasksForPlan(planId).joinToString(";") {
            "${it.id}|${it.csvRow}|${it.status}|${it.requiredSuccesses}|${it.completedSuccesses}"
        }
        val attemptLines = evidence.filterIsInstance<StopAttemptEvidence.Safe>()
            .sortedBy { it.attemptId }
            .joinToString(";") { it.line }
        val preimage = listOf(
            "stop-proof-v1", requestId, planId.toString(), session.id.toString(), session.startedAt.toString(),
            session.endedAt.toString(), session.status, session.totalCycles.toString(), taskLines, attemptLines
        ).joinToString("|")
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(preimage.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * #97 replacement boundary. The old plan and all descendants are retained. This transaction
     * accepts only the proposal's still-current active plan, refuses a recoverable session, inserts
     * the successor, then links the old row to it. A cancellation or parse failure never calls here.
     */
    suspend fun confirmSupersedingImport(
        expectedOldPlanId: Long,
        sourceFileName: String,
        globalBufferSeconds: Int,
        rows: List<WorklistRow>,
        importedAt: Long,
        supersededAt: Long,
        stopProof: SupersessionStopProof? = null
    ): SupersedingImportResult = db.withTransaction {
        val activePlan = db.planDao().getLatestPlan()
            ?: return@withTransaction SupersedingImportResult.StalePlan
        if (activePlan.id != expectedOldPlanId || activePlan.supersededAt != null) {
            return@withTransaction SupersedingImportResult.StalePlan
        }
        db.runSessionDao().findActiveRunningSession(expectedOldPlanId)?.let {
            return@withTransaction SupersedingImportResult.ActiveSession(it.id)
        }
        val latestSession = db.runSessionDao().getLatestForPlan(expectedOldPlanId)
        if (latestSession != null) {
            val proof = stopProof
                ?: return@withTransaction SupersedingImportResult.StopVerificationRequired(latestSession.id)
            if (proof.planId != expectedOldPlanId || proof.sessionId != latestSession.id ||
                proof.sessionStartedAt != latestSession.startedAt || proof.requestId.isBlank() ||
                readSupersessionStopEvidenceDigest(
                    expectedOldPlanId,
                    latestSession,
                    proof.requestId
                ) != proof.evidenceDigest
            ) {
                return@withTransaction SupersedingImportResult.StaleStopProof
            }
        } else if (stopProof != null) {
            return@withTransaction SupersedingImportResult.StaleStopProof
        }
        val successorId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = sourceFileName,
                importedAt = importedAt,
                globalBufferSeconds = globalBufferSeconds,
                totalRows = rows.size,
                totalRequiredSuccesses = rows.sumOf { it.requiredSuccesses }
            ),
            rows.map {
                LocationTask(
                    planId = 0,
                    csvRow = it.csvRow,
                    longitude = it.longitude,
                    latitude = it.latitude,
                    priority = it.priority,
                    requiredSuccesses = it.requiredSuccesses
                )
            }
        )
        check(db.planDao().markSuperseded(expectedOldPlanId, successorId, supersededAt) == 1) {
            "superseding import lost its active-plan owner"
        }
        SupersedingImportResult.Imported(successorId)
    }

    // ---- #12 plan-reset (re-run a finished/dead plan) ----

    /** Outcome of [resetPlanAsFreshGeneration]. */
    sealed interface PlanResetOutcome {
        /** Reset done: the old plan's rows were copied into a NEW generation. */
        data class Reset(val oldPlanId: Long, val newPlanId: Long, val rows: Int) : PlanResetOutcome

        /** No plan exists — nothing to reset. */
        data object NoPlan : PlanResetOutcome

        /** Guard refused the reset (plan unfinished, nothing RECOVERY_REQUIRED). */
        data class Refused(val reason: String) : PlanResetOutcome
    }

    /**
     * #12: re-activate the latest plan as a FRESH GENERATION — insert a new
     * plan row and copy its task rows verbatim (csvRow/coordinates/priority/
     * quota), all pending, in ONE transaction, then append a typed PLAN_RESET
     * audit row binding old→new plan ids.
     *
     * WHY COPY-ROWS instead of re-running the CSV import pipeline: the SAF Uri
     * grant from the original import is long gone and the app does not retain
     * the file bytes; the plan's own task rows ARE the validated worklist
     * (import validated them atomically). "Reset" means the SAME worklist,
     * fresh generation — wanting DIFFERENT rows is a normal CSV import, which
     * stays available. Trade-off: a corrupted/drifted task table would be
     * copied as-is; acceptable because those rows are the execution truth the
     * previous generation ran on.
     *
     * GUARD (importCsv parity, enforced INSIDE the transaction — not only in
     * hidden UI): reset is allowed only when the plan is complete OR at least
     * one attempt is terminally stuck RECOVERY_REQUIRED (the dead-lane escape
     * hatch). Old attempts/sessions/ledger rows are NEVER deleted — they are
     * the append-only audit trail (History/export keep them); only the new
     * plan's projection starts clean.
     */
    suspend fun resetPlanAsFreshGeneration(nowMs: Long = System.currentTimeMillis()): PlanResetOutcome =
        db.withTransaction {
            val plan = db.planDao().getLatestPlan() ?: return@withTransaction PlanResetOutcome.NoPlan
            val tasks = db.locationTaskDao().getTasksForPlan(plan.id)
            if (tasks.isEmpty()) {
                return@withTransaction PlanResetOutcome.Refused("plan has no tasks")
            }
            val complete = tasks.all { it.status == "completed" }
            val recoveryRequired = db.testAttemptDao().countRecoveryRequiredForPlan(plan.id) > 0
            if (!complete && !recoveryRequired) {
                return@withTransaction PlanResetOutcome.Refused(
                    "Current plan unfinished (${tasks.count { it.status == "completed" }}/${tasks.size} tasks) " +
                        "and no attempt is stuck RECOVERY_REQUIRED — resume it instead"
                )
            }

            // importedAt must strictly exceed the old plan's or the
            // `ORDER BY importedAt DESC LIMIT 1` latest-plan projection could
            // pick either generation when timestamps collide.
            val importedAt = maxOf(nowMs, plan.importedAt + 1)
            val newPlanId = db.planDao().insertPlanWithTasks(
                LocationPlan(
                    sourceFileName = plan.sourceFileName,
                    importedAt = importedAt,
                    globalBufferSeconds = plan.globalBufferSeconds,
                    totalRows = tasks.size,
                    totalRequiredSuccesses = tasks.sumOf { it.requiredSuccesses }
                ),
                tasks.map {
                    LocationTask(
                        planId = 0, // # 由 insertPlanWithTasks 回填
                        csvRow = it.csvRow,
                        longitude = it.longitude,
                        latitude = it.latitude,
                        priority = it.priority,
                        requiredSuccesses = it.requiredSuccesses
                        // # completedSuccesses/status 故意不复制：新代际从零开始
                    )
                }
            )

            // Typed audit row (§7.1 stream is append-only, never a state owner):
            // seq follows the same single-writer monotonic convention as
            // APlusAttemptDriver (stream length + 1).
            db.auditEventDao().insert(
                com.example.cellrebelauto.model.audit.AutoAuditEvent(
                    seq = db.auditEventDao().count().toLong() + 1,
                    attemptId = null, // plan-level event
                    correlationRef = "plan:${plan.id}->$newPlanId",
                    eventType = "PLAN_RESET",
                    payloadDigest = "src=${plan.sourceFileName}:rows=${tasks.size}:" +
                        "buffer=${plan.globalBufferSeconds}:recoveryRequired=$recoveryRequired",
                    recordedAt = importedAt
                )
            )
            PlanResetOutcome.Reset(oldPlanId = plan.id, newPlanId = newPlanId, rows = tasks.size)
        }

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

    /** Re-read the durable Attempt owner after a recovery step changes its phase. */
    suspend fun getAttempt(attemptId: Long): TestAttempt? =
        db.testAttemptDao().getAttemptById(attemptId)

    /** The active (running) session the recovery supersedes rather than duplicating (Sol round-8 P1-6). */
    suspend fun findActiveRunSession(planId: Long): RunSession? =
        db.runSessionDao().findActiveRunningSession(planId)

    // # A+ 恢复态投影（§8.2）：持久化 RECOVERING/PAUSED/RUNNING
    suspend fun markSessionStatus(sessionId: Long, status: String) =
        db.runSessionDao().updateStatus(sessionId, status)

    /** Terminalize a replacement run that cannot own the plan's durable A+ effect. */
    suspend fun interruptSessionForRecoveryConflict(sessionId: Long, endedAt: Long) =
        db.runSessionDao().interruptForRecoveryConflict(sessionId, endedAt)

    // # 可信完成投影（§7.3）：任务完成 = 可信计数达成；GREEN 由 F3 可信 SQL 承载
    suspend fun completeTaskIfQuotaReached(taskId: Long): Int =
        db.locationTaskDao().completeTaskIfQuotaReached(taskId)

    // # A+ 当前操作持久化（§7.1）：阶段 + lease（Sol round-8 P1-3/P1-4）
    suspend fun markAplusState(attemptId: Long, aplusState: String) =
        db.testAttemptDao().markAplusState(attemptId, aplusState)

    /**
     * Persist a recovery-required fact as one transaction: the Attempt remains the state owner,
     * while the append-only audit row records the same typed reason. Neither durable half may be
     * observed on its own after this call returns.
     */
    suspend fun markRecoveryRequired(
        attemptId: Long,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ) = db.withTransaction {
        val previous = requireNotNull(db.testAttemptDao().getAttemptById(attemptId)) {
            "cannot mark missing attempt $attemptId recovery-required"
        }
        db.testAttemptDao().markRecoveryRequired(attemptId, reason)
        db.auditEventDao().insert(
            AutoAuditEvent(
                seq = db.auditEventDao().count().toLong() + 1,
                attemptId = attemptId,
                correlationRef = null,
                eventType = "RECOVERY_REQUIRED",
                payloadDigest = "${previous.aplusState ?: "null"}->RECOVERY_REQUIRED[$reason]",
                recordedAt = nowMs
            )
        )
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
    /**
     * Idempotent per (attemptId, phase). Device-observed 2026-09-03 (ZY22, first real A+ lease):
     * APlusComposition.observeLive() persists the phase record "before returning" and the engine
     * then calls this for the same snapshot; the table is UNIQUE(attemptId, phase) and the DAO is
     * a bare @Insert, so the second write threw SQLiteConstraintException inside the engine
     * transaction and every fresh attempt paused at PRE_OBSERVED with an unresolved lease. The
     * first durable carrier wins; PR #65 replaces this pair with one carrier+phase transaction.
     */
    suspend fun persistObservation(attemptId: Long, phase: String, snapshot: com.example.cellrebelauto.environment.ObservationSnapshot): Long {
        db.durableObservationDao().forAttemptPhase(attemptId, phase)?.let { return it.id }
        return db.durableObservationDao().insert(
            com.example.cellrebelauto.model.ledger.DurableObservationRecord(
                attemptId = attemptId, phase = phase,
                leaseId = snapshot.leaseId,
                acceptedIntentHash = snapshot.acceptedIntentHash,
                coverage = snapshot.coverage,
                verificationLevel = snapshot.verificationLevel,
                deliveryMode = snapshot.deliveryMode,
                isMock = snapshot.isMock,
                scheduleDecision = snapshot.scheduleDecision,
                effectiveLat = snapshot.effectiveLat,
                effectiveLng = snapshot.effectiveLng,
                environmentRevision = snapshot.environmentRevision,
                environmentFingerprint = snapshot.environmentFingerprint,
                observedAtElapsedRealtimeMs = snapshot.observedAtElapsedRealtimeMs,
                observedAtEpochMs = snapshot.observedAtEpochMs,
                continuitySinceElapsedRealtimeMs = snapshot.continuitySinceElapsedRealtimeMs,
                continuitySinceEpochMs = null,
                evidenceRefsJson = org.json.JSONArray(snapshot.evidenceRefs).toString(),
                evidenceRefs = snapshot.evidenceRefs.joinToString(";")
            )
        )
    }

    suspend fun getObservation(attemptId: Long, phase: String): com.example.cellrebelauto.model.ledger.DurableObservationRecord? =
        db.durableObservationDao().forAttemptPhase(attemptId, phase)

    /**
     * R43 GREEN (M-CR-06): reconstruct the §6.4 [ObservationSnapshot] from the durable record —
     * the crash-recovery re-decide reads observations from HERE, never from a live source.
     * `evidenceRefs` is parsed back from the JSON array column (round-trippable).
     */
    suspend fun getObservationSnapshot(attemptId: Long, phase: String): com.example.cellrebelauto.environment.ObservationSnapshot? {
        val r = db.durableObservationDao().forAttemptPhase(attemptId, phase) ?: return null
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
            continuitySinceElapsedRealtimeMs = r.continuitySinceElapsedRealtimeMs,
            evidenceRefs = refs
        )
    }

    suspend fun persistCompletionReceipt(attemptId: Long, wire: Int, acceptedIntentHash: String, leaseId: String) =
        db.durableCompletionReceiptDao().insert(
            com.example.cellrebelauto.model.ledger.DurableCompletionReceipt(
                attemptId = attemptId, completionEvidenceWire = wire,
                acceptedIntentHash = acceptedIntentHash, leaseId = leaseId
            )
        )

    suspend fun getCompletionReceipt(attemptId: Long): com.example.cellrebelauto.model.ledger.DurableCompletionReceipt? =
        db.durableCompletionReceiptDao().forAttempt(attemptId)

    /** R43 GREEN (M-CR-06): owner lookup of the current execution row by executionId (never positional). */
    suspend fun getExecutionByExecutionId(executionId: String): com.example.cellrebelauto.model.execution.CellRebelExecution? =
        db.attemptExecutionDao().byExecutionId(executionId)

    /**
     * R43 (Sol GREEN-review-2 F3): persist the §7.1 execution evidence AT the COMPLETION_OBSERVED
     * phase boundary — conflict-ignoring (a re-observe or the later trusted-transaction persist
     * over the same executionId is a no-op, never a UNIQUE rollback).
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
    ) {
        db.attemptExecutionDao().insertIfAbsent(
            com.example.cellrebelauto.model.execution.CellRebelExecution(
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

    // # 恢复真相载体（Sol round-16 P1-1 / round-18 P1-1）：可信账本 / 未验证记录是 append-only 权威，
    // # 返回 typed entry 以绑定 attempt+task 并检测跨表矛盾，绝不信裸 phase 字符串
    suspend fun getTrustedEntry(attemptId: Long): TrustedQuotaEntry? =
        db.trustedQuotaDao().getByAttempt(attemptId)

    suspend fun getUnverifiedRecord(attemptId: Long): UnverifiedAttemptRecord? =
        db.unverifiedAttemptRecordDao().getByAttempt(attemptId)

    /**
     * Runs before session/cardinality/provider admission. Invalid history gets a durable local
     * rejection; healthy history is strictly unchanged. Convergence must validate again, not
     * consume this classification as an authority token after a suspension or provider read.
     */
    suspend fun validateLegacyRelease(attemptId: Long, recordedAt: Long): LegacyReleaseValidation =
        db.withTransaction {
            validateLegacyReleaseOwner(requireNotNull(db.testAttemptDao().getAttemptById(attemptId)), recordedAt)
        }

    private suspend fun validateLegacyReleaseOwner(attempt: TestAttempt, recordedAt: Long): LegacyReleaseValidation {
        val releasePhase = attempt.aplusState in setOf("RELEASED", "RECOVERY_REQUIRED", "RELEASE_PENDING")
        val migratedAdvancePhase = attempt.aplusState in setOf("ADVANCE_PENDING", "ADVANCE_OBSERVING", "ADVANCE_STATE_READBACK")
        if (!releasePhase && !migratedAdvancePhase) return LegacyReleaseValidation.NotLegacy
        val audit = db.auditEventDao().forAttempt(attempt.id)
        val legacyHistory = attempt.aplusState == "RELEASED" ||
            audit.any { it.eventType == "RECOVERY_REQUIRED" &&
                it.payloadDigest.startsWith("RELEASED->RECOVERY_REQUIRED[") }
        if (!legacyHistory) return LegacyReleaseValidation.NotLegacy
        // Once an advance phase was durably observed, projecting RECOVERY_REQUIRED cannot erase
        // that fact and later reclassify an impossible non-quota advance as a healthy release.
        val hadAdvancePhase = migratedAdvancePhase || audit.any {
            it.payloadDigest.startsWith("ADVANCE_") ||
                (it.eventType == "RELEASE_RECEIPT" && it.payloadDigest.startsWith("RELEASE_PENDING->ADVANCE_PENDING["))
        }
        val failure = legacyReleaseAuthorityFailure(attempt, hadAdvancePhase)
            ?: return LegacyReleaseValidation.Valid(reconcileLegacyRelease = releasePhase)
        val reason = "LEGACY_RELEASED_AUTHORITY:$failure"
        if (attempt.aplusState != "RECOVERY_REQUIRED" || attempt.failureReason != reason) {
            markRecoveryRequired(attempt.id, reason, recordedAt)
        }
        return LegacyReleaseValidation.Rejected(reason)
    }

    /**
     * Legacy RELEASED is historical external-effect provenance, never permission for a fresh
     * release. Validate its existing release/request tuple before any owner change. The original
     * RELEASED audit source keeps this quarantine sticky across subsequent RECOVERY_REQUIRED runs.
     */
    suspend fun recoverLegacyRelease(attemptId: Long, recordedAt: Long): LegacyReleaseRecovery =
        db.withTransaction {
            val attempt = requireNotNull(db.testAttemptDao().getAttemptById(attemptId))
            when (val validation = validateLegacyReleaseOwner(attempt, recordedAt)) {
                LegacyReleaseValidation.NotLegacy -> return@withTransaction LegacyReleaseRecovery.NotLegacy
                is LegacyReleaseValidation.Rejected -> return@withTransaction LegacyReleaseRecovery.Rejected(validation.reason)
                is LegacyReleaseValidation.Valid -> {
                    // The legacy source remains relevant for validation/census after migration,
                    // but later ADVANCE phases must resume their own reducer path, never release.
                    if (!validation.reconcileLegacyRelease) return@withTransaction LegacyReleaseRecovery.NotLegacy
                }
            }
            val trusted = db.trustedQuotaDao().getByAttempt(attemptId)
            val task = requireNotNull(db.locationTaskDao().getTaskById(attempt.taskId))
            val route = when {
                trusted == null -> ReleaseReceiptRoute.NOT_COMMITTED
                trustedCountForTask(attempt.taskId) < task.requiredSuccesses -> ReleaseReceiptRoute.COMMITTED_UNDER_QUOTA
                else -> ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED
            }
            val release = requireNotNull(db.releaseReceiptDao().byKey(APlusOperationIdentity.releaseIdempotencyKey(attemptId)))
            // RELEASED is outside the frozen reducer. Record an explicit owner recovery fact,
            // then consume its legal RECONCILE edge, all in this same owner transaction.
            markRecoveryRequired(attemptId, "LEGACY_RELEASED_RECONCILE", recordedAt)
            val next = APlusAttemptDriver(db.auditEventDao()) { recordedAt }.driveTransition(
                attemptId, AttemptState.RECOVERY_REQUIRED, AttemptEvent.RECONCILE)
            check(next == AttemptState.RELEASE_PENDING)
            check(db.testAttemptDao().compareAndSetAplusState(attemptId, "RECOVERY_REQUIRED", next.name) == 1)
            LegacyReleaseRecovery.Ready(commitReleaseReceipt(
                attemptId,
                ProviderReleaseHandoff(release.idempotencyKey, release.leaseId, release.releaseDigest,
                    release.resultOutcome, release.createdAt, alreadyDurable = true),
                route,
                // Quota requests were validated above and must already exist; this value can
                // never build a request. No recovery clock is consulted for historical identity.
                verifiedAtElapsedRealtimeMs = 0,
                recordedAt = recordedAt
            ))
        }

    private suspend fun legacyReleaseAuthorityFailure(attempt: TestAttempt, hadAdvancePhase: Boolean): String? {
        val lease = attempt.aplusLeaseId?.takeIf { it.isNotBlank() } ?: return "LEASE_MISSING"
        val key = APlusOperationIdentity.releaseIdempotencyKey(attempt.id)
        val byKey = db.releaseReceiptDao().byKey(key)
        val byLease = db.releaseReceiptDao().byLease(lease)
        if (byKey == null && byLease == null) return "RELEASE_RECEIPT_MISSING"
        if (byKey == null || byLease == null || byKey != byLease ||
            db.releaseReceiptDao().countForLease(lease) != 1) return "RELEASE_INDEX_CONFLICT"
        if (byKey.leaseId != lease || byKey.releaseDigest != APlusOperationIdentity.releaseDigest(lease) ||
            byKey.resultOutcome != "RELEASED") return "RELEASE_RECEIPT_MISMATCH"
        val trusted = db.trustedQuotaDao().getByAttempt(attempt.id)
        val negative = db.unverifiedAttemptRecordDao().getByAttempt(attempt.id)
        if (trusted != null && (negative != null || trusted.taskId != attempt.taskId)) return "TRUST_CARRIER_CONFLICT"
        if (trusted == null && negative == null) return "DECISION_CARRIER_MISSING"
        val task = db.locationTaskDao().getTaskById(attempt.taskId) ?: return "TASK_MISSING"
        val count = trustedCountForTask(attempt.taskId)
        val carrier = db.advanceReplayCarrierDao().byAttempt(attempt.id)
        if (trusted == null || count < task.requiredSuccesses) {
            if (hadAdvancePhase) {
                return "NON_QUOTA_ADVANCE_STATE"
            }
            return if (carrier != null || db.advanceReceiptDao().byAttempt(attempt.id) != null)
                "NON_QUOTA_ADVANCE_CARRIER" else null
        }
        if (carrier == null) return "ADVANCE_CARRIER_MISSING"
        val request = try {
            readAdvanceReplayRequest(attempt.id)
        } catch (_: IllegalStateException) { return "ADVANCE_CARRIER_INVALID" }
        if (request == null || carrier.releaseIdempotencyKey != key || request.leaseId != lease ||
            request.idempotencyKey != APlusOperationIdentity.applyIdempotencyKey(attempt.id) ||
            request.expectedScheduleId != attempt.aplusAnchorScheduleId ||
            request.expectedCurrentItemId != attempt.aplusAnchorItemId ||
            request.expectedScheduleVersion != attempt.aplusAnchorVersion ||
            request.completionProof.scheduleItemId != attempt.aplusAnchorItemId ||
            request.completionProof.quotaRequired != task.requiredSuccesses ||
            request.completionProof.trustedSuccessCount !in task.requiredSuccesses..count ||
            request.completionProof.ledgerRef != "ledger-${attempt.id}" ||
            request.callerProtocolVersion != io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
        ) return "ADVANCE_CARRIER_OWNER_MISMATCH"
        try {
            readAdvanceReceipt(attempt.id)
        } catch (_: IllegalStateException) { return "ADVANCE_RECEIPT_INVALID" }
        return null
    }

    /**
     * The sole production RELEASE_RECEIPT owner boundary. Provider I/O has already completed
     * outside Room. Release, exact quota request, legal owner transition and its audit either
     * commit together or all roll back. Recovery never fills a historical carrier gap.
     */
    suspend fun commitReleaseReceipt(
        attemptId: Long,
        handoff: ProviderReleaseHandoff,
        expectedRoute: ReleaseReceiptRoute,
        verifiedAtElapsedRealtimeMs: Long,
        recordedAt: Long
    ): AttemptState = db.withTransaction {
        val attempt = requireNotNull(db.testAttemptDao().getAttemptById(attemptId))
        val current = AttemptState.valueOf(requireNotNull(attempt.aplusState))
        val releaseKey = APlusOperationIdentity.releaseIdempotencyKey(attemptId)
        check(handoff.idempotencyKey == releaseKey && handoff.leaseId == attempt.aplusLeaseId &&
            handoff.releaseDigest == APlusOperationIdentity.releaseDigest(handoff.leaseId) &&
            handoff.resultOutcome == "RELEASED") { "RELEASE_HANDOFF_OWNER_MISMATCH:$attemptId" }
        val priorRelease = db.releaseReceiptDao().byKey(releaseKey)
        val priorByLease = db.releaseReceiptDao().byLease(handoff.leaseId)
        check(priorRelease == priorByLease) { "RELEASE_RECEIPT_INDEX_CONFLICT:$attemptId" }
        val trusted = db.trustedQuotaDao().getByAttempt(attemptId)
        val negative = db.unverifiedAttemptRecordDao().getByAttempt(attemptId)
        check(trusted == null || (negative == null && trusted.taskId == attempt.taskId)) {
            "RELEASE_TRUST_CARRIER_CONFLICT:$attemptId"
        }
        val task = requireNotNull(db.locationTaskDao().getTaskById(attempt.taskId))
        val trustedCount = trustedCountForTask(attempt.taskId)
        val route = when {
            trusted == null -> ReleaseReceiptRoute.NOT_COMMITTED
            trustedCount < task.requiredSuccesses -> ReleaseReceiptRoute.COMMITTED_UNDER_QUOTA
            else -> ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED
        }
        check(route == expectedRoute) { "RELEASE_ROUTE_CONFLICT:$attemptId:$route:$expectedRoute" }
        val existingRequest = getAdvanceReplayRequest(attemptId)
        if (route == ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED) {
            // A legacy receipt without the exact request cannot prove whether advance was sent.
            // Only an entirely new Auto release commit may mint a request using today's clock.
            check(existingRequest != null || (priorRelease == null && !handoff.alreadyDurable)) {
                "ADVANCE_REPLAY_CARRIER_MISSING:$attemptId"
            }
        } else {
            check(existingRequest == null) { "NON_QUOTA_ADVANCE_CARRIER:$attemptId" }
        }
        if (current != AttemptState.RELEASE_PENDING) {
            // A repeated handoff can observe a later phase, but cannot rewind it or emit another
            // release event. Receipt/carrier plus the original audit must prove the first commit.
            check(current in setOf(AttemptState.ADVANCE_PENDING, AttemptState.ADVANCE_OBSERVING,
                AttemptState.ADVANCE_STATE_READBACK, AttemptState.CLOSED, AttemptState.RECOVERY_REQUIRED) &&
                priorRelease != null && db.auditEventDao().forAttempt(attemptId).any {
                    it.eventType == "RELEASE_RECEIPT" && it.payloadDigest ==
                        "RELEASE_PENDING->${AttemptTransitions.nextAfterReleaseReceipt(AttemptState.RELEASE_PENDING, route)}[$route]"
                }) { "RELEASE_RECEIPT_ILLEGAL_STATE:$attemptId:$current" }
        }
        persistReleaseReceipt(handoff.idempotencyKey, handoff.leaseId, handoff.releaseDigest,
            handoff.resultOutcome, handoff.createdAt)
        if (route == ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED && existingRequest == null) {
            val scheduleId = checkNotNull(attempt.aplusAnchorScheduleId) { "ADVANCE_ANCHOR_MISSING:$attemptId" }
            val itemId = checkNotNull(attempt.aplusAnchorItemId) { "ADVANCE_ANCHOR_MISSING:$attemptId" }
            val version = checkNotNull(attempt.aplusAnchorVersion) { "ADVANCE_ANCHOR_MISSING:$attemptId" }
            val base = CompleteAndAdvanceRequestV1(
                leaseId = handoff.leaseId,
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "", expectedScheduleId = scheduleId,
                expectedScheduleVersion = version, expectedCurrentItemId = itemId,
                completionProof = CompletionProofV1(itemId, trustedCount, task.requiredSuccesses,
                    "ledger-$attemptId", verifiedAtElapsedRealtimeMs),
                callerProtocolVersion = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
            )
            persistAdvanceReplayCarrier(attemptId,
                base.copy(requestDigest = CanonicalAdvanceDigestV1.compute(base)), recordedAt)
        }
        if (current != AttemptState.RELEASE_PENDING) return@withTransaction current
        val next = AttemptTransitions.nextAfterReleaseReceipt(current, route)
        check(next != current) { "RELEASE_RECEIPT_UNDEFINED_EDGE:$attemptId:$current" }
        check(db.testAttemptDao().compareAndSetAplusState(attemptId, current.name, next.name) == 1) {
            "RELEASE_RECEIPT_OWNER_CHANGED:$attemptId"
        }
        check(APlusAttemptDriver(db.auditEventDao()) { recordedAt }
            .driveReleaseReceipt(attemptId, current, route) == next)
        next
    }

    /** Release authority now belongs to this transaction owner, including for advance replay. */
    suspend fun hasMatchingReleaseReceipt(key: String, lease: String, digest: String): Boolean =
        db.withTransaction {
            val byKey = db.releaseReceiptDao().byKey(key)
            val byLease = db.releaseReceiptDao().byLease(lease)
            byKey != null && byKey == byLease && byKey.leaseId == lease &&
                byKey.releaseDigest == digest && byKey.resultOutcome == "RELEASED"
        }

    /** Mirrors the release receipt into the transaction owner DB and verifies an exact replay. */
    suspend fun persistReleaseReceipt(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        createdAt: Long
    ) = db.withTransaction {
        val row = ReleaseReceiptRow(idempotencyKey, leaseId, releaseDigest, outcome, createdAt)
        db.releaseReceiptDao().insertIfAbsent(row)
        val persisted = requireNotNull(db.releaseReceiptDao().byKey(idempotencyKey)) {
            "RELEASE_RECEIPT_NOT_DURABLE:$idempotencyKey"
        }
        check(
            persisted.leaseId == leaseId && persisted.releaseDigest == releaseDigest &&
                persisted.resultOutcome == outcome
        ) { "RELEASE_RECEIPT_CONFLICT:$idempotencyKey" }
    }

    /**
     * #85: atomically bind an exact advance request to the already durable matching release
     * receipt. The full request is deliberately retained, including the timestamp excluded from
     * its canonical digest, so a recovery cannot create a timestamp-only rewrite under the same
     * idempotency key.
     */
    suspend fun persistAdvanceReplayCarrier(
        attemptId: Long,
        request: CompleteAndAdvanceRequestV1,
        createdAt: Long
    ) = db.withTransaction {
        requireNotNull(db.testAttemptDao().getAttemptById(attemptId)) {
            "ADVANCE_CARRIER_ATTEMPT_MISSING:$attemptId"
        }
        check(CanonicalAdvanceDigestV1.compute(request) == request.requestDigest) {
            "ADVANCE_CARRIER_REQUEST_DIGEST_INVALID:$attemptId"
        }
        val releaseKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
            .releaseIdempotencyKey(attemptId)
        val release = requireNotNull(db.releaseReceiptDao().byKey(releaseKey)) {
            "ADVANCE_CARRIER_RELEASE_RECEIPT_MISSING:$attemptId"
        }
        val expectedReleaseDigest = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
            .releaseDigest(request.leaseId)
        check(
            release.leaseId == request.leaseId &&
                release.releaseDigest == expectedReleaseDigest &&
                release.resultOutcome == "RELEASED"
        ) { "ADVANCE_CARRIER_RELEASE_RECEIPT_MISMATCH:$attemptId" }
        val row = AdvanceReplayCarrierRow(
            attemptId = attemptId,
            releaseIdempotencyKey = releaseKey,
            releaseLeaseId = release.leaseId,
            releaseDigest = release.releaseDigest,
            leaseId = request.leaseId,
            idempotencyKey = request.idempotencyKey,
            requestDigest = request.requestDigest,
            expectedScheduleId = request.expectedScheduleId,
            expectedScheduleVersion = request.expectedScheduleVersion,
            expectedCurrentItemId = request.expectedCurrentItemId,
            proofScheduleItemId = request.completionProof.scheduleItemId,
            proofTrustedSuccessCount = request.completionProof.trustedSuccessCount,
            proofQuotaRequired = request.completionProof.quotaRequired,
            proofLedgerRef = request.completionProof.ledgerRef,
            proofVerifiedAtElapsedRealtimeMs = request.completionProof.verifiedAtElapsedRealtimeMs,
            callerProtocolVersion = request.callerProtocolVersion,
            createdAt = createdAt
        )
        db.advanceReplayCarrierDao().insertIfAbsent(row)
        val persisted = requireNotNull(db.advanceReplayCarrierDao().byAttempt(attemptId)) {
            "ADVANCE_CARRIER_NOT_DURABLE:$attemptId"
        }
        // createdAt is audit metadata, not request identity: same exact request may race/replay
        // at a later wall time, while any wire-field difference is a conflict.
        check(persisted.copy(createdAt = createdAt) == row) {
            "ADVANCE_CARRIER_CONFLICT:$attemptId"
        }
    }

    /** Reads and validates the exact replay request; no mutable projection is consulted. */
    suspend fun getAdvanceReplayRequest(attemptId: Long): CompleteAndAdvanceRequestV1? =
        db.withTransaction { readAdvanceReplayRequest(attemptId) }

    // Call within the owner's transaction. Validation rejection must not throw out of a nested
    // Room transaction: even a caught nested failure poisons the owner's subsequent audit commit.
    private suspend fun readAdvanceReplayRequest(attemptId: Long): CompleteAndAdvanceRequestV1? {
        val row = db.advanceReplayCarrierDao().byAttempt(attemptId) ?: return null
        val request = row.toAdvanceRequest()
        check(CanonicalAdvanceDigestV1.compute(request) == request.requestDigest) {
            "ADVANCE_CARRIER_DURABLE_DIGEST_INVALID:$attemptId"
        }
        val release = db.releaseReceiptDao().byKey(row.releaseIdempotencyKey)
        check(
            release != null && release.leaseId == row.releaseLeaseId &&
                release.releaseDigest == row.releaseDigest && release.resultOutcome == "RELEASED" &&
                row.releaseLeaseId == request.leaseId
        ) { "ADVANCE_CARRIER_RELEASE_AUTHORITY_INVALID:$attemptId" }
        return request
    }

    /** Persist a provider advance receipt before projecting any receipt-driven owner state. */
    suspend fun persistAdvanceReceipt(
        attemptId: Long,
        request: CompleteAndAdvanceRequestV1,
        receipt: AdvanceReceiptV1,
        recordedAt: Long
    ) = db.withTransaction {
        val carrier = requireNotNull(db.advanceReplayCarrierDao().byAttempt(attemptId)) {
            "ADVANCE_RECEIPT_CARRIER_MISSING:$attemptId"
        }
        check(carrier.toAdvanceRequest() == request) { "ADVANCE_RECEIPT_REQUEST_CONFLICT:$attemptId" }
        check(
            CanonicalAdvanceReceiptDigestV1.verify(
                receipt,
                request.requestDigest,
                request.idempotencyKey
            )
        ) { "ADVANCE_RECEIPT_DIGEST_INVALID:$attemptId" }
        val row = AdvanceReceiptRow(
            attemptId = attemptId,
            idempotencyKey = request.idempotencyKey,
            requestDigest = request.requestDigest,
            outcomeWire = receipt.outcomeWire,
            advancedFromItemId = receipt.advancedFromItemId,
            advancedToItemId = receipt.advancedToItemId,
            scheduleVersionAfter = receipt.scheduleVersionAfter,
            effectiveIntentHash = receipt.effectiveIntentHash,
            effectiveEnvironmentRevision = receipt.effectiveEnvironmentRevision,
            receiptDigest = receipt.receiptDigest,
            recordedAt = recordedAt
        )
        db.advanceReceiptDao().insertIfAbsent(row)
        // recordedAt describes when this process observed the receipt, not what the provider
        // attested. A restart can replay the same immutable receipt at a later clock value.
        val persisted = requireNotNull(db.advanceReceiptDao().byAttempt(attemptId))
        check(persisted.copy(recordedAt = recordedAt) == row) {
            "ADVANCE_RECEIPT_CONFLICT:$attemptId"
        }
    }

    /** Returns only a receipt whose stored request binding still verifies. */
    suspend fun getAdvanceReceipt(attemptId: Long): AdvanceReceiptV1? =
        db.withTransaction { readAdvanceReceipt(attemptId) }

    private suspend fun readAdvanceReceipt(attemptId: Long): AdvanceReceiptV1? {
        val carrier = db.advanceReplayCarrierDao().byAttempt(attemptId) ?: return null
        val row = db.advanceReceiptDao().byAttempt(attemptId) ?: return null
        val request = carrier.toAdvanceRequest()
        check(row.idempotencyKey == request.idempotencyKey && row.requestDigest == request.requestDigest) {
            "ADVANCE_RECEIPT_REQUEST_BINDING_INVALID:$attemptId"
        }
        val receipt = row.toAdvanceReceipt()
        check(CanonicalAdvanceReceiptDigestV1.verify(receipt, row.requestDigest, row.idempotencyKey)) {
            "ADVANCE_RECEIPT_DURABLE_DIGEST_INVALID:$attemptId"
        }
        return receipt
    }

    /**
     * #86's negative complement to a trusted mint. Replays are allowed only when the immutable
     * typed reason and evidence digest match exactly; a conflicting pre-existing row is corruption,
     * not a second outcome that a recovery path may overwrite.
     */
    suspend fun recordUnverifiedOutcome(attemptId: Long, reason: String, evidenceDigest: String) =
        db.withTransaction {
            val row = UnverifiedAttemptRecord(
                attemptId = attemptId,
                reason = reason,
                evidenceDigest = evidenceDigest
            )
            db.unverifiedAttemptRecordDao().insert(row)
            val persisted = requireNotNull(db.unverifiedAttemptRecordDao().getByAttempt(attemptId)) {
                "unverified carrier was not durable for attempt $attemptId"
            }
            check(persisted.reason == reason && persisted.evidenceDigest == evidenceDigest) {
                "UNVERIFIED_CARRIER_CONFLICT:$attemptId"
            }
        }

    /** R44 (DSF review P1-2): the trusted-count projection for the completeAndAdvance proof. */
    suspend fun trustedCountForTask(taskId: Long): Int =
        db.trustedQuotaDao().trustedCountForTask(taskId)

    // # A+ PASS 终态化（P1-5）：标记 attempt succeeded，successOrdinal 由可信计数投影 1-based（Sol round-9
    // # P1-6：绝不动 legacy completedSuccesses、绝不写 successOrdinal=0）。
    suspend fun finalizeAplusSuccess(attemptId: Long, taskId: Long, endedAt: Long, webScore: Double?, videoScore: Double?) =
        db.testAttemptDao().markSucceeded(
            attemptId = attemptId,
            successOrdinal = db.trustedQuotaDao().trustedCountForTask(taskId),
            runningObservedAt = null,
            endedAt = endedAt,
            webScore = webScore,
            videoScore = videoScore,
            status = "succeeded"
        )

    // ---- Task lifecycle ----

    suspend fun markTaskActive(taskId: Long) = db.locationTaskDao().updateTaskStatus(taskId, "active")

    // # 恢复归一化：满配额但状态未翻转的历史崩溃窗口任务 → completed（F5 兜底）
    suspend fun normalizeQuotaCompletedTasks(): Int =
        db.locationTaskDao().normalizeQuotaCompletedTasks()

    // ---- Attempt lifecycle ----

    /**
     * Durably reserves the next AUTOINCREMENT attempt id without retaining an attempt row.
     * The insert+guarded-delete commit as one short local transaction: `sqlite_sequence` advances,
     * while readers see no attempt. This lets the provider preflight use the exact eventual owner
     * id without holding a Room transaction across Binder IPC or guessing `MAX(id)+1`.
     */
    suspend fun reserveAplusAttemptId(template: TestAttempt): Long = db.withTransaction {
        check(template.id == 0L) { "attempt id reservation requires an auto-generated template" }
        val reservedId = db.testAttemptDao().insert(template)
        check(
            db.testAttemptDao().deletePristineIdReservation(
                reservedId,
                template.taskId,
                template.runSessionId
            ) == 1
        ) { "attempt id reservation was not pristine" }
        reservedId
    }

    /** Persists a preflight-admitted A+ owner and its CAS anchor before any external execution. */
    suspend fun insertAdmittedAplusAttempt(
        attempt: TestAttempt,
        activateTask: Boolean,
        scheduleId: String,
        itemId: String,
        version: Long
    ): Long = db.withTransaction {
        check(attempt.id > 0L) { "admitted A+ attempt requires a reserved id" }
        check(
            attempt.status == "starting" &&
                attempt.runningObservedAt == null &&
                attempt.endedAt == null &&
                attempt.aplusState == null &&
                attempt.aplusLeaseId == null &&
                attempt.currentExecutionId == null &&
                attempt.aplusAnchorScheduleId == null &&
                attempt.aplusAnchorItemId == null &&
                attempt.aplusAnchorVersion == null
        ) { "admitted A+ attempt must start from a pristine reservation template" }
        if (activateTask) {
            db.locationTaskDao().updateTaskStatus(attempt.taskId, "active")
        }
        val insertedId = db.testAttemptDao().insert(
            attempt.copy(
                aplusState = "CREATED",
                aplusAnchorScheduleId = scheduleId,
                aplusAnchorItemId = itemId,
                aplusAnchorVersion = version
            )
        )
        check(insertedId == attempt.id) { "reserved attempt id changed during admission" }
        insertedId
    }

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
        // (1) Persist the FULL §7.1 evidence detail — copied verbatim from ctx.execution. The insert
        // is CONFLICT-IGNORING: the M-CR-06 recovery re-decide runs over an ALREADY-DURABLE execution
        // row; re-persisting it must be a no-op, never a UNIQUE rollback that unwinds the mint.
        db.attemptExecutionDao().insertIfAbsent(ctx.execution)
        // (2) Evaluate the §6.4 predicate.
        val decision = TrustPolicy().evaluate(ctx)
        // Attribution: resolve attemptId → taskId by REAL DB lookup (never a constant, R5-F1).
        // An UNRESOLVABLE attempt cannot be attributed to any task — fail-closed: NO mint, NO
        // unverified record (neither can be bound to a nonexistent attempt); the persisted
        // execution row itself is the audit trail. Decision stays FAIL in that case.
        val attempt = db.testAttemptDao().getAttemptById(ctx.execution.attemptId)
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
                    attemptId = ctx.execution.attemptId,
                    taskId = attempt.taskId,
                    evidenceDigest = ctx.execution.evidencePayloadDigest,
                    committedAt = commitClockMs
                )
            )
            if (inserted == -1L) {
                // IGNORE fired: row already exists. Read back and verify immutable fields match.
                val existing = db.trustedQuotaDao().getByAttempt(ctx.execution.attemptId)
                if (existing == null || existing.taskId != attempt.taskId ||
                    existing.evidenceDigest != ctx.execution.evidencePayloadDigest) {
                    // Immutable field mismatch: a corrupted row exists. Fail-closed.
                    throw IllegalStateException(
                        "TRUSTED_LEDGER_CORRUPTION: existing TrustedQuotaEntry for attempt " +
                        "${ctx.execution.attemptId} has mismatched immutable fields " +
                        "(taskId=${existing?.taskId} vs ${attempt.taskId}, " +
                        "evidenceDigest=${existing?.evidenceDigest} vs ${ctx.execution.evidencePayloadDigest})"
                    )
                }
                // Fields match: legitimate DECIDING crash window re-mint. No-op, proceed.
            }
        } else if (decision == TrustDecision.FAIL && attempt != null) {
            // FAIL for a REAL attempt: the exact unverified carrier (typed reason + evidence digest).
            db.unverifiedAttemptRecordDao().insert(
                UnverifiedAttemptRecord(
                    attemptId = ctx.execution.attemptId,
                    reason = "UNTRUSTED",
                    evidenceDigest = ctx.execution.evidencePayloadDigest
                )
            )
            val persisted = requireNotNull(
                db.unverifiedAttemptRecordDao().getByAttempt(ctx.execution.attemptId)
            ) { "unverified carrier was not durable for attempt ${ctx.execution.attemptId}" }
            check(
                persisted.reason == "UNTRUSTED" &&
                    persisted.evidenceDigest == ctx.execution.evidencePayloadDigest
            ) {
                "UNVERIFIED_CARRIER_CONFLICT:${ctx.execution.attemptId}"
            }
        }
        if (attempt == null) return@withTransaction TrustDecision.FAIL
        decision
    }

    // ---- Session lifecycle ----

    /** Durable start admission for #80; only this transaction creates a newly accepted session. */
    sealed interface RunSessionAdmission {
        data class Created(val sessionId: Long) : RunSessionAdmission
        data class Existing(val sessionId: Long) : RunSessionAdmission
        data object MissingPlan : RunSessionAdmission
    }

    suspend fun admitRunSession(planId: Long, startedAt: Long): RunSessionAdmission = db.withTransaction {
        if (db.planDao().getSelectablePlanById(planId) == null) {
            return@withTransaction RunSessionAdmission.MissingPlan
        }
        db.runSessionDao().findActiveRunningSession(planId)?.let {
            return@withTransaction RunSessionAdmission.Existing(it.id)
        }
        RunSessionAdmission.Created(
            db.runSessionDao().insert(
                RunSession(
                    startedAt = startedAt,
                    status = "starting",
                    configSnapshot = "plan:$planId",
                    planId = planId
                )
            )
        )
    }

    suspend fun createSession(planId: Long, startedAt: Long): Long =
        db.runSessionDao().insert(
            RunSession(startedAt = startedAt, planId = planId, configSnapshot = "plan:$planId")
        )

    suspend fun finishSession(sessionId: Long, status: String, endedAt: Long, totalCycles: Int) =
        db.runSessionDao().finish(sessionId, endedAt, status, totalCycles)
}

private fun AdvanceReplayCarrierRow.toAdvanceRequest(): CompleteAndAdvanceRequestV1 =
    CompleteAndAdvanceRequestV1(
        leaseId = leaseId,
        idempotencyKey = idempotencyKey,
        requestDigest = requestDigest,
        expectedScheduleId = expectedScheduleId,
        expectedScheduleVersion = expectedScheduleVersion,
        expectedCurrentItemId = expectedCurrentItemId,
        completionProof = CompletionProofV1(
            scheduleItemId = proofScheduleItemId,
            trustedSuccessCount = proofTrustedSuccessCount,
            quotaRequired = proofQuotaRequired,
            ledgerRef = proofLedgerRef,
            verifiedAtElapsedRealtimeMs = proofVerifiedAtElapsedRealtimeMs
        ),
        callerProtocolVersion = callerProtocolVersion
    )

private fun AdvanceReceiptRow.toAdvanceReceipt(): AdvanceReceiptV1 =
    AdvanceReceiptV1(
        outcomeWire = outcomeWire,
        advancedFromItemId = advancedFromItemId,
        advancedToItemId = advancedToItemId,
        scheduleVersionAfter = scheduleVersionAfter,
        effectiveIntentHash = effectiveIntentHash,
        effectiveEnvironmentRevision = effectiveEnvironmentRevision,
        receiptDigest = receiptDigest
    )
