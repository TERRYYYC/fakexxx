package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for append-only audit trail (Task 4).
 *
 * Not a state owner — purely observational. Correlation IDs link events
 * to sessions/attempts/advance records. Payload digest summarizes the
 * event's structured content without storing raw PII.
 *
 * # 只追加的审计事件记录。不是状态 owner，纯观察性质。
 */
@Entity(tableName = "auto_audit_events")
data class AutoAuditEvent(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val correlationSessionId: Long? = null,
    val correlationAttemptId: Long? = null,
    val correlationAdvanceRecordId: Long? = null,
    val event: String,
    val payloadDigest: String? = null,
    val createdAt: Long,
)
