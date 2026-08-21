package com.example.cellrebelauto.model.ledger

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Unverified completion record (§7.1, §8.1 UNVERIFIED_RECORDED): a completion that did NOT pass the
 * §6.4 trust predicate. Written to a table SEPARATE from [TrustedQuotaEntry] — it is the durable
 * carrier for a rejected completion, so the evidence binding (attempt + typed reason + evidence
 * digest) survives even though no trusted quota is minted.
 *
 * Sol round-8 P2: the frozen spec requires this independent carrier; a RED that only checks the
 * generic `TestAttempt.status == "failed"` cannot distinguish a correct impl (evidence preserved) from
 * a bad one (evidence discarded, reason synthesized). `UNIQUE(attemptId)` keeps it at-most-once per
 * attempt, mirroring the trusted ledger's uniqueness but in the OPPOSITE (untrusted) direction.
 *
 * # 未验证完成记录：与可信账本不同表；承载被 §6.4 拒绝的完成证据绑定（attempt+typed reason+digest）
 */
@Entity(
    tableName = "unverified_attempt_records",
    indices = [Index(value = ["attemptId"], unique = true)]
)
data class UnverifiedAttemptRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The attempt whose completion was judged unverified. UNIQUE — at-most-once (INV-11 negative). */
    val attemptId: Long,
    /** Typed reject reason (§8.6.2 wire / FailureReason.name). */
    val reason: String,
    /** Digest of the completion evidence that was REJECTED (§10.1 manifest). */
    val evidenceDigest: String
)
