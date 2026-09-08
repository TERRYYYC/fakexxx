package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.DurableObservationRecord

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
}
