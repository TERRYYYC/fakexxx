package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1

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
class EnvironmentObserver(
    private val tracker: ContinuityTracker,
    private val environment: QwyEnvironment,
    private val clock: MonotonicClock,
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

        val snap = tracker.snapshot()
        val effective = environment.observeEffective()
        val schedule = environment.scheduleSnapshot()

        return EnvironmentObservationV1(
            leaseId = lease.leaseId,
            acceptedIntentHash = lease.acceptedIntentHash,
            observedAtEpochMs = clock.epochMs(),
            observedAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            environmentRevision = snap.revision,
            environmentFingerprint = effective.environmentFingerprint,
            continuityCoverageWire = snap.coverageWire,
            continuitySinceEpochMs = snap.continuitySinceElapsedRealtimeMs?.let {
                // Convert elapsed to epoch for the epoch field (audit only)
                clock.epochMs() - (clock.elapsedRealtimeMs() - it)
            },
            continuitySinceElapsedRealtimeMs = snap.continuitySinceElapsedRealtimeMs,
            deliveryModeWire = effective.deliveryModeWire,
            verificationLevelWire = effective.verificationLevelWire,
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
