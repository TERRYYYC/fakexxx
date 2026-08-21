package com.example.cellrebelauto.model.audit

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Append-only Auto audit event (§7.1). The audit stream is NOT a state owner — it records what
 * happened for traceability/diagnostics. `seq` is monotonic. Correlation ids (attempt/lease/run)
 * are strings to stay decoupled from any one state object's PK type.
 *
 * # 只追加审计事件：不是状态 owner，seq 单调；仅记录，不参与判定
 */
@Entity(
    tableName = "auto_audit_events",
    indices = [Index("attemptId"), Index("seq")]
)
data class AutoAuditEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Monotonic sequence (correlates with chronological order within a run). */
    val seq: Long,
    /** Correlation: the attempt this event pertains to, if any. */
    val attemptId: Long?,
    /** Correlation: lease / run ids, formatted as needed. */
    val correlationRef: String?,
    /** Stable event type name (e.g. APPLY_RECEIVED, LEDGER_COMMITTED, RECOVERY_RECONCILE). */
    val eventType: String,
    /** Digest of the event payload (the raw payload is not stored here). */
    val payloadDigest: String,
    val recordedAt: Long
)
