package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1

/**
 * Assembles EnvironmentObservationV1 for an authorized caller (§6.4, table 2).
 *
 * Every field it emits has a defined trust-predicate role — a field nobody
 * validates is a free lie for a fake provider. Honesty rules:
 *  - coverage comes from the revision owner; polling/heartbeat never says FULL
 *  - evidenceRefs are qwy:<store>:<id> references into the audit store
 *  - epoch fields are audit-only; elapsedRealtime fields carry the predicates
 *  - the observation binds leaseId + acceptedIntentHash + scheduleItemId +
 *    scheduleVersion (§6.7.1: profile reuse across items means environment
 *    match can NEVER substitute item attribution)
 */
class EnvironmentObserver internal constructor(
    private val tracker: ContinuityTracker,
    private val environment: QwyEnvironment,
    private val clock: MonotonicClock,
    private val watermarks: VerifiedObservationWatermarkStore,
) {
    /**
     * @throws ContractException ENVIRONMENT_DRIFT when expectedIntentHash does
     *   not match the lease's accepted intent (M-IN-02 counterpart, provider side)
     */
    fun observe(lease: LeaseRecord, request: ObserveRequestV1): EnvironmentObservationV1 {
        // Intent hash drift check
        if (request.expectedIntentHash != lease.acceptedIntentHash) {
            throw ContractException(
                ContractErrorCodeV1.ENVIRONMENT_DRIFT,
                "expectedIntentHash mismatch: request=${request.expectedIntentHash} lease=${lease.acceptedIntentHash}",
            )
        }

        val effective = environment.observeEffective()
        val schedule = environment.scheduleSnapshot()
        // Read the revision AFTER the effective-state adapter has reconciled
        // any synchronously delivered relevant change. Handler callbacks share
        // the owner fence, so the tuple now has one linearization point.
        val snap = tracker.snapshot()
        val sourceTimes = effective.verifiedSourceElapsedRealtimeMs
        val conservativeEvidenceTime = sourceTimes
            .takeIf { it.keys == SystemMockTrustPolicy.REQUIRED_FRAMEWORK_SOURCES }
            ?.values
            ?.minOrNull()
        val metadataIsConsistent = conservativeEvidenceTime != null &&
            conservativeEvidenceTime == effective.evidenceObservedAtElapsedRealtimeMs
        val requestedVerified = effective.verificationLevelWire ==
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        val freshVerifiedEvidence = requestedVerified && metadataIsConsistent &&
            watermarks.admit(
                leaseId = lease.leaseId,
                sourceElapsedRealtimeMs = sourceTimes,
                // A missing watermark after an owner-generation discontinuity
                // could be a pre-restart PRE sample. Legacy/missing state is
                // therefore not granted first-use authority after recovery.
                allowFirstUse = lease.applyOwnerGeneration == tracker.generation,
            )
        val emittedVerificationLevel = if (requestedVerified && !freshVerifiedEvidence) {
            VerificationLevelV1.NONE.wire
        } else {
            effective.verificationLevelWire
        }
        val observedAtElapsedRealtimeMs =
            effective.evidenceObservedAtElapsedRealtimeMs ?: 0L
        val nowElapsedRealtimeMs = clock.elapsedRealtimeMs()
        val nowEpochMs = clock.epochMs()

        return EnvironmentObservationV1(
            leaseId = lease.leaseId,
            acceptedIntentHash = lease.acceptedIntentHash,
            observedAtEpochMs = nowEpochMs -
                (nowElapsedRealtimeMs - observedAtElapsedRealtimeMs).coerceAtLeast(0L),
            observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            environmentRevision = snap.revision,
            environmentFingerprint = effective.environmentFingerprint,
            continuityCoverageWire = snap.coverageWire,
            continuitySinceEpochMs = snap.continuitySinceElapsedRealtimeMs?.let {
                // Convert elapsed to epoch for the epoch field (audit only)
                nowEpochMs - (nowElapsedRealtimeMs - it)
            },
            continuitySinceElapsedRealtimeMs = snap.continuitySinceElapsedRealtimeMs,
            deliveryModeWire = effective.deliveryModeWire,
            verificationLevelWire = emittedVerificationLevel,
            effectiveLatitude = effective.latitude,
            effectiveLongitude = effective.longitude,
            isMock = effective.isMock,
            scheduleDecisionWire = environment.scheduleDecisionWire(
                schedule?.scheduleId ?: "",
            ),
            evidenceRefs = effective.evidenceRefs,
            scheduleItemId = schedule?.currentItemId ?: "",
            scheduleVersion = schedule?.scheduleVersion ?: 0L,
        )
    }
}
