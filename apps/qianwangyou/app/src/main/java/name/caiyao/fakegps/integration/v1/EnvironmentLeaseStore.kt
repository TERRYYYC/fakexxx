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
 *    cleanup but cannot become RELEASED until the caller-visible receipt is
 *    finalized (M-LS-17), only ACQUIRING/ACTIVE are subject to the
 *    cleanliness/generation rules (M-LS-07/12), EXPIRED/RELEASED untouched.
 *  - REVOKED is unreachable to its (former) caller; provider-driven internal
 *    cleanup pushes REVOKED → RELEASING → RELEASED (M-LS-04).
 */
class EnvironmentLeaseStore(
    private val storage: DurableKv,
    private val clock: MonotonicClock,
) {
    data class ProviderCleanupAttempt(
        val releasingLease: LeaseRecord,
        val outcome: CleanupOutcome,
    )

    companion object {
        private const val LEASE_NS = "integration.v1.leases"
        private const val CURRENT_KEY = "__current_lease_id__"
        private const val PROVIDER_REVOKED_CLEANUP_EVIDENCE =
            "provider:revoked-cleanup:v1"
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

    /** Mark this exact caller principal's lease REVOKED (M-LS-04/M-PA-09). */
    fun markRevoked(
        callerApplicationId: String,
        callerSignerDigest: String,
        source: RevokeSource,
    ) {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return
        val record = get(leaseId) ?: return
        if (
            record.callerApplicationId != callerApplicationId ||
            record.callerSignerDigest != callerSignerDigest
        ) return
        if (record.state == LeaseState.RELEASED) return
        put(record.copy(state = LeaseState.REVOKED, revokeSource = source))
    }

    /**
     * Provider-driven internal cleanup for REVOKED leases — the former caller
     * cannot call anymore, so qwy converges its own environment (M-LS-04).
     */
    fun runProviderCleanupForRevoked(
        environment: QwyEnvironment,
    ): ProviderCleanupAttempt? {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return null
        val record = get(leaseId) ?: return null
        val retryingProviderCleanup = record.state == LeaseState.RELEASE_INCOMPLETE &&
            isProviderRevokedCleanup(record)
        val resumingProviderCleanup = record.state == LeaseState.RELEASING &&
            isProviderRevokedCleanup(record)
        if (
            record.state != LeaseState.REVOKED &&
            !retryingProviderCleanup &&
            !resumingProviderCleanup
        ) return null
        // Phase 1: the provider owns a durable RELEASING row before cleanup.
        val releasing = if (resumingProviderCleanup) {
            record
        } else {
            record.copy(
                state = LeaseState.RELEASING,
                // A revoked principal can never replay a caller release. Clear
                // any interrupted caller key and persist provider ownership.
                releaseIdempotencyKey = null,
                recoveryEvidenceRef = PROVIDER_REVOKED_CLEANUP_EVIDENCE,
                residualReasonWires = emptyList(),
            ).also(::put)
        }
        // Phase 2: the external boundary is deliberately outside the handler's
        // transaction. A process death leaves RELEASING for restart/retry.
        val outcome = environment.cleanup(leaseId)
        return ProviderCleanupAttempt(releasing, outcome)
    }

    /**
     * Phase 3 mutation only. The handler calls this inside the same durable
     * transaction as revision and audit publication.
     */
    fun finalizeProviderCleanup(attempt: ProviderCleanupAttempt): LeaseRecord {
        val durable = checkNotNull(get(attempt.releasingLease.leaseId)) {
            "provider cleanup owner ${attempt.releasingLease.leaseId} disappeared"
        }
        check(durable.state == LeaseState.RELEASING && isProviderRevokedCleanup(durable)) {
            "provider cleanup owner changed before finalize: $durable"
        }
        val terminal = when (val outcome = attempt.outcome) {
            is CleanupOutcome.Complete -> durable.copy(
                state = LeaseState.RELEASED,
                residualReasonWires = emptyList(),
            )
            is CleanupOutcome.Incomplete -> durable.copy(
                state = LeaseState.RELEASE_INCOMPLETE,
                residualReasonWires = outcome.residualReasonWires,
            )
        }
        put(terminal)
        return terminal
    }

    /**
     * State-aware restart reconciliation (§8.4 recovery table). Called once when
     * the owner process comes up with a fresh generation.
     */
    fun recoverAfterRestart(
        currentGeneration: Long,
        cleanlinessProvable: Boolean,
        environment: QwyEnvironment,
    ): ProviderCleanupAttempt? {
        val leaseId = storage.read(LEASE_NS, CURRENT_KEY) ?: return null
        val record = get(leaseId) ?: return null
        var providerCleanupAttempt: ProviderCleanupAttempt? = null

        when (record.state) {
            // RELEASED/EXPIRED: untouched
            LeaseState.RELEASED, LeaseState.EXPIRED -> { /* no-op */ }

            // REVOKED/RELEASE_INCOMPLETE: preserved verbatim (M-LS-15/16)
            LeaseState.REVOKED, LeaseState.RELEASE_INCOMPLETE -> { /* no-op */ }

            // RELEASING: replay cleanup (M-LS-17). Provider-driven revoked
            // cleanup has no authorized caller and therefore MUST finish to
            // RELEASED itself. Caller-driven cleanup retains its non-null
            // release key and must stay RELEASING until that caller can commit
            // RELEASED + receipt together.
            LeaseState.RELEASING -> {
                val outcome = environment.cleanup(record.leaseId)
                if (isProviderRevokedCleanup(record)) {
                    // Handler owns the phase-3 transaction so terminal state,
                    // revision, and audit are published or rolled back together.
                    providerCleanupAttempt = ProviderCleanupAttempt(record, outcome)
                } else {
                    when (outcome) {
                        is CleanupOutcome.Complete -> {
                            // Caller-driven: await exact public receipt replay.
                        }
                        is CleanupOutcome.Incomplete ->
                            put(record.copy(
                                state = LeaseState.RELEASE_INCOMPLETE,
                                residualReasonWires = outcome.residualReasonWires,
                            ))
                    }
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
        return providerCleanupAttempt
    }

    /**
     * Explicit marker for new rows plus a narrow legacy decoder for rows that
     * predate it. Public release always carries a non-null idempotency key;
     * provider cleanup is rooted in a QWY_REVOKED_CALLER lease and carries none.
     */
    private fun isProviderRevokedCleanup(record: LeaseRecord): Boolean =
        record.releaseIdempotencyKey == null &&
            (
                record.recoveryEvidenceRef == PROVIDER_REVOKED_CLEANUP_EVIDENCE ||
                    record.revokeSource == RevokeSource.QWY_REVOKED_CALLER
            )

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
                // v1.75 step-3b attribution basis. Provider truth at apply
                // time (scheduleSnapshot()?.currentItemId — F12): a NEW row is
                // null when no schedule item is active at apply, and a legacy
                // pre-#18 row is null because the field did not exist. Either
                // way null is an explicit "unproven" fact at step 3b instead
                // of making upgrade decode crash or inventing an item binding.
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
