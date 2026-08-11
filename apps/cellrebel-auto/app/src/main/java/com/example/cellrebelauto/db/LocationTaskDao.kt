package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Query
import com.example.cellrebelauto.model.plan.LocationTask
import kotlinx.coroutines.flow.Flow

/**
 * DAO for location_tasks.
 * # 位置任务表的数据访问对象
 */
@Dao
interface LocationTaskDao {

    // # 执行顺序：priority ASC, csvRow ASC（INV-1）
    @Query("SELECT * FROM location_tasks WHERE planId = :planId ORDER BY priority ASC, csvRow ASC")
    suspend fun getTasksForPlan(planId: Long): List<LocationTask>

    // # 观察某计划的任务列表（执行顺序），供 Plan 页卡片实时刷新
    @Query("SELECT * FROM location_tasks WHERE planId = :planId ORDER BY priority ASC, csvRow ASC")
    fun observeTasksForPlan(planId: Long): Flow<List<LocationTask>>

    @Query("SELECT * FROM location_tasks WHERE planId = :planId AND status = 'active' LIMIT 1")
    suspend fun getActiveTaskForPlan(planId: Long): LocationTask?

    @Query("SELECT * FROM location_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): LocationTask?

    // # 全量任务（History/导出时的 attempt↔task 联接）
    @Query("SELECT * FROM location_tasks")
    suspend fun getAllTasks(): List<LocationTask>

    // # 观察全量任务（History 页联接）
    @Query("SELECT * FROM location_tasks")
    fun observeAllTasks(): Flow<List<LocationTask>>

    @Query("UPDATE location_tasks SET status = :status WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: Long, status: String)

    /**
     * Guarded increment for INV-3 idempotency: only increments when the current
     * completedSuccesses still equals the expected value. Returns affected rows.
     * # 守卫式自增（INV-3 幂等）：仅当前值仍等于期望值时才 +1，返回受影响行数
     */
    @Query(
        "UPDATE location_tasks SET completedSuccesses = completedSuccesses + 1 " +
            "WHERE id = :taskId AND completedSuccesses = :expectedCompletedSuccesses"
    )
    suspend fun incrementSuccessIfCurrent(taskId: Long, expectedCompletedSuccesses: Int): Int

    @Query("UPDATE location_tasks SET status = 'completed' WHERE id = :taskId")
    suspend fun markTaskCompleted(taskId: Long)

    /**
     * Quota-reached completion, called INSIDE the success finalize transaction
     * so the task never lingers active with a full quota (F5 crash window).
     * # 配额达成即完成：在成功收尾事务内调用，杜绝 active+满配额 的崩溃窗口
     */
    @Query("UPDATE location_tasks SET status = 'completed' WHERE id = :taskId AND completedSuccesses >= requiredSuccesses")
    suspend fun completeTaskIfQuotaReached(taskId: Long): Int

    /**
     * Recovery normalization for the pre-fix crash window: any task whose quota
     * is already full but status was never flipped gets completed on plan load.
     * # 恢复归一化：兜底修复历史崩溃窗口留下的满配额非 completed 任务
     */
    @Query("UPDATE location_tasks SET status = 'completed' WHERE completedSuccesses >= requiredSuccesses AND status != 'completed'")
    suspend fun normalizeQuotaCompletedTasks(): Int
}
