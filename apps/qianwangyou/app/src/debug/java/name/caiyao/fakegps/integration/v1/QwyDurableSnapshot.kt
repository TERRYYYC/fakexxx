package name.caiyao.fakegps.integration.v1

import java.io.File

/**
 * P10DBG-COLLECTOR-V1 — durable-state readback for the qwy-side collector.
 *
 * THE ONE RULE THIS FILE EXISTS TO ENFORCE
 * ----------------------------------------
 * Readback NEVER goes through [ProviderRuntime.handler]. Booting the runtime
 * singleton runs `onOwnerProcessStart` → §8.4 `recoverAfterRestart`, which
 * MUTATES the lease store (ACQUIRING/ACTIVE → RELEASE_INCOMPLETE/EXPIRED,
 * RELEASING → replayed release). An executor reading back a fault's result
 * through the singleton would destroy the evidence in the act of reading it.
 *
 * Instead, every capture constructs a FRESH [FileDurableKv] over the durable
 * directory and reads the committed bytes as-is. The read paths
 * ([EnvironmentLeaseStore.blockingLease]/[get], [DurablePairingStore],
 * [DurableIntegrationAuditStore.all]) are pure reads; the second instance
 * never writes, so the §6.6 L3 single-writer rule is not touched — the
 * split-brain the rule forbids is two writers, not a reader snapshot.
 *
 * Constructing fresh also makes the readback HONEST by construction: what it
 * reports is what is on disk at that moment, not an in-process cache — the
 * mutation "fired but state never persisted" is directly visible as a
 * mismatch, which QwyDurableSnapshotTest pins.
 */

/** Read-only projection of the provider's committed durable state. */
data class QwyCollectorSnapshot(
    val lease: QwyLeaseSnapshot,
    val pendingCallers: List<PendingPairingCandidate>,
    /** activeFor(appId, signer) — null after a successful revoke. */
    val pairingStillActive: Boolean?,
    /** Last audit events, seq order, newest last. */
    val auditTail: List<QwyAuditEvent>,
    /** True when the audit stream contains a caller_revoked event for the appId. */
    val revokeAudited: Boolean?,
)

object QwyDurableSnapshot {

    /**
     * Fixed clock: the read paths used here never consult the clock
     * (effectiveState is deliberately not called — raw persisted state is the
     * evidence), and passing a real clock would just widen the API for
     * something this snapshot must not do.
     */
    private object FrozenClock : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = 0L
        override fun epochMs(): Long = 0L
    }

    /**
     * The durable directory [ProviderRuntime.build] writes to. Duplicated as a
     * literal because kvRef is private (and should stay private); if the
     * production directory ever moves, QwyDurableSnapshotTest's
     * provider-shape contract test catches the drift by failing to observe a
     * lease written through the same constant in the test fixture.
     */
    const val DURABLE_DIR_NAME = "environment-control-v1"

    fun durableDir(context: android.content.Context): File =
        File(context.applicationContext.filesDir, DURABLE_DIR_NAME)

    /**
     * Capture committed state. `leaseId`, when given, additionally reads that
     * specific lease; `appId`+`signer` additionally resolve the pairing
     * readback (§5C: after revoke, findActive must be null and the audit row
     * must exist).
     */
    fun capture(
        kvDir: File,
        appId: String? = null,
        signer: String? = null,
        auditTailLimit: Int = 24,
    ): QwyCollectorSnapshot {
        // FRESH instance per capture: load()-from-disk is the point (see header).
        val kv = FileDurableKv(kvDir)
        val leases = EnvironmentLeaseStore(kv, FrozenClock)
        val blocking = leases.blockingLease()

        val pairing = DurablePairingStore(kv)
        val audit = DurableIntegrationAuditStore(kv, FrozenClock).all()

        val stillActive = if (appId != null && signer != null) {
            pairing.findActive(appId, signer) != null
        } else null

        val revokedAudited = appId?.let { id ->
            audit.any { it.event == "caller_revoked" && it.callerApplicationId == id }
        }

        return QwyCollectorSnapshot(
            lease = QwyLeaseSnapshot(
                currentLeaseId = blocking?.leaseId,
                leaseState = blocking?.state?.name,
                callerApplicationId = blocking?.callerApplicationId,
            ),
            pendingCallers = pairing.pendingCandidates(),
            pairingStillActive = stillActive,
            auditTail = audit.takeLast(auditTailLimit),
            revokeAudited = revokedAudited,
        )
    }

    /** Human-readable report block; every line is backed by durable state. */
    fun render(snapshot: QwyCollectorSnapshot): String = buildString {
        appendLine("durable lease: id=${snapshot.lease.currentLeaseId ?: "—"} " +
            "state=${snapshot.lease.leaseState ?: "—"} caller=${snapshot.lease.callerApplicationId ?: "—"}")
        if (snapshot.pairingStillActive != null) {
            appendLine("pairing still active for principal: ${snapshot.pairingStillActive}")
        }
        if (snapshot.revokeAudited != null) {
            appendLine("caller_revoked audit row present: ${snapshot.revokeAudited}")
        }
        appendLine("pending callers: ${snapshot.pendingCallers.size}")
        snapshot.auditTail.lastOrNull()?.let {
            appendLine("audit tail (last of ${snapshot.auditTail.size}): seq=${it.seq} ${it.event} ${it.callerApplicationId ?: ""}")
        }
    }
}
