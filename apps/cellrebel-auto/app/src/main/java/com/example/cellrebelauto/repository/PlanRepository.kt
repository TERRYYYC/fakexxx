package com.example.cellrebelauto.repository

import androidx.room.withTransaction
import com.example.cellrebelauto.automation.plan.PlanScheduler
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.db.TaskAttemptCount
import com.example.cellrebelauto.model.RunSession
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

    // ---- Session lifecycle ----

    suspend fun createSession(planId: Long, startedAt: Long): Long =
        db.runSessionDao().insert(
            RunSession(startedAt = startedAt, planId = planId, configSnapshot = "plan:$planId")
        )

    suspend fun finishSession(sessionId: Long, status: String, endedAt: Long, totalCycles: Int) =
        db.runSessionDao().finish(sessionId, endedAt, status, totalCycles)
}
