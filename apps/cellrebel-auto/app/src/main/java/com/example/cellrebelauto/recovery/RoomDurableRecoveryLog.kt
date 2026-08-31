package com.example.cellrebelauto.recovery

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction

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
    val verificationLevelWire: Int? = null,
    /** Frozen provider principal. Null only for migrated legacy rows, which are not replayable. */
    val providerApplicationId: String? = null,
    /** Immutable signer owner of the attempt/lease. Null only for legacy/unknown rows. */
    val providerSignerDigest: String? = null,
)

@Entity(tableName = "recovery_checkpoints")
data class RecoveryCheckpointRow(
    @PrimaryKey val attemptId: Long,
    val lastDurableStage: String,
    val receiptKey: String?,
    val recordedAt: Long,
    /** Frozen provider principal. Null only for migrated legacy rows. */
    val providerApplicationId: String? = null,
    /** Immutable signer owner. Null only for legacy/unknown rows. */
    val providerSignerDigest: String? = null,
)

@Entity(tableName = "release_receipts")
data class ReleaseReceiptRow(
    @PrimaryKey val idempotencyKey: String,
    val leaseId: String,
    val releaseDigest: String,
    val resultOutcome: String,
    val createdAt: Long,
    /** Release lease identity is scoped by (providerApplicationId, leaseId). */
    val providerApplicationId: String? = null,
    /** Immutable signer owner of the released lease. Null only for legacy/unknown rows. */
    val providerSignerDigest: String? = null,
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
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: RecoveryCheckpointRow)

    @Query("SELECT * FROM recovery_checkpoints WHERE attemptId = :attemptId LIMIT 1")
    suspend fun byAttempt(attemptId: Long): RecoveryCheckpointRow?

    @Query(
        "UPDATE recovery_checkpoints SET lastDurableStage = :lastDurableStage, " +
            "receiptKey = :receiptKey, recordedAt = :recordedAt " +
            "WHERE attemptId = :attemptId AND providerApplicationId IS :providerApplicationId " +
            "AND providerSignerDigest IS :providerSignerDigest"
    )
    suspend fun updateSameProvider(
        attemptId: Long,
        providerApplicationId: String?,
        providerSignerDigest: String?,
        lastDurableStage: String,
        receiptKey: String?,
        recordedAt: Long,
    ): Int

    /** REPLACE is forbidden: a null/foreign principal checkpoint cannot be overwritten as valid. */
    @Transaction
    suspend fun upsertSameProvider(row: RecoveryCheckpointRow): RecoveryCheckpointRow? {
        val existing = byAttempt(row.attemptId)
        if (existing == null) {
            insertIfAbsent(row)
        } else {
            if (existing.providerApplicationId != row.providerApplicationId ||
                existing.providerSignerDigest != row.providerSignerDigest
            ) return null
            if (updateSameProvider(
                    row.attemptId,
                    row.providerApplicationId,
                    row.providerSignerDigest,
                    row.lastDurableStage,
                    row.receiptKey,
                    row.recordedAt,
                ) != 1
            ) return null
        }
        return byAttempt(row.attemptId)?.takeIf {
            it.providerApplicationId == row.providerApplicationId &&
                it.providerSignerDigest == row.providerSignerDigest &&
                it.lastDurableStage == row.lastDurableStage &&
                it.receiptKey == row.receiptKey &&
                it.recordedAt == row.recordedAt
        }
    }
}

@Dao
interface ReleaseReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: ReleaseReceiptRow)

    @Query("SELECT * FROM release_receipts WHERE idempotencyKey = :key LIMIT 1")
    suspend fun byKey(key: String): ReleaseReceiptRow?

    @Query(
        "SELECT * FROM release_receipts WHERE leaseId = :leaseId " +
            "AND providerApplicationId IS :providerApplicationId LIMIT 1"
    )
    suspend fun byLease(
        leaseId: String,
        providerApplicationId: String? = null,
    ): ReleaseReceiptRow?

    /** Every row claiming [leaseId]. A release proof is unique only when this list has one row. */
    @Query(
        "SELECT * FROM release_receipts WHERE leaseId = :leaseId " +
            "AND providerApplicationId IS :providerApplicationId ORDER BY idempotencyKey ASC"
    )
    suspend fun allByLease(
        leaseId: String,
        providerApplicationId: String? = null,
    ): List<ReleaseReceiptRow>

    /**
     * Atomically insert/replay one release tuple while enforcing the logical one-row-per-lease
     * invariant. The schema's historical primary key is the operation key, so an already-ambiguous
     * lease must fail closed instead of letting an unordered LIMIT 1 choose its proof.
     */
    @Transaction
    suspend fun insertOrReplayUnambiguous(row: ReleaseReceiptRow): ReleaseReceiptRow? {
        val existingByKey = byKey(row.idempotencyKey)
        // Lease identity remains globally scoped by (P, leaseId). S is an immutable owner proof,
        // not a namespace that permits a rotated signer to append a second release row.
        val existingByLease = allByLease(row.leaseId, row.providerApplicationId)
        if (existingByKey != null) {
            return existingByKey.takeIf {
                existingByLease.size == 1 && existingByLease.single() == it &&
                    it.providerApplicationId == row.providerApplicationId &&
                    it.providerSignerDigest == row.providerSignerDigest &&
                    it.idempotencyKey == row.idempotencyKey &&
                    it.leaseId == row.leaseId &&
                    it.releaseDigest == row.releaseDigest &&
                    it.resultOutcome == row.resultOutcome
            }
        }
        if (existingByLease.isNotEmpty()) return null

        insertIfAbsent(row)
        val winnerByKey = byKey(row.idempotencyKey) ?: return null
        val winnersByLease = allByLease(row.leaseId, row.providerApplicationId)
        return winnerByKey.takeIf {
            winnersByLease.size == 1 && winnersByLease.single() == it &&
                it.providerApplicationId == row.providerApplicationId &&
                it.providerSignerDigest == row.providerSignerDigest &&
                it.idempotencyKey == row.idempotencyKey &&
                it.leaseId == row.leaseId &&
                it.releaseDigest == row.releaseDigest &&
                it.resultOutcome == row.resultOutcome
        }
    }
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
    expectedProviderSignerDigest: String? = null,
) : DurableRecoveryLog {

    private val providerSignerDigest: String? = expectedProviderSignerDigest?.let {
        com.example.cellrebelauto.environment.ProviderSignerDigest.requireCanonical(it)
    }

    private fun signerMatches(recordedSignerDigest: String?): Boolean =
        recordedSignerDigest == providerSignerDigest

    /** Legacy test/read convenience; production recovery always supplies its scoped principal. */
    fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt? =
        releaseReceiptFor(leaseId, null)

    override fun receiptFor(idempotencyKey: String): RecordedReceipt? =
        kotlinx.coroutines.runBlocking {
            receipts.byKey(idempotencyKey)?.takeIf {
                signerMatches(it.providerSignerDigest)
            }?.let {
                // R44 (Sol GREEN-review-3 F3): the readback carries the VERBATIM ApplyReceiptV1 proof
                // fields — a receipt that loses them on read is not the §7.1 OperationReceipt.
                RecordedReceipt(
                    it.idempotencyKey, it.requestDigest, it.resultOutcome, it.createdAt, it.leaseId,
                    it.operationId, it.acceptedIntentHash, it.appliedAtEpochMs, it.environmentRevision,
                    it.verificationLevelWire, it.providerApplicationId,
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
        verificationLevelWire: Int?,
        providerApplicationId: String?,
    ): RecordedReceipt? = kotlinx.coroutines.runBlocking {
        val existing = receipts.byKey(idempotencyKey)
        if (existing != null) {
            return@runBlocking if (existing.requestDigest == requestDigest &&
                existing.providerApplicationId == providerApplicationId &&
                signerMatches(existing.providerSignerDigest)
            ) {
                // R44 (Sol GREEN-review-3 F3): replay readback carries the stored verbatim proof fields.
                RecordedReceipt(
                    existing.idempotencyKey, existing.requestDigest, existing.resultOutcome, existing.createdAt,
                    existing.leaseId, existing.operationId, existing.acceptedIntentHash, existing.appliedAtEpochMs,
                    existing.environmentRevision, existing.verificationLevelWire,
                    existing.providerApplicationId,
                )
            } else null // INV-13 conflict, prior preserved
        }
        receipts.insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey, requestDigest, outcome, now, leaseId, operationId,
                acceptedIntentHash, appliedAtEpochMs, environmentRevision,
                verificationLevelWire, providerApplicationId, providerSignerDigest,
            )
        )
        // R43 (Sol GREEN-review-2 F4): after a CONCURRENT INSERT IGNORE race-loss the read-back row
        // is the WINNER's — re-validate the digest. Two different digests racing on one key must
        // surface INV-13 conflict for the loser, never the winner's receipt misread as a replay.
        val row = receipts.byKey(idempotencyKey)
            ?: return@runBlocking null // storage failed ⇒ not durable ⇒ fail closed
        if (row.requestDigest != requestDigest ||
            row.providerApplicationId != providerApplicationId ||
            !signerMatches(row.providerSignerDigest)
        ) return@runBlocking null // key/digest/principal conflict, winner preserved
        // R44 (Sol GREEN-review-3 F3): the post-insert readback carries the verbatim proof fields.
        return@runBlocking RecordedReceipt(
            row.idempotencyKey, row.requestDigest, row.resultOutcome, row.createdAt, row.leaseId,
            row.operationId, row.acceptedIntentHash, row.appliedAtEpochMs, row.environmentRevision,
            row.verificationLevelWire, row.providerApplicationId,
        )
    }

    override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? =
        kotlinx.coroutines.runBlocking {
            checkpoints.byAttempt(attemptId)?.takeIf {
                signerMatches(it.providerSignerDigest)
            }?.let {
                RecoveryCheckpoint(
                    it.attemptId, it.lastDurableStage, it.receiptKey, it.recordedAt,
                    it.providerApplicationId,
                )
            }
        }

    override fun recordCheckpoint(
        attemptId: Long,
        lastDurableStage: String,
        receiptKey: String?,
        now: Long,
        providerApplicationId: String?,
    ): RecoveryCheckpoint? = kotlinx.coroutines.runBlocking {
        checkpoints.upsertSameProvider(
            RecoveryCheckpointRow(
                attemptId, lastDurableStage, receiptKey, now, providerApplicationId,
                providerSignerDigest,
            )
        )?.let {
            RecoveryCheckpoint(
                it.attemptId, it.lastDurableStage, it.receiptKey, it.recordedAt,
                it.providerApplicationId,
            )
        }
    }

    override fun releaseReceiptFor(
        leaseId: String,
        providerApplicationId: String?,
    ): RecordedReleaseReceipt? =
        kotlinx.coroutines.runBlocking {
            releases.allByLease(leaseId, providerApplicationId).singleOrNull()
                ?.takeIf { signerMatches(it.providerSignerDigest) }
                ?.let {
                RecordedReleaseReceipt(
                    it.idempotencyKey, it.leaseId, it.releaseDigest, it.resultOutcome,
                    it.createdAt, it.providerApplicationId,
                )
            }
        }

    override fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt? =
        kotlinx.coroutines.runBlocking {
            releases.byKey(idempotencyKey)?.takeIf {
                signerMatches(it.providerSignerDigest)
            }?.let {
                RecordedReleaseReceipt(
                    it.idempotencyKey, it.leaseId, it.releaseDigest, it.resultOutcome,
                    it.createdAt, it.providerApplicationId,
                )
            }
        }

    override fun recordReleaseReceipt(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        now: Long,
        providerApplicationId: String?,
    ): RecordedReleaseReceipt? = kotlinx.coroutines.runBlocking {
        releases.insertOrReplayUnambiguous(
            ReleaseReceiptRow(
                idempotencyKey, leaseId, releaseDigest, outcome, now, providerApplicationId,
                providerSignerDigest,
            )
        )?.let { row ->
            RecordedReleaseReceipt(
                row.idempotencyKey,
                row.leaseId,
                row.releaseDigest,
                row.resultOutcome,
                row.createdAt,
                row.providerApplicationId,
            )
        }
    }
}
