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
     * The §6.3.1 intent preimage for an attempt, built from the DURABLE owner state. The legacy
     * (latitude, longitude) parameters are RETAINED in the signature for call-site compatibility
     * but are NOT part of the preimage (KB-8: coordinates were removed from the intent — the
     * provider resolves the effective location from its own schedule item data).
     */
    fun intent(runSessionId: Long, attemptId: Long): EnvironmentIntentV1 = EnvironmentIntentV1(
        runId = "auto-run-$runSessionId",
        attemptId = attemptId.toString(),
        profileRef = "auto-profile",     // single-profile batch v1; the plan's profile reference
        scheduleRef = "auto-schedule",   // single-schedule batch v1; the plan's schedule reference
        requiredVerificationWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
            .SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        notBeforeEpochMs = 0L,
        deadlineEpochMs = Long.MAX_VALUE // no per-attempt deadline in the batch v1 template
    )

    /**
     * Canonical request digest of the attempt's apply intent — the FROZEN §6.3.1 algorithm over
     * the KB-8 preimage (no coordinates), deterministic in the durable owner identity.
     */
    fun requestDigest(latitude: Double, longitude: Double, attemptId: Long, runSessionId: Long): String =
        CanonicalIntentDigestV1.compute(intent(runSessionId, attemptId))

    /**
     * Canonical release digest — domain-separated frozen framing over the LEASE id (§6.3.4: the
     * release operation is about the lease, so its integrity key covers the leaseId — NOT the
     * apply intent digest, Sol round-8 P1-4).
     */
    fun releaseDigest(leaseId: String): String =
        CanonicalDigestV1.digest("fakexxx:contract:v1:release", listOf(CanonicalDigestV1.utf8(leaseId)))
}
