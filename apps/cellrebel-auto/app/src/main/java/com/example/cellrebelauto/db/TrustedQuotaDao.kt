package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cellrebelauto.model.plan.TrustedQuotaEntry

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
}
