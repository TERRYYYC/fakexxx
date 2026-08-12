package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import java.util.UUID

/**
 * Transport-free orchestration of the six v1 operations (§6.1 AIDL surface).
 * EnvironmentControlService is Binder glue only; every rule lives here so the
 * whole provider is JVM-unit-testable (Task 3 test lanes are plain JUnit).
 *
 * Semantic map (spec → this class):
 *  - every op: authorize(callingUid) first — identity is Binder-resolved (INV-02)
 *  - apply: REQUEST_INVALID structural checks (§6.3.3 wire 13, M-RQ-01) →
 *    idempotent replay / IDEMPOTENCY_CONFLICT via requestDigest (§6.3.4,
 *    M-LS-05, M-CC-04) → conflict predicate INV-28 (M-LS-01..04/06) → deadline
 *    clock bridge + applyOwnerGeneration snapshot (§8.4) → durable lease then
 *    environment apply (never call out before the durable write, §8.1 mirror)
 *  - observe: lease must be ACTIVE for THIS caller (STALE_LEASE otherwise);
 *    expectedIntentHash mismatch → ENVIRONMENT_DRIFT (wire 9)
 *  - release: accepted from the owning caller in ACTIVE/EXPIRED/
 *    RELEASE_INCOMPLETE (§6.3.3 carve-out); REVOKED is caller-unreachable;
 *    cleanup that cannot prove completion → releaseComplete=false + residuals
 *    (INV-21); idempotent by key (M-CR-08 mirror)
 *  - completeAndAdvance (§6.7): REQUEST_INVALID when proof missing/mismatched
 *    (M-AD-01) → idempotent replay same key+digest returns the SAME receipt
 *    without a second advance (M-AD-02) / same key+different digest →
 *    IDEMPOTENCY_CONFLICT (M-AD-03) → preconditions: item mismatch wire 14
 *    (M-AD-04/05), version stale wire 15 (M-AD-06), exhausted-again wire 16
 *    (M-AD-11) → §6.7.4a: the caller must hold NO active lease (release comes
 *    first; leaseId in the request is a historical attribution ref, not a hold)
 *    → pointer advance + receipt persist in ONE transaction (§6.7.5) → receipt
 *    carries the frozen §6.7.3 receiptDigest (bound to requestDigest + key +
 *    outcome; null target encoded with the presence discriminator, not a
 *    sentinel) → last item completed = success receipt outcomeWire EXHAUSTED
 *    with advancedToItemId null (M-AD-10)
 */
class EnvironmentControlHandler(
    private val authorizer: CallerAuthorizer,
    private val pairingStore: PairingStore,
    private val leaseStore: EnvironmentLeaseStore,
    private val idempotency: IdempotencyStore,
    private val tracker: ContinuityTracker,
    private val observer: EnvironmentObserver,
    private val audit: IntegrationAuditStore,
    private val environment: QwyEnvironment,
    private val clock: MonotonicClock,
    private val storage: DurableKv,
) {
    fun discover(callingUid: Int): CapabilitySnapshotV1 {
        authorizer.authorize(callingUid)
        val snap = tracker.snapshot()
        val schedule = environment.scheduleSnapshot()
        return CapabilitySnapshotV1(
            protocolVersion = ContractV1.PROTOCOL_VERSION,
            serviceVersion = "1.0.0",
            supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            ),
            continuityCoverageWire = snap.coverageWire,
            environmentRevision = snap.revision,
            profileRefs = emptyList(),
            scheduleRefs = if (schedule != null) listOf(schedule.scheduleId) else emptyList(),
            currentScheduleId = schedule?.scheduleId,
            currentItemId = schedule?.currentItemId,
            scheduleVersion = schedule?.scheduleVersion,
        )
    }

    fun preflight(callingUid: Int, request: PreflightRequestV1): PreflightReportV1 {
        authorizer.authorize(callingUid)
        val intentHash = CanonicalIntentDigestV1.compute(request.intent)
        val snap = tracker.snapshot()
        val schedule = environment.scheduleSnapshot()
        val scheduleDecision = environment.scheduleDecisionWire(request.intent.scheduleRef)

        val blockers = mutableListOf<Int>()
        // Check for blocking lease — any non-RELEASED effective state blocks
        val blocking = leaseStore.blockingLease()
        if (blocking != null) {
            val eff = leaseStore.effectiveState(blocking.leaseId, tracker.generation)
            if (eff != LeaseState.RELEASED) {
                blockers.add(ContractErrorCodeV1.LEASE_CONFLICT.wire)
            }
        }

        return PreflightReportV1(
            acceptedIntentHash = intentHash,
            scheduleDecisionWire = scheduleDecision,
            waitUntilEpochMs = null,
            achievableVerificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            continuityCoverageWire = snap.coverageWire,
            environmentRevision = snap.revision,
            blockingReasonWires = blockers,
            scheduleItemId = schedule?.currentItemId ?: "",
            scheduleVersion = schedule?.scheduleVersion ?: 0L,
        )
    }

    /** §6.3.3 wire 13: structural validation of an apply intent (M-RQ-01). */
    private fun validateApplyRequest(intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1) {
        if (intent.runId.isBlank() || intent.attemptId.isBlank() || intent.profileRef.isBlank()
            || intent.scheduleRef.isBlank()) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "empty required ref in intent")
        }
        if (!intent.latitude.isFinite() || !intent.longitude.isFinite()) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "non-finite coordinate")
        }
        if (intent.latitude < -90.0 || intent.latitude > 90.0) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "latitude out of range: ${intent.latitude}")
        }
        if (intent.longitude < -180.0 || intent.longitude > 180.0) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "longitude out of range: ${intent.longitude}")
        }
        // Structural check: notBefore >= deadline with a future deadline is an
        // impossible window (M-RQ-01). Past deadlines are intentionally allowed
        // — they produce an immediately-expired lease via max(0, …) clock bridge
        // (M-LS-11); the provider never second-guesses the caller's scheduling.
        if (intent.notBeforeEpochMs >= intent.deadlineEpochMs
            && intent.deadlineEpochMs > clock.epochMs()) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "deadline <= notBefore (impossible future window)")
        }
    }

    fun apply(callingUid: Int, request: ApplyRequestV1): ApplyReceiptV1 {
        val caller = authorizer.authorize(callingUid)
        val intent = request.intent
        val intentHash = CanonicalIntentDigestV1.compute(intent)

        // §6.3.3 wire 13: structural validation (M-RQ-01)
        validateApplyRequest(intent)

        val requestDigest = RequestDigests.applyDigest(intentHash)

        // Critical section: idempotency check + conflict predicate + lease
        // creation + environment apply + receipt persist all run under a
        // serialized transaction (M-CC-03/04: exactly one winner when racing).
        return storage.transaction {
            // §6.3.4: idempotency check
            val existing = idempotency.find(caller.applicationId, ContractOperation.APPLY, request.idempotencyKey)
            if (existing != null) {
                if (existing.requestDigest == requestDigest) {
                    return@transaction deserializeApplyReceipt(existing.receiptPayload)
                } else {
                    throw ContractException(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                        "apply key=${request.idempotencyKey} replayed with different digest")
                }
            }

            // INV-28: conflict predicate — ANY non-RELEASED lease blocks.
            // §8.4: EXPIRED / REVOKED / RELEASE_INCOMPLETE keep blocking until
            // explicitly converged (release). TTL is never a bypass of INV-21.
            val blocking = leaseStore.blockingLease()
            if (blocking != null) {
                val effState = leaseStore.effectiveState(blocking.leaseId, tracker.generation)
                if (effState != LeaseState.RELEASED) {
                    throw ContractException(ContractErrorCodeV1.LEASE_CONFLICT,
                        "device lease ${blocking.leaseId} in state $effState")
                }
            }

            // Deadline clock bridge (§8.4): snapshot ONCE
            val nowEpoch = clock.epochMs()
            val nowElapsed = clock.elapsedRealtimeMs()
            val remainingMs = maxOf(0L, intent.deadlineEpochMs - nowEpoch)
            val deadlineElapsed = nowElapsed + remainingMs

            val leaseId = UUID.randomUUID().toString()
            val operationId = UUID.randomUUID().toString()
            val snap = tracker.snapshot()

            // Durable lease FIRST, then environment apply (§8.1 mirror)
            val lease = LeaseRecord(
                leaseId = leaseId,
                callerApplicationId = caller.applicationId,
                callerSignerDigest = caller.signerDigest,
                acceptedIntentHash = intentHash,
                state = LeaseState.ACQUIRING,
                applyIdempotencyKey = request.idempotencyKey,
                startingEnvironmentRevision = snap.revision,
                deadlineElapsedRealtimeMs = deadlineElapsed,
                applyOwnerGeneration = tracker.generation,
            )
            leaseStore.put(lease)

            // Now apply environment
            environment.applyEnvironment(intent)

            // Transition to ACTIVE
            val activeLease = lease.copy(state = LeaseState.ACTIVE)
            leaseStore.put(activeLease)

            // Bump revision for the environment change
            tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED)
            // Mark continuity established from now
            tracker.markContinuityEstablished()

            val receipt = ApplyReceiptV1(
                operationId = operationId,
                idempotencyKey = request.idempotencyKey,
                leaseId = leaseId,
                acceptedIntentHash = intentHash,
                appliedAtEpochMs = nowEpoch,
                environmentRevision = tracker.snapshot().revision,
                verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            )

            // Persist receipt for idempotent replay
            idempotency.record(OperationReceiptRecord(
                callerApplicationId = caller.applicationId,
                operation = ContractOperation.APPLY,
                idempotencyKey = request.idempotencyKey,
                requestDigest = requestDigest,
                resultDigest = "",
                receiptPayload = serializeApplyReceipt(receipt),
                createdAtElapsedRealtimeMs = nowElapsed,
            ))

            audit.append("apply",
                callerApplicationId = caller.applicationId,
                leaseId = leaseId,
                operationId = operationId,
            )

            // Wire up relevant-change listener
            environment.setRelevantChangeListener { reason ->
                tracker.bump(reason)
            }

            receipt
        }
    }

    fun observe(callingUid: Int, request: ObserveRequestV1): EnvironmentObservationV1 {
        val caller = authorizer.authorize(callingUid)

        val lease = leaseStore.get(request.leaseId)
            ?: throw ContractException(ContractErrorCodeV1.STALE_LEASE, "unknown lease ${request.leaseId}")

        // Lease must belong to this caller
        if (lease.callerApplicationId != caller.applicationId) {
            throw ContractException(ContractErrorCodeV1.STALE_LEASE,
                "lease ${request.leaseId} belongs to ${lease.callerApplicationId}")
        }

        // Effective state must be ACTIVE
        val effState = leaseStore.effectiveState(request.leaseId, tracker.generation)
        if (effState != LeaseState.ACTIVE) {
            throw ContractException(ContractErrorCodeV1.STALE_LEASE,
                "lease ${request.leaseId} effective state is $effState")
        }

        return observer.observe(lease, request)
    }

    fun release(callingUid: Int, request: ReleaseRequestV1): ReleaseReceiptV1 {
        val caller = authorizer.authorize(callingUid)

        val lease = leaseStore.get(request.leaseId)
            ?: throw ContractException(ContractErrorCodeV1.STALE_LEASE, "unknown lease ${request.leaseId}")

        if (lease.callerApplicationId != caller.applicationId) {
            throw ContractException(ContractErrorCodeV1.STALE_LEASE,
                "lease ${request.leaseId} belongs to ${lease.callerApplicationId}")
        }

        // §6.3.4 release idempotency check
        val requestDigest = RequestDigests.releaseDigest(request.leaseId)
        val existing = idempotency.find(caller.applicationId, ContractOperation.RELEASE, request.idempotencyKey)
        if (existing != null) {
            if (existing.requestDigest == requestDigest) {
                return deserializeReleaseReceipt(existing.receiptPayload)
            } else {
                throw ContractException(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                    "release key=${request.idempotencyKey} replayed with different digest")
            }
        }

        // Accept from ACTIVE/EXPIRED/RELEASE_INCOMPLETE (§6.3.3 carve-out)
        val effState = leaseStore.effectiveState(request.leaseId, tracker.generation)
        val allowedStates = setOf(LeaseState.ACTIVE, LeaseState.EXPIRED, LeaseState.RELEASE_INCOMPLETE)
        if (effState !in allowedStates) {
            throw ContractException(ContractErrorCodeV1.STALE_LEASE,
                "lease ${request.leaseId} in state $effState, release requires ACTIVE/EXPIRED/RELEASE_INCOMPLETE")
        }

        // Entire mutation (state transition + cleanup + receipt) in ONE transaction
        // so a crash between writes rolls back cleanly (release_crashBetweenWrites).
        return storage.transaction {
            // Transition to RELEASING
            leaseStore.put(lease.copy(state = LeaseState.RELEASING, releaseIdempotencyKey = request.idempotencyKey))

            // Cleanup
            val outcome = environment.cleanup(request.leaseId)
            val nowEpoch = clock.epochMs()

            val releaseComplete: Boolean
            val residualWires: List<Int>
            when (outcome) {
                is CleanupOutcome.Complete -> {
                    leaseStore.put(lease.copy(state = LeaseState.RELEASED, releaseIdempotencyKey = request.idempotencyKey))
                    releaseComplete = true
                    residualWires = emptyList()
                }
                is CleanupOutcome.Incomplete -> {
                    leaseStore.put(lease.copy(
                        state = LeaseState.RELEASE_INCOMPLETE,
                        releaseIdempotencyKey = request.idempotencyKey,
                        residualReasonWires = outcome.residualReasonWires,
                    ))
                    releaseComplete = false
                    residualWires = outcome.residualReasonWires
                }
            }

            // Bump revision for the environment change
            tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED)

            val receipt = ReleaseReceiptV1(
                operationId = request.operationId,
                idempotencyKey = request.idempotencyKey,
                leaseId = request.leaseId,
                releasedAtEpochMs = nowEpoch,
                environmentRevision = tracker.snapshot().revision,
                releaseComplete = releaseComplete,
                residualReasonWires = residualWires,
            )

            // Persist for idempotent replay — same transaction as state transition
            idempotency.record(OperationReceiptRecord(
                callerApplicationId = caller.applicationId,
                operation = ContractOperation.RELEASE,
                idempotencyKey = request.idempotencyKey,
                requestDigest = requestDigest,
                resultDigest = "",
                receiptPayload = serializeReleaseReceipt(receipt),
                createdAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ))

            audit.append("release",
                callerApplicationId = caller.applicationId,
                leaseId = request.leaseId,
                operationId = request.operationId,
            )

            receipt
        }
    }

    fun completeAndAdvance(callingUid: Int, request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
        val caller = authorizer.authorize(callingUid)
        val proof = request.completionProof

        // §6.7.4 judgment order: proof → idempotency → schedule(14/15/16) → lease(7) → mutation

        // --- proof gate (M-AD-01) ---
        if (proof.scheduleItemId.isBlank() || proof.ledgerRef.isBlank()) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "completion proof missing required refs")
        }
        if (proof.scheduleItemId != request.expectedCurrentItemId) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "proof.scheduleItemId=${proof.scheduleItemId} does not match expectedCurrentItemId=${request.expectedCurrentItemId}")
        }

        // --- idempotency gate (M-AD-02/03) ---
        val existing = idempotency.find(caller.applicationId, ContractOperation.ADVANCE, request.idempotencyKey)
        if (existing != null) {
            if (existing.requestDigest == request.requestDigest) {
                // M-AD-02: replay the SAME receipt without a second advance
                return deserializeAdvanceReceipt(existing.receiptPayload)
            } else {
                // M-AD-03: same key + different digest → IDEMPOTENCY_CONFLICT
                throw ContractException(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                    "advance key=${request.idempotencyKey} replayed with different digest")
            }
        }

        // --- schedule gates (14/15/16) ---
        val schedule = environment.scheduleSnapshot()
            ?: throw ContractException(ContractErrorCodeV1.REQUEST_INVALID, "no active schedule")

        // M-AD-04/05: item mismatch → wire 14
        if (request.expectedCurrentItemId != schedule.currentItemId) {
            throw ContractException(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH,
                "expected=${request.expectedCurrentItemId} current=${schedule.currentItemId}")
        }

        // M-AD-06: version stale → wire 15
        if (request.expectedScheduleVersion != schedule.scheduleVersion) {
            throw ContractException(ContractErrorCodeV1.SCHEDULE_VERSION_STALE,
                "expected=${request.expectedScheduleVersion} current=${schedule.scheduleVersion}")
        }

        // M-AD-11: already exhausted → wire 16
        if (schedule.exhausted) {
            throw ContractException(ContractErrorCodeV1.SCHEDULE_EXHAUSTED,
                "schedule already exhausted")
        }

        // --- lease gate (M-AD-12, §6.7.4a) ---
        // The caller must hold NO active lease — release comes first
        val blocking = leaseStore.blockingLease()
        if (blocking != null) {
            val effState = leaseStore.effectiveState(blocking.leaseId, tracker.generation)
            if (effState == LeaseState.ACTIVE || effState == LeaseState.ACQUIRING) {
                if (blocking.callerApplicationId == caller.applicationId) {
                    throw ContractException(ContractErrorCodeV1.LEASE_CONFLICT,
                        "caller holds active lease ${blocking.leaseId}; release first")
                }
            }
            // Non-caller blocking leases in other non-released states also block
            if (effState != LeaseState.RELEASED && effState != LeaseState.EXPIRED) {
                throw ContractException(ContractErrorCodeV1.LEASE_CONFLICT,
                    "device lease ${blocking.leaseId} in state $effState blocks advance")
            }
        }

        // --- mutation: pointer advance + receipt persist in ONE transaction (§6.7.5) ---
        // Crash between writes must roll back cleanly (advance_crashBetweenWrites).
        return storage.transaction {
            val snap = tracker.snapshot()
            val intentHash = leaseStore.get(request.leaseId)?.acceptedIntentHash ?: ""

            val advanceResult = environment.advancePointer(request.expectedCurrentItemId)

            val outcomeWire: Int
            val toItemId: String?
            val versionAfter: Long
            when (advanceResult) {
                is AdvancePointerOutcome.Advanced -> {
                    outcomeWire = AdvanceOutcomeV1.ADVANCED.wire
                    toItemId = advanceResult.toItemId
                    versionAfter = advanceResult.versionAfter
                }
                is AdvancePointerOutcome.Exhausted -> {
                    outcomeWire = AdvanceOutcomeV1.EXHAUSTED.wire
                    toItemId = null
                    versionAfter = advanceResult.versionAfter
                }
            }

            val receipt = AdvanceReceiptV1(
                outcomeWire = outcomeWire,
                advancedFromItemId = request.expectedCurrentItemId,
                advancedToItemId = toItemId,
                scheduleVersionAfter = versionAfter,
                effectiveIntentHash = intentHash,
                effectiveEnvironmentRevision = snap.revision,
                receiptDigest = "", // Placeholder, computed below
            )

            // Compute receipt digest using the contract's frozen helper
            val receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                receipt = receipt,
                requestDigest = request.requestDigest,
                idempotencyKey = request.idempotencyKey,
            )
            val finalReceipt = receipt.copy(receiptDigest = receiptDigest)

            // Persist receipt for idempotent replay — same transaction as pointer advance
            idempotency.record(OperationReceiptRecord(
                callerApplicationId = caller.applicationId,
                operation = ContractOperation.ADVANCE,
                idempotencyKey = request.idempotencyKey,
                requestDigest = request.requestDigest,
                resultDigest = receiptDigest,
                receiptPayload = serializeAdvanceReceipt(finalReceipt),
                createdAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ))

            audit.append("advance",
                callerApplicationId = caller.applicationId,
                leaseId = request.leaseId,
                operationId = request.idempotencyKey,
            )

            // Bump revision for schedule boundary
            tracker.bump(RevisionBumpReason.SCHEDULE_BOUNDARY)

            finalReceipt
        }
    }

    /**
     * Owner-process startup reconciliation: fresh tracker generation, then
     * state-aware lease recovery (§8.4 recovery table). Invoked by the service
     * on create and by tests via harness restart.
     */
    fun onOwnerProcessStart(cleanlinessProvable: Boolean) {
        // Tracker already allocated a new generation in its init block.
        // Now do state-aware lease recovery.
        leaseStore.recoverAfterRestart(tracker.generation, cleanlinessProvable, environment)

        // Bump revision for generation discontinuity
        tracker.bump(RevisionBumpReason.GENERATION_DISCONTINUITY)

        // Re-wire relevant-change listener
        environment.setRelevantChangeListener { reason ->
            tracker.bump(reason)
        }
    }

    /**
     * Operator revokes a caller on the qwy side (§6.5): pairing transitions to
     * revoked, the caller's lease is marked REVOKED (M-PA-09/M-LS-04), audit
     * records the source. New calls from that identity fail typed immediately.
     */
    fun onCallerRevoked(applicationId: String, signerDigest: String) {
        // §6.5: revoke the pairing so authorize() rejects with CALLER_NOT_ALLOWED
        // on any subsequent call from this identity (M-LS-04/09).
        pairingStore.revoke(applicationId, signerDigest, clock.elapsedRealtimeMs())
        // Mark the lease REVOKED (M-PA-09/M-LS-04)
        leaseStore.markRevoked(applicationId, RevokeSource.QWY_REVOKED_CALLER)
        audit.append("caller_revoked", callerApplicationId = applicationId)
    }

    /**
     * Provider-driven cleanup for REVOKED leases (§6.3.3 revocation table): the
     * former caller cannot call in, so qwy converges REVOKED → RELEASING →
     * RELEASED itself. No post-revoke capability is granted to the caller.
     */
    fun runRevokedLeaseCleanup() {
        leaseStore.runProviderCleanupForRevoked(environment)
    }

    // --- Receipt serialization (simple tab-delimited, no JSON dependency) ---

    private val RS = "\t"

    private fun serializeApplyReceipt(r: ApplyReceiptV1): String =
        listOf(r.operationId, r.idempotencyKey, r.leaseId, r.acceptedIntentHash,
            r.appliedAtEpochMs.toString(), r.environmentRevision.toString(),
            r.verificationLevelWire.toString()).joinToString(RS)

    private fun deserializeApplyReceipt(s: String): ApplyReceiptV1 {
        val p = s.split(RS)
        return ApplyReceiptV1(p[0], p[1], p[2], p[3], p[4].toLong(), p[5].toLong(), p[6].toInt())
    }

    private fun serializeReleaseReceipt(r: ReleaseReceiptV1): String =
        listOf(r.operationId, r.idempotencyKey, r.leaseId, r.releasedAtEpochMs.toString(),
            r.environmentRevision.toString(), r.releaseComplete.toString(),
            r.residualReasonWires.joinToString(",")).joinToString(RS)

    private fun deserializeReleaseReceipt(s: String): ReleaseReceiptV1 {
        val p = s.split(RS)
        return ReleaseReceiptV1(p[0], p[1], p[2], p[3].toLong(), p[4].toLong(), p[5].toBoolean(),
            p[6].takeIf { it.isNotEmpty() }?.split(",")?.map { it.toInt() } ?: emptyList())
    }

    private fun serializeAdvanceReceipt(r: AdvanceReceiptV1): String =
        listOf(r.outcomeWire.toString(), r.advancedFromItemId, r.advancedToItemId ?: "",
            r.scheduleVersionAfter.toString(), r.effectiveIntentHash,
            r.effectiveEnvironmentRevision.toString(), r.receiptDigest).joinToString(RS)

    private fun deserializeAdvanceReceipt(s: String): AdvanceReceiptV1 {
        val p = s.split(RS)
        return AdvanceReceiptV1(p[0].toInt(), p[1], p[2].ifEmpty { null },
            p[3].toLong(), p[4], p[5].toLong(), p[6])
    }
}
