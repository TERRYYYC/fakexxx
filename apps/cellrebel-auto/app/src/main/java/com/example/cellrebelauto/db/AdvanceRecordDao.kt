package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.plan.AdvanceRecord

/**
 * DAO for advance_records (§8.1 durable advance state).
 *
 * # 推进记录表的数据访问对象（§8.1 持久化推进状态）
 */
@Dao
interface AdvanceRecordDao {

    @Insert
    suspend fun insert(record: AdvanceRecord): Long

    /**
     * Find the latest unresolved advance for a task.
     * Unresolved states: pending, recovery_required.
     * R6 P1-1: provider absence leaves records pending — §8.1 requires
     * ADVANCE_PENDING path to persist until verified resolution.
     * # 查找某任务最新的未解决推进记录。
     */
    @Query(
        "SELECT * FROM advance_records " +
            "WHERE taskId = :taskId AND state IN ('pending', 'recovery_required') " +
            "ORDER BY id DESC LIMIT 1"
    )
    suspend fun getUnresolved(taskId: Long): AdvanceRecord?

    /**
     * Resolve a record with a terminal outcome.
     * # 以终态结果解决一条记录
     */
    @Query(
        "UPDATE advance_records SET state = :outcome, receiptDigest = :receiptDigest, reason = :reason " +
            "WHERE id = :id"
    )
    suspend fun resolve(id: Long, outcome: String, receiptDigest: String?, reason: String?)

    /**
     * List ALL unresolved records (for recovery sweep at engine start).
     * Only pending and recovery_required are replayed.
     * R6 P1-1: provider absence leaves records pending, preserving
     * a real-identity recovery path. Recovery retries when provider
     * becomes available — no synthetic identity concern.
     * # 列出全部未解决记录（引擎启动时恢复清扫用）。
     */
    @Query(
        "SELECT * FROM advance_records " +
            "WHERE state IN ('pending', 'recovery_required') " +
            "ORDER BY id ASC"
    )
    suspend fun listAllUnresolved(): List<AdvanceRecord>
}
