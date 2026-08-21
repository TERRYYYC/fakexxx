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

    /** Active = the (applicationId, currentSignerDigest) principal with revokedAt IS NULL (§6.5.4). */
    @Query("SELECT * FROM provider_pairing_records WHERE applicationId = :applicationId AND currentSignerDigest = :signerDigest AND revokedAt IS NULL LIMIT 1")
    suspend fun activeFor(applicationId: String, signerDigest: String): ProviderPairingRecord?

    @Query("SELECT * FROM provider_pairing_records ORDER BY applicationId ASC")
    suspend fun all(): List<ProviderPairingRecord>

    /** Revoke the ACTIVE row of the exact (applicationId, currentSignerDigest) principal only. */
    @Query("UPDATE provider_pairing_records SET revokedAt = :revokedAt WHERE applicationId = :applicationId AND currentSignerDigest = :signerDigest AND revokedAt IS NULL")
    suspend fun revoke(applicationId: String, signerDigest: String, revokedAt: Long): Int

    @Query("SELECT COUNT(*) FROM provider_pairing_records")
    suspend fun count(): Int
}
