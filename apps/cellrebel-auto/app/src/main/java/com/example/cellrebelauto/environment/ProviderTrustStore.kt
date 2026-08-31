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
 * R44 (Sol GREEN-review-3 F4): the authorization principal is the FROZEN pair
 * (applicationId, currentSignerDigest) (§6.5.4). All three methods key on the PAIR — signer
 * rotation coexists as a distinct principal row, re-approval after revocation appends a new row,
 * and a caller authorizes by the CURRENT signer, never by applicationId alone. Approving an
 * already-active principal is idempotent (returns the active record, never a duplicate).
 *
 * # Provider 信任 store（GREEN）：principal=(applicationId, signerDigest)；批准=插入 active 记录；撤销=置 revokedAt
 */
class ProviderTrustStore(private val dao: ProviderPairingDao) {

    /** Active = the exact (applicationId, currentSignerDigest) principal with revokedAt IS NULL. */
    suspend fun findActive(applicationId: String, signerDigest: String): ProviderPairingRecord? {
        val canonicalSigner = ProviderSignerDigest.normalizeOrNull(signerDigest) ?: return null
        return dao.activeFor(applicationId, canonicalSigner)
    }

    /**
     * Operator-approved pairing: insert a fresh active record for the principal and return it.
     * Idempotent for an ALREADY-active principal (returns the active record — never a duplicate
     * row). A prior REVOKED row is never resurrected — the new row is appended (history preserved;
     * M-PA-10 re-approval path).
     */
    suspend fun approve(
        applicationId: String,
        signerDigest: String,
        versionCode: Int,
        approvedAt: Long
    ): ProviderPairingRecord {
        val canonicalSigner = ProviderSignerDigest.requireCanonical(signerDigest)
        dao.activeFor(applicationId, canonicalSigner)?.let { return it }
        val id = dao.insert(
            ProviderPairingRecord(
                applicationId = applicationId,
                currentSignerDigest = canonicalSigner,
                approvedAt = approvedAt,
                revokedAt = null,
                approvedVersionCode = versionCode
            )
        )
        return ProviderPairingRecord(
            id = id,
            applicationId = applicationId,
            currentSignerDigest = canonicalSigner,
            approvedAt = approvedAt,
            revokedAt = null,
            approvedVersionCode = versionCode
        )
    }

    /** Revoke (state transition): set revokedAt on the ACTIVE record of the exact principal. */
    suspend fun revoke(applicationId: String, signerDigest: String, revokedAt: Long): Boolean {
        val canonicalSigner = ProviderSignerDigest.normalizeOrNull(signerDigest) ?: return false
        return dao.revoke(applicationId, canonicalSigner, revokedAt) > 0
    }
}
