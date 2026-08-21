package com.example.cellrebelauto.recovery

/**
 * The external apply call — the ONE place Auto actually drives the external provider's apply (§5
 * boundary, §10 crash/recovery matrix). Deliberately separated from [DurableRecoveryLog] so the
 * three crash windows around an apply are distinguishable, and so a coordinator that NEVER calls a
 * provider cannot green the recovery tests (Sol round-3 Finding 2).
 *
 * The provider enforces its OWN idempotency on `(caller, operation, idempotencyKey)` (§6.3.4): a
 * repeat call with the same key + canonical `requestDigest` returns the same outcome with NO second
 * side effect. That provider-side idempotency is exactly what makes the after-call-before-receipt
 * crash window recoverable — canonical **M-CR-02 = "provider already applied, Auto has no receipt"**:
 * Auto re-calls apply; the provider idempotently no-ops; Auto then records the receipt. The external
 * EFFECT happens once even though Auto's call may be repeated across crashes.
 *
 * The three windows (banked by the coordinator's reconcile orchestration):
 *  - (a) crash BEFORE the external call      ⇒ no provider effect, no receipt;
 *  - (b) crash AFTER the call, BEFORE receipt ⇒ provider applied (effect 1), no receipt  ← M-CR-02;
 *  - (c) crash AFTER receipt, BEFORE checkpoint ⇒ provider applied, receipt present.
 *
 * PRE-FREEZE SKELETON: the production binding (the real apply RPC) is a GREEN concern. RED tests
 * inject a test fake that models provider idempotency and counts effects/invocations, so the tests
 * assert the at-most-once PROVIDER EFFECT — not a counter on the receipt store (§10.1: no driver seam
 * in production code for tests).
 *
 * # 外部 apply 执行器：唯一真正调用外部 provider 的地方；与 receipt store 分离，使三崩溃窗口可区分
 */
interface ExternalApplyExecutor {

    /**
     * Drive the external apply for [intent] + [idempotencyKey] + [requestDigest] (§6.3.4 canonical
     * digest). The provider is idempotent on the key: a repeat returns the same outcome with no
     * second effect.
     *
     * R44 (Sol GREEN-review-3 F2): [intent] is the SAME object the digest was computed over — the
     * caller builds it once and hands it to both, so the wire request and the digest preimage can
     * never drift apart (the runSessionId-misbind probe shape). The attempt identity also lives
     * inside `intent.attemptId`; [attemptId] is retained for effect accounting.
     *
     * @return the apply outcome plus whether the provider had ALREADY applied this key (idempotent
     *         replay at the provider — the signal that distinguishes crash window (b) recovery).
     */
    fun apply(
        attemptId: Long,
        intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        idempotencyKey: String,
        requestDigest: String,
        now: Long
    ): ApplyOutcome

    /**
     * Drive the external lease RELEASE for [leaseId] (§8.1 BEGIN_RELEASE → RELEASE_RECEIPT; §8.2: no
     * fresh apply until RELEASED). Same provider idempotency contract as [apply] — a repeat call with
     * the same key is an idempotent no-op EFFECT — but a DISTINCT operation domain (the release key is
     * derived separately, see
     * [com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.releaseIdempotencyKey]).
     *
     * LEASE-BOUND (Sol round-8 P1-4): release names the [leaseId] it releases, and [releaseDigest] is
     * the §6.3.4 canonical digest OVER THE LEASE — never the apply intent digest. Releasing "someone
     * else's lease" or releasing by a digest unrelated to the lease must be structurally unrepresentable.
     *
     * # 外部 lease 释放调用：lease-bound（releaseDigest 覆盖 leaseId）；与 apply 同幂等契约、不同操作域
     */
    fun release(
        attemptId: Long,
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        now: Long
    ): ApplyOutcome

    // ---- R44 (Sol GREEN-review-3 F1): the rest of the frozen §6.1 journey surface. Without these
    // the production tree has NO discover/preflight/observe/completeAndAdvance call at all, so a
    // fresh attempt can never produce its first observation/completion evidence. Every method
    // fail-closes (null) on transport failure, validator failure, or an unknown wire code. ----

    /** discover(): provider capabilities + the §6.7.1 schedule projection group. Null = fail-closed. */
    fun discover(): io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1?

    /** preflight(): the provider's schedule decision for THIS intent (§6.7). Null = fail-closed. */
    fun preflight(
        intent: io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1,
        idempotencyKey: String,
        requestDigest: String
    ): io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1?

    /** observe(): a lease-bound §6.4 observation. Null = fail-closed. */
    fun observe(
        leaseId: String,
        operationId: String,
        expectedIntentHash: String
    ): io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1?

    /**
     * completeAndAdvance(): complete the current schedule item and advance (§6.7.3). The request
     * carries the CAS preconditions from the projection group captured WHEN THE ATTEMPT OPENED
     * (persisted, replayed verbatim — v1.72). [expectedIntentHash] is the attempt's APPLY intent
     * digest — the receipt's effectiveIntentHash must bind it. Null = fail-closed.
     */
    fun completeAndAdvance(
        request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
        expectedIntentHash: String
    ): io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1?
}

/**
 * Outcome of an external apply call.
 *
 * @property outcome the provider's apply result outcome (GREEN-defined wire string).
 * @property providerHadAlreadyApplied `true` iff the provider had already applied this
 *           `(idempotencyKey, requestDigest)` and this call was an idempotent no-op EFFECT — the
 *           M-CR-02 window (b) recovery signal (Auto called twice across a crash; the provider
 *           applied once).
 * @property leaseId the provider-returned lease id this apply acquired (Sol round-9 P1-2: the normal
 *           path MUST obtain the lease from the apply, never invent it). Null = fail-closed / no lease
 *           acquired (a release call also leaves it null — it is apply-domain only).
 */
data class ApplyOutcome(
    val outcome: String,
    val providerHadAlreadyApplied: Boolean,
    val leaseId: String? = null,
    // ---- R43 (Sol GREEN-review-2 F3): verbatim ApplyReceiptV1 proof fields carried back from the
    //      provider so the coordinator persists them atomically with the receipt (§7.1). ----
    val operationId: String? = null,
    val acceptedIntentHash: String? = null,
    val appliedAtEpochMs: Long? = null,
    val environmentRevision: Long? = null,
    val verificationLevelWire: Int? = null
)
