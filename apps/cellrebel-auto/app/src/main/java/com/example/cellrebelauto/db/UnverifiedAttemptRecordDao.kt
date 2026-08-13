package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord

/**
 * DAO for the unverified completion records (`unverified_attempt_records`, §7.1).
 *
 * Insert uses IGNORE: `UNIQUE(attemptId)` means a crash-replay re-insert of the SAME attempt is an
 * idempotent no-op (never throws), while a first insert records the attempt — the at-most-once negative
 * complement of the trusted ledger (INV-11, Sol round-9 P2). Insert-only (§7.1), distinct from
 * [TrustedQuotaDao].
 *
 * # 未验证完成记录 DAO：UNIQUE(attemptId) + IGNORE（crash 重放幂等不抛）；只插不改
 */
@Dao
interface UnverifiedAttemptRecordDao {

    /** Inserts an unverified record; a duplicate attemptId is an idempotent no-op (IGNORE). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: UnverifiedAttemptRecord): Long

    /** The unverified record for [attemptId], or null if none (the durable readback oracle, P2). */
    @Query("SELECT * FROM unverified_attempt_records WHERE attemptId = :attemptId LIMIT 1")
    suspend fun getByAttempt(attemptId: Long): UnverifiedAttemptRecord?

    @Query("SELECT COUNT(*) FROM unverified_attempt_records")
    suspend fun countAll(): Int
}
