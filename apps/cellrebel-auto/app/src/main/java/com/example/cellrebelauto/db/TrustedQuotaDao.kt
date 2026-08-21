package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cellrebelauto.model.plan.TrustedQuotaEntry
import kotlinx.coroutines.flow.Flow

/**
 * Projection row: taskId → trusted quota count.
 * # 投影行：任务 → 可信配额计数
 */
data class TaskQuotaProjection(val taskId: Long, val count: Int)

/**
 * DAO for the trusted quota ledger (§7.3).
 *
 * Insert-only: entries are never updated or deleted by application code.
 * [insert] uses IGNORE conflict strategy so duplicate attemptId inserts
 * return -1 without throwing — the caller doesn't need to catch exceptions.
 *
 * [countForTask] is the canonical quota query:
 *   `count(TrustedQuotaEntry where taskId) >= requiredSuccesses`
 * determines whether a task has met its quota for advance eligibility.
 *
 * [observeCountsForPlan] is the reactive projection for PlanUiState (§7.3).
 *
 * # 可信配额台账 DAO（§7.3）。
 * # 只插不改；IGNORE 策略让重复 attemptId 静默返回 -1。
 */
@Dao
interface TrustedQuotaDao {

    /**
     * Insert a new trusted quota entry. Returns the new row ID, or -1 if
     * the entry was ignored (duplicate attemptId — UNIQUE constraint).
     * # 插入一条可信配额条目。重复 attemptId 返回 -1（UNIQUE 约束忽略）。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: TrustedQuotaEntry): Long

    /**
     * Count trusted quota entries for a task. This is the durable source
     * of truth for quota decisions — NOT LocationTask.completedSuccesses.
     * # 统计某任务的可信配额条目数。这是配额决策的持久真相源。
     */
    @Query("SELECT COUNT(*) FROM trusted_quota_entries WHERE taskId = :taskId")
    suspend fun countForTask(taskId: Long): Int

    /**
     * Batch quota counts for all tasks in a plan (§7.3 projection).
     * Join through location_tasks to scope by planId.
     * Returns only tasks that have at least one entry.
     * # 批量查询某计划所有任务的配额计数。通过 location_tasks JOIN 限制范围。
     */
    @Query(
        "SELECT tqe.taskId, COUNT(*) as count FROM trusted_quota_entries tqe " +
            "INNER JOIN location_tasks lt ON tqe.taskId = lt.id " +
            "WHERE lt.planId = :planId GROUP BY tqe.taskId"
    )
    suspend fun countsForPlan(planId: Long): List<TaskQuotaProjection>

    /**
     * Observable quota counts for all tasks in a plan (reactive PlanUiState).
     * # 可观察的计划级配额计数（PlanUiState 响应式刷新用）。
     */
    @Query(
        "SELECT tqe.taskId, COUNT(*) as count FROM trusted_quota_entries tqe " +
            "INNER JOIN location_tasks lt ON tqe.taskId = lt.id " +
            "WHERE lt.planId = :planId GROUP BY tqe.taskId"
    )
    fun observeCountsForPlan(planId: Long): Flow<List<TaskQuotaProjection>>
}
