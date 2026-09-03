package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.DurableObservationRecord

@Dao
interface DurableObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(record: DurableObservationRecord): Long

    @Query("SELECT * FROM durable_observation_records WHERE attemptId = :attemptId AND phase = :phase LIMIT 1")
    suspend fun forAttemptPhase(attemptId: Long, phase: String): DurableObservationRecord?

    @Query("SELECT * FROM durable_observation_records WHERE attemptId = :attemptId")
    suspend fun forAttempt(attemptId: Long): List<DurableObservationRecord>

    @Query("SELECT COUNT(*) FROM durable_observation_records WHERE attemptId = :attemptId")
    suspend fun countForAttempt(attemptId: Long): Int
}
