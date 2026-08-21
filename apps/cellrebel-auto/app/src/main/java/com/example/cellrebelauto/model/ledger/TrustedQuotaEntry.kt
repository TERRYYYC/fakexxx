package com.example.cellrebelauto.model.ledger

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Trusted quota ledger row — the at-most-once trusted evidence chain (§7.1, INV-10).
 *
 * `UNIQUE(attemptId)` guarantees one attempt can mint at most one trusted entry (INV-10).
 * Insert-only: there is no update or delete path. The trusted completion count for a task is a
 * pure projection `count(rows where taskId=...)` (§7.3) — never a mutable counter column.
 *
 * # 可信配额账本：UNIQUE(attemptId)，只插不改；完成数是 count 投影不是计数列
 */
@Entity(
    tableName = "trusted_quota_entries",
    indices = [
        Index(value = ["attemptId"], unique = true),
        Index("taskId")
    ]
)
data class TrustedQuotaEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The attempt this trusted entry was minted for. UNIQUE — at-most-once per attempt (INV-10). */
    val attemptId: Long,
    /** The task whose quota this entry counts toward. */
    val taskId: Long,
    /** Digest of the completion evidence bundle that justified this entry (§7.1, §10.1 manifest). */
    val evidenceDigest: String,
    /** Monotonic commit timestamp (elapsed-realtime-style), for ordering / replay diagnostics. */
    val committedAt: Long
)
