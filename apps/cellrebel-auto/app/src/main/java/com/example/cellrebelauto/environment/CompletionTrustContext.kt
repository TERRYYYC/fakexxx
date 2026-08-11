package com.example.cellrebelauto.environment

import com.example.cellrebelauto.model.execution.CellRebelExecution

/**
 * One CellRebel observation bound to a lease (§6.3 EnvironmentObservationV1 projection). Pre- and
 * post-execution observations must share the same [leaseId] (INV-07), each carrying the
 * [acceptedIntentHash] observed at that point.
 *
 * # 一次绑定 lease 的 CellRebel 观察（§6.3 投影）：前后观察须同 lease（INV-07）
 */
data class ObservationSnapshot(
    val leaseId: String,
    val acceptedIntentHash: String,
    val observedAt: Long,
    /** Observation mode (e.g. gps/network) — must cross-match [CompletionTrustContext.verificationLevel] (INV-27). */
    val mode: String,
    val isMock: Boolean,
    /** Effective coordinates at observation time; non-null + within tolerance for trust (INV-23). */
    val effectiveLat: Double?,
    val effectiveLng: Double?
)

/**
 * The full input bundle [TrustPolicy] evaluates to decide whether a classified CellRebel completion
 * may mint a [com.example.cellrebelauto.model.ledger.TrustedQuotaEntry] (§8.1 DECIDING→QUOTA_COMMITTED).
 *
 * Carries EVERY discriminator the invariants require, so a policy cannot pass by checking a single
 * field (the false-oracle failure mode). Specifically:
 *  - [completionEvidenceWire] — only VERIFIED_NEW_COMPLETION (1) may pass (INV-11, §8.6.2);
 *  - [preObservation] / [postObservation] — must be bound to the SAME lease (INV-07) and must
 *    bracket the execution window (INV-27);
 *  - three-way intent binding — each observation's [acceptedIntentHash] must equal
 *    [applyReceiptIntentHash] AND [locallyRecomputedIntentHash] (INV-23);
 *  - coordinates — [effectiveLat]/[effectiveLng] non-null and within [locationToleranceMeters] of
 *    the target (INV-23);
 *  - mode/isMock/coverage/timing — must be cross-consistent with [verificationLevel] (INV-27).
 *
 * # 完成信任上下文：携带 INV-07/11/23/27 全部判别项，杜绝单字段通过（反 false-oracle）
 */
data class CompletionTrustContext(
    val execution: CellRebelExecution,
    /** §8.6.2 wire code carried by the classified execution (1=VERIFIED … 5=NO_EVIDENCE). */
    val completionEvidenceWire: Int,
    /** Intent hash recorded in the durable apply receipt (INV-23). */
    val applyReceiptIntentHash: String,
    /** Intent hash Auto recomputes locally from the canonical intent preimage (INV-23). */
    val locallyRecomputedIntentHash: String,
    /** Target coordinates the attempt was dispatched to (INV-23). */
    val targetLat: Double,
    val targetLng: Double,
    /** Frozen location tolerance in meters (INV-23, TRUSTED_LOCATION_TOLERANCE_METERS). */
    val locationToleranceMeters: Double,
    /** Required verification level; observations must cross-match it (INV-27). */
    val verificationLevel: String,
    /** Required coverage; observations must satisfy it (INV-27). */
    val coverage: String,
    val preObservation: ObservationSnapshot,
    val postObservation: ObservationSnapshot
)
