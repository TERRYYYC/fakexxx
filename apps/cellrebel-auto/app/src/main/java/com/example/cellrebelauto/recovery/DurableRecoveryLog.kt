package com.example.cellrebelauto.recovery

/**
 * Durable seam for crash-safe replay (§8.1 RECOVERY_REQUIRED, §10 crash/recovery matrix,
 * INV-13/15). This is the contract the GREEN [RecoveryCoordinator] will bind to a Room-backed
 * `OperationReceipt` / `RecoveryCheckpoint` store (§7.1).
 *
 * It is deliberately a DUMB durable primitive — storage plus idempotency/conflict detection — NOT
 * policy. The coordinator decides when to record and replay; this log only enforces the durable
 * invariants that mirror what SQLite will enforce for real (UNIQUE key, digest match/mismatch).
 * Pre-freeze there is no Room binding; tests inject an in-memory implementation
 * ([com.example.cellrebelauto.recovery.FakeDurableRecoveryLog]) to observe crash-window effects.
 *
 * Idempotency contract (INV-13): replaying the SAME idempotency key + canonical `requestDigest`
 * returns the existing receipt and does NOT re-apply; the SAME key with a DIFFERENT
 * `requestDigest` is an `IDEMPOTENCY_CONFLICT` — the prior receipt is preserved and the apply does
 * not run. `requestDigest` (§6.3.4 domain-separated canonical digest) is the integrity key, NOT
 * `resultDigest` — two different requests can legitimately share a result.
 *
 * # 持久恢复日志接缝（RED 骨架）：哑存储 + 幂等/冲突检测；策略由 RecoveryCoordinator 决定；GREEN 绑 Room
 */
interface DurableRecoveryLog {

    /** The durable receipt recorded for [idempotencyKey], or null if none (§7.1 OperationReceipt). */
    fun receiptFor(idempotencyKey: String): RecordedReceipt?

    /**
     * Record (or replay) an apply outcome for [idempotencyKey] + [requestDigest] (§6.3.4 canonical
     * digest). Contract:
     *  - no prior receipt for [idempotencyKey] ⇒ writes a new receipt, counts one apply, returns it;
     *  - prior receipt with the SAME [requestDigest] ⇒ returns the existing receipt, does NOT
     *    re-apply (idempotent replay), apply count unchanged;
     *  - prior receipt with a DIFFERENT [requestDigest] ⇒ returns null (INV-13 conflict), sets
     *    [lastConflictKey], prior receipt preserved, apply count unchanged.
     *
     * @param bucket key under which the apply effect is counted (e.g. `"attempt-$attemptId"`), so
     *        tests can assert at-most-once effect per attempt across a crash.
     */
    fun recordApply(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        bucket: String,
        now: Long
    ): RecordedReceipt?

    /** Times the underlying apply effect actually ran for [bucket] — at-most-once evidence. */
    fun applyCount(bucket: String): Int

    /** The idempotency key of the most recent same-key/different-digest conflict, or null. */
    val lastConflictKey: String?

    /** Durable recovery checkpoint for an attempt (§7.1 RecoveryCheckpoint), or null. */
    fun checkpointFor(attemptId: Long): RecoveryCheckpoint?

    /** Record a recovery checkpoint (terminal-state projection or reconcile progress). */
    fun recordCheckpoint(attemptId: Long, lastDurableStage: String, receiptKey: String?, now: Long)
}

/** A durable apply receipt (§7.1 OperationReceipt projection). */
data class RecordedReceipt(
    val idempotencyKey: String,
    val requestDigest: String,
    val resultOutcome: String,
    val createdAt: Long
)

/** A recovery checkpoint (§7.1 RecoveryCheckpoint projection). */
data class RecoveryCheckpoint(
    val attemptId: Long,
    val lastDurableStage: String,
    val receiptKey: String?,
    val recordedAt: Long
)
