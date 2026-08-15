package com.example.cellrebelauto.environment

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1

/**
 * R43 (Sol GREEN-review P1-1): the frozen-contract adapter between the wire DTO
 * [EnvironmentObservationV1] (Int wire codes, per ContractEnumsV1) and Auto's internal
 * [ObservationSnapshot] (enum-name strings, per §6.4).
 *
 * Mapping is EXPLICIT and FAIL-CLOSED: an unknown wire code (a newer peer) maps to null / a
 * sentinel the TrustPolicy rejects — never a crash inside a Binder transaction (M-VS-02) and never
 * a silent trust upgrade.
 *
 * # 契约 wire(Int) ↔ 内部 name(String) 适配器：未知 wire fail-closed，绝不静默升级信任
 */
object ObservationWireAdapter {

    /** Wire-code → frozen enum NAME (TrustPolicy compares names). Null = unknown peer ⇒ fail-closed. */
    fun coverageName(wire: Int): String? = ContinuityCoverageV1.fromWire(wire)?.name

    fun verificationLevelName(wire: Int): String? = VerificationLevelV1.fromWire(wire)?.name

    /** Null delivery mode (provider cannot determine) maps to the §6.4.1 contradiction sentinel. */
    fun deliveryModeName(wire: Int?): String? =
        wire?.let { w -> DeliveryModeV1.fromWire(w)?.name } ?: "UNKNOWN_DELIVERY"

    fun scheduleDecisionName(wire: Int): String? = ScheduleDecisionV1.fromWire(wire)?.name

    /**
     * Adapt the wire observation into the internal §6.4 snapshot. Any unknown wire code yields a
     * snapshot whose TrustPolicy evaluation FAILS (sentinel names never match the frozen values).
     */
    fun toSnapshot(wire: EnvironmentObservationV1): ObservationSnapshot = ObservationSnapshot(
        leaseId = wire.leaseId,
        acceptedIntentHash = wire.acceptedIntentHash,
        coverage = coverageName(wire.continuityCoverageWire) ?: "UNKNOWN_COVERAGE", // sentinel: TrustPolicy rejects
        verificationLevel = verificationLevelName(wire.verificationLevelWire) ?: "UNKNOWN_VERIFICATION",
        deliveryMode = deliveryModeName(wire.deliveryModeWire) ?: "UNKNOWN_DELIVERY",
        isMock = wire.isMock,
        scheduleDecision = scheduleDecisionName(wire.scheduleDecisionWire) ?: "UNKNOWN_SCHEDULE",
        effectiveLat = wire.effectiveLatitude,
        effectiveLng = wire.effectiveLongitude,
        environmentRevision = wire.environmentRevision,
        environmentFingerprint = wire.environmentFingerprint,
        observedAtElapsedRealtimeMs = wire.observedAtElapsedRealtimeMs,
        observedAtEpochMs = wire.observedAtEpochMs,
        continuitySinceElapsedRealtimeMs = wire.continuitySinceElapsedRealtimeMs,
        evidenceRefs = wire.evidenceRefs
    )
}
