package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.TestResult
import kotlinx.coroutines.flow.Flow

/**
 * DAO for test_results table.
 * # 测试结果表的数据访问对象
 */
@Dao
interface TestResultDao {

    @Insert
    suspend fun insert(result: TestResult): Long

    // # 按时间倒序获取所有结果（用于 UI 显示）
    @Query("SELECT * FROM test_results ORDER BY timestamp DESC")
    fun getAllResults(): Flow<List<TestResult>>

    // # 按时间倒序获取最近 N 条
    @Query("SELECT * FROM test_results ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentResults(limit: Int): Flow<List<TestResult>>

    // # 获取总记录数
    @Query("SELECT COUNT(*) FROM test_results")
    fun getCount(): Flow<Int>

    // # 按时间正序导出全部（用于 CSV）
    @Query("SELECT * FROM test_results ORDER BY timestamp ASC")
    suspend fun getAllResultsForExport(): List<TestResult>

    // # 获取某次 session 的所有结果
    @Query("SELECT * FROM test_results WHERE runSessionId = :sessionId ORDER BY cycleIndex ASC")
    suspend fun getBySession(sessionId: Long): List<TestResult>

    @Query("DELETE FROM test_results")
    suspend fun deleteAll()
}
