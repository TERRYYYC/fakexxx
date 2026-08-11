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

/** Durable implementation over [DurableKv]; lands in Task 3 GREEN. */
class DurableIntegrationAuditStore(
    private val storage: DurableKv,
    private val clock: MonotonicClock,
) : IntegrationAuditStore {
    override fun append(
        event: String,
        callerApplicationId: String?,
        leaseId: String?,
        operationId: String?,
        payloadDigest: String?,
    ): QwyAuditEvent = TODO("Task 3 GREEN")

    override fun all(): List<QwyAuditEvent> = TODO("Task 3 GREEN")
}
