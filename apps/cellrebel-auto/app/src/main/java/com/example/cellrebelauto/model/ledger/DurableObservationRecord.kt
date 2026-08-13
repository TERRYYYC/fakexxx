package com.example.cellrebelauto.model.ledger

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable pre/post observation snapshot persisted during the normal A+ path (§8.1 PRE_OBSERVED /
 * POST_OBSERVE_PENDING). Survives crashes so [com.example.cellrebelauto.automation.AutomationEngine]
 * recovery can re-decide from durable data, never from a stale live source (Sol R36 P1-1).
 *
 * One row per (attemptId, phase) — phase = "PRE" or "POST". The full §6.4 ObservationSnapshot field
 * set is stored as individual columns so TrustPolicy can consume them without re-acquisition.
 *
 * # 持久观察记录：pre/post 观察快照在正常路径写入，崩溃后恢复按 key 读取（Sol R36 P1-1）
 */
@Entity(
    tableName = "durable_observation_records",
    indices = [
        Index(value = ["attemptId", "phase"], unique = true),
        Index("attemptId")
    ]
)
data class DurableObservationRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val attemptId: Long,
    /** "PRE" or "POST" (§6.4 observation phase). */
    val phase: String,
    val leaseId: String,
    val acceptedIntentHash: String,
    val coverage: String,
    val verificationLevel: String,
    val deliveryMode: String,
    val isMock: Boolean?,
    val scheduleDecision: String,
    val effectiveLat: Double?,
    val effectiveLng: Double?,
    val environmentRevision: Long,
    val environmentFingerprint: String,
    val observedAtElapsedRealtimeMs: Long,
    val observedAtEpochMs: Long,
    val continuitySinceElapsedRealtimeMs: Long?,
    val continuitySinceEpochMs: Long?,
    /** JSON array of evidence ref strings (round-trippable, no lossy joinToString). */
    val evidenceRefsJson: String,
    /** Legacy evidenceRefs column kept for migration compatibility (semicolon-joined). */
    val evidenceRefs: String
)
