package com.example.cellrebelauto.recovery

/**
 * Test fake for [ExternalApplyExecutor] that models the external provider's OWN idempotency (§6.3.4:
 * `(caller, operation, idempotencyKey)` is idempotent at the provider) and counts the at-most-once
 * PROVIDER effect.
 *
 * DIGEST-BOUND (Sol round-9 P1-4): the provider remembers the canonical [requestDigest] per key — a
 * repeat call with the SAME key + SAME digest replays (no second effect), while the SAME key + a
 * DIFFERENT digest returns `IDEMPOTENCY_CONFLICT` (INV-13). RELEASE is likewise bound to the
 * (idempotencyKey, leaseId, releaseDigest) tuple (P1-5): same key + different lease/digest → conflict.
 * The RED asserts the provider call ARGS (via [releaseCalls]) — not just a call count — so a wrong
 * lease/digest cannot green.
 *
 * This is where the effect counter lives — NOT on [FakeDurableRecoveryLog] (§10.1: no driver seam in
 * production code for tests).
 *
 * # 外部 apply/release 执行器测试桩：digest-bound 幂等 + 冲突 + 逐字段 call 记录；effect 计数在这里
 */
class RecordingExternalApplyExecutor(
    /** The outcome the fake provider returns for a successful apply (default "RELEASED"). */
    private val outcome: String = "RELEASED",
    // R44 (Sol GREEN-review-3 F3): optional verbatim ApplyReceiptV1 proof fields the provider returns
    // with a successful apply — the durable receipt must persist + read back ALL of them.
    private val operationId: String? = null,
    private val acceptedIntentHash: String? = null,
    private val appliedAtEpochMs: Long? = null,
    private val environmentRevision: Long? = null,
    private val verificationLevelWire: Int? = null
) : ExternalApplyExecutor {

    private val appliedKeys = mutableSetOf<String>()
    private val appliedDigests = mutableMapOf<String, String>()
    private val effectCounts = mutableMapOf<Long, Int>()
    private val invocationCounts = mutableMapOf<String, Int>()

    override fun apply(
        attemptId: Long,
        intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        idempotencyKey: String,
        requestDigest: String,
        now: Long
    ): ApplyOutcome {
        invocationCounts[idempotencyKey] = (invocationCounts[idempotencyKey] ?: 0) + 1
        val priorDigest = appliedDigests[idempotencyKey]
        if (priorDigest != null && priorDigest != requestDigest) {
            // Same key + different canonical digest → IDEMPOTENCY_CONFLICT (INV-13), no effect, no lease.
            return ApplyOutcome(outcome = "IDEMPOTENCY_CONFLICT", providerHadAlreadyApplied = false, leaseId = null)
        }
        val alreadyApplied = priorDigest != null
        if (!alreadyApplied) {
            appliedKeys.add(idempotencyKey)
            appliedDigests[idempotencyKey] = requestDigest
            effectCounts[attemptId] = (effectCounts[attemptId] ?: 0) + 1
        }
        return ApplyOutcome(
            outcome = outcome, providerHadAlreadyApplied = alreadyApplied, leaseId = "lease-$attemptId",
            operationId = operationId ?: "op-$attemptId", acceptedIntentHash = acceptedIntentHash,
            appliedAtEpochMs = appliedAtEpochMs, environmentRevision = environmentRevision,
            verificationLevelWire = verificationLevelWire
        )
    }

    // ---- release: bound to (idempotencyKey, leaseId, releaseDigest) (P1-5) ----

    private val releasedKeys = mutableSetOf<String>()
    private val releaseBindings = mutableMapOf<String, Pair<String, String>>()
    private val releaseEffectCounts = mutableMapOf<Long, Int>()
    private val releaseInvocationCounts = mutableMapOf<String, Int>()

    /** Every release call's exact args — the RED asserts these, not a bare call count (P1-5). */
    data class ReleaseCall(val attemptId: Long, val idempotencyKey: String, val leaseId: String, val releaseDigest: String)
    private val releaseCalls = mutableListOf<ReleaseCall>()

    override fun release(
        attemptId: Long,
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        now: Long
    ): ApplyOutcome {
        releaseInvocationCounts[idempotencyKey] = (releaseInvocationCounts[idempotencyKey] ?: 0) + 1
        releaseCalls += ReleaseCall(attemptId, idempotencyKey, leaseId, releaseDigest)
        releaseEvents += "release:$leaseId"
        val prior = releaseBindings[idempotencyKey]
        if (prior != null && (prior.first != leaseId || prior.second != releaseDigest)) {
            // Same key + different lease/digest → conflict.
            return ApplyOutcome(outcome = "IDEMPOTENCY_CONFLICT", providerHadAlreadyApplied = false)
        }
        val already = prior != null
        if (!already) {
            releasedKeys.add(idempotencyKey)
            releaseBindings[idempotencyKey] = leaseId to releaseDigest
            releaseEffectCounts[attemptId] = (releaseEffectCounts[attemptId] ?: 0) + 1
        }
        return ApplyOutcome(outcome = outcome, providerHadAlreadyApplied = already)
    }

    fun releaseEffectCount(attemptId: Long): Int = releaseEffectCounts[attemptId] ?: 0
    fun releaseInvocationCount(idempotencyKey: String): Int = releaseInvocationCounts[idempotencyKey] ?: 0
    fun releaseCallsFor(attemptId: Long): List<ReleaseCall> = releaseCalls.filter { it.attemptId == attemptId }
    fun effectCount(attemptId: Long): Int = effectCounts[attemptId] ?: 0
    fun invocationCount(idempotencyKey: String): Int = invocationCounts[idempotencyKey] ?: 0

    // ---- R44 (Sol GREEN-review-3 F1): the frozen journey surface. A HEALTHY-provider default so the
    // engine's production journey can run in tests; each response is a settable fixture and every
    // call is recorded for oracle assertions. ----

    // F12: the discover fixture's currentScheduleId is the provider's durable anchor that the
    // engine persists and includes in the intent digest.  It MUST match FakeEvidenceSource's
    // default scheduleRef ("qwy-default-schedule") — otherwise the engine computes digest A
    // (from the anchored discover value) while the evidence source returns observations with
    // digest B (from its hardcoded scheduleRef), and trust policy rejects every observation.
    // Before F12 the intent digest used taskId, so the mismatch was invisible.
    var discoverFixture: io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1? =
        io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1(
            serviceVersion = "fake-provider-1",
            supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            profileRefs = listOf("plan-1"),
            scheduleRefs = listOf("qwy-default-schedule"),
            currentScheduleId = "qwy-default-schedule",
            currentItemId = "item-1",
            scheduleVersion = 1L,
            exhausted = false
        )
    var discoverCalls = 0
        private set

    override fun discover(): io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1? {
        discoverCalls++
        return discoverFixture
    }

    var preflightDecisionWire: Int = io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.ALLOWED_NOW.wire
    val preflightCalls = mutableListOf<String>() // idempotencyKeys

    override fun preflight(
        intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        idempotencyKey: String,
        requestDigest: String
    ): io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1 {
        preflightCalls += idempotencyKey
        return io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1(
            acceptedIntentHash = requestDigest,
            scheduleDecisionWire = preflightDecisionWire,
            waitUntilEpochMs = null,
            achievableVerificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            blockingReasonWires = emptyList(),
            scheduleItemId = "item-1",
            scheduleVersion = 1L,
            exhausted = false
        )
    }

    /** Phase-aware observation answers; default null (no observation). Tests driving the production
     *  evidence source set this to return §6.4-valid wire observations stamped in the elapsed domain. */
    var observeAnswers: (leaseId: String, operationId: String, expectedIntentHash: String) ->
        io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1? = { _, _, _ -> null }
    val observeCalls = mutableListOf<String>() // leaseIds

    /**
     * R45 (Sol R45 P1-5): the four-leg observation ARMED by a non-terminal advance — a healthy
     * provider's post-advance environment matches its own receipt. Served ONCE by the next
     * observe() (the engine's independent verification), then cleared: tests tamper legs by
     * overwriting [observeAnswers] instead.
     */
    var armedPostAdvanceObservation: io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1? = null

    override fun observe(
        leaseId: String,
        operationId: String,
        expectedIntentHash: String
    ): io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1? {
        observeCalls += leaseId
        val armed = armedPostAdvanceObservation
        if (armed != null) {
            armedPostAdvanceObservation = null
            return armed
        }
        return observeAnswers(leaseId, operationId, expectedIntentHash)
    }

    val advanceCalls = mutableListOf<io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1>()
    val advanceEvents = mutableListOf<String>() // "advance" — shared with release/apply ordering oracles
    val releaseEvents = mutableListOf<String>()
    var advanceOutcomeWire: Int = io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1.ADVANCED.wire

    override fun completeAndAdvance(
        request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
        expectedIntentHash: String
    ): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1 {
        advanceCalls += request
        advanceEvents += "advance"
        // The effective intent hash echoes the digest of the LAST apply this fake served (the intent
        // currently in effect for the lease) — a real provider's receipt binds the same way.
        val effectiveHash = appliedDigests.values.lastOrNull() ?: ""
        // R46 (Sol R46 P1-2): a HEALTHY provider signs its receipt canonically — the engine now
        // verifies this binding before trusting anything in the receipt. (The digest preimage
        // excludes receiptDigest itself, so sign-then-fill is the canonical construction.)
        var receipt = io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1(
            outcomeWire = advanceOutcomeWire,
            advancedFromItemId = request.expectedCurrentItemId,
            advancedToItemId = "item-2",
            scheduleVersionAfter = request.expectedScheduleVersion + 1,
            effectiveIntentHash = effectiveHash,
            effectiveEnvironmentRevision = 7L,
            receiptDigest = ""
        )
        receipt = receipt.copy(
            receiptDigest = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1.compute(
                receipt, request.requestDigest, request.idempotencyKey
            )
        )
        // R45 (Sol R45 P1-5): arm the four-leg-matching observation for the engine's independent
        // post-advance verification (a HEALTHY provider's environment matches its receipt).
        armedPostAdvanceObservation = io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1(
            leaseId = request.leaseId,
            acceptedIntentHash = receipt.effectiveIntentHash,
            observedAtEpochMs = 0L, observedAtElapsedRealtimeMs = 0L,
            environmentRevision = receipt.effectiveEnvironmentRevision,
            environmentFingerprint = "post-advance-fp",
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = null, continuitySinceElapsedRealtimeMs = null,
            deliveryModeWire = io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = null, effectiveLongitude = null, isMock = true,
            scheduleDecisionWire = io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = emptyList(),
            scheduleItemId = receipt.advancedToItemId!!,
            scheduleVersion = receipt.scheduleVersionAfter
        )
        return receipt
    }
}
