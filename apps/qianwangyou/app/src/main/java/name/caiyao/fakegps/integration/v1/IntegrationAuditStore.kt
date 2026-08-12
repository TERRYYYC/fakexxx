package name.caiyao.fakegps.integration.v1

/**
 * Append-only integration audit (§7.2 QwyAuditEvent, §11.3, INV-18).
 *
 * - Strictly monotonic seq assigned at append; no update/delete surface exists
 *   on this interface by design (append-only is an API property, not a policy).
 * - Events carry correlation ids (caller/lease/operation) but NEVER pairing
 *   secrets; signer material is redacted before persistence.
 * - The audit stream is evidence, not state truth (§5 log boundary).
 */
interface IntegrationAuditStore {
    /** Appends and returns the event with its assigned monotonic seq. */
    fun append(
        event: String,
        callerApplicationId: String? = null,
        leaseId: String? = null,
        operationId: String? = null,
        payloadDigest: String? = null,
    ): QwyAuditEvent

    /** Read back in seq order (for operator review and tests). */
    fun all(): List<QwyAuditEvent>
}

/** Durable implementation over [DurableKv]. */
class DurableIntegrationAuditStore(
    private val storage: DurableKv,
    private val clock: MonotonicClock,
) : IntegrationAuditStore {

    companion object {
        private const val AUDIT_NS = "integration.v1.audit"
        private const val SEQ_KEY = "__seq__"
    }

    override fun append(
        event: String,
        callerApplicationId: String?,
        leaseId: String?,
        operationId: String?,
        payloadDigest: String?,
    ): QwyAuditEvent = storage.transaction {
        val seq = (storage.read(AUDIT_NS, SEQ_KEY)?.toLong() ?: 0L) + 1L
        storage.write(AUDIT_NS, SEQ_KEY, seq.toString())
        val auditEvent = QwyAuditEvent(
            seq = seq,
            atElapsedRealtimeMs = clock.elapsedRealtimeMs(),
            event = event,
            callerApplicationId = callerApplicationId,
            leaseId = leaseId,
            operationId = operationId,
            payloadDigest = payloadDigest,
        )
        storage.write(AUDIT_NS, "evt:$seq", serializeEvent(auditEvent))
        auditEvent
    }

    override fun all(): List<QwyAuditEvent> {
        val maxSeq = storage.read(AUDIT_NS, SEQ_KEY)?.toLong() ?: return emptyList()
        return (1..maxSeq).mapNotNull { seq ->
            storage.read(AUDIT_NS, "evt:$seq")?.let { deserializeEvent(it) }
        }
    }

    private fun serializeEvent(e: QwyAuditEvent): String =
        listOf(
            e.seq.toString(),
            e.atElapsedRealtimeMs.toString(),
            e.event,
            e.callerApplicationId ?: "",
            e.leaseId ?: "",
            e.operationId ?: "",
            e.payloadDigest ?: "",
        ).let(DurableFieldCodec::encode)

    private fun deserializeEvent(s: String): QwyAuditEvent {
        // Shared total codec: operationId is the caller idempotency key (a FREE
        // string) on advance — tab-framing corrupted the record (Terra round-4 class).
        val parts = DurableFieldCodec.decode(s)
        return QwyAuditEvent(
            seq = parts[0].toLong(),
            atElapsedRealtimeMs = parts[1].toLong(),
            event = parts[2],
            callerApplicationId = parts[3].ifEmpty { null },
            leaseId = parts[4].ifEmpty { null },
            operationId = parts[5].ifEmpty { null },
            payloadDigest = parts.getOrNull(6)?.ifEmpty { null },
        )
    }
}
