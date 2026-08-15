package com.example.cellrebelauto.recovery

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

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
    val leaseId: String? = null
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
    private val releases: ReleaseReceiptDao
) : DurableRecoveryLog {

    override fun receiptFor(idempotencyKey: String): RecordedReceipt? =
        kotlinx.coroutines.runBlocking {
            receipts.byKey(idempotencyKey)?.let {
                RecordedReceipt(it.idempotencyKey, it.requestDigest, it.resultOutcome, it.createdAt, it.leaseId)
            }
        }

    override fun recordReceipt(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        now: Long,
        leaseId: String?
    ): RecordedReceipt? = kotlinx.coroutines.runBlocking {
        val existing = receipts.byKey(idempotencyKey)
        if (existing != null) {
            return@runBlocking if (existing.requestDigest == requestDigest) {
                RecordedReceipt(existing.idempotencyKey, existing.requestDigest, existing.resultOutcome, existing.createdAt, existing.leaseId)
            } else null // INV-13 conflict, prior preserved
        }
        receipts.insertIfAbsent(OperationReceiptRow(idempotencyKey, requestDigest, outcome, now, leaseId))
        val row = receipts.byKey(idempotencyKey)
            ?: return@runBlocking null // storage failed ⇒ not durable ⇒ fail closed
        RecordedReceipt(row.idempotencyKey, row.requestDigest, row.resultOutcome, row.createdAt, row.leaseId)
    }

    override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? =
        kotlinx.coroutines.runBlocking {
            checkpoints.byAttempt(attemptId)?.let {
                RecoveryCheckpoint(it.attemptId, it.lastDurableStage, it.receiptKey, it.recordedAt)
            }
        }

    override fun recordCheckpoint(attemptId: Long, lastDurableStage: String, receiptKey: String?, now: Long) {
        kotlinx.coroutines.runBlocking {
            checkpoints.upsert(RecoveryCheckpointRow(attemptId, lastDurableStage, receiptKey, now))
        }
    }

    override fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt? =
        kotlinx.coroutines.runBlocking {
            releases.byLease(leaseId)?.let {
                RecordedReleaseReceipt(it.idempotencyKey, it.leaseId, it.releaseDigest, it.resultOutcome, it.createdAt)
            }
        }

    override fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt? =
        kotlinx.coroutines.runBlocking {
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
    ): RecordedReleaseReceipt? = kotlinx.coroutines.runBlocking {
        val existing = releases.byKey(idempotencyKey)
        if (existing != null) {
            return@runBlocking if (existing.leaseId == leaseId && existing.releaseDigest == releaseDigest) {
                RecordedReleaseReceipt(existing.idempotencyKey, existing.leaseId, existing.releaseDigest, existing.resultOutcome, existing.createdAt)
            } else null // conflict, prior preserved
        }
        releases.insertIfAbsent(ReleaseReceiptRow(idempotencyKey, leaseId, releaseDigest, outcome, now))
        releases.byKey(idempotencyKey)?.let {
            RecordedReleaseReceipt(it.idempotencyKey, it.leaseId, it.releaseDigest, it.resultOutcome, it.createdAt)
        }
    }
}
