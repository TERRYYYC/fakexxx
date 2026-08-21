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
    companion object {
        private const val LEASE_NS = "integration.v1.leases"
        private const val CURRENT_KEY = "__current_lease_id__"
    }

    /**
     * The single lease blocking new applies, if any (any non-RELEASED effective
     * state). §8.4 INV-28: EXPIRED / REVOKED / RELEASE_INCOMPLETE keep blocking
     * until explicitly converged — TTL is never a bypass of INV-21.
     */
    fun blockingLease(): LeaseRecord? {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return null
        val record = get(leaseId) ?: return null
        // Must use stored state to decide blocking — effective state is only
        // for ACQUIRING/ACTIVE lazy expiration, but ALL non-RELEASED states block.
        return if (record.state != LeaseState.RELEASED) record else null
    }

    fun get(leaseId: String): LeaseRecord? {
        val raw = storage.read(LEASE_NS, "lease:$leaseId") ?: return null
        return deserialize(raw)
    }

    /**
     * Effective state evaluated lazily against the monotonic clock and the
     * CURRENT owner generation (deadline pass / generation mismatch → EXPIRED).
     */
    fun effectiveState(leaseId: String, currentGeneration: Long): LeaseState {
        val record = get(leaseId) ?: return LeaseState.RELEASED
        return effectiveStateOf(record, currentGeneration)
    }

    private fun effectiveStateOf(record: LeaseRecord, currentGeneration: Long): LeaseState {
        val stored = record.state
        // Only ACQUIRING/ACTIVE have lazy expiration
        if (stored != LeaseState.ACQUIRING && stored != LeaseState.ACTIVE) return stored
        // Generation mismatch → EXPIRED (M-LS-12..14)
        if (record.applyOwnerGeneration != currentGeneration) return LeaseState.EXPIRED
        // Deadline passed or exactly reached → EXPIRED (M-LS-11: max(0, …) produces
        // a deadline equal to nowElapsed for past deadlines → immediately expired)
        if (clock.elapsedRealtimeMs() >= record.deadlineElapsedRealtimeMs) return LeaseState.EXPIRED
        return stored
    }

    /** Persist a new or transitioned record (serialized read-modify-write). */
    fun put(record: LeaseRecord) {
        storage.write(LEASE_NS, "lease:${record.leaseId}", serialize(record))
        storage.write(LEASE_NS, CURRENT_KEY, record.leaseId)
    }

    /** Mark the caller's lease REVOKED when qwy revokes the caller (M-LS-04/M-PA-09). */
    fun markRevoked(callerApplicationId: String, source: RevokeSource) {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return
        val record = get(leaseId) ?: return
        if (record.callerApplicationId != callerApplicationId) return
        if (record.state == LeaseState.RELEASED) return
        put(record.copy(state = LeaseState.REVOKED, revokeSource = source))
    }

    /**
     * Provider-driven internal cleanup for REVOKED leases — the former caller
     * cannot call anymore, so qwy converges its own environment (M-LS-04).
     */
    fun runProviderCleanupForRevoked(environment: QwyEnvironment) {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return
        val record = get(leaseId) ?: return
        if (record.state != LeaseState.REVOKED) return
        // Transition REVOKED → RELEASING → attempt cleanup → RELEASED or RELEASE_INCOMPLETE
        put(record.copy(state = LeaseState.RELEASING))
        val outcome = environment.cleanup(leaseId)
        when (outcome) {
            is CleanupOutcome.Complete ->
                put(record.copy(state = LeaseState.RELEASED))
            is CleanupOutcome.Incomplete ->
                put(record.copy(
                    state = LeaseState.RELEASE_INCOMPLETE,
                    residualReasonWires = outcome.residualReasonWires,
                ))
        }
    }

    /**
     * State-aware restart reconciliation (§8.4 recovery table). Called once when
     * the owner process comes up with a fresh generation.
     */
    fun recoverAfterRestart(
        currentGeneration: Long,
        cleanlinessProvable: Boolean,
        environment: QwyEnvironment,
    ) {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return
        val record = get(leaseId) ?: return

        when (record.state) {
            // RELEASED/EXPIRED: untouched
            LeaseState.RELEASED, LeaseState.EXPIRED -> { /* no-op */ }

            // REVOKED/RELEASE_INCOMPLETE: preserved verbatim (M-LS-15/16)
            LeaseState.REVOKED, LeaseState.RELEASE_INCOMPLETE -> { /* no-op */ }

            // RELEASING: replay release (M-LS-17)
            LeaseState.RELEASING -> {
                val outcome = environment.cleanup(record.leaseId)
                when (outcome) {
                    is CleanupOutcome.Complete ->
                        put(record.copy(state = LeaseState.RELEASED))
                    is CleanupOutcome.Incomplete ->
                        put(record.copy(
                            state = LeaseState.RELEASE_INCOMPLETE,
                            residualReasonWires = outcome.residualReasonWires,
                        ))
                }
            }

            // ACQUIRING/ACTIVE: generation/cleanliness rules (M-LS-07/12)
            LeaseState.ACQUIRING, LeaseState.ACTIVE -> {
                if (!cleanlinessProvable) {
                    // Unclean shutdown → RELEASE_INCOMPLETE (M-LS-07): environment
                    // state unknown, convergence required before the device can
                    // accept new applies. NOT EXPIRED — EXPIRED + clean means
                    // "we know the env is fine, just the clock is stale"; unclean
                    // means "env might be dirty, caller must release to converge."
                    put(record.copy(state = LeaseState.RELEASE_INCOMPLETE))
                } else {
                    // Clean shutdown + generation mismatch → EXPIRED (M-LS-12)
                    put(record.copy(state = LeaseState.EXPIRED))
                }
            }
        }
    }

    // Shared total codec (Terra round-4/5): FREE strings frame without a
    // separator, and nullable fields (releaseIdempotencyKey, revokeSource,
    // recoveryEvidenceRef) encode null NATIVELY — no `?: ""` / `.ifEmpty{null}`
    // sentinel, so an empty idempotency key survives as "" not null.
    private fun serialize(r: LeaseRecord): String =
        DurableFieldCodec.encode(
            listOf(
                r.leaseId,
                r.callerApplicationId,
                r.callerSignerDigest,
                r.acceptedIntentHash,
                r.state.name,
                r.applyIdempotencyKey,
                r.startingEnvironmentRevision.toString(),
                r.deadlineElapsedRealtimeMs.toString(),
                r.applyOwnerGeneration.toString(),
                r.releaseIdempotencyKey,
                // residualReasonWires is a non-null Int list; "" ⟺ empty is
                // injective (an Int list can encode to nothing else).
                r.residualReasonWires.joinToString(","),
                r.revokeSource?.name,
                r.recoveryEvidenceRef,
                // v1.75 step-3b attribution basis. New rows are non-null by
                // construction; null preserves a pre-attribution row as an
                // explicit "unproven" fact instead of making upgrade decode
                // crash or inventing an item binding.
                r.earnedScheduleRef,
            ),
        )

    private fun deserialize(s: String): LeaseRecord {
        val parts = DurableFieldCodec.decode(s)
        return LeaseRecord(
            leaseId = parts[0]!!,
            callerApplicationId = parts[1]!!,
            callerSignerDigest = parts[2]!!,
            acceptedIntentHash = parts[3]!!,
            state = LeaseState.valueOf(parts[4]!!),
            applyIdempotencyKey = parts[5]!!,
            startingEnvironmentRevision = parts[6]!!.toLong(),
            deadlineElapsedRealtimeMs = parts[7]!!.toLong(),
            applyOwnerGeneration = parts[8]!!.toLong(),
            releaseIdempotencyKey = parts[9],
            residualReasonWires = parts[10]!!.takeIf { it.isNotEmpty() }
                ?.split(",")?.map { it.toInt() } ?: emptyList(),
            revokeSource = parts[11]?.let { RevokeSource.valueOf(it) },
            recoveryEvidenceRef = parts[12],
            // Backward-compatible append-only decode: rows written by the
            // immediately preceding provider schema have exactly 13 fields.
            // Missing attribution cannot be reconstructed from a digest, so
            // retain null and let step 3b fail closed with STALE_LEASE(8).
            earnedScheduleRef = parts.getOrNull(13),
        )
    }
}
