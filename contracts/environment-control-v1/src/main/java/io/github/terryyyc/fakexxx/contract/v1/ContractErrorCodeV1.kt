package io.github.terryyyc.fakexxx.contract.v1

/**
 * Typed failures for contract v1.
 *
 * Spec: `feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md` §6.3.2.
 *
 * Expected business failures travel in [EnvironmentControlResultV1.errorCodeWire].
 * The consumer maps the wire code back to one of these constants. **An unknown
 * code maps to [INTERNAL_FAILURE] and fails closed** — it is never
 * optimistically treated as compatible.
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
     *  - `apply`: every non-`ACTIVE` state (`EXPIRED`, `REVOKED`,
     *    `RELEASE_INCOMPLETE`), plus not-owned-by-this-caller and already
     *    `RELEASED`.
     *  - `observe`: the same, **except inside the post-advance verification
     *    window** -- see the third bullet below.
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
     *  - `observe`, post-advance verification window: the already-`RELEASED`
     *    branch **does not apply** when the `leaseId` is the historical
     *    reference carried by this caller's most recent successful
     *    [IEnvironmentControlV1.completeAndAdvance] and no new lease has since
     *    been granted to this caller. §6.7.5 freezes post-advance `observe` as
     *    **mandatory** ("the receipt is the peer's own account, not proof of
     *    effect"), but that call sits between `completeAndAdvance` and the next
     *    `apply`, and §6.7.4b step 5's lease gate is device-global -- so no
     *    ACTIVE lease can exist at that instant, and `ObserveRequestV1.leaseId`
     *    is non-null. Returning 8 there makes a step frozen as *mandatory*
     *    unconditionally unreachable, leaving Auto with only the receipt to
     *    trust -- the exact wrong-environment attribution §6.7.5 exists to
     *    block. Outside the window (a new lease was granted, or the `leaseId`
     *    is not that reference) it is still 8, and the
     *    not-owned-by-this-caller branch is untouched. A forged `leaseId`
     *    cannot use this exception: the match is against the receipt the
     *    provider itself retained for §6.7.5 idempotent re-fetch.
     *
     *    This does not swallow concurrent drift. If a foreign caller applies
     *    first, `observe` is still accepted and §6.7.5's
     *    `effectiveIntentHash` / `effectiveEnvironmentRevision` comparison
     *    reports the mismatch. **Accepting and diagnosing beats returning 8**,
     *    which is indistinguishable from "your lease expired" and tells Auto
     *    nothing about whether to retry or recover.
     *
     * **Frozen** (v1.75–v1.78; `KB-5` closed): `completeAndAdvance` judges
     * the `leaseId`'s attribution in §6.7.4b step 3b (order 3b → 4 → 5), and
     * every attribution failure is THIS code — wire 8's genus is "该 leaseId
     * 对本次操作不可用", with `foreign` (owned by a different caller),
     * `unproven` (no provider record, or no originating-item attribution — a
     * forged reference never buys history), and `wrong-item`
     * (quota earned for another item) as its species. 3b precedes the step-4
     * schedule gates so a forged or foreign reference can never buy current
     * schedule state (17/16/14/15) it never proved quota for.
     *
     * **Attribution and liveness are orthogonal (v1.78)**: an own,
     * item-matching reference that is provably live — still `ACTIVE`, never
     * `RELEASED` — is NOT `unproven`; it passes step 3b and step 5's
     * device-global lease gate answers `LEASE_CONFLICT`(7), not 8. `unproven
     * → 8` is reserved for an absent provider record or a missing
     * originating-item attribution. v1.76 removed recency from this gate: any
     * caller-owned reference with the matching earned item passes attribution
     * regardless of newer released leases.
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

    /**
     * Structurally invalid request: empty required ref, `deadline <= notBefore`
     * (§6.3.3, INV-04).
     *
     * **Corrected in spec v1.62.** No coordinate case exists here: KB-8 removed
     * coordinates from every request payload, so "coordinate out of range" is not
     * a reachable request shape in v1.
     *
     * **Corrected in spec v1.72.** v1.71 also routed "structurally complete, but
     * `expectedScheduleId` disagrees with the effective schedule" to this code.
     * That reading is retired: such a request IS structurally valid, and the case
     * now has its own code, [SCHEDULE_IDENTITY_MISMATCH] (17). This constant
     * covers NO compare-and-advance precondition mismatch — those are 14, 15, 16
     * and 17. The distinction is not cosmetic: a caller recovers from 13 by
     * fixing the request, and from 17 by re-discovering and reconciling.
     */
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

    /**
     * `completeAndAdvance`'s `expectedScheduleId` does not match the schedule that
     * is currently effective on the device (§6.3.3, §6.7.4b step 4). The request is
     * structurally well-formed and its proof is self-consistent; it simply targets
     * a DIFFERENT schedule than the one in force.
     *
     * **Added in spec v1.72.** v1.71 reused [REQUEST_INVALID] (13) for this case.
     * That broke §6.3.3's own rule — the table is the complete set of v1 typed
     * failures and forbids reusing a near-synonym code — and it contradicted 13's
     * own definition, which is scoped to *structurally* invalid requests. Two
     * coexisting definitions make the caller's recovery branch non-portable: "fix
     * the request and retry" and "re-discover / reconcile" are different actions.
     *
     * 14/15/16 do not fit either, and for a reason worth stating: they describe the
     * item, version and exhaustion state of the CURRENT schedule. When the identity
     * leg fails, that state does not describe the schedule the request is about, so
     * reporting any of them would answer a question about the wrong object.
     *
     * This is checked FIRST inside step 4 — identity before state — because
     * §6.7.1 and `M-AD-26` permit two schedules to reuse the same
     * `(itemId, scheduleVersion)` pair. Without the identity leg the CAS passes on
     * the wrong schedule and the advance is genuinely committed there; the
     * post-advance readback can then observe the damage but cannot prevent it.
     */
    SCHEDULE_IDENTITY_MISMATCH(17),
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
