package com.example.cellrebelauto.environment

import com.example.cellrebelauto.db.ProviderPairingDao
import com.example.cellrebelauto.model.plan.ProviderPairingRecord

/**
 * Lifecycle owner for [ProviderPairingRecord] (§6.5.3). Exposes exactly three narrow methods —
 * [findActive] / [approve] / [revoke] — so no code path can silently mint or resurrect a trusted
 * provider (INV-22). No silent TOFU: an unseen signer stops at local NOT_PAIRED until operator
 * approval; revocation is a state transition (sets revokedAt), not a delete.
 *
 * GREEN (contract v1 frozen): approve inserts a fresh active record and returns it; revoke sets
 * revokedAt on the active record (row survives, findActive stops returning it); findActive returns
 * the revokedAt-IS-NULL row only. Re-approval after revocation creates a NEW row (revoked rows are
 * never resurrected — M-PA-10: re-pairing walks operator approval again).
 *
 * # Provider 信任 store（GREEN）：批准=插入 active 记录；撤销=置 revokedAt 不删行；绝不复活旧记录
 */
class ProviderTrustStore(private val dao: ProviderPairingDao) {

    /** Active = applicationId present with revokedAt IS NULL. */
    suspend fun findActive(applicationId: String): ProviderPairingRecord? =
        dao.activeFor(applicationId)

    /**
     * Operator-approved pairing: insert a fresh active record and return it. A prior REVOKED row is
     * never resurrected — the new row is appended (history preserved; M-PA-10 re-approval path).
     */
    suspend fun approve(
        applicationId: String,
        signerDigest: String,
        versionCode: Int,
        approvedAt: Long
    ): ProviderPairingRecord {
        val id = dao.insert(
            ProviderPairingRecord(
                applicationId = applicationId,
                currentSignerDigest = signerDigest,
                approvedAt = approvedAt,
                revokedAt = null,
                approvedVersionCode = versionCode
            )
        )
        return ProviderPairingRecord(
            id = id,
            applicationId = applicationId,
            currentSignerDigest = signerDigest,
            approvedAt = approvedAt,
            revokedAt = null,
            approvedVersionCode = versionCode
        )
    }

    /** Revoke (state transition): set revokedAt on the ACTIVE record — the row is never deleted. */
    suspend fun revoke(applicationId: String, revokedAt: Long): Boolean =
        dao.revoke(applicationId, revokedAt) > 0
}
