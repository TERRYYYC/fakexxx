package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.cellrebelauto.model.RunSession

/**
 * Room entities for the prioritized location test plan (DB v4, F003 stageNotes).
 * # 位置测试计划的 Room 实体（数据库版本 4，F003 stageNotes）
 */

/**
 * One imported CSV worklist. NO status field — plan status is a pure projection.
 * # 一次导入的 CSV 工作清单。无 status 字段——计划状态是纯投影
 */
@Entity(tableName = "location_plans")
data class LocationPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // # 来源文件名
    val sourceFileName: String,
    // # 导入时间戳
    val importedAt: Long,
    // # 全局缓冲秒数（相邻两次尝试之间）
    val globalBufferSeconds: Int,
    // # CSV 数据行总数
    val totalRows: Int,
    // # 全部位置所需成功总数
    val totalRequiredSuccesses: Int
)

/**
 * One location from the worklist. status: pending | active | completed.
 * # 工作清单中的一个位置。状态：pending / active / completed
 */
@Entity(
    tableName = "location_tasks",
    foreignKeys = [ForeignKey(
        entity = LocationPlan::class,
        parentColumns = ["id"], childColumns = ["planId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("planId")]
)
data class LocationTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    // # 源文件中 1 起始的数据行号
    val csvRow: Int,
    val longitude: Double,
    val latitude: Double,
    val priority: Int,
    // # 该位置要求的成功次数
    val requiredSuccesses: Int,
    // # 已验证的成功次数
    val completedSuccesses: Int = 0,
    val status: String = "pending"
)

/**
 * One CellRebel test attempt under a task. status: starting | running | succeeded | ok_gps_only | failed | interrupted.
 * # 某任务下的一次 CellRebel 测试尝试
 */
@Entity(
    tableName = "test_attempts",
    foreignKeys = [
        ForeignKey(
            entity = LocationTask::class,
            parentColumns = ["id"], childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = RunSession::class,
            parentColumns = ["id"], childColumns = ["runSessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("taskId"), Index("runSessionId")]
)
data class TestAttempt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val runSessionId: Long,
    // # 任务内 1 起始的尝试序号
    val attemptOrdinal: Int,
    // # 任务内 1 起始的成功序号；仅在 succeeded 时非空
    val successOrdinal: Int?,
    val startedAt: Long,
    // # 观察到 RUNNING 的时间戳（AC-B2 审计）
    val runningObservedAt: Long?,
    val endedAt: Long?,
    val status: String,
    // # FailureReason.name；failed/interrupted 时必填
    val failureReason: String?,
    val webBrowsingScore: Double?,
    val videoStreamingScore: Double?,
    val latitude: Double,
    val longitude: Double,
    // # F003：阶段跳过审计标记（gps_skipped / test_skipped / null=无跳过）
    val stageNotes: String? = null
)

/**
 * Legacy v4 completion snapshot — written ONCE during v4→v5 migration, read-only thereafter.
 * Holds the pre-A+ historical count so the operator still sees "N completed before evidence
 * standard". Semantics: LEGACY_UNVERIFIED — **never generates TrustedQuotaEntry**, never enters
 * the completion projection (§7.1, §7.3, INV-24, M-MG-01).
 * # v4 历史完成快照：迁移时写一次，此后只读；绝不生成可信账本，不进完成投影
 */
@Entity(tableName = "legacy_completion_snapshots")
data class LegacyCompletionSnapshot(
    @PrimaryKey val taskId: Long,
    val legacyCompletedSuccesses: Int,
    val legacyStatus: String,
    val migratedFromSchemaVersion: Int,
    val migratedAt: Long
)

/**
 * Auto-side provider allowlist (§6.5.3). Distinct from qwy's caller allowlist (PairingRecord).
 * applicationId is the match key; currentSignerDigest is the trust root. `approvedVersionCode`
 * is immutable & audit-only — provider upgrades do NOT rewrite it (new version only enters the
 * append-only audit stream). Revocation sets `revokedAt` — it is a state transition, NOT a delete.
 * No silent/automatic TOFU: an unseen signer stops at local NOT_PAIRED until operator approval
 * (§6.5.3, §7.1, INV-22). Mutations only via ProviderTrustStore 3 narrow methods.
 * # Auto 侧 provider allowlist：撤销是状态迁移不是删除；禁止 silent TOFU
 */
@Entity(
    tableName = "provider_pairing_records",
    indices = [Index(value = ["applicationId"], unique = true)]
)
data class ProviderPairingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val applicationId: String,
    val currentSignerDigest: String,
    val approvedAt: Long,
    val revokedAt: Long?,
    val approvedVersionCode: Int
)
