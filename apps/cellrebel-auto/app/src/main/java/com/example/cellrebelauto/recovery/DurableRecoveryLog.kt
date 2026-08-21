package com.example.cellrebelauto.recovery

/**
 * Durable receipt + checkpoint store for crash-safe replay (§8.1 RECOVERY_REQUIRED, §10 crash/
 * recovery matrix, INV-13/15). PURE STORAGE — it stores receipts and checkpoints and detects
 * idempotency/conflict; it does NOT count apply effects and does NOT call any external provider.
 *
 * The apply EFFECT is driven by a separate [ExternalApplyExecutor]; this log only records the
 * durable receipt that proves the apply happened. That separation (Sol round-3 Finding 2) is what
 * makes the three crash windows around an apply distinguishable:
 *  - (a) crash before the external call      ⇒ no receipt here, no provider effect;
 *  - (b) crash after the call, before receipt ⇒ provider applied, but NO receipt here  ← M-CR-02;
 *  - (c) crash after receipt, before checkpoint ⇒ receipt present here.
 * A coordinator that never calls the executor cannot manufacture a receipt here, so it cannot green
 * the recovery tests by side-effecting the store alone.
 *
 * §10.1 — no driver seam in production code for tests: this interface exposes ONLY receipt presence,
 * receipt digest, and checkpoint state. There is NO `applyCount` / `lastConflictKey` surface; the
 * at-most-once effect is observed through the executor's test fake, never through this prod seam.
 *
 * Idempotency contract (INV-13): replaying the SAME idempotency key + canonical `requestDigest`
 * returns the existing receipt and does NOT re-write; the SAME key with a DIFFERENT
 * `requestDigest` returns `null` (conflict) and the prior receipt is preserved. `requestDigest`
 * (§6.3.4 domain-separated canonical digest) is the integrity key, NOT a result digest.
 *
 * Pre-freeze there is no Room binding; tests inject an in-memory implementation
 * ([com.example.cellrebelauto.recovery.FakeDurableRecoveryLog]). GREEN binds this to a Room-backed
 * `OperationReceipt` / `RecoveryCheckpoint` store (§7.1).
 *
 * # 持久 receipt+checkpoint 存储（纯存储，不计数、不调外部）：分离执行器使三崩溃窗口可区分
 */
interface DurableRecoveryLog {

    /** The durable receipt recorded for [idempotencyKey], or null if none (§7.1 OperationReceipt). */
    fun receiptFor(idempotencyKey: String): RecordedReceipt?

    /**
     * Record (or replay) a receipt for [idempotencyKey] + [requestDigest] (§6.3.4 canonical digest).
     * PURE STORAGE — does NOT count effects (the effect counter lives on the executor's test fake).
     * R43 (Sol GREEN-review P1-5): the provider LEASE from the apply receipt (`ApplyReceiptV1.leaseId`)
     * is persisted ATOMICALLY with the receipt — a crash between receipt durability and the attempt
     * owner's `markAplusLease` must still recover the lease from the receipt replay.
     * Contract:
     *  - no prior receipt for [idempotencyKey] ⇒ writes a new receipt (carrying [leaseId]) and returns it;
     *  - prior receipt with the SAME [requestDigest] ⇒ returns the existing receipt, does NOT
     *    re-write (idempotent replay);
     *  - prior receipt with a DIFFERENT [requestDigest] ⇒ returns null (INV-13 conflict), prior
     *    receipt preserved.
     */
    fun recordReceipt(
        idempotencyKey: String,
        requestDigest: String,
        outcome: String,
        now: Long,
        leaseId: String? = null,
        // R43 (Sol GREEN-review-2 F3): the VERBATIM ApplyReceiptV1 proof fields — persisted
        // atomically with the receipt so crash recovery re-derives the full §7.1 OperationReceipt.
        operationId: String? = null,
        acceptedIntentHash: String? = null,
        appliedAtEpochMs: Long? = null,
        environmentRevision: Long? = null,
        verificationLevelWire: Int? = null
    ): RecordedReceipt?

    /** Durable recovery checkpoint for an attempt (§7.1 RecoveryCheckpoint), or null. */
    fun checkpointFor(attemptId: Long): RecoveryCheckpoint?

    /** Record a recovery checkpoint (terminal-state projection or reconcile progress). */
    fun recordCheckpoint(attemptId: Long, lastDurableStage: String, receiptKey: String?, now: Long)

    /**
     * The durable RELEASE receipt for [leaseId], or null if none (§8.1 RELEASE_RECEIPT; §8.2: no fresh
     * apply until a release receipt is durable — Sol round-8 P1-4). A Boolean "released" is NOT a
     * durable proof; this typed readback is what the recovery RED asserts.
     */
    fun releaseReceiptFor(leaseId: String): RecordedReleaseReceipt?

    /**
     * The durable RELEASE receipt for the OPERATION key [idempotencyKey] (INV-13 idempotency is keyed by
     * the operation key, NOT the lease — Sol round-14 P1-2). Lets the coordinator detect a same-key /
     * different-lease-or-digest conflict BEFORE calling the provider.
     */
    fun releaseReceiptForKey(idempotencyKey: String): RecordedReleaseReceipt?

    /**
     * Record (or replay) a release receipt for [idempotencyKey] + [leaseId] + [releaseDigest] (§6.3.4
     * digest over the lease). Same idempotency contract as [recordReceipt]: same key+digest replays the
     * existing receipt; same key + different digest returns null (conflict), prior receipt preserved.
     */
    fun recordReleaseReceipt(
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        outcome: String,
        now: Long
    ): RecordedReleaseReceipt?
}

/** A durable apply receipt (§7.1 OperationReceipt projection). */
data class RecordedReceipt(
    val idempotencyKey: String,
    val requestDigest: String,
    val resultOutcome: String,
    val createdAt: Long,
    /**
     * The provider lease issued by the applied operation (contract `ApplyReceiptV1.leaseId`). Nullable:
     * legacy/test-seeded receipts predate the field; the GREEN Room binding always persists it from
     * the apply receipt, so a replay can hand the lease back without re-invoking the provider.
     */
    val leaseId: String? = null,
    /** Verbatim ApplyReceiptV1 proof fields (Sol GREEN-review-2 F3). */
    val operationId: String? = null,
    val acceptedIntentHash: String? = null,
    val appliedAtEpochMs: Long? = null,
    val environmentRevision: Long? = null,
    val verificationLevelWire: Int? = null
)

/** A recovery checkpoint (§7.1 RecoveryCheckpoint projection). */
data class RecoveryCheckpoint(
    val attemptId: Long,
    val lastDurableStage: String,
    val receiptKey: String?,
    val recordedAt: Long
)

/** A durable release receipt (§8.1 RELEASE_RECEIPT projection, lease-bound). */
data class RecordedReleaseReceipt(
    val idempotencyKey: String,
    val leaseId: String,
    val releaseDigest: String,
    val resultOutcome: String,
    val createdAt: Long
)
