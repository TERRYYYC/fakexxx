package io.github.terryyyc.fakexxx.contract.v1

/**
 * Typed failures for contract v1.
 *
 * Spec: `feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md` §6.3.2.
 *
 * Expected business failures travel as `ServiceSpecificException(wire)`. The
 * consumer maps the wire code back to one of these constants. **An unknown code
 * maps to [INTERNAL_FAILURE] and fails closed** — it is never optimistically
 * treated as compatible.
 *
 * Binder death and `RemoteException` are transport failures, not entries in this
 * enum; they enter recovery on their own path.
 *
 * Error `message` strings are for human diagnosis only. No machine decision may
 * depend on them, and they must never carry pairing secrets (INV-18).
 */
enum class ContractErrorCodeV1(val wire: Int) {
    /** Caller has not been approved by the operator on the provider side. */
    NOT_PAIRED(1),

    /**
     * Caller is resolvable but rejected: signer mismatch, shared UID (more than
     * one package for the calling UID), multiple signers, or a revoked pairing.
     */
    CALLER_NOT_ALLOWED(2),

    /** Protocol version or wire code outside the supported set. Fail closed. */
    INCOMPATIBLE_PROTOCOL(3),

    /** Requested capability is not available in the provider's current state. */
    CAPABILITY_UNAVAILABLE(4),

    /** Schedule denies the intent. */
    SCHEDULE_DENIED(5),

    /** Continuity coverage is not FULL, so the observation cannot support trust. */
    CONTINUITY_NOT_FULL(6),

    /** Another lease already holds the environment on this device. */
    LEASE_CONFLICT(7),

    /** The referenced lease is no longer current. */
    STALE_LEASE(8),

    /**
     * The environment no longer matches the lease's accepted intent — for
     * example a lease reused after the intent changed.
     */
    ENVIRONMENT_DRIFT(9),

    /** Release could not prove the environment was fully cleaned up (INV-21). */
    RELEASE_INCOMPLETE(10),

    /** Unclassified provider-side failure, and the sink for unknown wire codes. */
    INTERNAL_FAILURE(11),
    ;

    companion object {
        /**
         * Strict decode. Returns null for unknown codes so a caller that needs to
         * distinguish "unknown peer code" from a genuine [INTERNAL_FAILURE] can.
         */
        fun fromWire(code: Int): ContractErrorCodeV1? = entries.firstOrNull { it.wire == code }

        /**
         * Fail-closed decode used on the consumer's error path: an unknown code
         * becomes [INTERNAL_FAILURE] rather than being guessed as compatible.
         */
        fun fromWireOrInternalFailure(code: Int): ContractErrorCodeV1 =
            fromWire(code) ?: INTERNAL_FAILURE
    }
}
