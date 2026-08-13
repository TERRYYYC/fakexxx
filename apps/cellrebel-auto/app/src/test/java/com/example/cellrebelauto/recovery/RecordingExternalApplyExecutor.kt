package com.example.cellrebelauto.recovery

/**
 * Test fake for [ExternalApplyExecutor] that models the external provider's OWN idempotency (§6.3.4:
 * `(caller, operation, idempotencyKey)` is idempotent at the provider) and counts the at-most-once
 * PROVIDER effect.
 *
 * This is where the effect counter lives — NOT on [FakeDurableRecoveryLog] (§10.1: no driver seam in
 * production code for tests). The recovery tests assert `effectCount` / `invocationCount` here, so a
 * coordinator that returns ADVANCED without actually calling the executor cannot pass: its
 * invocation/effect counts stay at zero.
 *
 * Provider model: the provider remembers each idempotency key it has applied. A repeat call for an
 * already-applied key is an idempotent no-op EFFECT — it returns the same outcome with
 * `providerHadAlreadyApplied = true` and does NOT increment the effect count. This is exactly the
 * M-CR-02 window (b) recovery semantics: Auto may call twice across a crash; the provider applies once.
 *
 * # 外部 apply 执行器测试桩：建模 provider 自身幂等 + 计 provider effect；effect 计数在这里，不在 receipt store
 */
class RecordingExternalApplyExecutor(
    /** The outcome the fake provider returns for a successful apply (default "RELEASED"). */
    private val outcome: String = "RELEASED"
) : ExternalApplyExecutor {

    /** idempotency keys the provider has already applied (provider-side idempotency memory). */
    private val appliedKeys = mutableSetOf<String>()

    /** Provider EFFECT count per attempt — the at-most-once evidence. Incremented only on a real apply. */
    private val effectCounts = mutableMapOf<Long, Int>()

    /** How many times Auto has INVOKED apply per idempotency key (may exceed 1 across crashes). */
    private val invocationCounts = mutableMapOf<String, Int>()

    override fun apply(attemptId: Long, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome {
        invocationCounts[idempotencyKey] = (invocationCounts[idempotencyKey] ?: 0) + 1
        val alreadyApplied = idempotencyKey in appliedKeys
        if (!alreadyApplied) {
            // Fresh apply: the provider actually performs the side effect once.
            appliedKeys.add(idempotencyKey)
            effectCounts[attemptId] = (effectCounts[attemptId] ?: 0) + 1
        }
        // The provider returns a STABLE lease per attempt (derived from the attempt id) — the same
        // key replays the same lease (idempotent). The RED asserts the lease is persisted, not invented.
        return ApplyOutcome(outcome = outcome, providerHadAlreadyApplied = alreadyApplied, leaseId = "lease-$attemptId")
    }

    // ---- release (§8.1 BEGIN_RELEASE → RELEASE_RECEIPT; §8.2: no fresh apply until RELEASED) ----
    // # release 与 apply 同幂等契约、不同操作域（release key 独立派生）。effect 计数也独立，
    // # 使 finding ③（release 收敛）的 GREEN 可断言"释放 effect 至多一次 + 按真实 release key 调用"。

    private val releasedKeys = mutableSetOf<String>()
    private val releaseEffectCounts = mutableMapOf<Long, Int>()
    private val releaseInvocationCounts = mutableMapOf<String, Int>()

    override fun release(
        attemptId: Long,
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        now: Long
    ): ApplyOutcome {
        releaseInvocationCounts[idempotencyKey] = (releaseInvocationCounts[idempotencyKey] ?: 0) + 1
        val already = idempotencyKey in releasedKeys
        if (!already) {
            releasedKeys.add(idempotencyKey)
            releaseEffectCounts[attemptId] = (releaseEffectCounts[attemptId] ?: 0) + 1
        }
        return ApplyOutcome(outcome = outcome, providerHadAlreadyApplied = already)
    }

    /** Times the provider actually performed the release side effect for [attemptId] (at-most-once). */
    fun releaseEffectCount(attemptId: Long): Int = releaseEffectCounts[attemptId] ?: 0

    /** Times Auto has invoked release for [idempotencyKey]. */
    fun releaseInvocationCount(idempotencyKey: String): Int = releaseInvocationCounts[idempotencyKey] ?: 0

    /** Times the provider actually performed the side effect for [attemptId] (at-most-once ⇒ 0 or 1). */
    fun effectCount(attemptId: Long): Int = effectCounts[attemptId] ?: 0

    /** Times Auto has invoked apply for [idempotencyKey] (distinguishes fresh call from crash replay). */
    fun invocationCount(idempotencyKey: String): Int = invocationCounts[idempotencyKey] ?: 0
}
