package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for v4→v5 legacy completion data (§7.1, §7.3, Task 4).
 *
 * Written ONCE during MIGRATION_4_5: snapshots the pre-v5 `completedSuccesses`
 * and `status` from each LocationTask. These counts are LEGACY_UNVERIFIED —
 * they predate TrustedQuotaEntry and must NOT generate quota entries or
 * participate in the `completed` projection (§7.3).
 *
 * Read-only after migration. Displayed as historical context only.
 *
 * # v4→v5 迁移时一次性快照的旧进度数据。只读展示，不计入可信配额。
 */
@Entity(
    tableName = "legacy_completion_snapshots",
    foreignKeys = [ForeignKey(
        entity = LocationTask::class,
        parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId", unique = true)],
)
data class LegacyCompletionSnapshot(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val legacyCompletedSuccesses: Int,
    val legacyStatus: String,
    val migratedFromSchemaVersion: Int,
    val migratedAt: Long,
)
