package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
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
    // F-15: diagnostics seam so the step-3b species line reaches logcat in
    // production while the JVM lane (which does not mock android.util.Log)
    // keeps testing the rejection paths through a recorder.
    private val diagnostics: DiagnosticLog = DiagnosticLog.ANDROID,
) {
    fun discover(callingUid: Int): CapabilitySnapshotV1 = withOwnerFence {
        authorizer.authorize(callingUid)
        val snap = tracker.snapshot()
        val schedule = environment.scheduleSnapshot()
        CapabilitySnapshotV1(
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
            // v1.55 schedule projection group: all four null together when no
            // active schedule, all four non-null together otherwise.
            currentScheduleId = schedule?.scheduleId,
            currentItemId = schedule?.currentItemId,
            scheduleVersion = schedule?.scheduleVersion,
            exhausted = schedule?.exhausted,
        )
    }

    fun preflight(callingUid: Int, request: PreflightRequestV1): PreflightReportV1 = withOwnerFence {
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

        PreflightReportV1(
            acceptedIntentHash = intentHash,
            scheduleDecisionWire = scheduleDecision,
            waitUntilEpochMs = null,
            // F-17: consume the environment's computed capability ceiling —
            // the achievable level must vary with real capability (gateway,
            // current item, qwy-owned coordinates), never a constant. Same
            // fix shape as F-14/#41 at the receipt: claim follows measurement.
            achievableVerificationLevelWire = environment.achievableVerificationLevelWire(),
            continuityCoverageWire = snap.coverageWire,
            environmentRevision = snap.revision,
            blockingReasonWires = blockers,
            // v1.55 schedule projection group: all three null together when no
            // active schedule — NO sentinel values (""/0L are the round-5
            // anti-pattern at the wire layer; null reads as "no schedule").
            scheduleItemId = schedule?.currentItemId,
            scheduleVersion = schedule?.scheduleVersion,
            exhausted = schedule?.exhausted,
        )
    }

    /**
     * §6.3.3 wire 13: structural validation of an apply intent (M-RQ-01).
     *
     * v1.62 (KB-8=A) removed the coordinate branches: coordinates are qwy-owned
     * and no longer travel in the intent, so there is no coordinate assertion
     * this gate could mismatch against — the branch was unreachable by
     * construction and is deleted rather than kept dead.
     */
    private fun validateApplyRequest(intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1) {
        if (intent.runId.isBlank() || intent.attemptId.isBlank() || intent.profileRef.isBlank()
            || intent.scheduleRef.isBlank()) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "empty required ref in intent")
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

    fun apply(callingUid: Int, request: ApplyRequestV1): ApplyReceiptV1 = withOwnerFence {
        val caller = authorizer.authorize(callingUid)
        val intent = request.intent
        val intentHash = CanonicalIntentDigestV1.compute(intent)

        // §6.3.3 wire 13: structural validation (M-RQ-01)
        validateApplyRequest(intent)

        val requestDigest = RequestDigests.applyDigest(intentHash)

        // FC-1 three-phase protocol under the process owner fence:
        //   1. COMMIT admission (ACQUIRING owner + request identity/window)
        //   2. mutate the external qwy environment
        //   3. COMMIT ACTIVE + revision/coverage + receipt/audit atomically
        // FileDurableKv buffers transaction writes until the block returns.
        // Calling applyEnvironment inside that block let the external mutation
        // happen while ACQUIRING existed only in txBuffer; a final persist
        // failure then rolled back every durable owner. Splitting the commits
        // closes that ownerless window while ownerLock preserves M-CC-03/04.
        var replayedReceipt: ApplyReceiptV1? = null
        var admittedLease: LeaseRecord? = null
        var admittedAtEpochMs = 0L
        var admittedAtElapsedRealtimeMs = 0L
        val admissionStore = DurableApplyAdmissionStore(storage)
        storage.transaction {
            // §6.3.4: idempotency check
            val existing = idempotencyReceiptForCaller(
                caller = caller,
                operation = ContractOperation.APPLY,
                idempotencyKey = request.idempotencyKey,
                requestDigest = requestDigest,
            )
            if (existing != null) {
                if (existing.requestDigest == requestDigest) {
                    replayedReceipt = deserializeApplyReceipt(existing.receiptPayload)
                    return@transaction
                } else {
                    throw ContractException(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                        "apply key=${request.idempotencyKey} replayed with different digest")
                }
            }

            // A committed admission consumes the key before a receipt exists.
            // Same digest resumes the exact server-generated lease through the
            // public APPLY call; a different digest conflicts permanently even
            // if an operator later releases the uncertain owner.
            val priorAdmission = admissionStore.find(
                caller.applicationId,
                caller.signerDigest,
                request.idempotencyKey,
            )
            if (priorAdmission != null) {
                if (priorAdmission.requestDigest != requestDigest ||
                    priorAdmission.acceptedIntentHash != intentHash
                ) {
                    throw ContractException(
                        ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                        "apply key=${request.idempotencyKey} replayed with different digest",
                    )
                }

                val priorLease = checkNotNull(leaseStore.get(priorAdmission.leaseId)) {
                    "apply admission ${request.idempotencyKey} lost lease ${priorAdmission.leaseId}"
                }
                check(
                    leaseBelongsToCaller(priorLease, caller) &&
                        priorLease.applyIdempotencyKey == request.idempotencyKey &&
                        priorLease.acceptedIntentHash == intentHash
                ) { "apply admission and lease identity diverged" }

                val otherBlocking = leaseStore.blockingLease()
                if (otherBlocking != null && otherBlocking.leaseId != priorLease.leaseId) {
                    throw ContractException(
                        ContractErrorCodeV1.LEASE_CONFLICT,
                        "device lease ${otherBlocking.leaseId} blocks admitted lease ${priorLease.leaseId}",
                    )
                }

                val priorEffectiveState = leaseStore.effectiveState(
                    priorLease.leaseId,
                    tracker.generation,
                )
                if (priorEffectiveState !in APPLY_RESUMABLE_STATES) {
                    throw ContractException(
                        ContractErrorCodeV1.LEASE_CONFLICT,
                        "admitted lease ${priorLease.leaseId} cannot resume from $priorEffectiveState",
                    )
                }

                val resumedLease = priorLease.copy(
                    state = LeaseState.ACQUIRING,
                    releaseIdempotencyKey = null,
                    residualReasonWires = emptyList(),
                    // This newly authorized public APPLY now owns the lease.
                    // Neither the explicit provider marker nor its legacy
                    // revoke discriminator may survive into a later restart.
                    revokeSource = null,
                    recoveryEvidenceRef = null,
                )
                leaseStore.put(resumedLease)
                admittedLease = resumedLease
                admittedAtEpochMs = priorAdmission.admittedAtEpochMs
                admittedAtElapsedRealtimeMs = priorAdmission.admittedAtElapsedRealtimeMs
                storage.write(OBSERVE_WINDOW_NAMESPACE, observeWindowKey(caller), "")
                return@transaction
            }

            // INV-28: conflict predicate — ANY non-RELEASED lease blocks.
            // §8.4: EXPIRED / REVOKED / RELEASE_INCOMPLETE keep blocking until
            // explicitly converged (release). TTL is never a bypass of INV-21.
            val blocking = leaseStore.blockingLease()
            if (blocking != null) {
                val effState = leaseStore.effectiveState(blocking.leaseId, tracker.generation)
                if (effState != LeaseState.RELEASED) {
                    // A failed external/finalize phase intentionally leaves a
                    // durable blocking owner. Preserve the normal same-key
                    // conflict species even before a receipt exists: the same
                    // principal/key with a different intent is still an
                    // idempotency conflict, never permission to create lease 2.
                    if (leaseBelongsToCaller(blocking, caller) &&
                        blocking.applyIdempotencyKey == request.idempotencyKey &&
                        blocking.acceptedIntentHash != intentHash
                    ) {
                        throw ContractException(
                            ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                            "apply key=${request.idempotencyKey} replayed with different digest",
                        )
                    }
                    throw ContractException(ContractErrorCodeV1.LEASE_CONFLICT,
                        "device lease ${blocking.leaseId} in state $effState")
                }
            }

            // Deadline clock bridge (§8.4): snapshot ONCE
            admittedAtEpochMs = clock.epochMs()
            admittedAtElapsedRealtimeMs = clock.elapsedRealtimeMs()
            val remainingMs = maxOf(0L, intent.deadlineEpochMs - admittedAtEpochMs)
            val deadlineElapsed = admittedAtElapsedRealtimeMs + remainingMs

            val leaseId = UUID.randomUUID().toString()
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
                // v1.75 step 3b: the lease's quota is earned FOR the schedule
                // item the provider is ACTUALLY applying — provider truth at
                // apply time, NOT the caller's intent.scheduleRef declaration.
                // §6.3/v1.72 freezes scheduleRef as the item's stable ref, but
                // v1.72's own warning ("no gate or type system will object")
                // came true on the C5 device: Auto declared "task-N", and
                // anchoring attribution to that declaration made every legal
                // release→advance die wrong-item → STALE_LEASE(8) (F12). The
                // receipt/digest still bind the declared intent verbatim; only
                // this internal attribution anchor is provider-owned — same
                // philosophy as KB-8 coordinates and step 3b itself: untrusted
                // input must not buy historical facts. Null (no active
                // schedule) stays null and fails closed as unproven → 8.
                earnedScheduleRef = environment.scheduleSnapshot()?.currentItemId,
            )
            leaseStore.put(lease)
            admissionStore.record(
                ApplyAdmissionRecord(
                    callerApplicationId = caller.applicationId,
                    callerSignerDigest = caller.signerDigest,
                    idempotencyKey = request.idempotencyKey,
                    requestDigest = requestDigest,
                    acceptedIntentHash = intentHash,
                    leaseId = leaseId,
                    admittedAtEpochMs = admittedAtEpochMs,
                    admittedAtElapsedRealtimeMs = admittedAtElapsedRealtimeMs,
                ),
            )
            admittedLease = lease

            // §6.3.3 wire-8 observe exception: granting this caller a NEW lease
            // closes its post-advance verification window ("此后尚未有新 lease
            // 授予该 caller" leg) — same transaction as the grant itself.
            storage.write(OBSERVE_WINDOW_NAMESPACE, observeWindowKey(caller), "")
        }

        replayedReceipt?.let { return@withOwnerFence it }
        val lease = checkNotNull(admittedLease) {
            "apply admission committed neither replay nor ACQUIRING owner"
        }

        // External mutation is intentionally outside every DurableKv
        // transaction, but still inside ownerLock. At this point a fresh
        // durable handle can already resolve [lease] as ACQUIRING.
        val applyOutcome: ApplyOutcome
        val continuityCapability: ContinuityEvidenceCapability
        try {
            // F14/C5: consume the computed result; never stamp a constant.
            applyOutcome = environment.applyEnvironment(intent)
            continuityCapability = environment.continuityEvidenceCapability()
        } catch (failure: Throwable) {
            markApplyIncomplete(lease.leaseId, failure)
            throw failure
        }

        val operationId = UUID.randomUUID().toString()
        val receipt = try {
            storage.transaction {
                val durableLease = checkNotNull(leaseStore.get(lease.leaseId)) {
                    "durable ACQUIRING owner ${lease.leaseId} disappeared before finalize"
                }
                check(durableLease == lease && durableLease.state == LeaseState.ACQUIRING) {
                    "apply owner ${lease.leaseId} changed before finalize: $durableLease"
                }

                // Transition, revision/coverage and receipt are one commit.
                leaseStore.put(durableLease.copy(state = LeaseState.ACTIVE))
                tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED)
                // Endpoint readback and continuity history are separate proofs.
                // Public AppOps/provider callbacks are asynchronous: they can
                // verify the current endpoint but cannot prove an away→restore
                // transition did not occur before their callback was delivered.
                when (continuityCapability) {
                    ContinuityEvidenceCapability.COMPLETE ->
                        tracker.markContinuityEstablished()
                    ContinuityEvidenceCapability.INCOMPLETE ->
                        tracker.reportObserverGap(ContinuityCoverageV1.PARTIAL)
                    ContinuityEvidenceCapability.UNAVAILABLE ->
                        tracker.reportObserverGap(ContinuityCoverageV1.NONE)
                }

                val finalizedReceipt = ApplyReceiptV1(
                    operationId = operationId,
                    idempotencyKey = request.idempotencyKey,
                    leaseId = lease.leaseId,
                    acceptedIntentHash = intentHash,
                    appliedAtEpochMs = admittedAtEpochMs,
                    environmentRevision = tracker.snapshot().revision,
                    verificationLevelWire = applyOutcome.verificationLevelWire,
                )

                // Persist receipt for idempotent replay in the same commit as ACTIVE.
                idempotency.record(OperationReceiptRecord(
                    callerApplicationId = caller.applicationId,
                    callerSignerDigest = caller.signerDigest,
                    operation = ContractOperation.APPLY,
                    idempotencyKey = request.idempotencyKey,
                    requestDigest = requestDigest,
                    resultDigest = "",
                    receiptPayload = serializeApplyReceipt(finalizedReceipt),
                    createdAtElapsedRealtimeMs = admittedAtElapsedRealtimeMs,
                ))

                audit.append("apply",
                    callerApplicationId = caller.applicationId,
                    leaseId = lease.leaseId,
                    operationId = operationId,
                )

                finalizedReceipt
            }
        } catch (failure: Throwable) {
            markApplyIncomplete(lease.leaseId, failure)
            throw failure
        }

        // No external callback registration is allowed inside the finalize
        // transaction. A failure here is replay-safe because the receipt and
        // ACTIVE owner have already committed together.
        environment.setRelevantChangeListener(::recordRelevantChange)
        receipt
    }

    fun observe(callingUid: Int, request: ObserveRequestV1): EnvironmentObservationV1 = withOwnerFence {
        // Read-side entry is fenced too (Terra round-3 P1(b)): settling a
        // pending advance first means an observation can never report an
        // environment whose pointer lags a committed advance receipt.
        val caller = authorizer.authorize(callingUid)

        val lease = leaseStore.get(request.leaseId)
            ?: throw ContractException(ContractErrorCodeV1.STALE_LEASE, "unknown lease ${request.leaseId}")

        // Lease must belong to this caller — the "非本 caller 所有" branch is
        // NOT lifted by the post-advance window ("不受本例外影响").
        if (!leaseBelongsToCaller(lease, caller)) {
            throw ContractException(ContractErrorCodeV1.STALE_LEASE,
                "lease ${request.leaseId} belongs to a different caller principal")
        }

        // Effective state must be ACTIVE — with ONE frozen exception (§6.3.3
        // wire-8 row, third half of the v1.42/v1.43 rule): a non-terminal
        // advance MUST be independently verified by observe(), and at that
        // moment the only leaseId the caller can present is the RELEASED
        // historical reference its completeAndAdvance carried (step 5's lease
        // gate is device-global; the next lease arrives only with the next
        // apply). So: caller's own reference + it IS the caller's most recent
        // successful advance's reference + no new lease granted to this caller
        // since (the grant clears the slot) → the "已 RELEASED" branch does not
        // apply and the observation is SERVED. Refusing it would make the
        // frozen "must verify" hop unconditionally unreachable. Divergence
        // (another caller re-applied meanwhile) is deliberately NOT absorbed
        // here: serving lets the effectiveIntentHash / revision comparison
        // diagnose it; an 8 would be indistinguishable from "lease expired".
        // Every other state (EXPIRED / REVOKED / RELEASE_INCOMPLETE) and any
        // reference outside the window still answers 8.
        val effState = leaseStore.effectiveState(request.leaseId, tracker.generation)
        if (effState != LeaseState.ACTIVE) {
            val windowRef = storage.read(OBSERVE_WINDOW_NAMESPACE, observeWindowKey(caller))
            val inPostAdvanceWindow =
                effState == LeaseState.RELEASED && request.leaseId == windowRef
            if (!inPostAdvanceWindow) {
                throw ContractException(ContractErrorCodeV1.STALE_LEASE,
                    "lease ${request.leaseId} effective state is $effState")
            }
        }

        val observation = observer.observe(lease, request)
        // §6.4.1: VERIFIED with no provenance is unverifiable and Auto must reject it. The
        // production QwyEnvironmentController intentionally has no audit-store dependency, while
        // this contract boundary owns both the durable audit stream and the authorized caller /
        // lease identity. Append the exact observe event before returning and project its durable
        // qwy:<store>:<id> reference into the wire response. An audit write failure therefore
        // fails the observe call closed instead of emitting a false VERIFIED fact.
        val auditEvent = audit.append(
            event = "observe",
            callerApplicationId = caller.applicationId,
            leaseId = lease.leaseId,
            operationId = request.operationId,
            payloadDigest = request.expectedIntentHash,
        )
        observation.copy(
            evidenceRefs = (
                observation.evidenceRefs + "qwy:integration.v1.audit:${auditEvent.seq}"
            ).distinct(),
        )
    }

    fun release(callingUid: Int, request: ReleaseRequestV1): ReleaseReceiptV1 = withOwnerFence {
        val caller = authorizer.authorize(callingUid)
        val requestDigest = RequestDigests.releaseDigest(request.leaseId)
        var replayedReceipt: ReleaseReceiptV1? = null
        var admittedLease: LeaseRecord? = null

        // Phase 1: the owner must be durably RELEASING before cleanup crosses
        // the external boundary. A same-key replay can resume that exact state;
        // a different key cannot take over an in-flight release.
        storage.transaction {
            val lease = leaseStore.get(request.leaseId)
                ?: throw ContractException(
                    ContractErrorCodeV1.STALE_LEASE,
                    "unknown lease ${request.leaseId}",
                )
            if (!leaseBelongsToCaller(lease, caller)) {
                throw ContractException(
                    ContractErrorCodeV1.STALE_LEASE,
                    "lease ${request.leaseId} belongs to a different caller principal",
                )
            }

            val existing = idempotencyReceiptForCaller(
                caller = caller,
                operation = ContractOperation.RELEASE,
                idempotencyKey = request.idempotencyKey,
                requestDigest = requestDigest,
                requestLeaseId = request.leaseId,
            )
            if (existing != null) {
                if (existing.requestDigest != requestDigest) {
                    throw ContractException(
                        ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                        "release key=${request.idempotencyKey} replayed with different digest",
                    )
                }
                replayedReceipt = deserializeReleaseReceipt(existing.receiptPayload)
                return@transaction
            }

            val effectiveState = leaseStore.effectiveState(
                request.leaseId,
                tracker.generation,
            )
            val normalAdmission = effectiveState in setOf(
                LeaseState.ACTIVE,
                LeaseState.EXPIRED,
                LeaseState.RELEASE_INCOMPLETE,
            )
            val exactResume = effectiveState == LeaseState.RELEASING &&
                lease.releaseIdempotencyKey == request.idempotencyKey
            if (!normalAdmission && !exactResume) {
                throw ContractException(
                    ContractErrorCodeV1.STALE_LEASE,
                    "lease ${request.leaseId} in state $effectiveState cannot admit this release",
                )
            }

            val releasing = lease.copy(
                state = LeaseState.RELEASING,
                releaseIdempotencyKey = request.idempotencyKey,
                // A newly authorized public caller now owns receipt recovery.
                // Stale provider-cleanup provenance must never override this
                // non-null key after a process death.
                recoveryEvidenceRef = null,
                residualReasonWires = emptyList(),
            )
            leaseStore.put(releasing)
            admittedLease = releasing
        }

        replayedReceipt?.let { return@withOwnerFence it }
        val releasingLease = checkNotNull(admittedLease) {
            "release admission committed neither replay nor RELEASING owner"
        }

        // Phase 2: cleanup is outside every buffered DurableKv transaction but
        // remains serialized by ownerLock. A fresh durable handle can already
        // see the exact RELEASING lease/key at this point.
        val outcome = try {
            environment.cleanup(request.leaseId)
        } catch (failure: Throwable) {
            markReleaseIncomplete(releasingLease, failure)
            throw failure
        }
        val nowEpoch = clock.epochMs()
        val nowElapsed = clock.elapsedRealtimeMs()

        // Phase 3: terminal lease state, revision, receipt and audit are one
        // commit. If it fails after external cleanup, preserve a public-retryable
        // RELEASE_INCOMPLETE owner instead of rolling back to a false ACTIVE.
        try {
            storage.transaction {
                val durable = checkNotNull(leaseStore.get(request.leaseId)) {
                    "durable RELEASING owner ${request.leaseId} disappeared before finalize"
                }
                check(
                    durable.state == LeaseState.RELEASING &&
                        durable.releaseIdempotencyKey == request.idempotencyKey
                ) { "release owner changed before finalize: $durable" }

                val releaseComplete: Boolean
                val residualWires: List<Int>
                val terminal = when (outcome) {
                    is CleanupOutcome.Complete -> {
                        releaseComplete = true
                        residualWires = emptyList()
                        durable.copy(
                            state = LeaseState.RELEASED,
                            residualReasonWires = emptyList(),
                        )
                    }
                    is CleanupOutcome.Incomplete -> {
                        releaseComplete = false
                        residualWires = outcome.residualReasonWires
                        durable.copy(
                            state = LeaseState.RELEASE_INCOMPLETE,
                            residualReasonWires = residualWires,
                        )
                    }
                }
                leaseStore.put(terminal)

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

                idempotency.record(OperationReceiptRecord(
                    callerApplicationId = caller.applicationId,
                    callerSignerDigest = caller.signerDigest,
                    operation = ContractOperation.RELEASE,
                    idempotencyKey = request.idempotencyKey,
                    requestDigest = requestDigest,
                    resultDigest = "",
                    receiptPayload = serializeReleaseReceipt(receipt),
                    createdAtElapsedRealtimeMs = nowElapsed,
                ))

                audit.append(
                    "release",
                    callerApplicationId = caller.applicationId,
                    leaseId = request.leaseId,
                    operationId = request.operationId,
                )

                receipt
            }
        } catch (failure: Throwable) {
            markReleaseIncomplete(releasingLease, failure)
            throw failure
        }
    }

    fun completeAndAdvance(callingUid: Int, request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 = withOwnerFence {
        // §6.7.4b frozen judgment order (v1.42, amended through v1.76):
        //   safety(+recompute digest) → idempotency → proof →
        //   attribution(8) → schedule(17→16→14→15) → lease(7) → mutation
        // (17 = schedule identity, judged first per v1.72; then exhausted(16)
        // before item(14)/version(15) per the v1.54 intra-step reorder.)
        // Steps 2–6 run inside ONE serialized transaction, exactly like apply():
        // the step-5 lease gate is DEVICE-GLOBAL and must serialize against a
        // concurrent apply, so no new lease can slip in between the gate and the
        // pointer move (Terra PR#22 P1-3). This replaces the earlier v1.39 order
        // (proof-first, gates outside the transaction). The whole method runs
        // under withOwnerFence, so the commit→external-apply window is closed to
        // apply()/replay too (Terra round-3), and settlePendingAdvance() has
        // already reconciled any earlier interrupted advance before this runs.

        // --- step 1: outer safety gate — auth + recompute requestDigest ---
        val caller = authorizer.authorize(callingUid)
        val proof = request.completionProof

        // Terra PR#22 P1-2 / dsf F-7.3: the caller-supplied requestDigest is
        // UNTRUSTED input. Recompute it from the RECEIVED fields via the
        // contract's frozen helper and compare exactly — a mismatch means the
        // digest does not bind this payload, so a forged (key, digest) pair can
        // no longer fetch a receipt bound to a different request.
        // v1.71/v1.72: the preimage now includes expectedScheduleId — the
        // provider consumes the contract's CanonicalAdvanceDigestV1 directly
        // (a local field-assembly copy would be a second framing that drifts,
        // dsf F-8's own lesson).
        val recomputedDigest = CanonicalAdvanceDigestV1.compute(request)
        if (recomputedDigest != request.requestDigest) {
            throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                "requestDigest does not bind the received fields (recompute mismatch)")
        }

        // §6.7.5 single-commit protocol (Terra PR#22 round-2). The external qwy
        // pointer does NOT share the provider's transaction: FileDurableKv
        // commits when the block returns, QwyEnvironment has no shared
        // tx/prepare/rollback boundary. So mutate-inside-the-block gives
        // "pointer moved, receipt lost" on a commit failure — the §6.7.5
        // forbidden middle. Ordering is the fix:
        //   1. COMMIT { gates + precomputed receipt + pending marker }   ← the advance
        //   2. apply the external pointer (verified against the receipt)
        //   3. clear the marker
        // Crash before 1: nothing anywhere moved (no receipt ⇒ no advance).
        // Crash after 1: the next fenced entry (or owner startup) rolls the
        // pointer FORWARD from the marker before anything is served. The owner
        // fence serializes commit→apply→clear against every other entry point,
        // and the gates re-read state inside the tx.
        var replayed = true
        val committed = storage.transaction {
            // --- step 2: idempotency, keyed on the RECOMPUTED digest (M-AD-02/03) ---
            val existing = idempotencyReceiptForCaller(
                caller = caller,
                operation = ContractOperation.ADVANCE,
                idempotencyKey = request.idempotencyKey,
                requestDigest = recomputedDigest,
                requestLeaseId = request.leaseId,
            )
            if (existing != null) {
                if (existing.requestDigest == recomputedDigest) {
                    // M-AD-02: replay the SAME receipt without a second advance
                    return@transaction deserializeAdvanceReceipt(existing.receiptPayload)
                } else {
                    // M-AD-03: same key + different recomputed digest → IDEMPOTENCY_CONFLICT
                    throw ContractException(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT,
                        "advance key=${request.idempotencyKey} replayed with different digest")
                }
            }
            replayed = false

            // --- step 3: proof gate (M-AD-01) ---
            if (proof.scheduleItemId.isBlank() || proof.ledgerRef.isBlank()) {
                throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                    "completion proof missing required refs")
            }
            if (proof.scheduleItemId != request.expectedCurrentItemId) {
                throw ContractException(ContractErrorCodeV1.REQUEST_INVALID,
                    "proof.scheduleItemId=${proof.scheduleItemId} does not match expectedCurrentItemId=${request.expectedCurrentItemId}")
            }

            // --- step 3b: historical-reference attribution (v1.75, frozen
            // order 3b → 4 → 5) --- request.leaseId's attribution is judged
            // HERE and only here; every failure is STALE_LEASE(8), because
            // wire 8's genus is "该 leaseId 对本次操作不可用" and foreign /
            // unproven / wrong-item are its species. 3b must precede step 4:
            // answering 17/16/14/15 to a forged or foreign reference would
            // leak current schedule state to a caller who never proved earned
            // quota (same root as step 1 before step 2: untrusted input must
            // not buy historical facts). 3b does NOT judge liveness — the
            // caller's own ACTIVE reference passes here and step 5 answers 7.
            //
            // v1.76 removes recency from this gate: any caller-owned, provably
            // persisted lease with the matching earned item passes attribution,
            // even when a newer released lease exists. "Most recent" belongs
            // only to the post-advance observe exception window below. A
            // pre-v1.75 durable row has no item attribution and therefore
            // remains decodable but fails closed here as `unproven` → 8.
            //
            // F-15: the judgment itself now lives in attributeLease()
            // (StaleLeaseAttribution.kt) so the four rejection branches are
            // species-distinguishable — the verdict crosses Binder unchanged
            // (messages are the historical strings, verbatim), and the species
            // additionally reaches logcat via one greppable line, because the
            // C5 probe never surfaced diagnosticMessage and F-12 could not
            // tell the four wire-8 branches apart.
            val attributed = leaseStore.get(request.leaseId)
            val attribution = attributeLease(
                leaseRow = attributed,
                leaseId = request.leaseId,
                caller = caller,
                expectedCurrentItemId = request.expectedCurrentItemId,
            )
            val attributedRow: LeaseRecord
            val earnedScheduleRef: String
            when (attribution) {
                is StaleLeaseAttribution.Rejected -> {
                    diagnostics.warn(
                        DIAG_TAG,
                        staleLeaseSpeciesLogLine(
                            attribution.species,
                            request.leaseId,
                            request.expectedCurrentItemId,
                        ),
                    )
                    throw ContractException(ContractErrorCodeV1.STALE_LEASE, attribution.message)
                }
                is StaleLeaseAttribution.Attributed -> {
                    attributedRow = attribution.row
                    earnedScheduleRef = attribution.earnedScheduleRef
                }
            }

            // --- step 4: schedule gates (17→16→14→15) ---
            // v1.72: identity leg FIRST (wire 17 SCHEDULE_IDENTITY_MISMATCH) —
            // 14/15/16 describe the state of ANOTHER schedule when the id
            // doesn't match, so identity is judged before everything else.
            // Then v1.54 intra-order 16→14→15: exhausted (terminal) before
            // item mismatch before version stale.
            val schedule = environment.scheduleSnapshot()
                ?: throw ContractException(ContractErrorCodeV1.REQUEST_INVALID, "no active schedule")

            if (request.expectedScheduleId != schedule.scheduleId) {
                throw ContractException(ContractErrorCodeV1.SCHEDULE_IDENTITY_MISMATCH,
                    "expected schedule ${request.expectedScheduleId} current ${schedule.scheduleId}")
            }

            // M-AD-11: already exhausted → wire 16 (checked FIRST per v1.54:
            // terminal state must be reported before item/version mismatch,
            // so a stale item on an exhausted schedule gets 16 not 14)
            if (schedule.exhausted) {
                throw ContractException(ContractErrorCodeV1.SCHEDULE_EXHAUSTED,
                    "schedule already exhausted")
            }

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

            // --- step 5: lease gate — DEVICE-GLOBAL (§6.7.4a/b, M-AD-12) ---
            // ANY non-RELEASED / unconverged lease blocks advance, regardless of
            // caller and WITHOUT an EXPIRED exemption (INV-28: EXPIRED never
            // auto-releases; TTL is not a bypass). Same predicate as apply()'s
            // conflict gate; reading it inside this transaction is what
            // serializes it against a concurrent apply (Terra PR#22 P1-3/P1-4).
            val blocking = leaseStore.blockingLease()
            if (blocking != null) {
                val effState = leaseStore.effectiveState(blocking.leaseId, tracker.generation)
                if (effState != LeaseState.RELEASED) {
                    throw ContractException(ContractErrorCodeV1.LEASE_CONFLICT,
                        "device lease ${blocking.leaseId} in state $effState blocks advance")
                }
            }

            // --- step 6a: PRECOMPUTE the outcome from the SAME snapshot the
            // gates passed on. The external pointer is not touched before the
            // commit point — the receipt IS the advance (§6.7.5).
            val idx = schedule.itemIds.indexOf(request.expectedCurrentItemId)
            check(idx >= 0) {
                "schedule integrity: current item ${request.expectedCurrentItemId} not in ${schedule.itemIds}"
            }
            val toItemId = if (idx == schedule.itemIds.lastIndex) null else schedule.itemIds[idx + 1]
            val outcomeWire =
                if (toItemId == null) AdvanceOutcomeV1.EXHAUSTED.wire else AdvanceOutcomeV1.ADVANCED.wire

            // Step 3b proved the reference exists and is the caller's own —
            // reuse that read; a fallback here would silently mask a broken gate.
            val intentHash = attributedRow.acceptedIntentHash

            // The receipt names the revision AFTER the committed schedule
            // boundary. A pre-bump snapshot makes the mandatory immediate
            // observe deterministically disagree by one.
            val effectiveEnvironmentRevision =
                tracker.bump(RevisionBumpReason.SCHEDULE_BOUNDARY)

            val receipt = AdvanceReceiptV1(
                outcomeWire = outcomeWire,
                advancedFromItemId = request.expectedCurrentItemId,
                advancedToItemId = toItemId,
                scheduleVersionAfter = schedule.scheduleVersion + 1, // spec v1.56: terminal/non-terminal both V+1
                effectiveIntentHash = intentHash,
                effectiveEnvironmentRevision = effectiveEnvironmentRevision,
                receiptDigest = "", // Placeholder, computed below
            )

            // Compute receipt digest using the contract's frozen helper
            val receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                receipt = receipt,
                requestDigest = request.requestDigest,
                idempotencyKey = request.idempotencyKey,
            )
            val finalReceipt = receipt.copy(receiptDigest = receiptDigest)

            // Persist receipt for idempotent replay — the commit point.
            idempotency.record(OperationReceiptRecord(
                callerApplicationId = caller.applicationId,
                callerSignerDigest = caller.signerDigest,
                operation = ContractOperation.ADVANCE,
                idempotencyKey = request.idempotencyKey,
                requestDigest = request.requestDigest,
                resultDigest = receiptDigest,
                receiptPayload = serializeAdvanceReceipt(finalReceipt),
                createdAtElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            ))

            // --- step 6b: the roll-forward instruction, committed WITH the
            // receipt: (from, presence, to). If the crash lands after this
            // commit but before the external apply, owner startup finishes the
            // committed advance from this slot.
            storage.write(
                ADVANCE_PENDING_NAMESPACE, ADVANCE_PENDING_KEY,
                // (fromItemId, toItemId?, versionAfter). The full receipt tuple
                // makes recovery idempotent without guessing from mutable state.
                DurableFieldCodec.encode(
                    listOf(
                        request.expectedCurrentItemId,
                        toItemId,
                        finalReceipt.scheduleVersionAfter.toString(),
                    ),
                ),
            )

            // §6.3.3 wire-8 observe exception window opens only for a
            // NON-terminal advance: that hop must observe the new environment.
            // EXHAUSTED verifies through discover() schedule-state readback,
            // so accepting a released observe there would widen the exception.
            // Committed WITH the receipt; a forged leaseId cannot land here
            // because step 3b precedes this write.
            storage.write(
                OBSERVE_WINDOW_NAMESPACE,
                observeWindowKey(caller),
                if (toItemId == null) "" else request.leaseId,
            )

            audit.append("advance",
                callerApplicationId = caller.applicationId,
                leaseId = request.leaseId,
                operationId = request.idempotencyKey,
            )

            finalReceipt
        }
        if (replayed) return@withOwnerFence committed

        // Commit point passed — the advance exists regardless of what happens
        // next. Apply the external mutation and clear the slot; any crash in
        // this window is finished by settlePendingAdvance() at the next fenced
        // entry or owner startup.
        applyCommittedAdvance(
            committed.advancedFromItemId,
            committed.advancedToItemId,
            committed.scheduleVersionAfter,
        )
        storage.write(ADVANCE_PENDING_NAMESPACE, ADVANCE_PENDING_KEY, "")
        committed
    }

    /**
     * §6.7.5 single-commit protocol: apply the EXTERNAL qwy pointer mutation
     * for an already-committed advance receipt, and verify the environment
     * agreed with the committed outcome. Divergence here is an integrity
     * failure (fail loud), never a typed business answer — the receipt is
     * already durable truth.
     */
    private fun applyCommittedAdvance(
        fromItemId: String,
        expectedToItemId: String?,
        expectedVersionAfter: Long,
    ) {
        val outcome = environment.convergeAdvance(
            fromItemId = fromItemId,
            expectedToItemId = expectedToItemId,
            expectedVersionAfter = expectedVersionAfter,
        )
        val (actualToItemId, actualVersionAfter) = when (outcome) {
            is AdvancePointerOutcome.Advanced -> outcome.toItemId to outcome.versionAfter
            is AdvancePointerOutcome.Exhausted -> null to outcome.versionAfter
        }
        check(actualToItemId == expectedToItemId && actualVersionAfter == expectedVersionAfter) {
            "committed advance diverged: receipt says ${expectedToItemId ?: "EXHAUSTED"}, " +
                "version=$expectedVersionAfter; environment answered " +
                "${actualToItemId ?: "EXHAUSTED"}, version=$actualVersionAfter"
        }
    }

    /**
     * §6.7.5 roll-forward, invoked by EVERY fenced entry (via [withOwnerFence])
     * and once at owner startup: a non-empty pending slot means a receipt
     * committed but the crash/exception landed before the external pointer
     * apply (or before the slot clear). Finish the committed advance BEFORE
     * anything is served, then clear the slot. Idempotent — the alreadyApplied
     * check makes a repeat call a no-op. Callers hold [ownerLock] (startup is
     * single-threaded); the operation is not itself re-entrant-safe without it.
     */
    private fun settlePendingAdvance() {
        val marker = storage.read(ADVANCE_PENDING_NAMESPACE, ADVANCE_PENDING_KEY)
        if (marker.isNullOrEmpty()) return
        val parts = DurableFieldCodec.decode(marker)
        val fromItemId = parts[0]!!
        val toItemId = parts[1]
        val schedule = checkNotNull(environment.scheduleSnapshot()) {
            "pending advance slot present but environment has no schedule"
        }
        // Backward-compatible decode for a marker written before versionAfter
        // was added. If the pointer already matches, its current version is the
        // committed one; otherwise the pending logical advance is exactly V+1.
        val expectedVersionAfter = parts.getOrNull(2)?.toLongOrNull()
            ?: if (
                (toItemId != null && schedule.currentItemId == toItemId) ||
                (toItemId == null && schedule.exhausted)
            ) {
                schedule.scheduleVersion
            } else {
                schedule.scheduleVersion + 1L
            }
        // Never infer projection convergence from the pointer alone: the
        // pointer may have committed immediately before process death.
        applyCommittedAdvance(fromItemId, toItemId, expectedVersionAfter)
        storage.write(ADVANCE_PENDING_NAMESPACE, ADVANCE_PENDING_KEY, "")
    }

    /**
     * Owner-process startup reconciliation: fresh tracker generation, then
     * state-aware lease recovery (§8.4 recovery table). Invoked by the service
     * on create and by tests via harness restart.
     */
    fun onOwnerProcessStart(cleanlinessProvable: Boolean): Unit = synchronized(ownerLock) {
        // Bind the OS source before pending convergence: production projection
        // proof checks current AppOps ownership through this monitor. Binding
        // later makes every real restart convergence fail while the fake passes.
        // ownerLock serializes any immediately delivered callback with startup.
        environment.setRelevantChangeListener(::recordRelevantChange)

        // §6.7.5 roll-forward FIRST after the proof sources are live: a
        // committed advance whose external pointer/projection was interrupted
        // must finish before ANY state is served or lease recovery begins.
        settlePendingAdvance()

        // Tracker already allocated a new generation in its init block.
        // Now do state-aware lease recovery.
        val recoveredProviderCleanup = leaseStore.recoverAfterRestart(
            tracker.generation,
            cleanlinessProvable,
            environment,
        )

        storage.transaction {
            recoveredProviderCleanup?.let { attempt ->
                val terminal = leaseStore.finalizeProviderCleanup(attempt)
                audit.append(
                    event = "provider_revoked_cleanup",
                    callerApplicationId = terminal.callerApplicationId,
                    leaseId = terminal.leaseId,
                    payloadDigest = terminal.state.name,
                )
            }
            // Generation discontinuity and any recovered provider-cleanup
            // terminal above are one durable publication.
            tracker.bump(RevisionBumpReason.GENERATION_DISCONTINUITY)
        }

    }

    /**
     * Operator revokes a caller on the qwy side (§6.5): pairing transitions to
     * revoked, the caller's lease is marked REVOKED (M-PA-09/M-LS-04), audit
     * records the source. New calls from that identity fail typed immediately.
     */
    fun onCallerRevoked(applicationId: String, signerDigest: String): Unit = withOwnerFence {
        // Pairing denial, lease ownership transfer to provider cleanup, and the
        // success audit are ONE durable decision. Committing the pairing first
        // creates an unrecoverable crash window: the former caller can no
        // longer release while its lease is still ACTIVE, and provider cleanup
        // only owns REVOKED rows. FileDurableKv and the fault-injection fake
        // both guarantee rollback of this entire transaction.
        storage.transaction {
            // §6.5: revoke the pairing so authorize() rejects with
            // CALLER_NOT_ALLOWED on subsequent calls (M-LS-04/09).
            pairingStore.revoke(applicationId, signerDigest, clock.elapsedRealtimeMs())
            // Mark the lease REVOKED (M-PA-09/M-LS-04).
            leaseStore.markRevoked(
                callerApplicationId = applicationId,
                callerSignerDigest = signerDigest,
                source = RevokeSource.QWY_REVOKED_CALLER,
            )
            audit.append("caller_revoked", callerApplicationId = applicationId)
        }
    }

    /**
     * Provider-driven cleanup for REVOKED leases (§6.3.3 revocation table): the
     * former caller cannot call in, so qwy converges REVOKED → RELEASING →
     * RELEASED itself. No post-revoke capability is granted to the caller.
     */
    fun runRevokedLeaseCleanup(): Unit = withOwnerFence {
        val attempt = leaseStore.runProviderCleanupForRevoked(environment)
            ?: return@withOwnerFence
        storage.transaction {
            val terminal = leaseStore.finalizeProviderCleanup(attempt)
            tracker.bump(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED)
            audit.append(
                event = "provider_revoked_cleanup",
                callerApplicationId = terminal.callerApplicationId,
                leaseId = terminal.leaseId,
                payloadDigest = terminal.state.name,
            )
        }
    }

    // --- Receipt serialization via the shared total codec (DurableFieldCodec) ---

    /**
     * In-process owner fence. EVERY entry point that reads or mutates lease /
     * schedule-pointer state runs under this lock via [withOwnerFence] and
     * settles a pending advance before serving, so the §6.7.5 commit→external-
     * apply window is closed to ALL callers — not only to a concurrent advance
     * (Terra round-3: fencing only advances left apply() and the live-process
     * replay path able to observe the forbidden middle).
     */
    private val ownerLock = Any()

    /**
     * External apply/finalize failed after admission committed. Preserve the
     * original owner and fail closed as RELEASE_INCOMPLETE so release() can
     * converge it. If even this recovery commit fails, ACQUIRING remains the
     * durable blocker; the recovery failure is attached without hiding the
     * primary cause.
     */
    private fun markApplyIncomplete(leaseId: String, primaryFailure: Throwable) {
        try {
            storage.transaction {
                val durable = leaseStore.get(leaseId) ?: return@transaction
                if (durable.state == LeaseState.ACQUIRING) {
                    leaseStore.put(
                        durable.copy(
                            state = LeaseState.RELEASE_INCOMPLETE,
                            residualReasonWires = listOf(
                                ContractErrorCodeV1.RELEASE_INCOMPLETE.wire,
                            ),
                        ),
                    )
                    tracker.reportObserverGap(ContinuityCoverageV1.NONE)
                }
            }
        } catch (recoveryFailure: Throwable) {
            primaryFailure.addSuppressed(recoveryFailure)
        }
    }

    /**
     * Cleanup/finalize failed after the RELEASING admission committed. Keep the
     * exact lease and release key public-retryable and attach a typed durable
     * reason. If this best-effort commit also fails, RELEASING itself remains a
     * safe blocker that the same key can resume after storage recovers.
     */
    private fun markReleaseIncomplete(
        releasingLease: LeaseRecord,
        primaryFailure: Throwable,
    ) {
        try {
            storage.transaction {
                val durable = leaseStore.get(releasingLease.leaseId) ?: return@transaction
                if (durable.state == LeaseState.RELEASING &&
                    durable.releaseIdempotencyKey == releasingLease.releaseIdempotencyKey
                ) {
                    leaseStore.put(
                        durable.copy(
                            state = LeaseState.RELEASE_INCOMPLETE,
                            residualReasonWires = listOf(
                                ContractErrorCodeV1.RELEASE_INCOMPLETE.wire,
                            ),
                        ),
                    )
                    tracker.reportObserverGap(ContinuityCoverageV1.NONE)
                }
            }
        } catch (recoveryFailure: Throwable) {
            primaryFailure.addSuppressed(recoveryFailure)
        }
    }

    /**
     * A delivered callback is serialized with every handler response. This
     * does not upgrade an asynchronous source to COMPLETE, but it guarantees
     * an already-delivered bump cannot race between effective read and the
     * observation's revision snapshot.
     */
    private fun recordRelevantChange(reason: RevisionBumpReason) {
        synchronized(ownerLock) {
            if (reason == RevisionBumpReason.OBSERVER_GAP) {
                tracker.reportObserverGap(ContinuityCoverageV1.PARTIAL)
            } else {
                tracker.bump(reason)
            }
        }
    }

    /**
     * Lease ownership uses the same full authorization principal as pairing:
     * `(applicationId, signerDigest)`. An application reinstall or signer
     * replacement must not inherit the previous principal's historical lease,
     * release authority, or post-advance observe exception window merely by
     * retaining the package/application id.
     */
    private fun leaseBelongsToCaller(lease: LeaseRecord, caller: CallerIdentity): Boolean =
        lease.callerApplicationId == caller.applicationId &&
            lease.callerSignerDigest == caller.signerDigest

    /** Durable key for state owned by the frozen full caller principal. */
    private fun observeWindowKey(caller: CallerIdentity): String =
        DurableFieldCodec.encode(listOf(caller.applicationId, caller.signerDigest))

    /**
     * Read an idempotency receipt in the full caller-principal scope.
     *
     * The immediately preceding provider schema keyed receipts by
     * applicationId only and did not persist signerDigest. [DurableIdempotencyStore]
     * can still surface such a row, but it is not trusted merely because the
     * package name matches: its operation's lease must independently prove the
     * current signer owned the receipt. A safe row is rewritten under the new
     * four-part scope. An unsafe/ambiguous row is treated as belonging to a
     * different caller, so normal operation gates decide the request without
     * leaking or replaying the old principal's receipt.
     *
     * For a legacy ADVANCE row, the receipt omits leaseId. It is safely
     * attributable only for an exact-digest replay, where the received
     * request's digest binds [requestLeaseId]. A different digest cannot be
     * reverse-mapped to the historical request, so the legacy key is returned
     * without migration and the caller's normal digest comparison fails closed
     * as IDEMPOTENCY_CONFLICT rather than treating the key as unused.
     */
    private fun idempotencyReceiptForCaller(
        caller: CallerIdentity,
        operation: ContractOperation,
        idempotencyKey: String,
        requestDigest: String,
        requestLeaseId: String? = null,
    ): OperationReceiptRecord? {
        val record = idempotency.find(
            caller.applicationId,
            caller.signerDigest,
            operation,
            idempotencyKey,
        ) ?: return null

        val storedSigner = record.callerSignerDigest
        if (storedSigner != null) {
            return record.takeIf {
                it.callerApplicationId == caller.applicationId &&
                    storedSigner == caller.signerDigest
            }
        }

        val legacyLeaseId = when (operation) {
            ContractOperation.APPLY -> deserializeApplyReceipt(record.receiptPayload).leaseId
            ContractOperation.RELEASE -> deserializeReleaseReceipt(record.receiptPayload).leaseId
            ContractOperation.ADVANCE -> {
                if (record.requestDigest != requestDigest) return record
                requestLeaseId ?: return null
            }
        }
        val legacyLease = leaseStore.get(legacyLeaseId) ?: return null
        if (!leaseBelongsToCaller(legacyLease, caller)) return null

        return record.copy(callerSignerDigest = caller.signerDigest).also(idempotency::record)
    }

    /**
     * Run [block] under the owner fence, after finishing any advance whose
     * receipt committed but whose external pointer apply had not completed.
     * Settling FIRST means no entry — write, read, or replay — is served while
     * a committed advance is unreflected in the external schedule.
     */
    private inline fun <T> withOwnerFence(block: () -> T): T = synchronized(ownerLock) {
        settlePendingAdvance()
        block()
    }

    companion object {
        private val APPLY_RESUMABLE_STATES: Set<LeaseState> = setOf(
            LeaseState.ACQUIRING,
            LeaseState.EXPIRED,
            LeaseState.RELEASE_INCOMPLETE,
        )

        /**
         * §6.7.5 single-commit protocol: the roll-forward slot for a committed
         * advance whose external pointer apply has not happened yet. Empty
         * string = no pending advance (DurableKv has no delete).
         */
        const val ADVANCE_PENDING_NAMESPACE: String = "integration.v1.advance.pending"
        const val ADVANCE_PENDING_KEY: String = "slot"

        /**
         * §6.3.3 wire-8 observe exception window (v1.75 GREEN): per-caller slot
         * (storage key = total-codec framing of applicationId + signerDigest)
         * holding the leaseId carried by that caller's most recent
         * successful completeAndAdvance. Written in the
         * advance commit transaction; cleared ("" — DurableKv has no delete)
         * when a NEW lease is granted to the caller (apply admission), which is
         * the frozen "此后尚未有新 lease 授予该 caller" window boundary.
         * Provider-internal storage only — no wire/DTO/AIDL change.
         */
        const val OBSERVE_WINDOW_NAMESPACE: String = "integration.v1.observe.window"

        /** F-15: logcat tag for the step-3b species diagnostics line. */
        const val DIAG_TAG: String = "EnvironmentControlHandler"
    }

    // --- Durable field framing (Terra round-4 P1) ----------------------------
    // All durable carriers (pending-advance marker, receipt payloads) go
    // through the SHARED total codec [DurableFieldCodec] — free-string fields,
    // length-prefix framing, presence discriminators instead of sentinels. One
    // helper by design: a second hand-written framing is a drift point (v1.38).

    private fun encodeFields(fields: List<String>): String = DurableFieldCodec.encode(fields)

    private fun decodeFields(encoded: String): List<String> = DurableFieldCodec.decodeNonNull(encoded)

    private fun serializeApplyReceipt(r: ApplyReceiptV1): String = encodeFields(
        listOf(r.operationId, r.idempotencyKey, r.leaseId, r.acceptedIntentHash,
            r.appliedAtEpochMs.toString(), r.environmentRevision.toString(),
            r.verificationLevelWire.toString()))

    private fun deserializeApplyReceipt(s: String): ApplyReceiptV1 {
        val p = decodeFields(s)
        return ApplyReceiptV1(p[0], p[1], p[2], p[3], p[4].toLong(), p[5].toLong(), p[6].toInt())
    }

    private fun serializeReleaseReceipt(r: ReleaseReceiptV1): String = encodeFields(
        listOf(r.operationId, r.idempotencyKey, r.leaseId, r.releasedAtEpochMs.toString(),
            r.environmentRevision.toString(), r.releaseComplete.toString(),
            r.residualReasonWires.joinToString(",")))

    private fun deserializeReleaseReceipt(s: String): ReleaseReceiptV1 {
        val p = decodeFields(s)
        return ReleaseReceiptV1(p[0], p[1], p[2], p[3].toLong(), p[4].toLong(), p[5].toBoolean(),
            p[6].takeIf { it.isNotEmpty() }?.split(",")?.map { it.toInt() } ?: emptyList())
    }

    // advancedToItemId is nullable (exhausted → null): the codec encodes null
    // natively (length "-1:"), so no manual presence bit — one null encoding.
    private fun serializeAdvanceReceipt(r: AdvanceReceiptV1): String = DurableFieldCodec.encode(
        listOf(r.outcomeWire.toString(), r.advancedFromItemId, r.advancedToItemId,
            r.scheduleVersionAfter.toString(), r.effectiveIntentHash,
            r.effectiveEnvironmentRevision.toString(), r.receiptDigest))

    private fun deserializeAdvanceReceipt(s: String): AdvanceReceiptV1 {
        val p = DurableFieldCodec.decode(s)
        return AdvanceReceiptV1(p[0]!!.toInt(), p[1]!!, p[2],
            p[3]!!.toLong(), p[4]!!, p[5]!!.toLong(), p[6]!!)
    }
}
