package name.caiyao.fakegps.integration.v1

/**
 * Durable caller allowlist (§6.5, §7.2, §8.5).
 *
 * Principal is exactly (applicationId, current signerDigest). Revocation is a
 * state transition, never a delete; re-approval after revocation is a NEW
 * operator decision (no automatic resurrection — M-PA-10).
 */
interface PairingStore {
    /** Only records with revokedAt == null participate in authorization. */
    fun findActive(applicationId: String, signerDigest: String): PairingRecord?

    /** Operator approval: writes (or re-activates via a new decision) a record. */
    fun approve(candidate: PendingPairingCandidate, atElapsedRealtimeMs: Long): PairingRecord

    /** Sets revokedAt; keeps the record for the audit chain. */
    fun revoke(applicationId: String, signerDigest: String, atElapsedRealtimeMs: Long)

    /**
     * Persist an in-call identity snapshot (§6.5): candidates come from
     * Binder-resolved identity, never from UI-side package scans, and must be
     * durable before the call returns NOT_PAIRED.
     */
    fun recordCandidate(candidate: PendingPairingCandidate)

    fun pendingCandidates(): List<PendingPairingCandidate>
}

/** Durable implementation over [DurableKv]; lands in Task 3 GREEN. */
class DurablePairingStore(
    private val storage: DurableKv,
) : PairingStore {
    override fun findActive(applicationId: String, signerDigest: String): PairingRecord? =
        TODO("Task 3 GREEN")

    override fun approve(candidate: PendingPairingCandidate, atElapsedRealtimeMs: Long): PairingRecord =
        TODO("Task 3 GREEN")

    override fun revoke(applicationId: String, signerDigest: String, atElapsedRealtimeMs: Long): Unit =
        TODO("Task 3 GREEN")

    override fun recordCandidate(candidate: PendingPairingCandidate): Unit =
        TODO("Task 3 GREEN")

    override fun pendingCandidates(): List<PendingPairingCandidate> =
        TODO("Task 3 GREEN")
}
