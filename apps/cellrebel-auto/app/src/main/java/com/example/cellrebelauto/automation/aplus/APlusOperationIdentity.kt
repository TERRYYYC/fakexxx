package com.example.cellrebelauto.automation.aplus

import io.github.terryyyc.fakexxx.contract.v1.CanonicalDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1

/**
 * Auto-local operation identity for the A+ apply/release lifecycle (Issue #5 R8, §8.1/§8.2, INV-13).
 *
 * The idempotency key + request digest of an attempt's apply/release MUST be recomputable after a
 * process crash from the ATTEMPT-OWNER state (the persisted attempt→task→run rows — §7.1: the
 * Attempt owns its 当前 operation). Deriving both from the durable attempt identity gives exactly
 * that: the same attempt always yields the same key/digest (same-key replay, §8.1 CRASH_RECOVER),
 * and two attempts can never collide.
 *
 * R43 GREEN (Sol GREEN-review-2 F2): [requestDigest] now delegates to the FROZEN
 * [CanonicalIntentDigestV1] over a real [EnvironmentIntentV1] preimage — KB-8: NO coordinates
 * (the provider is the sole coordinate authority; the digest binds run/attempt/profile/schedule
 * identity + verification + time-window). The digest is a real 64-hex SHA-256, deterministic in
 * the durable owner state, and sensitive to every preimage field.
 *
 * # A+ 操作身份（GREEN）：requestDigest 委托冻结 CanonicalIntentDigestV1（KB-8 无坐标 preimage）
 */
object APlusOperationIdentity {

    /** Idempotency key of the attempt's APPLY operation (deterministic in the attempt id). */
    fun applyIdempotencyKey(attemptId: Long): String = "auto-aplus-apply-$attemptId"

    /** Idempotency key of the attempt's RELEASE operation (distinct domain from apply). */
    fun releaseIdempotencyKey(attemptId: Long): String = "auto-aplus-release-$attemptId"

    /**
     * The §6.3.1 intent preimage for an attempt, built from the DURABLE owner state. KB-8: NO
     * coordinates — the provider is the sole coordinate authority and resolves the effective
     * location from its own schedule item data; Auto only hands over STABLE references.
     *
     * R44 (Sol GREEN-review-3 F2): the refs and the validity window are REAL, never invented
     * constants — `profileRef`/`scheduleRef` name the plan/task the attempt belongs to, and
     * [notBeforeEpochMs]/[deadlineEpochMs] are the attempt's own validity window (its persisted
     * start .. start + test timeout). Every input is recomputable after a crash from the persisted
     * attempt row + plan config, so the digest is byte-identical across the normal path and
     * recovery — and "auto-profile"/"auto-schedule"/infinite-window placeholders are gone.
     */
    fun intent(
        runSessionId: Long,
        attemptId: Long,
        planId: Long,
        taskId: Long,
        notBeforeEpochMs: Long,
        deadlineEpochMs: Long
    ): EnvironmentIntentV1 = EnvironmentIntentV1(
        runId = "auto-run-$runSessionId",
        attemptId = attemptId.toString(),
        profileRef = "plan-$planId",   // the plan IS the batch profile (stable, plan-bound)
        scheduleRef = "task-$taskId",  // the task IS the schedule item reference (stable, task-bound)
        requiredVerificationWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
            .SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs = notBeforeEpochMs,
        deadlineEpochMs = deadlineEpochMs
    )

    /**
     * Canonical request digest of an apply intent — the FROZEN §6.3.1 algorithm over the SAME
     * [EnvironmentIntentV1] object that goes on the wire. R44 (Sol GREEN-review-3 F2): the digest
     * preimage and the Binder request are ONE object by construction — never two recomputations
     * that can drift apart (the runSessionId-misbind probe).
     */
    fun requestDigest(intent: EnvironmentIntentV1): String = CanonicalIntentDigestV1.compute(intent)

    /**
     * Canonical release digest — domain-separated frozen framing over the LEASE id (§6.3.4: the
     * release operation is about the lease, so its integrity key covers the leaseId — NOT the
     * apply intent digest, Sol round-8 P1-4).
     */
    fun releaseDigest(leaseId: String): String =
        CanonicalDigestV1.digest("fakexxx:contract:v1:release", listOf(CanonicalDigestV1.utf8(leaseId)))
}
