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
     * Drive the external apply for [idempotencyKey] + [requestDigest] (§6.3.4 canonical digest). The
     * provider is idempotent on the key: a repeat returns the same outcome with no second effect.
     *
     * @return the apply outcome plus whether the provider had ALREADY applied this key (idempotent
     *         replay at the provider — the signal that distinguishes crash window (b) recovery).
     */
    fun apply(attemptId: Long, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome

    /**
     * Drive the external lease RELEASE for [idempotencyKey] (§8.1 BEGIN_RELEASE → RELEASE_RECEIPT;
     * §8.2: no fresh apply until RELEASED). Same provider idempotency contract as [apply] — a repeat
     * call with the same key is an idempotent no-op EFFECT — but a DISTINCT operation domain (the
     * release key is derived separately, see
     * [com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.releaseIdempotencyKey]).
     *
     * # 外部 lease 释放调用：与 apply 同幂等契约、不同操作域
     */
    fun release(attemptId: Long, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome
}

/**
 * Outcome of an external apply call.
 *
 * @property outcome the provider's apply result outcome (GREEN-defined wire string).
 * @property providerHadAlreadyApplied `true` iff the provider had already applied this
 *           `(idempotencyKey, requestDigest)` and this call was an idempotent no-op EFFECT — the
 *           M-CR-02 window (b) recovery signal (Auto called twice across a crash; the provider
 *           applied once).
 */
data class ApplyOutcome(val outcome: String, val providerHadAlreadyApplied: Boolean)
