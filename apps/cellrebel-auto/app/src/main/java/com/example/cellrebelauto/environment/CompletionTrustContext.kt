package com.example.cellrebelauto.environment

import com.example.cellrebelauto.model.execution.CellRebelExecution

/**
 * One CellRebel observation bound to a lease (§6.4 EnvironmentObservationV1 projection). Pre- and
 * post-execution observations must each independently satisfy the §6.4 trust field set, and the two
 * must agree on revision/fingerprint/continuity (§6.4). Every field below has an explicit role in the
 * §6.4.1 trust-predicate table — "field present but unchecked" is a silent trust hole.
 *
 * # 一次绑定 lease 的 CellRebel 观察（§6.4 投影）：每个字段都在 §6.4.1 谓词表里有角色
 */
data class ObservationSnapshot(
    /** Must equal the apply receipt's leaseId (INV-07). */
    val leaseId: String,
    /** Must equal apply.acceptedIntentHash == locallyRecomputedIntentHash (INV-23). */
    val acceptedIntentHash: String,
    /** continuityCoverageWire; must be FULL (§6.4). */
    val coverage: String,
    /** verificationLevelWire; must be SYSTEM_MOCK_INDEPENDENTLY_VERIFIED (§6.4). */
    val verificationLevel: String,
    /** deliveryModeWire; must be non-null SYSTEM_MOCK (§6.4.1 — HOOK masquerading as verify ⇒ fail). */
    val deliveryMode: String,
    /** Nullable so the §6.4.1 isMock=null / isMock=false 矛盾 tuples are representable; must be true. */
    val isMock: Boolean?,
    /** scheduleDecisionWire; must be ALLOWED_NOW (§6.4.1 — DENIED/WAIT_UNTIL + VERIFIED ⇒ fail). */
    val scheduleDecision: String,
    /** Effective coords at observation time; non-null + within tolerance of intent (INV-23). */
    val effectiveLat: Double?,
    val effectiveLng: Double?,
    /** environmentRevision; pre must equal post (§6.4). */
    val environmentRevision: Long,
    /** environmentFingerprint; pre must equal post (§6.4). */
    val environmentFingerprint: String,
    /**
     * §6.4.2 monotonic observation timestamp (SystemClock.elapsedRealtime). The ONLY comparable clock.
     * pre < execution.startedAtElapsed; post > execution.completedAtElapsed.
     */
    val observedAtElapsedRealtimeMs: Long,
    /** Audit-only wall clock (§6.4.2); NEVER enters a trust predicate. */
    val observedAtEpochMs: Long,
    /**
     * §6.4.2 monotonic continuity-window start. Nullable so the §6.4.1 continuitySince=null 矛盾 tuple
     * is representable. pre/post must both be non-null, EQUAL, and <= pre.observedAtElapsedRealtimeMs.
     */
    val continuitySinceElapsedRealtimeMs: Long?,
    /** Structural evidence refs (`qwy:<store>:<id>`); non-empty required (§6.4.1 — empty + VERIFIED ⇒ fail). */
    val evidenceRefs: List<String>
)

/**
 * The full input bundle [TrustPolicy] evaluates to decide whether a classified CellRebel completion
 * may mint a [com.example.cellrebelauto.model.ledger.TrustedQuotaEntry] (§8.1 DECIDING → QUOTA_COMMITTED).
 *
 * Carries EVERY §6.4 discriminator so a policy cannot pass by checking a single field (the false-oracle
 * failure mode Sol proved: an impl that accepts one wrong tuple greens all tests). The canonical
 * positive tuple (§6.4 lines 1493-1530) requires ALL of:
 *  - wire == VERIFIED_NEW_COMPLETION (1) only (INV-11, §8.6.2);
 *  - each observation: coverage FULL, verificationLevel SYSTEM_MOCK_INDEPENDENTLY_VERIFIED,
 *    deliveryMode SYSTEM_MOCK, isMock true, scheduleDecision ALLOWED_NOW, evidenceRefs non-empty,
 *    effectiveLat/Lng non-null, observedAtElapsedRealtimeMs the only comparable clock;
 *  - cross-obs: revision/fingerprint equal, continuitySince equal + non-null + <= pre.observedAt;
 *  - window: pre.observedAt < execution.startedAtElapsed < ... < completedAtElapsed < post.observedAt;
 *  - intent three-way: applyReceiptIntentHash == locallyRecomputedIntentHash == observation hash;
 *  - coords within [locationToleranceMeters] (= TRUSTED_LOCATION_TOLERANCE_METERS = 1.0 m).
 * Every §6.4.1 矛盾 tuple is a distinct must-fail case.
 *
 * # 完成信任上下文：携带 §6.4 全部判别项，杜绝单字段/错 tuple 通过（反 false-oracle）
 */
data class CompletionTrustContext(
    val execution: CellRebelExecution,
    /** §8.6.2 wire code carried by the classified execution (1=VERIFIED … 5=NO_EVIDENCE). */
    val completionEvidenceWire: Int,
    /** Intent hash recorded in the durable apply receipt (INV-23). */
    val applyReceiptIntentHash: String,
    /** Intent hash Auto recomputes locally from the canonical intent preimage (INV-23). */
    val locallyRecomputedIntentHash: String,
    /**
     * The lease id recorded in the durable apply receipt (INV-07). R6-F1 (§11.7): each observation's
     * [ObservationSnapshot.leaseId] must equal THIS receipt lease — binding the observations to the
     * receipt, not merely to each other. Without this field a predicate that checks only
     * `pre.leaseId == post.leaseId` is a false oracle (two observations can agree on the wrong lease).
     */
    val applyReceiptLease: String,
    /** Target coordinates the attempt was dispatched to (INV-23). */
    val targetLat: Double,
    val targetLng: Double,
    /**
     * Location tolerance in meters (INV-23, TRUSTED_LOCATION_TOLERANCE_METERS = 1.0). R6-F1 (§11.7): this
     * is a CALLER-supplied field; the GREEN predicate MUST gate on the FROZEN 1.0 m constant, NOT on this
     * value — otherwise a caller injects its own pass threshold (Sol's round-5 caller-tolerance false-oracle).
     * Kept on the context for audit/projection only; the `R6-F1 caller-injected loose tolerance` negative
     * pins GREEN to the frozen bound by passing 50.0 here with coords ~20 m off.
     */
    val locationToleranceMeters: Double,
    val preObservation: ObservationSnapshot,
    val postObservation: ObservationSnapshot
)
