package name.caiyao.fakegps.integration.v1

/**
 * Durable append-only binding created before an APPLY crosses the external
 * environment boundary.
 *
 * A receipt cannot represent an operation that has not finalized yet, but the
 * idempotency key is already consumed once admission commits. Keeping this
 * record separate from the mutable lease state lets the same request resume the
 * exact server-generated lease while a different digest stays conflicting even
 * after that failed lease was explicitly released.
 */
internal data class ApplyAdmissionRecord(
    val callerApplicationId: String,
    val callerSignerDigest: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val acceptedIntentHash: String,
    val leaseId: String,
    val admittedAtEpochMs: Long,
    val admittedAtElapsedRealtimeMs: Long,
)

internal class DurableApplyAdmissionStore(
    private val storage: DurableKv,
) {
    fun find(
        callerApplicationId: String,
        callerSignerDigest: String,
        idempotencyKey: String,
    ): ApplyAdmissionRecord? {
        val raw = storage.read(
            NAMESPACE,
            scopeKey(callerApplicationId, callerSignerDigest, idempotencyKey),
        ) ?: return null
        return deserialize(raw)
    }

    fun record(record: ApplyAdmissionRecord) {
        val key = scopeKey(
            record.callerApplicationId,
            record.callerSignerDigest,
            record.idempotencyKey,
        )
        check(storage.read(NAMESPACE, key) == null) {
            "apply admission already exists for this caller/key"
        }
        storage.write(NAMESPACE, key, serialize(record))
    }

    private fun serialize(record: ApplyAdmissionRecord): String = DurableFieldCodec.encode(
        listOf(
            record.callerApplicationId,
            record.callerSignerDigest,
            record.idempotencyKey,
            record.requestDigest,
            record.acceptedIntentHash,
            record.leaseId,
            record.admittedAtEpochMs.toString(),
            record.admittedAtElapsedRealtimeMs.toString(),
        ),
    )

    private fun deserialize(raw: String): ApplyAdmissionRecord {
        val fields = DurableFieldCodec.decodeNonNull(raw)
        check(fields.size == FIELD_COUNT) {
            "invalid apply admission field count ${fields.size}"
        }
        return ApplyAdmissionRecord(
            callerApplicationId = fields[0],
            callerSignerDigest = fields[1],
            idempotencyKey = fields[2],
            requestDigest = fields[3],
            acceptedIntentHash = fields[4],
            leaseId = fields[5],
            admittedAtEpochMs = fields[6].toLong(),
            admittedAtElapsedRealtimeMs = fields[7].toLong(),
        )
    }

    private companion object {
        const val NAMESPACE = "integration.v1.apply_admissions"
        const val FIELD_COUNT = 8

        fun scopeKey(
            callerApplicationId: String,
            callerSignerDigest: String,
            idempotencyKey: String,
        ): String = DurableFieldCodec.encode(
            listOf(callerApplicationId, callerSignerDigest, idempotencyKey),
        )
    }
}
