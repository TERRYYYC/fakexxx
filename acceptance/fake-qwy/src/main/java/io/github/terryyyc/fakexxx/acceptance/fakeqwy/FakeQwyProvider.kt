package io.github.terryyyc.fakexxx.acceptance.fakeqwy

import io.github.terryyyc.fakexxx.contract.v1.*
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Independent provider implementation for acceptance testing (#6, §10.1
 * lane `sol-blackbox`).
 *
 * Implemented from the FROZEN CONTRACT TEXT ALONE — deliberately NOT shared
 * with apps/qianwangyou's production provider. If this implementation and
 * the product disagree under the same scenario, that is a contract-ambiguity
 * finding, not a bug in either.
 *
 * Iron rule: ZERO shared code with #4's production provider. The only common
 * ancestor is the contract module (`:environment-control-v1`). Copying from
 * `EnvironmentControlHandler.kt` would kill the entire lane's value.
 *
 * ## State machine (§8.4 frozen)
 *
 * ```
 * (none) ──apply──► ACTIVE ──release──► RELEASING ──► RELEASED
 *                     │                     │
 *                     ├──deadline──► EXPIRED │
 *                     │                │    ├──cleanup partial──► RELEASE_INCOMPLETE
 *                     └──revoke──► REVOKED  │
 *                                   │       │
 *                     EXPIRED ──release──► RELEASING
 *                     REVOKED ──self-cleanup──► RELEASING
 *                     RELEASE_INCOMPLETE ──manual──► RELEASED
 * ```
 *
 * Non-RELEASED states block new `apply()`.
 */
class FakeQwyProvider(
    val clock: FakeProviderClock,
    private val serviceVersion: String = "fake-qwy-1.0.0",
) {

    // ─── Internal state types ────────────────────────────────────────────

    enum class LeaseState {
        ACQUIRING, ACTIVE, RELEASING, RELEASED, EXPIRED, REVOKED, RELEASE_INCOMPLETE
    }

    data class LeaseRecord(
        val leaseId: String,
        val callerApplicationId: String,
        val intent: EnvironmentIntentV1,
        val acceptedIntentHash: String,
        var state: LeaseState,
        val deadlineElapsedMs: Long,
        val appliedAtEpochMs: Long,
        val environmentRevisionAtApply: Long,
        val verificationLevelWire: Int,
        val applyOwnerGeneration: Long,
        val idempotencyKey: String,
    )

    data class ScheduleItem(
        val itemId: String,
        val profileRef: String = "profile:default",
        val scheduleRef: String = "schedule:default",
        val latitude: Double = 31.2304,
        val longitude: Double = 121.4737,
    )

    data class ScheduleState(
        val scheduleId: String,
        val items: List<ScheduleItem>,
        var currentIndex: Int = 0,
        var version: Long = 1L,
        var exhausted: Boolean = false,
    ) {
        val currentItem: ScheduleItem? get() = items.getOrNull(currentIndex)
        val currentItemId: String? get() = currentItem?.itemId
    }

    data class PairingEntry(
        val applicationId: String,
        val signerDigest: String,
        var revoked: Boolean = false,
    )

    private data class IdempotencyKey(
        val callerApplicationId: String,
        val operationKey: String,
    )

    private sealed class IdempotencyEntry {
        abstract val payloadDigest: String
        data class Apply(val result: EnvironmentControlResultV1, override val payloadDigest: String) : IdempotencyEntry()
        data class Release(val result: EnvironmentControlResultV1, override val payloadDigest: String) : IdempotencyEntry()
        data class Advance(val result: EnvironmentControlResultV1, override val payloadDigest: String) : IdempotencyEntry()
    }

    // ─── State ──────────────────────────────────────────────────────────

    private val pairings = mutableMapOf<String, PairingEntry>() // applicationId → entry
    private var currentLease: LeaseRecord? = null
    private var schedule: ScheduleState? = null
    private val idempotencyStore = mutableMapOf<IdempotencyKey, IdempotencyEntry>()
    private var environmentRevision: Long = 1L
    private var environmentFingerprint: String = "env-fp-initial"
    private var generation: Long = 1L

    // Continuity tracking (§6.6)
    private var continuityCoverage: ContinuityCoverageV1 = ContinuityCoverageV1.FULL
    private var continuitySinceElapsedMs: Long? = clock.elapsedRealtimeMs
    private var continuitySinceEpochMs: Long? = clock.epochMs

    // Post-advance verification window (§6.7.5 / STALE_LEASE KDoc)
    private var lastAdvanceLeaseId: String? = null
    private var lastAdvanceCallerApplicationId: String? = null

    // ─── Response overrides (test knobs) ─────────────────────────────────

    /** Override delivery mode wire code in observations. */
    var overrideDeliveryModeWire: Int? = null

    /** Override verification level wire code in observations. */
    var overrideVerificationLevelWire: Int? = null

    /** Override isMock in observations. */
    var overrideIsMock: Boolean? = null

    /** Override effective coordinates in observations (null pair = force null). */
    var overrideCoordinates: Pair<Double?, Double?>? = null

    /** Override releaseComplete in release receipts. */
    var overrideReleaseComplete: Boolean? = null

    /** Override residual reasons in release receipts. */
    var overrideResidualReasons: List<Int>? = null

    /**
     * Force a specific intent hash in observations — simulates a drift scenario
     * where the observation's intent hash disagrees with the lease's.
     */
    var forceObservationIntentHash: String? = null

    /**
     * When true, inject unknown wire codes in responses (e.g., verification
     * level 999) for unknown-wire testing (M-VS-02).
     */
    var injectUnknownWireCodes: Boolean = false

    /**
     * Force schedule decision in preflight to a specific wire code.
     */
    var forceScheduleDecisionWire: Int? = null

    /**
     * Force a specific waitUntilEpochMs in preflight. Use with [forceScheduleDecisionWire]
     * set to WAIT_UNTIL but this left null to produce a structurally invalid response (M-RS-01).
     */
    var forceWaitUntilEpochMs: Long? = UNSET_MARKER

    // ─── Test controls ───────────────────────────────────────────────────

    /** Add a caller to the approved pairings. */
    fun addPairing(caller: FakeCallerIdentity) {
        pairings[caller.applicationId] = PairingEntry(
            applicationId = caller.applicationId,
            signerDigest = caller.signerDigest,
        )
    }

    /** Revoke a caller's pairing. Active lease enters recovery (§6.5, M-PA-09). */
    fun revokeCaller(applicationId: String) {
        pairings[applicationId]?.revoked = true
        val lease = currentLease
        if (lease != null && lease.callerApplicationId == applicationId) {
            if (lease.effectiveState() == LeaseState.ACTIVE) {
                lease.state = LeaseState.REVOKED
            }
        }
    }

    /** Install a schedule. */
    fun setSchedule(scheduleId: String, items: List<ScheduleItem>) {
        schedule = ScheduleState(scheduleId = scheduleId, items = items)
    }

    /** Simulate a provider process restart. Increments generation. */
    fun simulateRestart() {
        generation++
        // Restart continuity: coverage drops to NONE, window unknown (§6.6)
        continuityCoverage = ContinuityCoverageV1.NONE
        continuitySinceElapsedMs = null
        continuitySinceEpochMs = null
        // State-aware recovery (§8.4): check each lease
        currentLease?.let { lease ->
            when (lease.state) {
                LeaseState.ACTIVE, LeaseState.ACQUIRING -> {
                    // Generation mismatch → EXPIRED (intentional false-red)
                    if (lease.applyOwnerGeneration != generation) {
                        lease.state = LeaseState.EXPIRED
                    }
                }
                LeaseState.REVOKED -> {} // stays REVOKED
                LeaseState.RELEASE_INCOMPLETE -> {} // stays as-is
                LeaseState.RELEASING -> {} // replay release
                LeaseState.RELEASED -> {} // no-op
                LeaseState.EXPIRED -> {} // stays EXPIRED
            }
        }
    }

    /** Reset continuity to FULL from the current clock. */
    fun resetContinuityToFull() {
        continuityCoverage = ContinuityCoverageV1.FULL
        continuitySinceElapsedMs = clock.elapsedRealtimeMs
        continuitySinceEpochMs = clock.epochMs
    }

    /** Force continuity to a specific coverage. */
    fun setContinuity(coverage: ContinuityCoverageV1, sinceElapsedMs: Long?, sinceEpochMs: Long?) {
        continuityCoverage = coverage
        continuitySinceElapsedMs = sinceElapsedMs
        continuitySinceEpochMs = sinceEpochMs
    }

    /** Bump environment revision (simulates schedule change during run, M-RC-02). */
    fun bumpEnvironmentRevision() {
        environmentRevision++
        environmentFingerprint = "env-fp-rev-$environmentRevision"
    }

    /** Force a specific environment fingerprint. */
    fun setEnvironmentFingerprint(fp: String) {
        environmentFingerprint = fp
    }

    /** Manually transition a RELEASE_INCOMPLETE lease to RELEASED. */
    fun manualRecovery() {
        val lease = currentLease ?: return
        if (lease.state == LeaseState.RELEASE_INCOMPLETE) {
            lease.state = LeaseState.RELEASED
        }
    }

    /** Get the current lease (for test assertions). */
    fun currentLeaseSnapshot(): LeaseRecord? = currentLease?.copy()

    /** Get the current schedule state (for test assertions). */
    fun currentScheduleSnapshot(): ScheduleState? = schedule?.copy()

    // ─── Contract operations ─────────────────────────────────────────────

    /**
     * discover() — no caller identity required (§6.3).
     * Returns the provider's current capability snapshot.
     */
    fun discover(): EnvironmentControlResultV1 {
        val sched = schedule
        return EnvironmentControlResultV1.discover(
            CapabilitySnapshotV1(
                protocolVersion = ContractV1.PROTOCOL_VERSION,
                serviceVersion = serviceVersion,
                supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
                supportedVerificationLevelWires = listOf(
                    VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                ),
                continuityCoverageWire = continuityCoverage.wire,
                environmentRevision = environmentRevision,
                profileRefs = sched?.items?.map { it.profileRef }?.distinct() ?: emptyList(),
                scheduleRefs = sched?.items?.map { it.scheduleRef }?.distinct() ?: emptyList(),
                currentScheduleId = sched?.scheduleId,
                currentItemId = sched?.currentItemId,
                scheduleVersion = sched?.version,
                exhausted = sched?.exhausted,
            )
        )
    }

    /**
     * preflight(caller, request) — §6.3.2.
     * Returns schedule verdict without side effects.
     */
    fun preflight(
        caller: FakeCallerIdentity,
        request: PreflightRequestV1,
    ): EnvironmentControlResultV1 {
        // Step 1: caller authorization
        authorizeCaller(caller)?.let { return it }

        // Step 2: protocol version
        if (request.callerProtocolVersion != ContractV1.PROTOCOL_VERSION) {
            return fail(ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL)
        }

        // Step 3: validate request
        validateIntent(request.intent)?.let { return it }

        val acceptedIntentHash = CanonicalIntentDigestV1.compute(request.intent)

        // Step 4: schedule decision
        val sched = schedule
        val decisionWire = forceScheduleDecisionWire
            ?: ScheduleDecisionV1.ALLOWED_NOW.wire

        val waitUntilMs: Long? = if (forceWaitUntilEpochMs != UNSET_MARKER) {
            forceWaitUntilEpochMs
        } else if (decisionWire == ScheduleDecisionV1.WAIT_UNTIL.wire) {
            clock.epochMs + 60_000L // default: wait 60s
        } else {
            null
        }

        val verificationWire = if (injectUnknownWireCodes) 999
        else VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire

        val blockingReasons = mutableListOf<Int>()
        // Check lease conflict at preflight
        val effectiveLease = currentLease?.let { if (it.effectiveState() != LeaseState.RELEASED) it else null }
        if (effectiveLease != null) {
            blockingReasons.add(ContractErrorCodeV1.LEASE_CONFLICT.wire)
        }

        return EnvironmentControlResultV1.preflight(
            PreflightReportV1(
                acceptedIntentHash = acceptedIntentHash,
                scheduleDecisionWire = decisionWire,
                waitUntilEpochMs = waitUntilMs,
                achievableVerificationLevelWire = verificationWire,
                continuityCoverageWire = if (injectUnknownWireCodes) 999 else continuityCoverage.wire,
                environmentRevision = environmentRevision,
                blockingReasonWires = blockingReasons,
                scheduleItemId = sched?.currentItemId,
                scheduleVersion = sched?.version,
                exhausted = sched?.exhausted,
            )
        )
    }

    /**
     * apply(caller, request) — §6.3.2.
     * Makes the intent effective and issues a lease.
     */
    fun apply(
        caller: FakeCallerIdentity,
        request: ApplyRequestV1,
    ): EnvironmentControlResultV1 {
        // Step 1: caller authorization
        authorizeCaller(caller)?.let { return it }

        // Step 2: protocol version
        if (request.callerProtocolVersion != ContractV1.PROTOCOL_VERSION) {
            return fail(ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL)
        }

        // Step 3: validate request
        validateIntent(request.intent)?.let { return it }

        val acceptedIntentHash = CanonicalIntentDigestV1.compute(request.intent)
        val payloadDigest = acceptedIntentHash // digest of the intent is the payload identity

        // Step 4: idempotency check
        val idemKey = IdempotencyKey(caller.applicationId, request.idempotencyKey)
        val existing = idempotencyStore[idemKey]
        if (existing is IdempotencyEntry.Apply) {
            if (existing.payloadDigest == payloadDigest) {
                return existing.result // replay
            } else {
                return fail(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT)
            }
        }

        // Step 5: lease conflict
        val activeLease = currentLease
        if (activeLease != null && activeLease.effectiveState() != LeaseState.RELEASED) {
            return fail(ContractErrorCodeV1.LEASE_CONFLICT)
        }

        // Step 6: create lease (ACQUIRING → apply environment → ACTIVE)
        val leaseId = "lease-${UUID.randomUUID()}"
        // Deadline clock bridging (§8.4): convert wall clock deadline to monotonic
        val deadlineElapsedMs = if (request.intent.deadlineEpochMs <= clock.epochMs) {
            // Past deadline → immediately-expired (M-LS-11)
            clock.elapsedRealtimeMs
        } else {
            clock.elapsedRealtimeMs + (request.intent.deadlineEpochMs - clock.epochMs)
        }

        val currentItem = schedule?.currentItem
        val (effectiveLat, effectiveLng) = effectiveCoordinates(currentItem)
        val verificationWire = coordinateVerificationLevelWire(
            effectiveLat = effectiveLat,
            effectiveLng = effectiveLng,
            targetItem = currentItem,
        )

        // Create with ACQUIRING (production handler does ACQUIRING → apply → ACTIVE)
        val lease = LeaseRecord(
            leaseId = leaseId,
            callerApplicationId = caller.applicationId,
            intent = request.intent,
            acceptedIntentHash = acceptedIntentHash,
            state = LeaseState.ACQUIRING,
            deadlineElapsedMs = deadlineElapsedMs,
            appliedAtEpochMs = clock.epochMs,
            environmentRevisionAtApply = environmentRevision,
            verificationLevelWire = verificationWire,
            applyOwnerGeneration = generation,
            idempotencyKey = request.idempotencyKey,
        )
        currentLease = lease

        // Environment apply succeeds → transition to ACTIVE
        // (In the fake, the "apply" is instantaneous; in production, this is where
        // the mock location provider is actually set up.)
        lease.state = LeaseState.ACTIVE
        environmentRevision++ // apply changes the environment

        val receipt = ApplyReceiptV1(
            operationId = "op-apply-${UUID.randomUUID()}",
            idempotencyKey = request.idempotencyKey,
            leaseId = leaseId,
            acceptedIntentHash = acceptedIntentHash,
            appliedAtEpochMs = clock.epochMs,
            environmentRevision = environmentRevision,
            verificationLevelWire = verificationWire,
        )

        val result = EnvironmentControlResultV1.apply(receipt)
        idempotencyStore[idemKey] = IdempotencyEntry.Apply(result, payloadDigest)
        return result
    }

    /**
     * observe(caller, request) — §6.3.2.
     * Returns a fresh observation of the lease's effective environment.
     */
    fun observe(
        caller: FakeCallerIdentity,
        request: ObserveRequestV1,
    ): EnvironmentControlResultV1 {
        // Step 1: caller authorization
        authorizeCaller(caller)?.let { return it }

        // Step 2: find lease
        val lease = currentLease
        if (lease == null || lease.leaseId != request.leaseId) {
            // Check post-advance verification window (§6.7.5 / STALE_LEASE KDoc)
            if (request.leaseId == lastAdvanceLeaseId
                && caller.applicationId == lastAdvanceCallerApplicationId
            ) {
                // Post-advance window: accepted even though lease is RELEASED
                return assembleObservation(
                    lease = lease, // may be null or different
                    leaseId = request.leaseId,
                    expectedIntentHash = request.expectedIntentHash,
                    allowPostAdvanceWindow = true,
                )
            }
            return fail(ContractErrorCodeV1.STALE_LEASE)
        }

        // Step 3: check ownership
        if (lease.callerApplicationId != caller.applicationId) {
            return fail(ContractErrorCodeV1.STALE_LEASE)
        }

        // Step 4: check lease state
        val effectiveState = lease.effectiveState()
        when (effectiveState) {
            LeaseState.ACTIVE -> {} // OK
            LeaseState.RELEASED -> {
                // Check post-advance window
                if (request.leaseId == lastAdvanceLeaseId
                    && caller.applicationId == lastAdvanceCallerApplicationId
                ) {
                    return assembleObservation(
                        lease = lease,
                        leaseId = request.leaseId,
                        expectedIntentHash = request.expectedIntentHash,
                        allowPostAdvanceWindow = true,
                    )
                }
                return fail(ContractErrorCodeV1.STALE_LEASE)
            }
            else -> return fail(ContractErrorCodeV1.STALE_LEASE)
        }

        // Step 5: intent hash drift check
        if (request.expectedIntentHash != lease.acceptedIntentHash) {
            return fail(ContractErrorCodeV1.ENVIRONMENT_DRIFT)
        }

        return assembleObservation(
            lease = lease,
            leaseId = request.leaseId,
            expectedIntentHash = request.expectedIntentHash,
            allowPostAdvanceWindow = false,
        )
    }

    /**
     * release(caller, request) — §6.3.2.
     * Releases a lease and undoes the environment.
     */
    fun release(
        caller: FakeCallerIdentity,
        request: ReleaseRequestV1,
    ): EnvironmentControlResultV1 {
        // Step 1: caller authorization
        authorizeCaller(caller)?.let { return it }

        // Step 2: idempotency check
        val payloadDigest = request.leaseId // lease ID is the payload identity for release
        val idemKey = IdempotencyKey(caller.applicationId, request.idempotencyKey)
        val existing = idempotencyStore[idemKey]
        if (existing is IdempotencyEntry.Release) {
            if (existing.payloadDigest == payloadDigest) {
                return existing.result
            } else {
                return fail(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT)
            }
        }

        // Step 3: find lease
        val lease = currentLease
        if (lease == null || lease.leaseId != request.leaseId) {
            return fail(ContractErrorCodeV1.STALE_LEASE)
        }

        // Step 4: check ownership
        if (lease.callerApplicationId != caller.applicationId) {
            return fail(ContractErrorCodeV1.STALE_LEASE)
        }

        // Step 5: check state — release accepts ACTIVE, EXPIRED, RELEASE_INCOMPLETE (§8.4)
        val effectiveState = lease.effectiveState()
        when (effectiveState) {
            LeaseState.ACTIVE, LeaseState.EXPIRED, LeaseState.RELEASE_INCOMPLETE -> {
                // Transition to RELEASING → then to RELEASED or RELEASE_INCOMPLETE
                lease.state = LeaseState.RELEASING

                val releaseComplete = overrideReleaseComplete ?: true
                val residualReasons = overrideResidualReasons ?: emptyList()

                if (releaseComplete) {
                    lease.state = LeaseState.RELEASED
                } else {
                    lease.state = LeaseState.RELEASE_INCOMPLETE
                }

                environmentRevision++ // release changes the environment

                val receipt = ReleaseReceiptV1(
                    operationId = request.operationId,
                    idempotencyKey = request.idempotencyKey,
                    leaseId = request.leaseId,
                    releasedAtEpochMs = clock.epochMs,
                    environmentRevision = environmentRevision,
                    releaseComplete = releaseComplete,
                    residualReasonWires = residualReasons,
                )

                val result = EnvironmentControlResultV1.release(receipt)
                idempotencyStore[idemKey] = IdempotencyEntry.Release(result, payloadDigest)
                return result
            }
            LeaseState.RELEASED -> return fail(ContractErrorCodeV1.STALE_LEASE)
            LeaseState.REVOKED -> {
                // REVOKED: the caller was revoked, but if they're still authorized
                // (re-paired?), they can release. Actually per §8.4: REVOKED → RELEASING
                // is via "qwy internal self-cleanup", not caller release.
                // For M-PA-09: revoked caller → CALLER_NOT_ALLOWED (caught at step 1).
                // If we reach here, the caller is authorized but the lease is REVOKED.
                return fail(ContractErrorCodeV1.STALE_LEASE)
            }
            else -> return fail(ContractErrorCodeV1.STALE_LEASE)
        }
    }

    /**
     * completeAndAdvance(caller, request) — §6.7.3 / §6.7.4.
     * Complete the current schedule item and advance to the next.
     */
    fun completeAndAdvance(
        caller: FakeCallerIdentity,
        request: CompleteAndAdvanceRequestV1,
    ): EnvironmentControlResultV1 {
        // Step 1: caller authorization
        authorizeCaller(caller)?.let { return it }

        // Step 2: protocol version
        if (request.callerProtocolVersion != ContractV1.PROTOCOL_VERSION) {
            return fail(ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL)
        }

        // Step 3: idempotency check
        val requestDigest = CanonicalAdvanceDigestV1.compute(request)
        val idemKey = IdempotencyKey(caller.applicationId, request.idempotencyKey)
        val existing = idempotencyStore[idemKey]
        if (existing is IdempotencyEntry.Advance) {
            if (existing.payloadDigest == requestDigest) {
                return existing.result
            } else {
                return fail(ContractErrorCodeV1.IDEMPOTENCY_CONFLICT)
            }
        }

        // Step 4: request digest validation
        if (request.requestDigest != requestDigest) {
            return fail(ContractErrorCodeV1.REQUEST_INVALID,
                "requestDigest mismatch: expected $requestDigest, got ${request.requestDigest}")
        }

        // Step 5: lease gate — ANY non-RELEASED lease → LEASE_CONFLICT (device-global, §6.7.4b step 5)
        val lease = currentLease
        if (lease != null && lease.effectiveState() != LeaseState.RELEASED) {
            return fail(ContractErrorCodeV1.LEASE_CONFLICT)
        }

        // Step 6: schedule checks
        val sched = schedule ?: return fail(ContractErrorCodeV1.CAPABILITY_UNAVAILABLE,
            "no schedule configured")

        // Schedule gate order: 17→16→14→15 (v1.54 16-first, v1.72 identity-first)
        //
        // 1. Identity (17) — SCHEDULE_IDENTITY_MISMATCH: wrong schedule entirely
        // 2. Exhausted (16) — SCHEDULE_EXHAUSTED: schedule is done, no more items
        // 3. Item (14) — SCHEDULE_ITEM_MISMATCH: wrong current item
        // 4. Version (15) — SCHEDULE_VERSION_STALE: right item but stale version
        //
        // Rationale: exhausted (16) before version (15) per v1.54 frozen ordering;
        // an exhausted+stale request must report 16, not 15 (M-AD-11, M-AD-13).

        // (17) Identity FIRST (v1.72)
        if (request.expectedScheduleId != sched.scheduleId) {
            return fail(ContractErrorCodeV1.SCHEDULE_IDENTITY_MISMATCH)
        }

        // (16) Exhausted
        if (sched.exhausted || sched.currentItemId == null) {
            return fail(ContractErrorCodeV1.SCHEDULE_EXHAUSTED)
        }

        // (14) Current item
        if (request.expectedCurrentItemId != sched.currentItemId) {
            return fail(ContractErrorCodeV1.SCHEDULE_ITEM_MISMATCH)
        }

        // (15) Version (last in gate sequence)
        if (request.expectedScheduleVersion != sched.version) {
            return fail(ContractErrorCodeV1.SCHEDULE_VERSION_STALE)
        }

        // Step 7: execute advance
        val advancedFromItemId = sched.currentItemId!!
        val oldVersion = sched.version
        sched.version = oldVersion + 1

        val isLastItem = sched.currentIndex >= sched.items.size - 1
        val advancedToItemId: String?
        val outcomeWire: Int

        if (isLastItem) {
            // Terminal: schedule exhausted
            sched.exhausted = true
            advancedToItemId = null
            outcomeWire = AdvanceOutcomeV1.EXHAUSTED.wire
        } else {
            // Non-terminal: advance to next item
            sched.currentIndex++
            advancedToItemId = sched.currentItemId
            outcomeWire = AdvanceOutcomeV1.ADVANCED.wire
        }

        // Intent hash for the current effective intent (from the apply that earned the lease)
        val effectiveIntentHash = lease?.acceptedIntentHash ?: ""

        val receipt = AdvanceReceiptV1(
            outcomeWire = outcomeWire,
            advancedFromItemId = advancedFromItemId,
            advancedToItemId = advancedToItemId,
            scheduleVersionAfter = sched.version,
            effectiveIntentHash = effectiveIntentHash,
            effectiveEnvironmentRevision = environmentRevision,
            receiptDigest = "", // placeholder, computed below
        )

        // Compute receipt digest
        val receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
            receipt, requestDigest, request.idempotencyKey,
        )
        val receiptWithDigest = receipt.copy(receiptDigest = receiptDigest)

        // Track post-advance verification window
        lastAdvanceLeaseId = request.leaseId
        lastAdvanceCallerApplicationId = caller.applicationId

        val result = EnvironmentControlResultV1.completeAndAdvance(receiptWithDigest)
        idempotencyStore[idemKey] = IdempotencyEntry.Advance(result, requestDigest)
        return result
    }

    // ─── Internal helpers ────────────────────────────────────────────────

    /**
     * Authorize caller. Returns an error result if unauthorized, null if OK.
     *
     * Identity matching uses (applicationId, signerDigest). versionCode is
     * carried but ignored (§6.5.4). This means M-PA-12 (same signer + new
     * versionCode) passes — the pairing is preserved.
     */
    private fun authorizeCaller(caller: FakeCallerIdentity): EnvironmentControlResultV1? {
        val pairing = pairings[caller.applicationId]
        if (pairing == null) {
            return fail(ContractErrorCodeV1.NOT_PAIRED)
        }
        if (pairing.revoked) {
            return fail(ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "pairing revoked for ${caller.applicationId}")
        }
        if (pairing.signerDigest != caller.signerDigest) {
            // Signer mismatch — a different app with the same package name,
            // or a reinstall with a different signing key.
            return fail(ContractErrorCodeV1.CALLER_NOT_ALLOWED,
                "signer mismatch: expected ${pairing.signerDigest}, got ${caller.signerDigest}")
        }
        return null // authorized
    }

    /**
     * Validate an intent's structural requirements (§6.3.3).
     * Returns error result if invalid, null if OK.
     */
    private fun validateIntent(intent: EnvironmentIntentV1): EnvironmentControlResultV1? {
        // Empty required refs
        if (intent.runId.isEmpty() || intent.attemptId.isEmpty()
            || intent.profileRef.isEmpty() || intent.scheduleRef.isEmpty()
        ) {
            return fail(ContractErrorCodeV1.REQUEST_INVALID, "empty required ref")
        }
        // deadline <= notBefore validation (§6.3.3, M-LS-11 tension):
        // Only reject notBefore >= deadline when deadline is in the future.
        // Past deadlines are allowed — they produce immediately-expired leases.
        if (intent.deadlineEpochMs > clock.epochMs
            && intent.notBeforeEpochMs >= intent.deadlineEpochMs
        ) {
            return fail(ContractErrorCodeV1.REQUEST_INVALID,
                "notBefore >= deadline for a future deadline")
        }
        return null
    }

    /**
     * Compute the effective lease state, considering deadline and generation (§8.4).
     */
    private fun LeaseRecord.effectiveState(): LeaseState {
        if (state == LeaseState.ACTIVE || state == LeaseState.ACQUIRING) {
            // Generation mismatch → EXPIRED (restart recovery)
            if (applyOwnerGeneration != generation) {
                state = LeaseState.EXPIRED
                return state
            }
            // Deadline reached on monotonic clock (§8.4)
            if (clock.elapsedRealtimeMs >= deadlineElapsedMs) {
                state = LeaseState.EXPIRED
                return state
            }
        }
        return state
    }

    /**
     * Assemble an observation response.
     *
     * Coordinate verification (KB-8, INV-23): The provider is the sole coordinate
     * authority. When effective coordinates are null or diverge from the schedule
     * item's target, the provider MUST downgrade verificationLevelWire to NONE —
     * it cannot claim INDEPENDENTLY_VERIFIED for an environment it knows is wrong.
     * This is M-IN-01 (partial apply) and M-IN-03 (null coordinates).
     */
    private fun assembleObservation(
        lease: LeaseRecord?,
        leaseId: String,
        expectedIntentHash: String,
        allowPostAdvanceWindow: Boolean,
    ): EnvironmentControlResultV1 {
        val sched = schedule
        val currentItem = sched?.currentItem

        // Intent hash: use override, or lease's hash, or the expected hash for post-advance window
        val intentHash = forceObservationIntentHash
            ?: lease?.acceptedIntentHash
            ?: expectedIntentHash

        // Drift check (only when we have a real lease, not in post-advance window)
        if (!allowPostAdvanceWindow && lease != null) {
            if (expectedIntentHash != lease.acceptedIntentHash) {
                return fail(ContractErrorCodeV1.ENVIRONMENT_DRIFT)
            }
        }

        // Coordinates: when overrideCoordinates is set (even to Pair(null, null)),
        // use its values directly — don't fall back to the schedule item.
        val (lat, lng) = effectiveCoordinates(currentItem)

        // ── Coordinate verification (KB-8): provider-side honesty gate ──
        // The provider holds both the target (schedule item) and effective
        // coordinates. If they disagree — or if effective coords are null —
        // the provider cannot honestly claim INDEPENDENTLY_VERIFIED.
        // Wire codes
        val deliveryModeWire = overrideDeliveryModeWire
            ?: if (injectUnknownWireCodes) 999 else DeliveryModeV1.SYSTEM_MOCK.wire
        val verificationLevelWire = overrideVerificationLevelWire
            ?: if (injectUnknownWireCodes) 999
            else coordinateVerificationLevelWire(lat, lng, currentItem)
        val isMock = overrideIsMock ?: true
        val scheduleDecisionWire = forceScheduleDecisionWire
            ?: if (injectUnknownWireCodes) 999 else ScheduleDecisionV1.ALLOWED_NOW.wire

        val observation = EnvironmentObservationV1(
            leaseId = leaseId,
            acceptedIntentHash = intentHash,
            observedAtEpochMs = clock.epochMs,
            observedAtElapsedRealtimeMs = clock.elapsedRealtimeMs,
            environmentRevision = environmentRevision,
            environmentFingerprint = environmentFingerprint,
            continuityCoverageWire = if (injectUnknownWireCodes) 999 else continuityCoverage.wire,
            continuitySinceEpochMs = continuitySinceEpochMs,
            continuitySinceElapsedRealtimeMs = continuitySinceElapsedMs,
            deliveryModeWire = deliveryModeWire,
            verificationLevelWire = verificationLevelWire,
            effectiveLatitude = lat,
            effectiveLongitude = lng,
            isMock = isMock,
            scheduleDecisionWire = scheduleDecisionWire,
            evidenceRefs = listOf("qwy:audit:${clock.elapsedRealtimeMs}"),
            scheduleItemId = sched?.currentItemId ?: "no-schedule",
            scheduleVersion = sched?.version ?: 0L,
        )

        return EnvironmentControlResultV1.observe(observation)
    }

    /**
     * Coordinate verification: can the provider honestly claim INDEPENDENTLY_VERIFIED?
     *
     * KB-8 assigns coordinate authority to the provider. The provider holds both
     * the target (from the schedule item) and the effective coordinates. It must
     * refuse to claim VERIFIED when:
     * - effective coordinates are null (cannot confirm delivery)
     * - effective coordinates diverge from the target (partial apply / stuck)
     * - no schedule item exists (no target to verify against)
     *
     * Tolerance is the contract's frozen 1.0 metres, measured as a geodesic
     * distance rather than an axis-aligned degree box.
     */
    private fun verifyCoordinates(
        effectiveLat: Double?,
        effectiveLng: Double?,
        targetItem: ScheduleItem?,
    ): Boolean {
        // Null coordinates → cannot verify
        if (effectiveLat == null || effectiveLng == null) return false
        // No target → cannot verify
        if (targetItem == null) return false
        if (!effectiveLat.isFinite() || !effectiveLng.isFinite()) return false
        if (effectiveLat !in -90.0..90.0 || effectiveLng !in -180.0..180.0) return false
        // KB-8 fake/production parity (Terra PR-#65 review P1): the TARGET must
        // pass the same finite/range validity gate production Qianwangyou applies
        // (SystemMockTrustPolicy.validCoordinates on the target) BEFORE any
        // distance comparison. Without this, an out-of-domain target like
        // (0.0, 360.0) wraps inside haversine (Δλ=2π ⇒ sin(Δλ/2)²≈1.5e-32) to a
        // ~1.56e-9 m "distance" and mints INDEPENDENTLY_VERIFIED from a
        // coordinate that can never exist.
        if (!targetItem.latitude.isFinite() || !targetItem.longitude.isFinite()) return false
        if (targetItem.latitude !in -90.0..90.0 || targetItem.longitude !in -180.0..180.0) return false
        return distanceMeters(
            effectiveLat,
            effectiveLng,
            targetItem.latitude,
            targetItem.longitude,
        ) <= ContractV1.TRUSTED_LOCATION_TOLERANCE_METERS
    }

    private fun effectiveCoordinates(targetItem: ScheduleItem?): Pair<Double?, Double?> =
        overrideCoordinates ?: Pair(targetItem?.latitude, targetItem?.longitude)

    private fun coordinateVerificationLevelWire(
        effectiveLat: Double?,
        effectiveLng: Double?,
        targetItem: ScheduleItem?,
    ): Int = if (verifyCoordinates(effectiveLat, effectiveLng, targetItem)) {
        VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
    } else {
        VerificationLevelV1.NONE.wire
    }

    private fun distanceMeters(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double,
    ): Double {
        val latARadians = Math.toRadians(latitudeA)
        val latBRadians = Math.toRadians(latitudeB)
        val deltaLatitude = latBRadians - latARadians
        val deltaLongitude = Math.toRadians(longitudeB - longitudeA)
        val halfChord = sin(deltaLatitude / 2.0).let { it * it } +
            cos(latARadians) * cos(latBRadians) *
            sin(deltaLongitude / 2.0).let { it * it }
        return 2.0 * EARTH_MEAN_RADIUS_METERS *
            atan2(sqrt(halfChord), sqrt((1.0 - halfChord).coerceAtLeast(0.0)))
    }

    private fun fail(code: ContractErrorCodeV1, message: String? = null): EnvironmentControlResultV1 =
        EnvironmentControlResultV1.failure(code.wire, message)

    companion object {
        /**
         * Sentinel for "not explicitly set". [forceWaitUntilEpochMs] uses this
         * to distinguish "not overridden" from "explicitly set to null" (the
         * structurally invalid response case for M-RS-01).
         */
        private const val UNSET_MARKER: Long = Long.MIN_VALUE

        private const val EARTH_MEAN_RADIUS_METERS = 6_371_008.8
    }
}
