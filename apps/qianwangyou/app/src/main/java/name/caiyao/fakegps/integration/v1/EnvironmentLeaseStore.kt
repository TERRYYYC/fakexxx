package name.caiyao.fakegps.integration.v1

/**
 * Durable lease store implementing the §8.4 seven-state machine.
 *
 * Frozen semantics this store owns:
 *  - Conflict predicate (INV-28): ANY non-RELEASED lease on the device blocks a
 *    new apply; the only exception is the same caller replaying the same
 *    idempotencyKey.
 *  - EXPIRED / REVOKED / RELEASE_INCOMPLETE keep blocking until explicitly
 *    converged — TTL is never a bypass of INV-21 (false-red over false-green).
 *  - Deadline clock bridge: deadlineElapsedRealtimeMs is snapshotted ONCE at
 *    apply admission (max(0, deadlineEpochMs - nowEpoch) + nowElapsed); wall
 *    clock movement never changes lease lifecycle afterwards (M-LS-10/11).
 *  - Monotonic comparability rides on applyOwnerGeneration: generation change ⊇
 *    clock-epoch change, so ACQUIRING/ACTIVE with a stale generation is EXPIRED
 *    (M-LS-12..14 — deliberate false-red policy).
 *  - State-aware restart recovery (§8.4 recovery table): REVOKED and
 *    RELEASE_INCOMPLETE are preserved verbatim (M-LS-15/16), RELEASING replays
 *    release (M-LS-17), only ACQUIRING/ACTIVE are subject to the
 *    cleanliness/generation rules (M-LS-07/12), EXPIRED/RELEASED untouched.
 *  - REVOKED is unreachable to its (former) caller; provider-driven internal
 *    cleanup pushes REVOKED → RELEASING → RELEASED (M-LS-04).
 */
class EnvironmentLeaseStore(
    private val storage: DurableKv,
    private val clock: MonotonicClock,
) {
    /** The single lease blocking new applies, if any (any non-RELEASED state). */
    fun blockingLease(): LeaseRecord? = TODO("Task 3 GREEN")

    fun get(leaseId: String): LeaseRecord? = TODO("Task 3 GREEN")

    /**
     * Effective state evaluated lazily against the monotonic clock and the
     * CURRENT owner generation (deadline pass / generation mismatch → EXPIRED).
     */
    fun effectiveState(leaseId: String, currentGeneration: Long): LeaseState =
        TODO("Task 3 GREEN")

    /** Persist a new or transitioned record (serialized read-modify-write). */
    fun put(record: LeaseRecord): Unit = TODO("Task 3 GREEN")

    /** Mark the caller's lease REVOKED when qwy revokes the caller (M-LS-04/M-PA-09). */
    fun markRevoked(callerApplicationId: String, source: RevokeSource): Unit =
        TODO("Task 3 GREEN")

    /**
     * Provider-driven internal cleanup for REVOKED leases — the former caller
     * cannot call anymore, so qwy converges its own environment (M-LS-04).
     */
    fun runProviderCleanupForRevoked(environment: QwyEnvironment): Unit =
        TODO("Task 3 GREEN")

    /**
     * State-aware restart reconciliation (§8.4 recovery table). Called once when
     * the owner process comes up with a fresh generation.
     */
    fun recoverAfterRestart(
        currentGeneration: Long,
        cleanlinessProvable: Boolean,
        environment: QwyEnvironment,
    ): Unit = TODO("Task 3 GREEN")
}
