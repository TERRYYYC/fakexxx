package com.example.cellrebelauto.recovery

/**
 * In-memory [DurableRecoveryLog] for RED tests — PURE STORAGE. Models the durable invariants the
 * GREEN Room binding will enforce (UNIQUE idempotency key; same-key/same-digest replay is a no-op;
 * same-key/different-digest returns null and preserves the prior receipt).
 *
 * This fake ONLY stores receipts + checkpoints. It does NOT count apply effects — that is the job of
 * [RecordingExternalApplyExecutor], which models the provider's idempotent apply. Keeping effect
 * counting OUT of the receipt store is the §10.1 fix (no driver seam in prod) and lets the tests
 * observe the at-most-once PROVIDER effect through the executor, not the store.
 *
 * A "crash" is modelled by constructing a brand-new [RecoveryCoordinator] over the SAME fake
 * instances (executor + log) — the volatile process dies, the durable state survives.
 *
 * # 持久 receipt+checkpoint 内存实现（纯存储，不计数）；崩溃 = 同 fake 上新建 coordinator
 */
class FakeDurableRecoveryLog : DurableRecoveryLog {

    val receipts = mutableMapOf<String, RecordedReceipt>()
    val checkpoints = mutableMapOf<Long, RecoveryCheckpoint>()

    /** Pre-populate a durable receipt (e.g. to seed a schedule-advance or a replay scenario). */
    fun seedReceipt(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        createdAt: Long
    ) {
        receipts[idempotencyKey] = RecordedReceipt(idempotencyKey, requestDigest, outcome, createdAt)
    }

    override fun receiptFor(idempotencyKey: String): RecordedReceipt? = receipts[idempotencyKey]

    override fun recordReceipt(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        now: Long
    ): RecordedReceipt? {
        val existing = receipts[idempotencyKey]
        if (existing != null) {
            // Same key: replay is idempotent iff the canonical request digest matches (INV-13).
            if (existing.requestDigest == requestDigest) return existing
            // Same key, different canonical digest → conflict; prior receipt preserved, no re-write.
            return null
        }
        val receipt = RecordedReceipt(idempotencyKey, requestDigest, outcome, now)
        receipts[idempotencyKey] = receipt
        return receipt
    }

    override fun checkpointFor(attemptId: Long): RecoveryCheckpoint? = checkpoints[attemptId]

    override fun recordCheckpoint(
        attemptId: Long,
        lastDurableStage: String,
        receiptKey: String?,
        now: Long
    ) {
        checkpoints[attemptId] = RecoveryCheckpoint(attemptId, lastDurableStage, receiptKey, now)
    }

    // ---- release receipts (Sol round-8 P1-4: lease-bound durable proof; round-9 P1-5: operation-key binding) ----

    private val releaseReceiptsByKey = mutableMapOf<String, RecordedReleaseReceipt>()
    private val releaseReceiptsByLease = mutableMapOf<String, RecordedReleaseReceipt>()

    /** Pre-populate a durable release receipt (to seed a release-replay scenario). */
    fun seedReleaseReceipt(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        createdAt: Long
    ) {
        val receipt = RecordedReleaseReceipt(idempotencyKey, leaseId, releaseDigest, outcome, createdAt)
        releaseReceiptsByKey[idempotencyKey] = receipt
        releaseReceiptsByLease[leaseId] = receipt
    }

    /** Seed ONLY the key index (a partial index — the coordinator must fail closed, Sol round-18 P1-5). */
    fun seedReleaseReceiptKeyOnly(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        createdAt: Long
    ) {
        releaseReceiptsByKey[idempotencyKey] = RecordedReleaseReceipt(idempotencyKey, leaseId, releaseDigest, outcome, createdAt)
    }

    /** Seed ONLY the lease index (a partial index — the coordinator must fail closed, Sol round-18 P1-5). */
    fun seedReleaseReceiptLeaseOnly(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        createdAt: Long
    ) {
        releaseReceiptsByLease[leaseId] = RecordedReleaseReceipt(idempotencyKey, leaseId, releaseDigest, outcome, createdAt)
    }

    override fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt? = releaseReceiptsByLease[leaseId]

    override fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt? = releaseReceiptsByKey[idempotencyKey]

    override fun recordReleaseReceipt(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        now: Long
    ): RecordedReleaseReceipt? {
        val existing = releaseReceiptsByKey[idempotencyKey]
        if (existing != null) {
            // Same operation key: replay iff the lease AND digest match (INV-13); else conflict.
            if (existing.leaseId == leaseId && existing.releaseDigest == releaseDigest) return existing
            return null
        }
        val receipt = RecordedReleaseReceipt(idempotencyKey, leaseId, releaseDigest, outcome, now)
        releaseReceiptsByKey[idempotencyKey] = receipt
        releaseReceiptsByLease[leaseId] = receipt
        return receipt
    }
}
