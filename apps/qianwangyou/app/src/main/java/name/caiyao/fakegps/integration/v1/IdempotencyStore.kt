package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.CanonicalDigestV1

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

/** Durable implementation over [DurableKv]. */
class DurableIdempotencyStore(
    private val storage: DurableKv,
) : IdempotencyStore {

    companion object {
        /** The only namespace receipts live in — referenced by crash-injection tests. */
        const val RECEIPT_NAMESPACE: String = "integration.v1.receipts"

        /**
         * Scope = (caller, operation, key), framed with the shared total codec:
         * keys are FREE strings (§6.3.4), so a separator-joined scope key is
         * forgeable by a key containing the separator (Terra round-4 class).
         */
        private fun scopeKey(callerApplicationId: String, operation: ContractOperation, idempotencyKey: String) =
            DurableFieldCodec.encode(listOf(callerApplicationId, operation.name, idempotencyKey))
    }

    override fun find(
        callerApplicationId: String,
        operation: ContractOperation,
        idempotencyKey: String,
    ): OperationReceiptRecord? {
        val raw = storage.read(RECEIPT_NAMESPACE, scopeKey(callerApplicationId, operation, idempotencyKey))
            ?: return null
        return deserialize(raw)
    }

    override fun record(record: OperationReceiptRecord) {
        storage.write(
            RECEIPT_NAMESPACE,
            scopeKey(record.callerApplicationId, record.operation, record.idempotencyKey),
            serialize(record),
        )
    }

    // Length-prefix framing via the shared codec: the previous tab-join guarded
    // only receiptPayload (split limit) while idempotencyKey — a FREE string
    // that precedes it — could shift every following field (Terra round-4).
    private fun serialize(r: OperationReceiptRecord): String = DurableFieldCodec.encode(
        listOf(
            r.callerApplicationId,
            r.operation.name,
            r.idempotencyKey,
            r.requestDigest,
            r.resultDigest,
            r.createdAtElapsedRealtimeMs.toString(),
            r.receiptPayload,
        ))

    private fun deserialize(s: String): OperationReceiptRecord {
        val parts = DurableFieldCodec.decodeNonNull(s)
        return OperationReceiptRecord(
            callerApplicationId = parts[0],
            operation = ContractOperation.valueOf(parts[1]),
            idempotencyKey = parts[2],
            requestDigest = parts[3],
            resultDigest = parts[4],
            createdAtElapsedRealtimeMs = parts[5].toLong(),
            receiptPayload = parts[6],
        )
    }
}

/**
 * Frozen request-digest computations (§6.3.4 apply/release, §6.7.3 advance).
 * Implementation MUST delegate framing to the contract module's
 * CanonicalDigestV1 helper — no local reimplementation.
 */
object RequestDigests {
    /** §6.3.4 frozen domain: "fakexxx.contract.v1.apply" (dot form, predates v1.38 colon domains). */
    fun applyDigest(acceptedIntentHash: String): String =
        CanonicalDigestV1.digest("fakexxx.contract.v1.apply", listOf(
            CanonicalDigestV1.utf8(acceptedIntentHash),
        ))

    /** §6.3.4 frozen domain: "fakexxx.contract.v1.release". */
    fun releaseDigest(leaseId: String): String =
        CanonicalDigestV1.digest("fakexxx.contract.v1.release", listOf(
            CanonicalDigestV1.utf8(leaseId),
        ))

    /** §6.7.3 advance-request preimage: binds BOTH preconditions + proof fields. */
    fun advanceRequestDigest(
        leaseId: String,
        expectedScheduleVersion: Long,
        expectedCurrentItemId: String,
        proofScheduleItemId: String,
        proofTrustedSuccessCount: Int,
        proofQuotaRequired: Int,
        proofLedgerRef: String,
    ): String = CanonicalDigestV1.digest(CanonicalDigestV1.DOMAIN_ADVANCE_REQUEST, listOf(
        CanonicalDigestV1.utf8(leaseId),
        CanonicalDigestV1.decimal(expectedScheduleVersion),
        CanonicalDigestV1.utf8(expectedCurrentItemId),
        CanonicalDigestV1.utf8(proofScheduleItemId),
        CanonicalDigestV1.decimal(proofTrustedSuccessCount),
        CanonicalDigestV1.decimal(proofQuotaRequired),
        CanonicalDigestV1.utf8(proofLedgerRef),
    ))
}
