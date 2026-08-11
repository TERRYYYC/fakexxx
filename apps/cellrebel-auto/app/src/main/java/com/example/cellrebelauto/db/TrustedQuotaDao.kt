package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry

/**
 * DAO for the trusted quota ledger (`trusted_quota_entries`).
 *
 * Insert uses the default ABORT conflict strategy: a second insert with the same `attemptId`
 * throws (UNIQUE violation) — this is the at-most-once guarantee (INV-10). There is intentionally
 * no update or delete method: the ledger is insert-only (§7.1).
 *
 * # 可信账本 DAO：默认 ABORT，重复 attemptId 抛异常；无 update/delete（只插不改）
 */
@Dao
interface TrustedQuotaDao {

    /** Inserts a trusted entry. Throws on UNIQUE(attemptId) conflict — at-most-once (INV-10). */
    @Insert
    suspend fun insert(entry: TrustedQuotaEntry): Long

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
