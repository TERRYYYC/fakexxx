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
    /**
     * No approved pairing covers this call. Canonical §6.3.3 gives it two
     * branches, and naming only the first was how this KDoc read before: the
     * caller is not in the provider's allowlist, **or** — on the Auto side, as
     * local state — the provider has not been approved. Whichever side owns the
     * pairing record asserts it.
     *
     * Not caught by the §6.3.3 KDoc gate (check-contract-v1.sh section 5c): the
     * row scopes nothing to a method and carries no correction note, so a KDoc
     * naming one of two branches satisfies both of its rules.
     */
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

    /**
     * Conflict with an active or unconverged lease. The scope is **per method**,
     * and the difference between the two is normative (§6.3.3):
     *
     *  - `apply` (§6.6): a lease held by *another* caller, or for another intent.
     *  - `completeAndAdvance` (§6.7.4a): *any* non-`RELEASED` / unconverged lease
     *    present on the device — **regardless of which caller owns it**, this
     *    caller's own included.
     *
     * Reading the second case as "another caller's lease" is the literal reading
     * §6.7.4a's lease gate exists to forbid: the gate is pre-emptive, stopping
     * drift *before* it happens, which is what separates it from
     * [ENVIRONMENT_DRIFT] (9) — that one is observed after the fact.
     */
    LEASE_CONFLICT(7),

    /**
     * The `leaseId` is unusable **for this particular operation**. The scope is
     * per method, not a property of the lease state alone (§6.3.3):
     *
     *  - `apply` / `observe`: every non-`ACTIVE` state (`EXPIRED`, `REVOKED`,
     *    `RELEASE_INCOMPLETE`), plus not-owned-by-this-caller and already
     *    `RELEASED`.
     *  - `release`: **must be accepted** in `ACTIVE`, `EXPIRED` and
     *    `RELEASE_INCOMPLETE` when the owning caller asks, and must drive the
     *    state machine. Returning 8 for `EXPIRED` would make §8.4's
     *    `EXPIRED → RELEASING` convergence unreachable and strand the lease in a
     *    blocking state permanently. Only "not this caller's" or "already
     *    `RELEASED`" return 8.
     *  - `completeAndAdvance`: the already-`RELEASED` branch **does not apply**.
     *    That `leaseId` is a historical attribution reference to the lease the
     *    quota was earned under, not a live hold, and §6.7.4a freezes the call
     *    order as release-then-advance — so "already `RELEASED`" is this
     *    request's *normal* shape. Rejecting it would fail the only legal call
     *    form and make the method unconditionally unusable.
     *
     * **Not frozen** (§20.1 `KB-5`): what `completeAndAdvance` does with a
     * `leaseId` owned by a *different* caller. §6.7.4b's precedence order has no
     * gate for it. Neither side may pick a reading and depend on it before that
     * is adjudicated — one rejecting with 8 while the other accepts makes Auto's
     * recovery strategy non-portable.
     */
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
     * Advance requested when the schedule was **already exhausted**: there is no
     * current item left to complete, so this is a genuine *caller* error (§6.7.4).
     *
     * **Corrected in spec v1.39.** This KDoc previously read "terminal, NOT a
     * failure", which §6.7.4 contradicts. Completing the LAST item is a
     * **success**: it returns [AdvanceReceiptV1] with
     * `outcomeWire = AdvanceOutcomeV1.EXHAUSTED` and `advancedToItemId = null`,
     * never this code. Wire 16 is only for a request that arrives *after*
     * exhaustion. [AdvanceOutcomeV1] has carried that distinction since the same
     * revision — this constant was the half that did not get updated, so the two
     * KDocs in this module stated opposite things about the same wire number.
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
