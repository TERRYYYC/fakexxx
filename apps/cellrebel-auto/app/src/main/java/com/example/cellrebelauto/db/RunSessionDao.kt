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

    // # A+ 恢复态投影（§8.2 RECOVERING/PAUSED）：仅改状态，不结束会话
    @Query("UPDATE run_sessions SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT * FROM run_sessions WHERE id = :id")
    suspend fun getById(id: Long): RunSession?

    /**
     * The active (running) session for a plan — the crashed owner session the A+ recovery must
     * TRANSITION (RECOVERING → RUNNING/PAUSED) rather than mint a second active run
     * (Sol round-8 P1-6).
     */
    @Query("SELECT * FROM run_sessions WHERE planId = :planId AND status = 'running' ORDER BY startedAt DESC LIMIT 1")
    suspend fun findActiveRunningSession(planId: Long): RunSession?

    /**
     * Recovery sweep (O4): sessions left `running` by a dead process → interrupted.
     * # 恢复清扫（O4）：进程死亡残留的 running 会话 → interrupted
     */
    @Query("UPDATE run_sessions SET status = 'interrupted', endedAt = :nowMs WHERE status = 'running'")
    suspend fun markStaleRunningSessionsInterrupted(nowMs: Long): Int

    // # 获取所有会话列表（用于历史查看）
    @Query("SELECT * FROM run_sessions ORDER BY startedAt DESC")
    fun getAllSessions(): Flow<List<RunSession>>
}
