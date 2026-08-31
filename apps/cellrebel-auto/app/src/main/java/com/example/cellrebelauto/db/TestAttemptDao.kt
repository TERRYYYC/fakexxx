package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.flow.Flow

/**
 * Per-task attempt count projection for the Plan screen cards.
 * # 每个任务的尝试总数投影（Plan 页卡片用）
 */
data class TaskAttemptCount(
    val taskId: Long,
    val count: Int
)

/**
 * DAO for test_attempts.
 * # 测试尝试表的数据访问对象
 */
@Dao
interface TestAttemptDao {

    @Insert
    suspend fun insert(attempt: TestAttempt): Long

    // # 全量尝试（导出用，按 id 升序 = 时间顺序）
    @Query("SELECT * FROM test_attempts ORDER BY id ASC")
    suspend fun getAllAttempts(): List<TestAttempt>

    // # 观察全量尝试（History 页，最新在前）
    @Query("SELECT * FROM test_attempts ORDER BY id DESC")
    fun observeAllAttempts(): Flow<List<TestAttempt>>

    @Query("SELECT * FROM test_attempts WHERE taskId = :taskId ORDER BY attemptOrdinal ASC")
    suspend fun getAttemptsForTask(taskId: Long): List<TestAttempt>

    /** The single attempt row by id (GREEN: recordTrustedCompletion resolves attemptId → taskId here, never a constant). */
    @Query("SELECT * FROM test_attempts WHERE id = :attemptId LIMIT 1")
    suspend fun getAttemptById(attemptId: Long): TestAttempt?

    // # 经任务表联查某计划下的全部尝试
    @Query(
        "SELECT a.* FROM test_attempts a INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId ORDER BY a.id ASC"
    )
    suspend fun getAttemptsForPlan(planId: Long): List<TestAttempt>

    // # 最近一次终态尝试的 endedAt，用于缓冲投影（INV-5）。
    // # F3R1-2：终态判定 = endedAt IS NOT NULL（对所有终态免疫，
    // # 包括 ok_gps_only 与未来的新增终态），不再按 status 枚举
    @Query(
        "SELECT MAX(a.endedAt) FROM test_attempts a INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId AND a.endedAt IS NOT NULL"
    )
    suspend fun getLatestTerminalAttemptEndedAtForPlan(planId: Long): Long?

    @Query("SELECT COUNT(*) FROM test_attempts WHERE taskId = :taskId")
    suspend fun countAttemptsForTask(taskId: Long): Int

    // # 观察某计划下每个任务的尝试总数（Plan 页卡片 Attempts n）
    @Query(
        "SELECT a.taskId AS taskId, COUNT(*) AS count FROM test_attempts a " +
            "INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId GROUP BY a.taskId"
    )
    fun observeAttemptCountsForPlan(planId: Long): Flow<List<TaskAttemptCount>>

    /**
     * Recovery sweep (INV-9): mark leftover starting/running rows interrupted.
     *
     * R43 (Sol GREEN-review P1-2): rows that entered the A+ lifecycle (aplusState non-null) are
     * EXCLUDED — they are §8.1 owner-state machines whose non-terminal phases are RECONCILED by the
     * A+ recovery path (re-observe / classify / re-decide), never blind-swept to interrupted. A
     * just-advanced recovery phase (e.g. CELLREBEL_START_PENDING with status still `starting`) must
     * survive this sweep.
     *
     * # 恢复清扫（INV-9）：A+ 生命周期内的行绝不盲扫——由 §8.1 恢复路径协调
     */
    @Query(
        "UPDATE test_attempts SET status = 'interrupted', failureReason = 'INTERRUPTED', endedAt = :nowMs " +
            "WHERE status IN ('starting', 'running') AND aplusState IS NULL"
    )
    suspend fun markNonTerminalInterrupted(nowMs: Long): Int

    /**
     * Persist the starting -> running transition the moment RUNNING evidence is
     * observed (spec O3 state table, C2). Only from 'starting' — never clobbers
     * a terminal state.
     * # 观察到 RUNNING 证据的瞬间持久化 starting -> running 迁移（C2）；
     * # 仅从 starting 迁移，绝不覆盖终态
     */
    @Query(
        "UPDATE test_attempts SET status = 'running', runningObservedAt = :runningAt " +
            "WHERE id = :attemptId AND status = 'starting'"
    )
    suspend fun markRunning(attemptId: Long, runningAt: Long): Int

    /**
     * Finalize a succeeded attempt (called inside the finalize transaction, INV-3).
     * # 成功尝试收尾（在 finalize 事务内调用）
     */    @Query(
        "UPDATE test_attempts SET status = :status, successOrdinal = :successOrdinal, " +
            "runningObservedAt = COALESCE(runningObservedAt, :runningObservedAt), endedAt = :endedAt, " +
            "webBrowsingScore = :webScore, videoStreamingScore = :videoScore WHERE id = :attemptId"
    )
    suspend fun markSucceeded(
        attemptId: Long,
        successOrdinal: Int,
        runningObservedAt: Long?,
        endedAt: Long,
        webScore: Double?,
        videoScore: Double?,
        status: String = "succeeded"
    )

    /**
     * Finalize a failed attempt with a typed reason (INV-4/10).
     * # 失败尝试收尾，带类型化原因
     */
    @Query("UPDATE test_attempts SET status = 'failed', failureReason = :reason, endedAt = :endedAt WHERE id = :attemptId")
    suspend fun markFailed(attemptId: Long, reason: String, endedAt: Long)

    /**
     * Stop/cancel path: interrupt the in-flight attempt only if still non-terminal.
     * # 停止/取消路径：仅当尝试仍为非终态时标记 interrupted
     */
    @Query(
        "UPDATE test_attempts SET status = 'interrupted', failureReason = 'INTERRUPTED', endedAt = :nowMs " +
            "WHERE id = :attemptId AND status IN ('starting', 'running')"
    )
    suspend fun markInterruptedIfNonTerminal(attemptId: Long, nowMs: Long)

    // ---- A+ current-operation owner state (R9, Sol round-8 P1-3/P1-4) ----

    /** Persist the current §8.1 phase on the attempt (§7.1: the Attempt owns its 当前 operation). */
    @Query("UPDATE test_attempts SET aplusState = :aplusState WHERE id = :attemptId")
    suspend fun markAplusState(attemptId: Long, aplusState: String)

    /**
     * Atomically mark RECOVERY_REQUIRED with a typed reason (Sol R2 P1-3: durable leg-specific reason).
     * The reason is stored in `failureReason` — the test can read it back to verify which specific
     * invariant was violated (e.g., "OBSERVED_TUPLE_MISMATCH:acceptedIntentHash").
     */
    @Query("UPDATE test_attempts SET aplusState = 'RECOVERY_REQUIRED', failureReason = :reason WHERE id = :attemptId")
    suspend fun markRecoveryRequired(attemptId: Long, reason: String)

    /**
     * Publish a terminal-failure continuation and its RELEASE_PENDING owner boundary together.
     * A process may observe neither field or both fields, never a typed reason on an older phase.
     */
    @Query(
        "UPDATE test_attempts SET failureReason = :reason, aplusState = 'RELEASE_PENDING' " +
            "WHERE id = :attemptId AND status IN ('starting','running') " +
            "AND aplusState NOT IN ('RELEASED','CLOSED','ADVANCE_PENDING','ADVANCE_OBSERVING','ADVANCE_STATE_READBACK')"
    )
    suspend fun markFailureContinuationAndReleasePending(attemptId: Long, reason: String): Int

    /** RELEASE_PENDING → RELEASED CAS used by the atomic audit/checkpoint transaction. */
    @Query(
        "UPDATE test_attempts SET aplusState = 'RELEASED' " +
            "WHERE id = :attemptId AND aplusState = 'RELEASE_PENDING'"
    )
    suspend fun markReleasedFromPending(attemptId: Long): Int

    /** Persist the provider-returned lease id (NOT derivable — must be durable, Sol round-8 P1-4). */
    @Query("UPDATE test_attempts SET aplusLeaseId = :leaseId WHERE id = :attemptId")
    suspend fun markAplusLease(attemptId: Long, leaseId: String)

    /** The persisted lease id for an attempt (the release is bound to it, Sol round-8 P1-4). */
    @Query("SELECT aplusLeaseId FROM test_attempts WHERE id = :attemptId")
    suspend fun getAplusLeaseId(attemptId: Long): String?

    /** Persist the current §8.6.1 executionId (Sol R35 P1-2: recovery must locate the CURRENT execution). */
    @Query("UPDATE test_attempts SET currentExecutionId = :executionId WHERE id = :attemptId")
    suspend fun markCurrentExecutionId(attemptId: Long, executionId: String)

    // ---- R45 (Sol R45 P1-3 / spec §4.3 step 1): the advance CAS anchor triple ----
    // Persisted at attempt open from the SAME discover() projection group; completeAndAdvance
    // replays this triple byte-for-byte and MUST NOT re-discover at advance time (§6.7.3 v1.72).

    /** Persist the anchored advance CAS triple (before any external execution starts). */
    @Query(
        "UPDATE test_attempts SET aplusAnchorScheduleId = :scheduleId, aplusAnchorItemId = :itemId, " +
            "aplusAnchorVersion = :version WHERE id = :attemptId"
    )
    suspend fun markAplusAdvanceAnchor(attemptId: Long, scheduleId: String, itemId: String, version: Long)

    /** The anchored triple for an attempt (null columns = not yet anchored). */
    @Query(
        "SELECT aplusAnchorScheduleId, aplusAnchorItemId, aplusAnchorVersion FROM test_attempts WHERE id = :attemptId"
    )
    suspend fun getAplusAdvanceAnchor(attemptId: Long): AplusAdvanceAnchor?

    /** The persisted apply-receipt operationId for an attempt (post-advance observe tuple leg). */
    @Query(
        "SELECT r.operationId FROM operation_receipts r WHERE r.idempotencyKey = :idempotencyKey"
    )
    suspend fun getOperationIdForIdempotencyKey(idempotencyKey: String): String?

    /** Anchor projection row for [getAplusAdvanceAnchor]. */
    data class AplusAdvanceAnchor(
        val aplusAnchorScheduleId: String?,
        val aplusAnchorItemId: String?,
        val aplusAnchorVersion: Long?
    )

    /** The persisted current executionId for an attempt. */
    @Query("SELECT currentExecutionId FROM test_attempts WHERE id = :attemptId")
    suspend fun getCurrentExecutionId(attemptId: Long): String?

    /**
     * A+ recoverable attempts: non-terminal rows that entered the A+ lifecycle (aplusState non-null) —
     * recovery branches on their persisted phase, never on a generic `starting|running` status
     * (Sol round-8 P1-3).
     */
    @Query(
        "SELECT a.* FROM test_attempts a INNER JOIN location_tasks t ON a.taskId = t.id " +
            "WHERE t.planId = :planId AND a.aplusState IS NOT NULL AND a.status IN ('starting','running')"
    )
    suspend fun findAplusRecoverableAttempts(planId: Long): List<TestAttempt>
}
