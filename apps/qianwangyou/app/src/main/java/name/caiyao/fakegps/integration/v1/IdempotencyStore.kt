package name.caiyao.fakegps.integration.v1

/**
 * Durable operation receipts (§7.2 OperationReceipt, §6.3.4, §6.7.3).
 *
 * Lookup scope is (caller, operation, idempotencyKey). Same key + same
 * requestDigest → replay the ORIGINAL receipt; same key + different digest →
 * IDEMPOTENCY_CONFLICT (wire 12). resultDigest can never prove request equality
 * — two different requests may produce identical responses.
 *
 * requestDigest preimages are the §6.3.4 / §6.7.3 frozen canonical forms with
 * domain separation, computed via the contract's shared framing helper
 * (CanonicalDigestV1) — a second hand-written framing is a drift point, not a
 * convenience.
 */
enum class ContractOperation { APPLY, RELEASE, ADVANCE }

data class OperationReceiptRecord(
    val callerApplicationId: String,
    val operation: ContractOperation,
    val idempotencyKey: String,
    val requestDigest: String,
    val resultDigest: String,
    /** Serialized receipt payload so replays return the byte-identical answer. */
    val receiptPayload: String,
    val createdAtElapsedRealtimeMs: Long,
)

interface IdempotencyStore {
    fun find(
        callerApplicationId: String,
        operation: ContractOperation,
        idempotencyKey: String,
    ): OperationReceiptRecord?

    fun record(record: OperationReceiptRecord)
}

/** Durable implementation over [DurableKv]; lands in Task 3 GREEN. */
class DurableIdempotencyStore(
    private val storage: DurableKv,
) : IdempotencyStore {
    override fun find(
        callerApplicationId: String,
        operation: ContractOperation,
        idempotencyKey: String,
    ): OperationReceiptRecord? = TODO("Task 3 GREEN")

    override fun record(record: OperationReceiptRecord): Unit = TODO("Task 3 GREEN")
}

/**
 * Frozen request-digest computations (§6.3.4 apply/release, §6.7.3 advance).
 * Implementation MUST delegate framing to the contract module's
 * CanonicalDigestV1 helper — no local reimplementation.
 */
object RequestDigests {
    /** §6.3.4 frozen domain: "fakexxx.contract.v1.apply" (dot form, predates v1.38 colon domains). */
    fun applyDigest(acceptedIntentHash: String): String = TODO("Task 3 GREEN")

    /** §6.3.4 frozen domain: "fakexxx.contract.v1.release". */
    fun releaseDigest(leaseId: String): String = TODO("Task 3 GREEN")

    /** §6.7.3 advance-request preimage: binds BOTH preconditions + proof fields. */
    fun advanceRequestDigest(
        leaseId: String,
        expectedScheduleVersion: Long,
        expectedCurrentItemId: String,
        proofScheduleItemId: String,
        proofTrustedSuccessCount: Int,
        proofQuotaRequired: Int,
        proofLedgerRef: String,
    ): String = TODO("Task 3 GREEN")
}
