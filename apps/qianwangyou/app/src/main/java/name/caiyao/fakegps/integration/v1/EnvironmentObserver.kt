package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.CanonicalDigestV1
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
    private val audit: IntegrationAuditStore,
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

        val observation = EnvironmentObservationV1(
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
            evidenceRefs = emptyList(),
            scheduleItemId = schedule?.currentItemId ?: "",
            scheduleVersion = schedule?.scheduleVersion ?: 0L,
        )

        // The reference crosses Binder only after its backing row is durable.
        // A write failure therefore fails the whole observe call closed; it can
        // never return a structurally valid but unresolvable evidence ref.
        val evidence = audit.append(
            event = "observe",
            callerApplicationId = lease.callerApplicationId,
            leaseId = lease.leaseId,
            operationId = request.operationId,
            payloadDigest = QwyObservationEvidenceDigest.compute(observation),
        )
        return observation.copy(evidenceRefs = listOf("qwy:audit:${evidence.seq}"))
    }
}

/** Canonical binding for the observation payload backed by a QWY audit row. */
internal object QwyObservationEvidenceDigest {
    private const val DOMAIN = "fakexxx:qwy:v1:observation-evidence"
    private const val ABSENT = "0"
    private const val PRESENT = "1"

    /**
     * [EnvironmentObservationV1.evidenceRefs] is intentionally excluded: its
     * sequence is assigned by the append this digest protects. Every nullable
     * field has an explicit presence discriminator, so absence cannot collide
     * with a legitimate value.
     */
    fun compute(observation: EnvironmentObservationV1): String =
        CanonicalDigestV1.digest(
            DOMAIN,
            listOf(
                CanonicalDigestV1.utf8(observation.leaseId),
                CanonicalDigestV1.utf8(observation.acceptedIntentHash),
                CanonicalDigestV1.decimal(observation.observedAtEpochMs),
                CanonicalDigestV1.decimal(observation.observedAtElapsedRealtimeMs),
                CanonicalDigestV1.decimal(observation.environmentRevision),
                CanonicalDigestV1.utf8(observation.environmentFingerprint),
                CanonicalDigestV1.decimal(observation.continuityCoverageWire),
            ) + optionalLong(observation.continuitySinceEpochMs) +
                optionalLong(observation.continuitySinceElapsedRealtimeMs) +
                optionalInt(observation.deliveryModeWire) +
                listOf(CanonicalDigestV1.decimal(observation.verificationLevelWire)) +
                optionalDouble(observation.effectiveLatitude) +
                optionalDouble(observation.effectiveLongitude) +
                optionalBoolean(observation.isMock) +
                listOf(
                    CanonicalDigestV1.decimal(observation.scheduleDecisionWire),
                    CanonicalDigestV1.utf8(observation.scheduleItemId),
                    CanonicalDigestV1.decimal(observation.scheduleVersion),
                ),
        )

    private fun optionalLong(value: Long?): List<ByteArray> = optional(
        value?.let(CanonicalDigestV1::decimal),
    )

    private fun optionalInt(value: Int?): List<ByteArray> = optional(
        value?.let(CanonicalDigestV1::decimal),
    )

    private fun optionalDouble(value: Double?): List<ByteArray> = optional(
        value?.toRawBits()?.let(CanonicalDigestV1::decimal),
    )

    private fun optionalBoolean(value: Boolean?): List<ByteArray> = optional(
        value?.let { CanonicalDigestV1.utf8(if (it) "1" else "0") },
    )

    private fun optional(value: ByteArray?): List<ByteArray> =
        if (value == null) {
            listOf(CanonicalDigestV1.utf8(ABSENT))
        } else {
            listOf(CanonicalDigestV1.utf8(PRESENT), value)
        }
}
