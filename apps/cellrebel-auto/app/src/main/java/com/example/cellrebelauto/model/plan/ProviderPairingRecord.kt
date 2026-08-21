package com.example.cellrebelauto.model.plan

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for Auto's provider allowlist (§6.5.3, Task 4).
 *
 * Auto trusts a provider's observations ONLY when its (applicationId,
 * currentSignerDigest) pair exists and is active in this table.
 * Write access is restricted to ProviderTrustStore's three narrow methods:
 * findActive / approve / revoke.
 *
 * approvedVersionCode is immutable audit data — version upgrades go to
 * AutoAuditEvent, not here (§6.5.3 minimal write surface).
 *
 * Revocation is a status transition (revokedAt != null), not deletion.
 *
 * # Auto 侧的 provider allowlist。只通过 ProviderTrustStore 三个窄方法写入。
 */
@Entity(tableName = "provider_pairing_records")
data class ProviderPairingRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val applicationId: String,
    val currentSignerDigest: String,
    val approvedVersionCode: Long,
    val approvedAt: Long,
    val revokedAt: Long? = null,
)
