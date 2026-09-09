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

    // # A+ 恢复态投影（§8.2 RECOVERING/PAUSED）：重开先前被错误终态化的 owner 时，
    // # active 状态与 endedAt 必须原子一致（endedAt=null 才表示仍可恢复）。
    @Query("UPDATE run_sessions SET status = :status, endedAt = NULL WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    /** Supersede a replacement session without overwriting its accumulated cycle count. */
    @Query("UPDATE run_sessions SET status = 'interrupted', endedAt = :endedAt WHERE id = :id")
    suspend fun interruptForRecoveryConflict(id: Long, endedAt: Long)

    /** #97: terminalize only the exact active owner after Stop/Verify has joined the engine job. */
    @Query(
        "UPDATE run_sessions SET status = 'stopped', endedAt = :endedAt " +
            "WHERE id = :id AND status IN ('starting','running','recovering','paused')"
    )
    suspend fun stopForSupersession(id: Long, endedAt: Long): Int

    /**
     * #135 abandon: terminalize the plan's exact active session with the SAME guarded
     * owner shape as [stopForSupersession] — never touches a terminal row or the
     * accumulated cycle count.
     * # 放弃计划（#135）：以与 stopForSupersession 相同的 owner 守卫终态化活跃 session；
     * # 绝不覆盖已终态行与累计循环数
     */
    @Query(
        "UPDATE run_sessions SET status = 'stopped', endedAt = :endedAt " +
            "WHERE id = :id AND status IN ('starting','running','recovering','paused')"
    )
    suspend fun stopForAbandon(id: Long, endedAt: Long): Int

    @Query("SELECT * FROM run_sessions WHERE id = :id")
    suspend fun getById(id: Long): RunSession?

    @Query("SELECT * FROM run_sessions WHERE planId = :planId ORDER BY startedAt DESC, id DESC LIMIT 1")
    suspend fun getLatestForPlan(planId: Long): RunSession?

    /**
     * The active session for a plan — the crashed owner session the A+ recovery must TRANSITION
     * (RECOVERING → RUNNING/PAUSED) rather than mint a second active run (Sol round-8 P1-6). Recognizes
     * `running`, `recovering` AND `paused`: a crash DURING recovery persists `recovering` (second-restart),
     * and a cancel/throw persists `paused` with a still-live lease that the next start must reconcile, not
     * orphan (Sol round-10 P1-5).
     */
    @Query("SELECT * FROM run_sessions WHERE planId = :planId AND status IN ('starting','running','recovering','paused') ORDER BY startedAt DESC LIMIT 1")
    suspend fun findActiveRunningSession(planId: Long): RunSession?

    /**
     * Recovery sweep (O4) that EXCLUDES the current owner session: sessions left `running` by a dead
     * process → interrupted, but the A+-recovered owner (which recovery just marked `running`) must not
     * be clobbered (Sol round-9 P1-4: the global sweep used to interrupt the just-recovered owner).
     */
    @Query(
        "UPDATE run_sessions SET status = 'interrupted', endedAt = :nowMs " +
            "WHERE status = 'running' AND id != :excludeId"
    )
    suspend fun markStaleSessionsInterruptedExcept(nowMs: Long, excludeId: Long): Int

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
