package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for CellRebel execution evidence (§8.6, Task 4).
 *
 * Each CellRebel attempt flow produces one execution record. The executionId
 * is Auto-local (not CellRebel's physical identity — §8.6.1). Evidence fields
 * use elapsedRealtime (§6.4.2 frozen clock source).
 *
 * # CellRebel 执行证据记录。executionId 是 Auto 本地生成的。
 */
@Entity(
    tableName = "cellrebel_executions",
    foreignKeys = [ForeignKey(
        entity = TestAttempt::class,
        parentColumns = ["id"], childColumns = ["attemptId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("attemptId")],
)
data class CellRebelExecution(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptId: Long,
    val executionId: String,
    // # §8.6.2: five-value completion evidence
    val completionVerdict: String,
    // # §6.4.2: elapsedRealtime timestamps
    val startedAtElapsed: Long,
    val endedAtElapsed: Long? = null,
    val createdAt: Long,
)
