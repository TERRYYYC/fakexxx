package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import java.security.MessageDigest

/**
 * Durable, single-use ownership of one future revision. Merely creating this
 * value does not change [RevisionSnapshot.revision]; the future revision is
 * committed only together with the matching authoritative oracle ACK.
 */
data class AuthoritativeRevisionReservation(
    val mutationId: String,
    val baseRevision: Long,
    val reservedRevision: Long,
    val startingBootId: String,
    val startingOracleInstanceId: String,
    val startingSequence: Long,
    val startingSemanticDigest: String,
    /** Exact generation whose semantic digest was frozen at reservation time. */
    val ownerGenerationAtReservation: Long,
)

/**
 * Single-writer revision owner (§6.6 L1–L6, INV-25).
 *
 * environmentRevision and continuityCoverage are shared mutable state across the
 * main process, :hook_verify and hooked processes. This class is the ONE owner:
 *  - L1 sole owner of the underlying storage namespace [REVISION_NAMESPACE]
 *  - L2 all bumps/observes arrive via synchronous IPC to the owner
 *  - L3 serialized durable read-modify-write; monotonicity survives restart
 *  - L4 a bump ACK happens only AFTER the durable commit
 *  - L5 every observe reflects all previously ACKed bumps
 *  - L6 each owner start allocates+persists a NEW generation; when continuity
 *    with the previous generation's observation window cannot be proven, bump
 *    revision and degrade coverage to PARTIAL/NONE
 *
 * A lost or late bump looks exactly like "coverage FULL and revision unchanged"
 * — the false-trust INV-08/09 exists to prevent. "Probably won't drop" is not
 * accepted; concurrency and crash-injection tests are (M-MP-01..03, M-CR-09).
 *
 * Lossy event sources (FileObserver-class) must self-report: a resubscribe or
 * any unprovable gap is an OBSERVER_GAP bump + degrade — "no events received"
 * is never "nothing changed" (M-MP-03).
 */
class ContinuityTracker(
    private val storage: DurableKv,
    private val clock: MonotonicClock,
) {
    companion object {
        /** The only namespace this owner writes; static guard M-BP-08 scans for foreign writers. */
        const val REVISION_NAMESPACE: String = "integration.v1.revision"
        private const val KEY_GENERATION = "generation"
        private const val KEY_REVISION = "revision"
        private const val KEY_COVERAGE = "coverage"
        private const val KEY_CONTINUITY_SINCE = "continuity_since"
        private const val KEY_ORACLE_ACK_BOOT_ID = "oracle_ack_boot_id"
        private const val KEY_ORACLE_ACK_INSTANCE_ID = "oracle_ack_instance_id"
        const val ORACLE_ACK_SEQUENCE_KEY = "oracle_ack_sequence"
        private const val KEY_ORACLE_ACK_EVIDENCE = "oracle_ack_evidence"
        private const val KEY_ORACLE_POISON = "oracle_poison"
        private const val KEY_ORACLE_LAST_INVALID = "oracle_last_invalid"
        private const val KEY_ORACLE_RESERVATION = "oracle_reservation"
        private const val KEY_ORACLE_RECOVERY_FENCE = "oracle_recovery_fence"
        private const val KEY_ORACLE_QUARANTINED_MUTATIONS = "oracle_quarantined_mutations"
    }

    private var _generation: Long

    init {
        // §6.6 L6: each owner start allocates+persists a NEW generation.
        // When generation > 1, continuity with the previous generation's
        // observation window cannot be proven → bump revision + degrade
        // coverage to NONE (M-MP-02).
        _generation = storage.transaction {
            val prev = storage.read(REVISION_NAMESPACE, KEY_GENERATION)?.toLong() ?: 0L
            val next = prev + 1L
            storage.write(REVISION_NAMESPACE, KEY_GENERATION, next.toString())
            // First generation starts with revision 1; subsequent ones inherit the
            // persisted revision and keep counting monotonically.
            val currentRevision = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong()
            if (currentRevision == null) {
                storage.write(REVISION_NAMESPACE, KEY_REVISION, "1")
            } else if (prev > 0L &&
                storage.read(REVISION_NAMESPACE, KEY_ORACLE_RESERVATION).isNullOrEmpty()
            ) {
                // Not the first generation → unprovable continuity → bump revision
                storage.write(REVISION_NAMESPACE, KEY_REVISION, (currentRevision + 1L).toString())
            }
            // New generation starts with NONE coverage (unproven continuity)
            storage.write(REVISION_NAMESPACE, KEY_COVERAGE,
                ContinuityCoverageV1.NONE.wire.toString())
            storage.write(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE, "")
            next
        }
    }

    /** Generation persisted for THIS owner instantiation (§6.6 L6). */
    val generation: Long
        get() = _generation

    /** Serialized durable bump; returns the post-commit revision (ACK after commit, L3/L4). */
    fun bump(reason: RevisionBumpReason): Long = storage.transaction {
        val current = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong() ?: 0L
        val next = current + 1L
        storage.write(REVISION_NAMESPACE, KEY_REVISION, next.toString())
        // Every revision reason names a continuity-relevant transition. The
        // new state needs a fresh authoritative observation window; retaining
        // an earlier FULL/since claim across the bump would publish stale
        // continuity through discover/preflight before that window exists.
        storage.write(
            REVISION_NAMESPACE,
            KEY_COVERAGE,
            ContinuityCoverageV1.NONE.wire.toString(),
        )
        storage.write(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE, "")
        next
    }

    /** Reflects every ACKed bump (L5); coverage honest per §6.4 rules. */
    fun snapshot(): RevisionSnapshot {
        val revision = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong() ?: 1L
        val coverageWire = storage.read(REVISION_NAMESPACE, KEY_COVERAGE)?.toInt()
            ?: ContinuityCoverageV1.NONE.wire
        val continuitySince = storage.read(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE)
            ?.takeIf { it.isNotEmpty() }?.toLong()
        return RevisionSnapshot(
            revision = revision,
            coverageWire = coverageWire,
            generation = _generation,
            continuitySinceElapsedRealtimeMs = continuitySince,
        )
    }

    /** Lossy observer self-report (§6.6): bump + degrade, never silent. */
    fun reportObserverGap(
        coverage: ContinuityCoverageV1 = ContinuityCoverageV1.PARTIAL,
    ) {
        require(coverage != ContinuityCoverageV1.FULL) {
            "an observer gap cannot establish FULL continuity"
        }
        storage.transaction {
            val current = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong() ?: 0L
            storage.write(REVISION_NAMESPACE, KEY_REVISION, (current + 1L).toString())
            storage.write(REVISION_NAMESPACE, KEY_COVERAGE,
                coverage.wire.toString())
            // A degraded observer cannot retain the previous FULL window's
            // start: that would make a null proof look continuous in audits.
            storage.write(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE, "")
        }
    }

    /**
     * A callback delivered while an authoritative reservation owns the next
     * revision cannot consume that reservation's base CAS. It must still revoke
     * any previously published FULL claim immediately.
     */
    fun invalidateCoverageForPendingReservation() {
        storage.transaction {
            check(activeAuthoritativeReservationLocked() != null) {
                "coverage-only invalidation requires an active reservation"
            }
            invalidateCoverageLocked()
        }
    }

    /** Revokes a stale proof without manufacturing a second revision event. */
    fun invalidateCoverage() = storage.transaction {
        invalidateCoverageLocked()
    }

    /** Mark that full-coverage continuity is established from now (window start). */
    fun markContinuityEstablished() {
        storage.transaction {
            storage.write(REVISION_NAMESPACE, KEY_COVERAGE,
                ContinuityCoverageV1.FULL.wire.toString())
            storage.write(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE,
                clock.elapsedRealtimeMs().toString())
        }
    }

    /**
     * Reserves exactly `R + 1` for one QWY semantic mutation without exposing
     * that revision. The starting oracle cursor must already be the durable ACK
     * cursor; otherwise an unseen earlier mutation could be incorrectly folded
     * into this receipt's revision.
     */
    fun reserveAuthoritativeMutation(
        mutationId: String,
        startingSnapshot: AuthoritativeContinuitySnapshot,
    ): AuthoritativeRevisionReservation = storage.transaction {
        require(mutationId.isNotBlank()) { "authoritative mutation id is required" }
        require(startingSnapshot.sequence >= 0L && startingSnapshot.sequence % 2L == 0L) {
            "authoritative reservation requires a stable starting sequence"
        }

        val currentRevision =
            storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLongOrNull() ?: 1L
        check(currentRevision < Long.MAX_VALUE) { "environment revision overflow" }
        val candidate = AuthoritativeRevisionReservation(
            mutationId = mutationId,
            baseRevision = currentRevision,
            reservedRevision = currentRevision + 1L,
            startingBootId = startingSnapshot.bootId,
            startingOracleInstanceId = startingSnapshot.oracleInstanceId,
            startingSequence = startingSnapshot.sequence,
            startingSemanticDigest = checkNotNull(startingSnapshot.qwySemanticDigest),
            ownerGenerationAtReservation = _generation,
        )
        val existing = activeAuthoritativeReservationLocked()
        if (existing != null) {
            check(existing == candidate) {
                "another authoritative revision reservation is already active"
            }
            return@transaction existing
        }

        check(storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID) ==
            startingSnapshot.bootId &&
            storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID) ==
            startingSnapshot.oracleInstanceId &&
            storage.read(REVISION_NAMESPACE, ORACLE_ACK_SEQUENCE_KEY)?.toLongOrNull() ==
            startingSnapshot.sequence &&
            storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE) ==
            authoritativeEvidenceDigest(startingSnapshot)
        ) {
            "authoritative reservation start is not the durable ACK evidence"
        }
        storage.write(
            REVISION_NAMESPACE,
            KEY_ORACLE_RESERVATION,
            encodeReservation(candidate),
        )
        candidate
    }

    fun activeAuthoritativeReservation(): AuthoritativeRevisionReservation? =
        storage.transaction { activeAuthoritativeReservationLocked() }

    fun isAuthoritativeCursorAcknowledged(
        snapshot: AuthoritativeContinuitySnapshot,
    ): Boolean = storage.transaction {
        storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID) == snapshot.bootId &&
            storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID) ==
            snapshot.oracleInstanceId &&
            storage.read(REVISION_NAMESPACE, ORACLE_ACK_SEQUENCE_KEY)?.toLongOrNull() ==
            snapshot.sequence &&
            storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE) ==
            authoritativeEvidenceDigest(snapshot)
    }

    /**
     * Binds an already-accounted handler revision bump to one exact QWY
     * mutation. This method never bumps: callers invoke it in the same outer
     * transaction immediately after their own `tracker.bump(...)`.
     */
    fun acknowledgeAccountedAuthoritativeMutation(
        completedSnapshot: AuthoritativeContinuitySnapshot,
        expectedMutationId: String,
        expectedAfterSemanticDigest: String,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
    ): RevisionSnapshot = storage.transaction {
        check(completedSnapshot.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)) {
            "accounted authoritative mutation is not healthy and complete"
        }
        check(completedSnapshot.lastCompletedQwyMutationId == expectedMutationId) {
            "accounted authoritative mutation correlation mismatch"
        }
        check(completedSnapshot.qwySemanticDigest == expectedAfterSemanticDigest) {
            "accounted authoritative mutation semantic digest mismatch"
        }
        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
        val ackSequence = storage.read(
            REVISION_NAMESPACE,
            ORACLE_ACK_SEQUENCE_KEY,
        )?.toLongOrNull()
        check(ackBoot == completedSnapshot.bootId &&
            ackInstance == completedSnapshot.oracleInstanceId &&
            ackSequence != null && completedSnapshot.sequence == ackSequence + 2L
        ) {
            "accounted authoritative mutation is not the next durable oracle sequence"
        }
        writeOracleAck(
            completedSnapshot,
            authoritativeEvidenceDigest(completedSnapshot),
        )
        clearOracleFailureMarkers()
        writeCoverage(ContinuityCoverageV1.NONE, null)
        snapshotLocked()
    }

    /**
     * Folds QWY session registration into the owner-generation publication.
     * The generation allocation already accounts for this discontinuity, so
     * this writes only the new ACK cursor and keeps coverage NONE.
     */
    fun acknowledgeAuthoritativeOwnerGenerationBaseline(
        snapshot: AuthoritativeContinuitySnapshot,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
    ): RevisionSnapshot = storage.transaction {
        check(snapshot.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)) {
            "owner-generation oracle baseline is not healthy and complete"
        }
        val evidence = authoritativeEvidenceDigest(snapshot)
        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
        val ackSequence = storage.read(
            REVISION_NAMESPACE,
            ORACLE_ACK_SEQUENCE_KEY,
        )?.toLongOrNull()
        val ackEvidence = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE)
        if (ackBoot == snapshot.bootId && ackInstance == snapshot.oracleInstanceId &&
            ackSequence != null && snapshot.sequence <= ackSequence
        ) {
            when {
                snapshot.sequence < ackSequence -> poisonOnce(
                    "owner-baseline-regression:${snapshot.bootId}:" +
                        "${snapshot.oracleInstanceId}:$ackSequence>${snapshot.sequence}",
                )

                ackEvidence != evidence -> poisonOnce(
                    "owner-baseline-unsequenced:${snapshot.bootId}:" +
                        "${snapshot.oracleInstanceId}:${snapshot.sequence}:$evidence",
                )

                else -> writeCoverage(ContinuityCoverageV1.NONE, null)
            }
            return@transaction snapshotLocked()
        }
        writeOracleAck(snapshot, evidence)
        clearOracleFailureMarkers()
        writeCoverage(ContinuityCoverageV1.NONE, null)
        snapshotLocked()
    }

    /**
     * Accounts a live-process semantic-session registration before the global
     * writer lane becomes visible. The registration itself is one exact +2
     * boundary; any earlier unseen session-loss cursor is coalesced into this
     * one conservative discontinuity, and FULL stays revoked until a later
     * stable raw-read window.
     */
    fun acknowledgeAuthoritativeWriterRegistration(
        before: AuthoritativeContinuitySnapshot,
        after: AuthoritativeContinuitySnapshot,
        expectedSemanticDigest: String,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
    ): RevisionSnapshot = storage.transaction {
        check(activeAuthoritativeReservationLocked() == null) {
            "writer registration cannot account across an active reservation"
        }
        check(before.isSemanticRegistrationBaselineFor(
            expectedOwnerPackage,
            expectedOwnerUid,
            expectedSemanticDigest,
        )) { "writer registration did not start from a safe semantic baseline" }
        check(after.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)) {
            "writer registration did not finish healthy and complete"
        }
        check(after.bootId == before.bootId &&
            after.oracleInstanceId == before.oracleInstanceId &&
            after.sequence == before.sequence + 2L &&
            after.qwySemanticDigest == expectedSemanticDigest
        ) { "writer registration was not one exact same-oracle boundary" }

        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
        val ackSequence = storage.read(
            REVISION_NAMESPACE,
            ORACLE_ACK_SEQUENCE_KEY,
        )?.toLongOrNull()
        val sameOracle = ackBoot == before.bootId && ackInstance == before.oracleInstanceId
        check(!sameOracle || ackSequence == null || ackSequence <= before.sequence) {
            "writer registration baseline regressed behind the durable ACK"
        }
        if (sameOracle && ackSequence == before.sequence) {
            check(storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE) ==
                authoritativeEvidenceDigest(before)
            ) { "writer registration baseline did not match durable ACK evidence" }
        }

        bumpLocked()
        writeOracleAck(after, authoritativeEvidenceDigest(after))
        clearOracleFailureMarkers()
        writeCoverage(ContinuityCoverageV1.NONE, null)
        snapshotLocked()
    }

    /**
     * Accounts a completed central writer observed immediately before a
     * handler mutation. This is not a continuity observation: it advances the
     * durable revision/ACK once and retains NONE, solely so the handler can
     * open its own next exact bracket instead of falling back to a raw write.
     */
    fun acknowledgeStableAuthoritativeCursorBeforeMutation(
        snapshot: AuthoritativeContinuitySnapshot,
        expectedSemanticDigest: String,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
    ): RevisionSnapshot = storage.transaction {
        check(activeAuthoritativeReservationLocked() == null) {
            "central writer cursor cannot be accounted across an active reservation"
        }
        check(snapshot.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid) &&
            snapshot.qwySemanticDigest == expectedSemanticDigest
        ) { "central writer cursor is not healthy for the current semantic digest" }
        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
        val ackSequence = storage.read(
            REVISION_NAMESPACE,
            ORACLE_ACK_SEQUENCE_KEY,
        )?.toLongOrNull()
        check(ackBoot == snapshot.bootId &&
            ackInstance == snapshot.oracleInstanceId &&
            ackSequence != null && snapshot.sequence > ackSequence
        ) { "central writer cursor is not newer than the same-oracle durable ACK" }

        bumpLocked()
        writeOracleAck(snapshot, authoritativeEvidenceDigest(snapshot))
        clearOracleFailureMarkers()
        writeCoverage(ContinuityCoverageV1.NONE, null)
        snapshotLocked()
    }

    /** Fail-closed selection helper for the handler's legacy/authoritative fork. */
    fun tryReserveAuthoritativeMutation(
        mutationId: String,
        startingSnapshot: AuthoritativeContinuitySnapshot?,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
    ): AuthoritativeRevisionReservation? = storage.transaction {
        val snapshot = startingSnapshot
            ?.takeIf { it.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid) }
            ?: return@transaction null
        val ackMatches =
            storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID) == snapshot.bootId &&
                storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID) ==
                snapshot.oracleInstanceId &&
                storage.read(REVISION_NAMESPACE, ORACLE_ACK_SEQUENCE_KEY)?.toLongOrNull() ==
                snapshot.sequence &&
                storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE) ==
                authoritativeEvidenceDigest(snapshot)
        if (!ackMatches || activeAuthoritativeReservationLocked() != null) {
            return@transaction null
        }
        reserveAuthoritativeMutation(mutationId, snapshot)
    }

    /**
     * Commits the reserved revision, oracle ACK, coverage, and reservation
     * consumption in one durability boundary. Every mismatch throws while the
     * reservation and base revision remain intact, keeping fenced callers from
     * observing a speculative receipt revision.
     */
    fun finalizeAuthoritativeReservation(
        reservation: AuthoritativeRevisionReservation,
        completedSnapshot: AuthoritativeContinuitySnapshot,
        expectedAfterSemanticDigest: String,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
        commitSideEffects: () -> Unit = {},
    ): RevisionSnapshot = storage.transaction {
        check(activeAuthoritativeReservationLocked() == reservation) {
            "authoritative revision reservation is absent or changed"
        }
        check(storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLongOrNull() ==
            reservation.baseRevision
        ) {
            "environment revision moved while authoritative reservation was pending"
        }
        check(reservation.reservedRevision == reservation.baseRevision + 1L) {
            "authoritative reservation is not the next revision"
        }
        check(completedSnapshot.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)) {
            "completed authoritative mutation is not healthy and complete"
        }
        check(completedSnapshot.bootId == reservation.startingBootId &&
            completedSnapshot.oracleInstanceId == reservation.startingOracleInstanceId
        ) {
            "authoritative oracle identity changed while reservation was pending"
        }
        check(completedSnapshot.sequence == reservation.startingSequence + 2L) {
            "authoritative mutation sequence did not advance exactly once"
        }
        check(completedSnapshot.lastCompletedQwyMutationId == reservation.mutationId) {
            "authoritative mutation correlation does not match reservation"
        }
        check(completedSnapshot.qwySemanticDigest == expectedAfterSemanticDigest) {
            "authoritative semantic digest does not match converged environment"
        }

        storage.write(
            REVISION_NAMESPACE,
            KEY_REVISION,
            reservation.reservedRevision.toString(),
        )
        writeOracleAck(
            completedSnapshot,
            authoritativeEvidenceDigest(completedSnapshot),
        )
        clearOracleFailureMarkers()
        // Finalization proves that the correlated mutation reached one stable
        // endpoint; it is not itself PRE/raw-read/POST history. Keep this
        // publication at NONE. The immediate observation can establish FULL
        // at the same revision after its own identical PRE and POST reads.
        writeCoverage(ContinuityCoverageV1.NONE, null)
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_RESERVATION, "")
        commitSideEffects()
        snapshotLocked()
    }

    /**
     * Owner-process recovery for a receipt whose authoritative reservation was
     * already durable when that owner died. Session re-registration is itself
     * a QWY-generation discontinuity, so its stable cursor can be more than
     * exactly `start + 2`. The reserved R+1 safely coalesces every unseen
     * transition into one new epoch, but only on the same boot/oracle instance
     * and only after the committed pointer has converged to a healthy current
     * semantic digest. A one-window fence prevents the restart baseline from
     * immediately looking like inherited FULL continuity.
     */
    fun finalizeRecoveredAuthoritativeReservation(
        reservation: AuthoritativeRevisionReservation,
        recoveredSnapshot: AuthoritativeContinuitySnapshot,
        expectedCurrentSemanticDigest: String,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
        commitSideEffects: () -> Unit = {},
    ): RevisionSnapshot = storage.transaction {
        check(activeAuthoritativeReservationLocked() == reservation) {
            "recovered authoritative revision reservation is absent or changed"
        }
        check(storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLongOrNull() ==
            reservation.baseRevision
        ) { "environment revision moved during authoritative owner recovery" }
        check(reservation.reservedRevision == reservation.baseRevision + 1L) {
            "recovered authoritative reservation is not the next revision"
        }
        check(recoveredSnapshot.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)) {
            "recovered authoritative snapshot is not healthy and complete"
        }
        check(recoveredSnapshot.bootId == reservation.startingBootId &&
            recoveredSnapshot.oracleInstanceId == reservation.startingOracleInstanceId
        ) { "boot or system oracle changed during authoritative owner recovery" }
        check(recoveredSnapshot.sequence == reservation.startingSequence + 6L) {
            "owner recovery did not contain exactly death, registration, and reserved mutation"
        }
        check(recoveredSnapshot.lastCompletedQwyMutationId == reservation.mutationId) {
            "owner recovery did not preserve the exact reserved mutation correlation"
        }
        check(expectedCurrentSemanticDigest != reservation.startingSemanticDigest &&
            recoveredSnapshot.qwySemanticDigest == expectedCurrentSemanticDigest
        ) { "owner recovery semantic generation was not re-established" }

        storage.write(
            REVISION_NAMESPACE,
            KEY_REVISION,
            reservation.reservedRevision.toString(),
        )
        writeOracleAck(
            recoveredSnapshot,
            authoritativeEvidenceDigest(recoveredSnapshot),
        )
        clearOracleFailureMarkers()
        writeCoverage(ContinuityCoverageV1.NONE, null)
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_RECOVERY_FENCE, "1")
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_RESERVATION, "")
        commitSideEffects()
        snapshotLocked()
    }

    /**
     * Retires an irrecoveribly interleaved reservation without replaying its
     * stale R+1 receipt as trusted success. R+1 accounts the committed advance;
     * R+2 accounts the changed/unbounded oracle epoch. The current healthy
     * cursor is ACKed with a one-window NONE fence, while the mutation ID stays
     * durably quarantined so idempotent replay fails loud instead of returning
     * a receipt that can no longer equal the environment revision.
     */
    fun quarantineAuthoritativeReservation(
        reservation: AuthoritativeRevisionReservation,
        currentSnapshot: AuthoritativeContinuitySnapshot,
        expectedCurrentSemanticDigest: String,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
        commitSideEffects: () -> Unit = {},
    ): RevisionSnapshot = storage.transaction {
        check(activeAuthoritativeReservationLocked() == reservation) {
            "quarantined authoritative reservation is absent or changed"
        }
        check(storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLongOrNull() ==
            reservation.baseRevision
        ) { "environment revision moved before authoritative quarantine" }
        check(reservation.reservedRevision < Long.MAX_VALUE) {
            "environment revision overflow during authoritative quarantine"
        }
        check(currentSnapshot.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)) {
            "authoritative quarantine requires a healthy current cursor"
        }
        val sameStartingOracle =
            currentSnapshot.bootId == reservation.startingBootId &&
                currentSnapshot.oracleInstanceId == reservation.startingOracleInstanceId
        check(!sameStartingOracle ||
            currentSnapshot.sequence > reservation.startingSequence
        ) {
            "authoritative quarantine cannot ACK a regressed or aliased cursor"
        }
        check(currentSnapshot.qwySemanticDigest == expectedCurrentSemanticDigest) {
            "authoritative quarantine semantic digest mismatch"
        }

        storage.write(
            REVISION_NAMESPACE,
            KEY_REVISION,
            (reservation.reservedRevision + 1L).toString(),
        )
        writeOracleAck(currentSnapshot, authoritativeEvidenceDigest(currentSnapshot))
        clearOracleFailureMarkers()
        writeCoverage(ContinuityCoverageV1.NONE, null)
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_RECOVERY_FENCE, "1")
        val quarantined = quarantinedMutationIdsLocked().toMutableList()
        if (reservation.mutationId !in quarantined) quarantined += reservation.mutationId
        storage.write(
            REVISION_NAMESPACE,
            KEY_ORACLE_QUARANTINED_MUTATIONS,
            DurableFieldCodec.encode(quarantined),
        )
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_RESERVATION, "")
        commitSideEffects()
        snapshotLocked()
    }

    fun isAuthoritativeMutationQuarantined(mutationId: String): Boolean = storage.transaction {
        mutationId in quarantinedMutationIdsLocked()
    }

    /**
     * Atomically reconciles one authoritative PRE/raw-read/POST window with
     * the durable revision cursor required by issue #66.
     *
     * The ACK and every bump it accounts for are written in this ONE
     * transaction. A storage exception therefore exposes neither, and a
     * response-lost retry sees both. Invalid windows are deduplicated by an
     * evidence digest so a stuck Binder/odd sequence cannot create an
     * unbounded revision loop merely because Auto retries observe().
     */
    fun reconcileAuthoritativeWindow(
        window: AuthoritativeObservationWindow,
        expectedOwnerPackage: String,
        expectedOwnerUid: Int,
    ): RevisionSnapshot = storage.transaction {
        val verdict = classifyAuthoritativeWindow(
            pre = window.pre,
            post = window.post,
            expectedPackage = expectedOwnerPackage,
            expectedUid = expectedOwnerUid,
        )
        val post = window.post
        when (verdict) {
            AuthoritativeWindowVerdict.VALID -> reconcileStableSnapshot(
                snapshot = checkNotNull(post),
                windowStartElapsedRealtimeMs = window.windowStartElapsedRealtimeMs,
            )

            AuthoritativeWindowVerdict.BOOT_OR_INSTANCE_CHANGED,
            AuthoritativeWindowVerdict.MUTATING_OR_CHANGED -> {
                if (post != null && post.sequence >= 0L && post.sequence % 2L == 0L &&
                    post.isStableCompleteFor(expectedOwnerPackage, expectedOwnerUid)
                ) {
                    acknowledgeRejectedStablePost(post, verdict.name)
                } else {
                    degradeOnce(invalidWindowDigest(verdict, window))
                }
            }

            AuthoritativeWindowVerdict.SEQUENCE_REGRESSION ->
                poisonOnce(invalidWindowDigest(verdict, window))

            AuthoritativeWindowVerdict.UNHEALTHY -> {
                val invalid = invalidWindowDigest(verdict, window)
                if (hasUnsequencedAcknowledgedEvidence(window)) {
                    poisonOnce("unsequenced-unhealthy:$invalid")
                } else {
                    degradeOnce(invalid)
                }
            }
        }
        snapshotLocked()
    }

    /** Stable identical PRE/POST; compare it to the durable ACK cursor. */
    private fun reconcileStableSnapshot(
        snapshot: AuthoritativeContinuitySnapshot,
        windowStartElapsedRealtimeMs: Long,
    ) {
        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
        val ackSequence = storage.read(REVISION_NAMESPACE, ORACLE_ACK_SEQUENCE_KEY)?.toLongOrNull()
        val ackEvidence = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE)
        val evidence = authoritativeEvidenceDigest(snapshot)

        if (ackBoot == null || ackInstance == null || ackSequence == null) {
            // The initial owner-generation allocation already accounts for a
            // cold start. Establish a durable cursor without inventing another
            // bump. Existing FULL (from an authoritative apply fake) may stay;
            // otherwise this first baseline remains NONE until a second stable
            // window proves continuity from a known boundary.
            writeOracleAck(snapshot, evidence)
            clearOracleFailureMarkers()
            val recoveryFence = consumeOracleRecoveryFence()
            if (recoveryFence || currentCoverage() != ContinuityCoverageV1.FULL) {
                writeCoverage(ContinuityCoverageV1.NONE, null)
            }
            return
        }

        if (ackBoot != snapshot.bootId || ackInstance != snapshot.oracleInstanceId) {
            bumpLocked()
            writeOracleAck(snapshot, evidence)
            clearOracleFailureMarkers()
            consumeOracleRecoveryFence()
            writeCoverage(ContinuityCoverageV1.NONE, null)
            return
        }

        when {
            snapshot.sequence < ackSequence -> {
                poisonOnce("regression:${snapshot.bootId}:${snapshot.oracleInstanceId}:" +
                    "$ackSequence>${snapshot.sequence}")
            }

            snapshot.sequence == ackSequence && ackEvidence != evidence -> {
                // Endpoint/health/semantic state changed without a sequence:
                // the producer violated the oracle contract. Do not ACK the
                // lie; keep the last higher-integrity cursor.
                poisonOnce("unsequenced:${snapshot.bootId}:${snapshot.oracleInstanceId}:" +
                    "${snapshot.sequence}:$evidence")
            }

            snapshot.sequence == ackSequence &&
                storage.read(REVISION_NAMESPACE, KEY_ORACLE_POISON).orEmpty().isNotEmpty() -> {
                writeCoverage(ContinuityCoverageV1.NONE, null)
            }

            snapshot.sequence == ackSequence -> {
                storage.write(REVISION_NAMESPACE, KEY_ORACLE_LAST_INVALID, "")
                if (consumeOracleRecoveryFence()) {
                    writeCoverage(ContinuityCoverageV1.NONE, null)
                } else if (currentCoverage() != ContinuityCoverageV1.FULL) {
                    writeCoverage(
                        ContinuityCoverageV1.FULL,
                        windowStartElapsedRealtimeMs,
                    )
                }
            }

            else -> {
                // A newer stable sequence happened before PRE. This window
                // proves the new endpoint remained stable throughout the raw
                // read, so bump+ACK and establish from PRE in one commit.
                bumpLocked()
                writeOracleAck(snapshot, evidence)
                val recoveryFence = consumeOracleRecoveryFence()
                clearOracleFailureMarkers()
                if (recoveryFence) {
                    writeCoverage(ContinuityCoverageV1.NONE, null)
                } else {
                    writeCoverage(
                        ContinuityCoverageV1.FULL,
                        windowStartElapsedRealtimeMs,
                    )
                }
            }
        }
    }

    /**
     * PRE/POST did not match, but POST is itself a complete stable endpoint.
     * ACK it with NONE so the just-rejected transition cannot be replayed as
     * an unseen change; a later identical window may establish a new start.
     */
    private fun acknowledgeRejectedStablePost(
        post: AuthoritativeContinuitySnapshot,
        reason: String,
    ) {
        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
        val ackSequence = storage.read(REVISION_NAMESPACE, ORACLE_ACK_SEQUENCE_KEY)?.toLongOrNull()
        val evidence = authoritativeEvidenceDigest(post)
        val isNewCursor = ackBoot != post.bootId || ackInstance != post.oracleInstanceId ||
            ackSequence == null || post.sequence > ackSequence

        if (isNewCursor) {
            bumpLocked()
            writeOracleAck(post, evidence)
            clearOracleFailureMarkers()
        } else if (post.sequence < (ackSequence ?: Long.MIN_VALUE)) {
            poisonOnce("rejected-regression:$reason:${post.bootId}:${post.oracleInstanceId}:" +
                "${post.sequence}")
            return
        } else {
            degradeOnce("rejected:$reason:${post.bootId}:${post.oracleInstanceId}:" +
                "${post.sequence}:$evidence")
            return
        }
        writeCoverage(ContinuityCoverageV1.NONE, null)
    }

    private fun writeOracleAck(
        snapshot: AuthoritativeContinuitySnapshot,
        evidence: String,
    ) {
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID, snapshot.bootId)
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID, snapshot.oracleInstanceId)
        storage.write(REVISION_NAMESPACE, ORACLE_ACK_SEQUENCE_KEY, snapshot.sequence.toString())
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE, evidence)
    }

    private fun degradeOnce(evidence: String) {
        if (storage.read(REVISION_NAMESPACE, KEY_ORACLE_LAST_INVALID) != evidence) {
            bumpLocked()
            storage.write(REVISION_NAMESPACE, KEY_ORACLE_LAST_INVALID, evidence)
        }
        writeCoverage(ContinuityCoverageV1.NONE, null)
    }

    private fun poisonOnce(evidence: String) {
        if (storage.read(REVISION_NAMESPACE, KEY_ORACLE_POISON) != evidence) {
            bumpLocked()
            storage.write(REVISION_NAMESPACE, KEY_ORACLE_POISON, evidence)
        }
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_LAST_INVALID, evidence)
        writeCoverage(ContinuityCoverageV1.NONE, null)
    }

    private fun clearOracleFailureMarkers() {
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_POISON, "")
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_LAST_INVALID, "")
    }

    /** The recovery fence belongs only to the first valid observation window. */
    private fun consumeOracleRecoveryFence(): Boolean {
        if (storage.read(REVISION_NAMESPACE, KEY_ORACLE_RECOVERY_FENCE) != "1") {
            return false
        }
        storage.write(REVISION_NAMESPACE, KEY_ORACLE_RECOVERY_FENCE, "")
        return true
    }

    /**
     * An unhealthy endpoint at the already-ACKed cursor is direct evidence
     * that covered state changed without the mandatory sequence transition.
     * Missing Binder reads have no comparable cursor and remain recoverable;
     * concrete same-cursor evidence is sticky until a newer sequence/instance.
     */
    private fun hasUnsequencedAcknowledgedEvidence(
        window: AuthoritativeObservationWindow,
    ): Boolean {
        val ackBoot = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_BOOT_ID)
            ?: return false
        val ackInstance = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_INSTANCE_ID)
            ?: return false
        val ackSequence = storage.read(
            REVISION_NAMESPACE,
            ORACLE_ACK_SEQUENCE_KEY,
        )?.toLongOrNull() ?: return false
        val ackEvidence = storage.read(REVISION_NAMESPACE, KEY_ORACLE_ACK_EVIDENCE)
            ?: return false
        return sequenceOf(window.pre, window.post)
            .filterNotNull()
            .any { observed ->
                observed.bootId == ackBoot &&
                    observed.oracleInstanceId == ackInstance &&
                    observed.sequence == ackSequence &&
                    authoritativeEvidenceDigest(observed) != ackEvidence
            }
    }

    private fun bumpLocked(): Long {
        val current = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLongOrNull() ?: 0L
        return (current + 1L).also {
            storage.write(REVISION_NAMESPACE, KEY_REVISION, it.toString())
        }
    }

    private fun currentCoverage(): ContinuityCoverageV1 {
        val wire = storage.read(REVISION_NAMESPACE, KEY_COVERAGE)?.toIntOrNull()
            ?: ContinuityCoverageV1.NONE.wire
        return ContinuityCoverageV1.fromWire(wire) ?: ContinuityCoverageV1.NONE
    }

    private fun invalidateCoverageLocked() {
        writeCoverage(ContinuityCoverageV1.NONE, null)
    }

    private fun writeCoverage(
        coverage: ContinuityCoverageV1,
        continuitySinceElapsedRealtimeMs: Long?,
    ) {
        storage.write(REVISION_NAMESPACE, KEY_COVERAGE, coverage.wire.toString())
        storage.write(
            REVISION_NAMESPACE,
            KEY_CONTINUITY_SINCE,
            continuitySinceElapsedRealtimeMs?.toString().orEmpty(),
        )
    }

    private fun snapshotLocked(): RevisionSnapshot = RevisionSnapshot(
        revision = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLongOrNull() ?: 1L,
        coverageWire = storage.read(REVISION_NAMESPACE, KEY_COVERAGE)?.toIntOrNull()
            ?: ContinuityCoverageV1.NONE.wire,
        generation = _generation,
        continuitySinceElapsedRealtimeMs = storage.read(
            REVISION_NAMESPACE,
            KEY_CONTINUITY_SINCE,
        )?.takeIf(String::isNotEmpty)?.toLongOrNull(),
    )

    private fun activeAuthoritativeReservationLocked(): AuthoritativeRevisionReservation? {
        val encoded = storage.read(REVISION_NAMESPACE, KEY_ORACLE_RESERVATION)
            ?.takeIf(String::isNotEmpty) ?: return null
        val fields = DurableFieldCodec.decodeNonNull(encoded)
        check(fields.size == 8) { "invalid authoritative reservation field count" }
        return AuthoritativeRevisionReservation(
            mutationId = fields[0].also {
                check(it.isNotBlank()) { "invalid authoritative reservation mutation id" }
            },
            baseRevision = fields[1].toLong(),
            reservedRevision = fields[2].toLong(),
            startingBootId = fields[3].also {
                check(it.isNotBlank()) { "invalid authoritative reservation boot id" }
            },
            startingOracleInstanceId = fields[4].also {
                check(it.isNotBlank()) { "invalid authoritative reservation instance id" }
            },
            startingSequence = fields[5].toLong(),
            startingSemanticDigest = fields[6].also {
                check(it.isNotBlank()) { "invalid authoritative reservation semantic digest" }
            },
            ownerGenerationAtReservation = fields[7].toLong(),
        ).also {
            check(it.baseRevision >= 0L && it.reservedRevision == it.baseRevision + 1L) {
                "invalid authoritative reservation revision range"
            }
            check(it.startingSequence >= 0L && it.startingSequence % 2L == 0L) {
                "invalid authoritative reservation starting sequence"
            }
        }
    }

    private fun quarantinedMutationIdsLocked(): List<String> {
        val encoded = storage.read(REVISION_NAMESPACE, KEY_ORACLE_QUARANTINED_MUTATIONS)
            ?.takeIf(String::isNotEmpty) ?: return emptyList()
        return DurableFieldCodec.decodeNonNull(encoded).also { values ->
            check(values.all(String::isNotBlank)) {
                "invalid quarantined authoritative mutation id"
            }
        }
    }

    private fun encodeReservation(reservation: AuthoritativeRevisionReservation): String =
        DurableFieldCodec.encode(
            listOf(
                reservation.mutationId,
                reservation.baseRevision.toString(),
                reservation.reservedRevision.toString(),
                reservation.startingBootId,
                reservation.startingOracleInstanceId,
                reservation.startingSequence.toString(),
                reservation.startingSemanticDigest,
                reservation.ownerGenerationAtReservation.toString(),
            ),
        )

    private fun invalidWindowDigest(
        verdict: AuthoritativeWindowVerdict,
        window: AuthoritativeObservationWindow,
    ): String = digest(
        listOf(
            verdict.name,
            window.pre?.let(::authoritativeEvidenceDigest).orEmpty(),
            window.post?.let(::authoritativeEvidenceDigest).orEmpty(),
            window.pre?.sequence?.toString().orEmpty(),
            window.post?.sequence?.toString().orEmpty(),
        ),
    )

    private fun authoritativeEvidenceDigest(
        value: AuthoritativeContinuitySnapshot,
    ): String = digest(
        listOf(
            value.protocolVersion.toString(),
            value.bootId,
            value.oracleInstanceId,
            value.sequence.toString(),
            value.ownerUid?.toString().orEmpty(),
            value.ownerPackage.orEmpty(),
            value.gpsProviderEnabled.toString(),
            value.networkProviderEnabled.toString(),
            value.requiredCoverageMask.toString(),
            value.installedCoverageMask.toString(),
            value.health.name,
            value.qwySemanticDigest.orEmpty(),
            value.lastCompletedQwyMutationId.orEmpty(),
        ),
    )

    private fun digest(values: List<String>): String {
        val canonical = values.joinToString(separator = "") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
