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

    /** Same `idempotencyKey` replayed with a different payload digest (§6.3.3, INV-13). */
    IDEMPOTENCY_CONFLICT(12),

    /** Structurally invalid request: empty required ref, out-of-range coordinate,
     *  `deadline <= notBefore` (§6.3.3, INV-04). */
    REQUEST_INVALID(13),

    /**
     * `expectedCurrentItemId` does not match the owner's `currentItemId` (§6.7.4).
     *
     * This is what makes advance a compare-and-advance rather than a blind
     * increment: it is the single code that stops BOTH a wrong-item advance and a
     * double advance, because a caller holding a stale current item produces the
     * same mismatch in either case. A skipped item needs no code of its own — it
     * arrives here or at [SCHEDULE_VERSION_STALE].
     */
    SCHEDULE_ITEM_MISMATCH(14),

    /**
     * `expectedScheduleVersion` does not match the owner's `scheduleVersion` (§6.7.4):
     * the schedule changed while Auto was proving quota, so the completion result no
     * longer provably belongs to the same item.
     */
    SCHEDULE_VERSION_STALE(15),

    /**
     * No next item. Terminal, NOT a failure: the current item is retained and the
     * schedule does not wrap (§6.7.4). Callers must not retry this into an advance.
     */
    SCHEDULE_EXHAUSTED(16),
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
