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
    val evidenceRefs: String,
    /** #79 identity legs from the provider observation; nullable only for migrated v8 history. */
    val scheduleItemId: String? = null,
    val scheduleVersion: Long? = null,
    // ---- v10 (v1.81 CI-attestation): the serving cell AS THE DEVICE REPORTED IT ----
    /** Captured at observation mint time (observeLive) from THIS device's
     *  TelephonyManager — under the hook that is the injected value, without it
     *  the real cell. This is the device-side half of the cross-attestation
     *  against the discover `configuredCell*` projection (CellRebel 看到的小区
     * 标识 ↔ 配额入账互证). Null = NOT CAPTURED (migrated v9 rows, or the radio
     *  read failed/withheld the field) — "未捕获" is honest absence, never zero.
     *  SCOPE RED LINE (operator, 2026-09-08): evidence ONLY — never read by
     *  TrustPolicy or the quota path; the §6.4 evidence chain stays the sole
     *  trust input. */
    val servingCi: Long? = null,
    val servingTac: Int? = null,
    val servingPci: Int? = null,
    val servingMcc: String? = null,
    val servingMnc: String? = null,
    /** LTE RSRP / NR SS-RSRP in dBm at capture time. */
    val servingRsrpDbm: Int? = null,
)
