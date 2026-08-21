package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity for durable advance protocol state (§8.1 state machine).
 *
 * Each advance attempt writes a record BEFORE calling the coordinator.
 * If the process crashes between write and resolve, the record survives
 * in "pending" state. The recovery sweep at engine start replays these
 * with the persisted idempotency key — the provider's idempotency store
 * ensures at-most-once execution.
 *
 * ScheduleContext fields are flattened because Room doesn't support
 * embedded data classes from another package without TypeConverters,
 * and the flat layout keeps the migration simple.
 *
 * state: pending | quota_not_met | advanced | exhausted | recovery_required
 *
 * R6 P1-1: provider_unavailable removed — provider absence leaves records
 * pending. §8.1 requires ADVANCE_PENDING path to persist until verified
 * ADVANCED or EXHAUSTED receipt/readback resolves it.
 *
 * # 持久化的推进协议状态（§8.1 状态机）。
 * # 协调器调用前写入，崩溃后恢复清扫重放。
 */
@Entity(
    tableName = "advance_records",
    foreignKeys = [ForeignKey(
        entity = LocationTask::class,
        parentColumns = ["id"], childColumns = ["taskId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("taskId"), Index("state")],
)
data class AdvanceRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val idempotencyKey: String,
    // # ScheduleContext flattened (§6.7.3 v1.72)
    val scheduleId: String,
    val currentItemId: String,
    val scheduleVersion: Long,
    val ledgerRef: String,
    val verifiedAtElapsedRealtimeMs: Long,
    // # The lease under which this advance was earned
    val leaseId: String,
    // # §8.1 state machine edge
    val state: String,
    // # Terminal receipt digest (set on resolve for advanced/exhausted)
    val receiptDigest: String? = null,
    // # Recovery/failure reason (set on resolve for recovery_required)
    val reason: String? = null,
    // # Timestamps
    val createdAt: Long,
)
