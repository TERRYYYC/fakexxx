package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Trusted quota ledger entry (§7.3, §8.1 M-AD-14).
 *
 * Each successful attempt that passes the CAS guard inserts exactly one
 * entry. The quota gate reads `count(TrustedQuotaEntry where taskId)`.
 * UNIQUE(attemptId) enforces at-most-once semantics — duplicate inserts
 * are silently ignored (INSERT OR IGNORE), making the ledger idempotent
 * across crash/replay.
 *
 * This is INSERT-ONLY: entries are never updated or deleted by application
 * code. They are the durable truth for quota decisions; the denormalized
 * `LocationTask.completedSuccesses` counter is kept in sync within the
 * same transaction for UI convenience but is NOT the source of truth for
 * advance decisions.
 *
 * # 可信配额台账条目（§7.3、§8.1 M-AD-14）。
 * # 每次通过 CAS 守卫的成功尝试插入恰好一条。
 * # UNIQUE(attemptId) 保证至多一次语义——重复插入静默忽略。
 * # 只插不改，是配额决策的持久真相源。
 */
@Entity(
    tableName = "trusted_quota_entries",
    foreignKeys = [
        ForeignKey(
            entity = LocationTask::class,
            parentColumns = ["id"], childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = TestAttempt::class,
            parentColumns = ["id"], childColumns = ["attemptId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["attemptId"], unique = true),
        Index("taskId"),
    ],
)
data class TrustedQuotaEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // # 关联的尝试 ID（UNIQUE — 幂等性守卫）
    val attemptId: Long,
    // # 关联的任务 ID
    val taskId: Long,
    // # 完成证据摘要（attempt 完成上下文的 SHA-256）
    val evidenceDigest: String,
    // # 插入时间戳
    val createdAt: Long,
)
