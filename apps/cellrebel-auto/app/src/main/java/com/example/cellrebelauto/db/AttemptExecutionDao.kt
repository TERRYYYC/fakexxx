package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.execution.CellRebelExecution

/**
 * DAO for `cellrebel_executions` — the observed external execution records (§7.1, §8.6).
 * # CellRebel 执行记录 DAO
 */
@Dao
interface AttemptExecutionDao {

    @Insert
    suspend fun insert(execution: CellRebelExecution): Long

    @Query("SELECT * FROM cellrebel_executions WHERE attemptId = :attemptId ORDER BY id ASC")
    suspend fun forAttempt(attemptId: Long): List<CellRebelExecution>

    @Query("SELECT * FROM cellrebel_executions WHERE executionId = :executionId LIMIT 1")
    suspend fun byExecutionId(executionId: String): CellRebelExecution?

    /** Count executions by executionId (Sol R35 P1-2: detects cross-attempt executionId collision). */
    @Query("SELECT COUNT(*) FROM cellrebel_executions WHERE executionId = :executionId")
    suspend fun countByExecutionId(executionId: String): Int

    /** Count all executions for an attempt (Sol R35 P1-2: detects decoy/current confusion). */
    @Query("SELECT COUNT(*) FROM cellrebel_executions WHERE attemptId = :attemptId")
    suspend fun countForAttempt(attemptId: Long): Int
}
