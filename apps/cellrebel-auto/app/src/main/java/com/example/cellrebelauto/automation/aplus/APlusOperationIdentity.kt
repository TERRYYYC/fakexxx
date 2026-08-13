package com.example.cellrebelauto.automation.aplus

/**
 * Auto-local operation identity for the A+ apply/release lifecycle (Issue #5 R8, §8.1/§8.2, INV-13).
 *
 * The idempotency key + request digest of an attempt's apply/release MUST be recomputable after a
 * process crash from the ATTEMPT-OWNER state (the persisted attempt→task→run rows — §7.1: the
 * Attempt owns its 当前 operation; `AutoAuditEvent` is append-only and NOT a state owner — Sol
 * round-7 P1-4). Deriving both from the durable attempt identity gives exactly that: the same
 * attempt always yields the same key/digest (same-key replay, §8.1 CRASH_RECOVER), and two attempts
 * can never collide — a receipt minted for attempt A is structurally invisible to attempt B, which
 * is what makes the cross-bound receipt attack unrepresentable Auto-locally (Sol round-7 P1-5; the
 * receipt↔leaseId/intentHash/revision binding is contract-owned and waits for #3 — NOT invented
 * here).
 *
 * The ENCODING below is a deterministic placeholder. GREEN re-binds [requestDigest] to the frozen
 * §6.3.4 domain-separated length-prefixed preimage (contract-owned); the DERIVATION SOURCE (the
 * persisted attempt intent) is the pre-freeze-pinned part.
 *
 * # A+ 操作身份（Auto 本地）：key/digest 由持久 attempt 身份派生、崩溃后可重算；编码占位，GREEN 绑 §6.3.4
 */
object APlusOperationIdentity {

    /** Idempotency key of the attempt's APPLY operation (deterministic in the attempt id). */
    fun applyIdempotencyKey(attemptId: Long): String = "auto-aplus-apply-$attemptId"

    /** Idempotency key of the attempt's RELEASE operation (distinct domain from apply). */
    fun releaseIdempotencyKey(attemptId: Long): String = "auto-aplus-release-$attemptId"

    /**
     * Canonical request digest of the attempt's apply intent, over the FROZEN intent fields (dispatched
     * coords + attempt id + run id). The full frozen §6.3.4 preimage additionally covers profileRef /
     * scheduleRef / verification / time-window (Sol round-9 P1-4) — those are contract-owned and land with
     * #3; the DERIVATION SOURCE here is the durable owner state, not a divergent constant. Placeholder
     * encoding — GREEN replaces it with the §6.3.4 frozen domain-separated preimage.
     */
    fun requestDigest(latitude: Double, longitude: Double, attemptId: Long, runSessionId: Long): String =
        "auto-aplus-intent:v0-placeholder:$latitude,$longitude,$attemptId,$runSessionId"

    /**
     * Canonical release digest, derived over the LEASE id (§6.3.4: the release operation is about the
     * lease, so its integrity key covers the leaseId — NOT the apply intent digest, Sol round-8 P1-4).
     * Placeholder encoding — GREEN replaces it with the §6.3.4 frozen preimage.
     */
    fun releaseDigest(leaseId: String): String = "auto-aplus-release:v0-placeholder:$leaseId"
}
