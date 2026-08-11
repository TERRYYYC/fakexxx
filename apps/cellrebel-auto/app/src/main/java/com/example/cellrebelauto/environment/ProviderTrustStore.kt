package com.example.cellrebelauto.environment

import com.example.cellrebelauto.db.ProviderPairingDao
import com.example.cellrebelauto.model.plan.ProviderPairingRecord

/**
 * Lifecycle owner for [ProviderPairingRecord] (§6.5.3). Exposes exactly three narrow methods —
 * [findActive] / [approve] / [revoke] — so no code path can silently mint or resurrect a trusted
 * provider (INV-22). No silent TOFU: an unseen signer stops at local NOT_PAIRED until operator
 * approval; revocation is a state transition (sets revokedAt), not a delete.
 *
 * PRE-FREEZE SKELETON (RED): all mutations are no-ops and [findActive] always returns null. Tests
 * asserting an approved provider becomes active will FAIL until GREEN. The 3-method surface is
 * frozen now so GREEN plugs logic in WITHOUT widening the trust boundary.
 *
 * # Provider 信任 store 骨架（RED）：三方法面已冻结，逻辑为空；禁止 silent TOFU
 */
class ProviderTrustStore(private val dao: ProviderPairingDao) {

    /** Active = applicationId present with revokedAt IS NULL. RED: always null. */
    fun findActive(applicationId: String): ProviderPairingRecord? = null

    /**
     * Operator-approved pairing. RED: returns null (no persistence, no trust minted).
     * GREEN will insert via [dao] and return the active record.
     */
    fun approve(
        applicationId: String,
        signerDigest: String,
        versionCode: Int,
        approvedAt: Long
    ): ProviderPairingRecord? = null

    /** Revoke (state transition). RED: returns false. */
    fun revoke(applicationId: String, revokedAt: Long): Boolean = false
}
