package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.RunSession
import kotlinx.coroutines.flow.Flow

/**
 * DAO for run_sessions table.
 * # 运行会话表的数据访问对象
 */
@Dao
interface RunSessionDao {

    @Insert
    suspend fun insert(session: RunSession): Long

    // # 结束会话：更新结束时间、状态和循环数
    @Query("UPDATE run_sessions SET endedAt = :endedAt, status = :status, totalCycles = :totalCycles WHERE id = :id")
    suspend fun finish(id: Long, endedAt: Long, status: String, totalCycles: Int)

    // # 获取最近一次会话
    @Query("SELECT * FROM run_sessions ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatest(): RunSession?

    @Query("SELECT * FROM run_sessions WHERE id = :id")
    suspend fun getById(id: Long): RunSession?

    /**
     * Recovery sweep (O4): sessions left `running` by a dead process → interrupted.
     * # 恢复清扫（O4）：进程死亡残留的 running 会话 → interrupted
     */
    @Query("UPDATE run_sessions SET status = 'interrupted', endedAt = :nowMs WHERE status = 'running'")
    suspend fun markStaleRunningSessionsInterrupted(nowMs: Long): Int

    /**
     * Issue #19 R7 P1-2: upgrade advance_pending sessions ONLY when THIS
     * session's own advance records are all resolved. Scoped by runSessionId
     * to prevent Plan A's unresolved from blocking Plan B's completion.
     * §8.1: session stays advance_pending until verified ADVANCED or
     * EXHAUSTED receipt/readback resolves all its advance records.
     * # 推进收尾：仅当本 session 自身的 advance 记录全部 resolved 时升级；
     * # 按 runSessionId 隔离，Plan A 未解决不阻塞 Plan B
     */
    @Query(
        "UPDATE run_sessions SET status = 'completed' " +
            "WHERE status = 'advance_pending' " +
            "AND NOT EXISTS (" +
            "  SELECT 1 FROM advance_records " +
            "  WHERE advance_records.runSessionId = run_sessions.id " +
            "  AND state IN ('pending', 'recovery_required')" +
            ")"
    )
    suspend fun upgradeAdvancePendingSessions(): Int

    // # 获取所有会话列表（用于历史查看）
    @Query("SELECT * FROM run_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<RunSession>>
}
