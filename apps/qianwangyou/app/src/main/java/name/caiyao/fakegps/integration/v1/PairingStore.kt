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

    /** True when ANY pairing record exists for this applicationId (active or revoked). */
    fun hasAnyPairing(applicationId: String): Boolean

    /**
     * Persist an in-call identity snapshot (§6.5): candidates come from
     * Binder-resolved identity, never from UI-side package scans, and must be
     * durable before the call returns NOT_PAIRED.
     */
    fun recordCandidate(candidate: PendingPairingCandidate)

    fun pendingCandidates(): List<PendingPairingCandidate>
}

/** Durable implementation over [DurableKv]. */
class DurablePairingStore(
    private val storage: DurableKv,
) : PairingStore {

    companion object {
        private const val PAIRING_NS = "integration.v1.pairing"
        private const val CANDIDATE_NS = "integration.v1.pairing.candidates"
        private fun pairingKey(applicationId: String, signerDigest: String) =
            "$applicationId|$signerDigest"
    }

    override fun findActive(applicationId: String, signerDigest: String): PairingRecord? {
        val json = storage.read(PAIRING_NS, pairingKey(applicationId, signerDigest))
            ?: return null
        val record = deserializePairing(json)
        return if (record.revokedAtElapsedRealtimeMs == null) record else null
    }

    override fun approve(candidate: PendingPairingCandidate, atElapsedRealtimeMs: Long): PairingRecord {
        val record = PairingRecord(
            applicationId = candidate.callerApplicationId,
            signerDigest = candidate.currentSignerDigest,
            approvedAtElapsedRealtimeMs = atElapsedRealtimeMs,
            revokedAtElapsedRealtimeMs = null,
            observedVersionCode = candidate.observedVersionCode,
        )
        storage.write(PAIRING_NS, pairingKey(record.applicationId, record.signerDigest),
            serializePairing(record))
        return record
    }

    override fun revoke(applicationId: String, signerDigest: String, atElapsedRealtimeMs: Long) {
        val key = pairingKey(applicationId, signerDigest)
        val json = storage.read(PAIRING_NS, key) ?: return
        val existing = deserializePairing(json)
        val revoked = existing.copy(revokedAtElapsedRealtimeMs = atElapsedRealtimeMs)
        storage.write(PAIRING_NS, key, serializePairing(revoked))
    }

    override fun recordCandidate(candidate: PendingPairingCandidate) {
        val key = "${candidate.callerApplicationId}|${candidate.currentSignerDigest}"
        storage.write(CANDIDATE_NS, key, serializeCandidate(candidate))
    }

    override fun hasAnyPairing(applicationId: String): Boolean =
        storage.keys(PAIRING_NS).any { it.startsWith("$applicationId|") }

    override fun pendingCandidates(): List<PendingPairingCandidate> =
        storage.keys(CANDIDATE_NS).mapNotNull { key ->
            storage.read(CANDIDATE_NS, key)?.let { deserializeCandidate(it) }
        }

    // --- simple serialization (no JSON lib dependency; fields are pipe-delimited) ---

    private fun serializePairing(r: PairingRecord): String =
        "${r.applicationId}|${r.signerDigest}|${r.approvedAtElapsedRealtimeMs}|${r.revokedAtElapsedRealtimeMs ?: "null"}|${r.observedVersionCode ?: "null"}"

    private fun deserializePairing(s: String): PairingRecord {
        val parts = s.split("|")
        return PairingRecord(
            applicationId = parts[0],
            signerDigest = parts[1],
            approvedAtElapsedRealtimeMs = parts[2].toLong(),
            revokedAtElapsedRealtimeMs = parts[3].takeIf { it != "null" }?.toLong(),
            observedVersionCode = parts[4].takeIf { it != "null" }?.toLong(),
        )
    }

    private fun serializeCandidate(c: PendingPairingCandidate): String =
        "${c.callerApplicationId}|${c.currentSignerDigest}|${c.observedVersionCode ?: "null"}|${c.firstSeenAtElapsedRealtimeMs}"

    private fun deserializeCandidate(s: String): PendingPairingCandidate {
        val parts = s.split("|")
        return PendingPairingCandidate(
            callerApplicationId = parts[0],
            currentSignerDigest = parts[1],
            observedVersionCode = parts[2].takeIf { it != "null" }?.toLong(),
            firstSeenAtElapsedRealtimeMs = parts[3].toLong(),
        )
    }
}
