package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
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
    private val authoritativeSource: AuthoritativeContinuitySource,
    private val expectedOracleOwnerPackage: String,
    private val expectedOracleOwnerUid: Int,
    private val semanticWriterReadiness: QwySemanticWriterReadiness,
) {
    /**
     * Non-observation claims may reuse FULL only while the current local digest,
     * central writer lane, oracle snapshot, and durable ACK still name the same
     * state. A settings/profile writer can advance the oracle without an AppOps
     * callback, so serving the cached tracker value alone would leak stale FULL.
     */
    fun continuitySnapshotForClaim(): RevisionSnapshot {
        val cached = tracker.snapshot()
        if (cached.coverageWire != ContinuityCoverageV1.FULL.wire ||
            !environment.authoritativeSemanticMutationEnabled()
        ) {
            return cached
        }
        val localDigest = environment.authoritativeSemanticDigest(tracker.generation)
        val current = localDigest?.takeIf(semanticWriterReadiness::ensureReadyFor)
            ?.let { digest ->
                runCatching(authoritativeSource::snapshot).getOrNull()
                    ?.takeIf {
                        it.isStableCompleteFor(
                            expectedOracleOwnerPackage,
                            expectedOracleOwnerUid,
                        ) &&
                            it.qwySemanticDigest == digest &&
                            tracker.isAuthoritativeCursorAcknowledged(it)
                    }
            }
        if (current == null) {
            tracker.invalidateCoverage()
            return tracker.snapshot()
        }
        // ensureReadyFor may have registered a recovered writer session and
        // atomically published a newer revision/ACK with coverage NONE. Never
        // return the pre-read cache across that side effect.
        return tracker.snapshot()
    }

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

        // FULL is derived only from one synchronous authoritative
        // PRE/raw-read/POST interval. A missing or throwing Binder endpoint is
        // represented as a null endpoint and reconciles fail-closed to NONE.
        // Exact same-coordinate refresh ticks remain outside the journal. The
        // system-server producer separately journals coordinate-bit changes,
        // so reading fresh framework evidence alone does not manufacture a bump.
        val authoritativeSemantics = environment.authoritativeSemanticMutationEnabled()
        val localSemanticDigestBefore = if (authoritativeSemantics) {
            environment.authoritativeSemanticDigest(tracker.generation)
        } else {
            null
        }
        // A retry may register/install the central writer lane. Do that before
        // PRE so its generation boundary is visible to reconciliation rather
        // than hidden inside an otherwise stable observation window.
        val semanticLaneReady = !authoritativeSemantics ||
            (localSemanticDigestBefore != null &&
                semanticWriterReadiness.ensureReadyFor(localSemanticDigestBefore))
        val pre = if (semanticLaneReady) {
            runCatching(authoritativeSource::snapshot).getOrNull()
        } else {
            null
        }
        // This timestamp is the conservative start of the proved interval.
        // Readiness may block and may itself register/ACK a real +2 boundary,
        // so time before the successful PRE can never enter continuitySince.
        val windowStartElapsedRealtimeMs = clock.elapsedRealtimeMs()
        val effective = environment.observeEffective()
        val schedule = environment.scheduleSnapshot()
        val localSemanticDigestAfter = if (authoritativeSemantics) {
            environment.authoritativeSemanticDigest(tracker.generation)
        } else {
            null
        }
        val semanticWindowMatches = !authoritativeSemantics ||
            (localSemanticDigestBefore != null &&
                localSemanticDigestAfter == localSemanticDigestBefore &&
                pre?.qwySemanticDigest == localSemanticDigestBefore)
        val post = if (semanticLaneReady && semanticWindowMatches) {
            runCatching(authoritativeSource::snapshot).getOrNull()
                ?.takeIf {
                    !authoritativeSemantics ||
                        it.qwySemanticDigest == localSemanticDigestAfter
                }
        } else {
            null
        }
        val snap = tracker.reconcileAuthoritativeWindow(
            window = AuthoritativeObservationWindow(
                pre = pre,
                post = post,
                windowStartElapsedRealtimeMs = windowStartElapsedRealtimeMs,
            ),
            expectedOwnerPackage = expectedOracleOwnerPackage,
            expectedOwnerUid = expectedOracleOwnerUid,
        )
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
