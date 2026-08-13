package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord

/**
 * DAO for the unverified completion records (`unverified_attempt_records`, §7.1).
 *
 * Insert uses the default ABORT conflict strategy: `UNIQUE(attemptId)` means a second insert for the
 * same attempt throws — the at-most-once negative complement of the trusted ledger (INV-11). Insert-only
 * (§7.1), distinct from [TrustedQuotaDao].
 *
 * # 未验证完成记录 DAO：UNIQUE(attemptId)，只插不改；可信账本的反向互补载体
 */
@Dao
interface UnverifiedAttemptRecordDao {

    /** Inserts an unverified record. Throws on UNIQUE(attemptId) conflict. */
    @Insert
    suspend fun insert(record: UnverifiedAttemptRecord): Long

    /** The unverified record for [attemptId], or null if none (the durable readback oracle, P2). */
    @Query("SELECT * FROM unverified_attempt_records WHERE attemptId = :attemptId LIMIT 1")
    suspend fun getByAttempt(attemptId: Long): UnverifiedAttemptRecord?

    @Query("SELECT COUNT(*) FROM unverified_attempt_records")
    suspend fun countAll(): Int
}
