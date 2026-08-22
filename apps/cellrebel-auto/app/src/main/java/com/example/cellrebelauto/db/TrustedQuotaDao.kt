package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry

/**
 * DAO for the trusted quota ledger (`trusted_quota_entries`).
 *
 * [insert] uses ABORT — the normal-path at-most-once guarantee (INV-10); any caller that cannot
 * tolerate a UNIQUE collision (because the entry is already committed) must use [insertIfAbsent].
 * There is intentionally no update or delete method: the ledger is insert-only (§7.1).
 *
 * [insertIfAbsent] uses IGNORE — recovery-safe: the DECIDING crash window (ledger committed,
 * phase not yet committed) causes `redecideDecidingAttempt` to re-run `recordTrustedCompletion`;
 * if the prior mint survived the crash, IGNORE is the correct no-op (the mint is durable, the
 * count projection is idempotent). Without this, ABORT rolls back the entire recovery transaction
 * and the attempt is stuck at DECIDING forever (P1-2 Sol closure review Issue #19/#20).
 *
 * # 可信账本 DAO：insert=ABORT（正路径 at-most-once）；insertIfAbsent=IGNORE（恢复路径幂等）
 */
@Dao
interface TrustedQuotaDao {

    /** Inserts a trusted entry. Throws on UNIQUE(attemptId) conflict — at-most-once (INV-10). */
    @Insert
    suspend fun insert(entry: TrustedQuotaEntry): Long

    /**
     * Recovery-safe insert: if the entry already exists (DECIDING crash window), this is a no-op.
     * Returns the rowId on success, or -1 if ignored (Room IGNORE convention).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: TrustedQuotaEntry): Long

    /** Trusted count for a task — the completion projection numerator (§7.3). */
    @Query("SELECT COUNT(*) FROM trusted_quota_entries WHERE taskId = :taskId")
    suspend fun trustedCountForTask(taskId: Long): Int

    @Query("SELECT * FROM trusted_quota_entries WHERE attemptId = :attemptId LIMIT 1")
    suspend fun getByAttempt(attemptId: Long): TrustedQuotaEntry?

    @Query("SELECT * FROM trusted_quota_entries WHERE taskId = :taskId ORDER BY id ASC")
    suspend fun entriesForTask(taskId: Long): List<TrustedQuotaEntry>

    @Query("SELECT COUNT(*) FROM trusted_quota_entries")
    suspend fun countAll(): Int
}
