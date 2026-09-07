package com.example.cellrebelauto.recovery

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.cellrebelauto.cutover.CutoverAccessGate

/**
 * R43 (Sol GREEN-review P1-1): Room-backed durable operation receipts + checkpoints — the
 * production binding of [DurableRecoveryLog] (§7.1 OperationReceipt / RecoveryCheckpoint owner).
 *
 * The apply receipt carries the provider LEASE atomically (ApplyReceiptV1.leaseId — Sol
 * GREEN-review P1-5), so the crash window between receipt durability and the attempt-owner
 * `markAplusLease` recovers the lease from the receipt replay.
 *
 * # Room 持久 receipt/checkpoint store：apply receipt 原子携带 provider lease
 */
@Entity(tableName = "operation_receipts")
data class OperationReceiptRow(
    @PrimaryKey val idempotencyKey: String,
    val requestDigest: String,
    val resultOutcome: String,
    val createdAt: Long,
    /** The provider lease issued by the applied operation (ApplyReceiptV1.leaseId); nullable for legacy rows. */
    val leaseId: String? = null,
    // ---- R43 (Sol GREEN-review-2 F3): the VERBATIM ApplyReceiptV1 proof fields (§7.1 OperationReceipt) ----
    /** ApplyReceiptV1.operationId — the provider-side operation identity. */
    val operationId: String? = null,
    /** ApplyReceiptV1.acceptedIntentHash — the INV-23 attribution proof. */
    val acceptedIntentHash: String? = null,
    /** ApplyReceiptV1.appliedAtEpochMs. */
    val appliedAtEpochMs: Long? = null,
    /** ApplyReceiptV1.environmentRevision. */
    val environmentRevision: Long? = null,
    /** ApplyReceiptV1.verificationLevelWire. */
    val verificationLevelWire: Int? = null
)

@Entity(tableName = "recovery_checkpoints")
data class RecoveryCheckpointRow(
    @PrimaryKey val attemptId: Long,
    val lastDurableStage: String,
    val receiptKey: String?,
    val recordedAt: Long
)

@Entity(tableName = "release_receipts")
data class ReleaseReceiptRow(
    @PrimaryKey val idempotencyKey: String,
    val leaseId: String,
    val releaseDigest: String,
    val resultOutcome: String,
    val createdAt: Long
)

/**
 * #85: the complete, exact CompleteAndAdvance request accepted for an attempt. It is written
 * before the Binder call and never updated: recovery reconstructs the request from this row, not
 * from a new clock or from mutable projections. The release tuple proves the request was admitted
 * only after the matching lease release was durable.
 */
@Entity(
    tableName = "advance_replay_carriers",
    indices = [Index(value = ["idempotencyKey"], unique = true)]
)
data class AdvanceReplayCarrierRow(
    @PrimaryKey val attemptId: Long,
    val releaseIdempotencyKey: String,
    val releaseLeaseId: String,
    val releaseDigest: String,
    val leaseId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val expectedScheduleId: String,
    val expectedScheduleVersion: Long,
    val expectedCurrentItemId: String,
    val proofScheduleItemId: String,
    val proofTrustedSuccessCount: Int,
    val proofQuotaRequired: Int,
    val proofLedgerRef: String,
    val proofVerifiedAtElapsedRealtimeMs: Long,
    val callerProtocolVersion: Int,
    val createdAt: Long
)

/** Provider receipt for an exact stored advance request. Insert-only and verified on every read. */
@Entity(tableName = "advance_receipts")
data class AdvanceReceiptRow(
    @PrimaryKey val attemptId: Long,
    val idempotencyKey: String,
    val requestDigest: String,
    val outcomeWire: Int,
    val advancedFromItemId: String,
    val advancedToItemId: String?,
    val scheduleVersionAfter: Long,
    val effectiveIntentHash: String,
    val effectiveEnvironmentRevision: Long,
    val receiptDigest: String,
    val recordedAt: Long
)

@Dao
interface OperationReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: OperationReceiptRow)

    @Query("SELECT * FROM operation_receipts WHERE idempotencyKey = :key LIMIT 1")
    suspend fun byKey(key: String): OperationReceiptRow?
}

@Dao
interface RecoveryCheckpointRoomDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: RecoveryCheckpointRow)

    @Query("SELECT * FROM recovery_checkpoints WHERE attemptId = :attemptId LIMIT 1")
    suspend fun byAttempt(attemptId: Long): RecoveryCheckpointRow?
}

@Dao
interface ReleaseReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: ReleaseReceiptRow)

    @Query("SELECT * FROM release_receipts WHERE idempotencyKey = :key LIMIT 1")
    suspend fun byKey(key: String): ReleaseReceiptRow?

    @Query("SELECT * FROM release_receipts WHERE leaseId = :leaseId LIMIT 1")
    suspend fun byLease(leaseId: String): ReleaseReceiptRow?

    @Query("SELECT COUNT(*) FROM release_receipts WHERE leaseId = :leaseId")
    suspend fun countForLease(leaseId: String): Int
}

@Dao
interface AdvanceReplayCarrierDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: AdvanceReplayCarrierRow): Long

    @Query("SELECT * FROM advance_replay_carriers WHERE attemptId = :attemptId LIMIT 1")
    suspend fun byAttempt(attemptId: Long): AdvanceReplayCarrierRow?
}

@Dao
interface AdvanceReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: AdvanceReceiptRow): Long

    @Query("SELECT * FROM advance_receipts WHERE attemptId = :attemptId LIMIT 1")
    suspend fun byAttempt(attemptId: Long): AdvanceReceiptRow?
}

/**
 * The production [DurableRecoveryLog] over Room — same idempotency contract as the in-memory fake
 * (same key+digest replays; different digest conflicts, prior preserved). The suspend DAO calls
 * are bridged with [kotlinx.coroutines.runBlocking] because the log interface is synchronous
 * (frozen seam; the callers already execute inside coroutine scopes on Dispatchers.IO-adjacent
 * contexts, and the SQLite writes are microsecond-scale).
 */
class RoomDurableRecoveryLog(
    private val receipts: OperationReceiptDao,
    private val checkpoints: RecoveryCheckpointRoomDao,
    private val releases: ReleaseReceiptDao,
    private val accessGate: CutoverAccessGate
) : DurableRecoveryLog {

    override fun receiptFor(idempotencyKey: String): RecordedReceipt? =
        gated {
            receipts.byKey(idempotencyKey)?.let {
                // R44 (Sol GREEN-review-3 F3): the readback carries the VERBATIM ApplyReceiptV1 proof
                // fields — a receipt that loses them on read is not the §7.1 OperationReceipt.
                RecordedReceipt(
                    it.idempotencyKey, it.requestDigest, it.resultOutcome, it.createdAt, it.leaseId,
                    it.operationId, it.acceptedIntentHash, it.appliedAtEpochMs, it.environmentRevision,
                    it.verificationLevelWire
                )
            }
        }

    override fun recordReceipt(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        now: Long,
        leaseId: String?,
        operationId: String?,
        acceptedIntentHash: String?,
        appliedAtEpochMs: Long?,
        environmentRevision: Long?,
        verificationLevelWire: Int?
    ): RecordedReceipt? = gated {
        val existing = receipts.byKey(idempotencyKey)
        if (existing != null) {
            return@gated if (existing.requestDigest == requestDigest) {
                // R44 (Sol GREEN-review-3 F3): replay readback carries the stored verbatim proof fields.
                RecordedReceipt(
                    existing.idempotencyKey, existing.requestDigest, existing.resultOutcome, existing.createdAt,
                    existing.leaseId, existing.operationId, existing.acceptedIntentHash, existing.appliedAtEpochMs,
                    existing.environmentRevision, existing.verificationLevelWire
                )
            } else null // INV-13 conflict, prior preserved
        }
        receipts.insertIfAbsent(OperationReceiptRow(idempotencyKey, requestDigest, outcome, now, leaseId, operationId, acceptedIntentHash, appliedAtEpochMs, environmentRevision, verificationLevelWire))
        // R43 (Sol GREEN-review-2 F4): after a CONCURRENT INSERT IGNORE race-loss the read-back row
        // is the WINNER's — re-validate the digest. Two different digests racing on one key must
        // surface INV-13 conflict for the loser, never the winner's receipt misread as a replay.
        val row = receipts.byKey(idempotencyKey)
            ?: return@gated null // storage failed ⇒ not durable ⇒ fail closed
        if (row.requestDigest != requestDigest) return@gated null // INV-13 conflict, winner preserved
        // R44 (Sol GREEN-review-3 F3): the post-insert readback carries the verbatim proof fields.
        return@gated RecordedReceipt(
            row.idempotencyKey, row.requestDigest, row.resultOutcome, row.createdAt, row.leaseId,
            row.operationId, row.acceptedIntentHash, row.appliedAtEpochMs, row.environmentRevision,
            row.verificationLevelWire
        )
    }

    override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? =
        gated {
            checkpoints.byAttempt(attemptId)?.let {
                RecoveryCheckpoint(it.attemptId, it.lastDurableStage, it.receiptKey, it.recordedAt)
            }
        }

    override fun recordCheckpoint(attemptId: Long, lastDurableStage: String, receiptKey: String?, now: Long) {
        gated {
            checkpoints.upsert(RecoveryCheckpointRow(attemptId, lastDurableStage, receiptKey, now))
        }
    }

    override fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt? =
        gated {
            releases.byLease(leaseId)?.let {
                RecordedReleaseReceipt(it.idempotencyKey, it.leaseId, it.releaseDigest, it.resultOutcome, it.createdAt)
            }
        }

    override fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt? =
        gated {
            releases.byKey(idempotencyKey)?.let {
                RecordedReleaseReceipt(it.idempotencyKey, it.leaseId, it.releaseDigest, it.resultOutcome, it.createdAt)
            }
        }

    override fun recordReleaseReceipt(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        now: Long
    ): RecordedReleaseReceipt? = gated {
        val existing = releases.byKey(idempotencyKey)
        if (existing != null) {
            return@gated if (existing.leaseId == leaseId && existing.releaseDigest == releaseDigest) {
                RecordedReleaseReceipt(existing.idempotencyKey, existing.leaseId, existing.releaseDigest, existing.resultOutcome, existing.createdAt)
            } else null // conflict, prior preserved
        }
        releases.insertIfAbsent(ReleaseReceiptRow(idempotencyKey, leaseId, releaseDigest, outcome, now))
        // R43 (Sol GREEN-review-2 F4): race-loss re-validation — the read-back row may be the
        // winner's; a differing (lease, digest) tuple is a conflict, never a successful replay.
        val row = releases.byKey(idempotencyKey)
            ?: return@gated null
        if (row.leaseId != leaseId || row.releaseDigest != releaseDigest) return@gated null
        RecordedReleaseReceipt(row.idempotencyKey, row.leaseId, row.releaseDigest, row.resultOutcome, row.createdAt)
    }

    private fun <T> gated(block: suspend () -> T): T =
        accessGate.withNormalAccessBlockingOrThrow {
            kotlinx.coroutines.runBlocking { block() }
        }
}
