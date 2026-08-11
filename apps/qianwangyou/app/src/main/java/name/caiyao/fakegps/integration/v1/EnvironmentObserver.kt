package name.caiyao.fakegps.integration.v1

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
    fun observe(lease: LeaseRecord, request: ObserveRequestV1): EnvironmentObservationV1 =
        TODO("Task 3 GREEN")
}
