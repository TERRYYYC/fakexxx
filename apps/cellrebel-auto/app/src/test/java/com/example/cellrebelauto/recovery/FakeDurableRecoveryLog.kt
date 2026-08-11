package com.example.cellrebelauto.recovery

/**
 * In-memory [DurableRecoveryLog] for RED tests. Models the durable invariants the GREEN Room
 * binding will enforce for real (UNIQUE idempotency key; same-key/same-digest replay is a no-op;
 * same-key/different-digest is a conflict whose prior receipt is preserved).
 *
 * This fake is the DURABLE STORE; a "crash" is modelled by constructing a brand-new
 * [RecoveryCoordinator] over the SAME fake instance — the volatile process dies, the durable state
 * survives. That is exactly the crash-window semantics the coordinator must be a pure function of.
 *
 * # 持久恢复日志内存实现（RED 测试用）：模拟 UNIQUE/幂等/冲突；"崩溃"= 同 fake 上新建 coordinator
 */
class FakeDurableRecoveryLog : DurableRecoveryLog {

    val receipts = mutableMapOf<String, RecordedReceipt>()
    val applyCounts = mutableMapOf<String, Int>()
    val checkpoints = mutableMapOf<Long, RecoveryCheckpoint>()
    override var lastConflictKey: String? = null
        private set

    /** Pre-populate a durable receipt (e.g. to seed a schedule-advance or a replay scenario). */
    fun seedReceipt(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        createdAt: Long,
        bucket: String = defaultBucket(idempotencyKey)
    ) {
        receipts[idempotencyKey] = RecordedReceipt(idempotencyKey, requestDigest, outcome, createdAt)
        applyCounts[bucket] = (applyCounts[bucket] ?: 0) + 1
    }

    override fun receiptFor(idempotencyKey: String): RecordedReceipt? = receipts[idempotencyKey]

    override fun recordApply(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        bucket: String,
        now: Long
    ): RecordedReceipt? {
        val existing = receipts[idempotencyKey]
        if (existing != null) {
            // Same key: replay is idempotent iff the canonical request digest matches (INV-13).
            if (existing.requestDigest == requestDigest) return existing
            // Same key, different canonical digest → conflict; prior receipt preserved, no re-apply.
            lastConflictKey = idempotencyKey
            return null
        }
        val receipt = RecordedReceipt(idempotencyKey, requestDigest, outcome, now)
        receipts[idempotencyKey] = receipt
        applyCounts[bucket] = (applyCounts[bucket] ?: 0) + 1
        return receipt
    }

    override fun applyCount(bucket: String): Int = applyCounts[bucket] ?: 0

    override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? = checkpoints[attemptId]

    override fun recordCheckpoint(
        attemptId: Long,
        lastDurableStage: String,
        receiptKey: String?,
        now: Long
    ) {
        checkpoints[attemptId] = RecoveryCheckpoint(attemptId, lastDurableStage, receiptKey, now)
    }

    private fun defaultBucket(idempotencyKey: String): String = "seed-$idempotencyKey"
}
