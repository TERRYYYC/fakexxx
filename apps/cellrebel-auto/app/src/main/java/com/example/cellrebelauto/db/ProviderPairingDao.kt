package com.example.cellrebelauto.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.cellrebelauto.model.plan.ProviderPairingRecord

/**
 * DAO for `provider_pairing_records` — Auto provider allowlist (§7.1, §6.5.3).
 *
 * The raw DAO exposes only insert + reads. Trust mutations (approve/revoke) are gated behind
 * [com.example.cellrebelauto.environment.ProviderTrustStore]'s three narrow methods so that no
 * code path can silently mint or resurrect a trusted provider (INV-22). Revocation sets
 * `revokedAt` (state transition), it does not delete the row.
 *
 * # Provider allowlist DAO：信任决定只经 ProviderTrustStore 三方法，撤销=置 revokedAt 不删除
 */
@Dao
interface ProviderPairingDao {

    @Insert
    suspend fun insert(record: ProviderPairingRecord): Long

    @Query("SELECT * FROM provider_pairing_records WHERE applicationId = :applicationId ORDER BY id ASC")
    suspend fun byApplicationId(applicationId: String): List<ProviderPairingRecord>

    /** Active = revokedAt IS NULL. */
    @Query("SELECT * FROM provider_pairing_records WHERE applicationId = :applicationId AND revokedAt IS NULL LIMIT 1")
    suspend fun activeFor(applicationId: String): ProviderPairingRecord?

    @Query("SELECT * FROM provider_pairing_records ORDER BY applicationId ASC")
    suspend fun all(): List<ProviderPairingRecord>

    @Query("UPDATE provider_pairing_records SET revokedAt = :revokedAt WHERE applicationId = :applicationId AND revokedAt IS NULL")
    suspend fun revoke(applicationId: String, revokedAt: Long): Int

    @Query("SELECT COUNT(*) FROM provider_pairing_records")
    suspend fun count(): Int
}
