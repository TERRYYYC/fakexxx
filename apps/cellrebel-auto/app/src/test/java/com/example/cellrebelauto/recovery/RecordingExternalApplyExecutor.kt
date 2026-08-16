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
            operationId = operationId, acceptedIntentHash = acceptedIntentHash,
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
}
