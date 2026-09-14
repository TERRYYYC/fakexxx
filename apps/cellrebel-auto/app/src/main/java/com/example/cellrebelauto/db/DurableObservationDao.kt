package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface DurableObservationDao {
    @Insert
    suspend fun insert(record: DurableObservationRecord): Long

    @Query("SELECT * FROM durable_observation_records WHERE attemptId = :attemptId AND phase = :phase LIMIT 1")
    suspend fun forAttemptPhase(attemptId: Long, phase: String): DurableObservationRecord?

    @Query("SELECT * FROM durable_observation_records WHERE attemptId = :attemptId")
    suspend fun forAttempt(attemptId: Long): List<DurableObservationRecord>

    @Query("SELECT COUNT(*) FROM durable_observation_records WHERE attemptId = :attemptId")
    suspend fun countForAttempt(attemptId: Long): Int

    /**
     * v1.81 CI-attestation read side: every PRE/POST record of one plan that
     * carries a captured serving cell (attempt → task → plan join), oldest
     * first. Rows with a NULL servingCi are the honest "未捕获" records
     * (migrated v9 history / failed radio reads) and are excluded — they
     * attest nothing. Evidence-only: the result feeds offline cross-checks
     * against the discover `configuredCell*` projection, never TrustPolicy.
     */
    @Query(
        "SELECT durable_observation_records.* FROM durable_observation_records " +
            "INNER JOIN test_attempts ON test_attempts.id = durable_observation_records.attemptId " +
            "WHERE test_attempts.taskId IN (SELECT id FROM location_tasks WHERE planId = :planId) " +
            "AND durable_observation_records.servingCi IS NOT NULL " +
            "ORDER BY durable_observation_records.id ASC"
    )
    suspend fun observationsWithServingCellForPlan(planId: Long): List<DurableObservationRecord>

    /**
     * Plan-map 实测层（evidence-only）的每任务一行投影：该 task 最近一次 succeeded
     * attempt（status IN ('succeeded','ok_gps_only')，与 RunProgressProjection 的成功
     * 语义一致）内 **id 最大且坐标非空**的那条观察（同 attempt 内后写入的行 id 更大，
     * 正常流即 POST；若 POST 未捕获坐标而 PRE 有，则如实取 PRE——坐标为 NULL 的行
     * 是诚实的"未捕获"，绝不参与）。该 attempt 若没有任何带坐标观察，该 task 不出现
     * 在结果里（严格"最近一次 succeeded"语义，绝不回退到更早的 attempt——宁可无
     * 标记，不画旧证据）。
     *
     * 跨 run 新鲜度（#185 F4）：只认该 plan 最近一次 run session（run_sessions.planId
     * 关联）启动之后捕获的观察——`observedAtEpochMs >= MAX(session.startedAt)`，重复
     * 运行时上一 run 的旧证据不立即上屏，语义即"该计划最近一次 run 的实测"。v3 之前
     * 无 plan 关联的旧会话不设门槛（COALESCE 0，历史数据行为不变）；run_sessions 进
     * 查询后 Room 失效跟踪会在新 session 开始时自动重发本流。
     *
     * #190：投影同时带出同一条观察采样到的 `servingCi`（observeLive 在坐标同一
     * 时刻读的 TelephonyManager 值——hook 下即注入值，正是要与 expectedCi 对照的
     * 实测）。NULL = 该观察未捕获小区（v10 前历史行/读数失败）：UI 如实显示
     * "未捕获"，绝不编造。仍只选带坐标的行——位置与小区取同一条证据，两层
     * 对照永远同源同时刻。
     *
     * SCOPE RED LINE: 展示层只读投影——与 TrustPolicy / 配额入账判定完全无关，
     * 匹配判定仅复用 [com.example.cellrebelauto.automation.selfheal.CoordinateGuard]
     * 的容差语义做可视化。
     */
    @Query(
        "SELECT t.id AS taskId, o.effectiveLat AS measuredLat, o.effectiveLng AS measuredLng, " +
            "o.observedAtEpochMs AS observedAtEpochMs, o.verificationLevel AS verificationLevel, " +
            "o.servingCi AS servingCi " +
            "FROM location_tasks t " +
            "INNER JOIN test_attempts latest ON latest.id = (" +
            "  SELECT MAX(a.id) FROM test_attempts a WHERE a.taskId = t.id " +
            "  AND a.status IN ('succeeded', 'ok_gps_only')) " +
            "INNER JOIN durable_observation_records o ON o.attemptId = latest.id " +
            "  AND o.effectiveLat IS NOT NULL AND o.effectiveLng IS NOT NULL " +
            "  AND o.id = (" +
            "    SELECT MAX(o2.id) FROM durable_observation_records o2 " +
            "    WHERE o2.attemptId = latest.id " +
            "    AND o2.effectiveLat IS NOT NULL AND o2.effectiveLng IS NOT NULL) " +
            "WHERE t.planId = :planId " +
            "AND o.observedAtEpochMs >= COALESCE(" +
            "  (SELECT MAX(rs.startedAt) FROM run_sessions rs WHERE rs.planId = t.planId), 0)"
    )
    fun observeLatestSucceededObservationsForPlan(planId: Long): Flow<List<TaskMeasuredObservation>>
}

/**
 * One row of the plan-map measured overlay projection (see
 * [DurableObservationDao.observeLatestSucceededObservationsForPlan]).
 * # 实测层投影行：taskId → 最近一次 succeeded attempt 的最新带坐标观察
 * # （#190：servingCi = 同一条观察采样的实测小区；null = 未捕获）
 */
data class TaskMeasuredObservation(
    val taskId: Long,
    val measuredLat: Double,
    val measuredLng: Double,
    val observedAtEpochMs: Long,
    val verificationLevel: String,
    val servingCi: Long? = null,
)
