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
    /**
     * §8.4 EXPIRED precondition (M-LS-12): true when the durable clean-shutdown
     * marker is set (recorded, not yet consumed). Read NON-destructively — the
     * production [ProviderRuntime.CleanShutdownMarker.consume] would clear it,
     * which is exactly what a readback must never do. The executor confirms
     * `cleanShutdownMarkerSet=true` BEFORE restart so the clean-shutdown +
     * generation-mismatch → EXPIRED branch is entered on purpose, not blind.
     */
    val cleanShutdownMarkerSet: Boolean,
    /**
     * R5 P1 durable witnesses for the seed's owner-quiescence bracket:
     * a non-empty ADVANCE_PENDING slot is a committed advance the next fenced
     * entry/boot will REPLAY (refuse to seed over it); maxAuditSeq is the
     * owner's own monotonic side-effect counter — every fenced mutation
     * (apply/release/advance/revoke) appends an audit row, so an unchanged
     * maxAuditSeq across the whole seed proves no fenced owner write
     * interleaved (the racing writer cannot avoid self-incriminating).
     */
    val advancePendingRaw: String?,
    val maxAuditSeq: Long,
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

    /**
     * The namespace/key [ProviderRuntime.CleanShutdownMarker] writes the clean
     * shutdown flag under. Duplicated as literals because they are `private
     * const` in production; QwyDurableSnapshotTest pins them against drift by
     * writing through the REAL `record()` and reading them back here — if the
     * production literals move, that read returns absent and the test fails.
     */
    const val CLEAN_SHUTDOWN_NS = "runtime"
    const val CLEAN_SHUTDOWN_KEY = "clean_shutdown"

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

        // NON-destructive read of the clean-shutdown marker. `read` never
        // clears (only production `consume` does), so dumping the marker cannot
        // corrupt the very EXPIRED precondition it reports.
        val cleanMarkerSet = kv.read(CLEAN_SHUTDOWN_NS, CLEAN_SHUTDOWN_KEY) == "1"

        // R5 P1 witnesses. ADVANCE_PENDING namespace/key are PUBLIC handler
        // consts — referenced directly, no drift-prone literal duplication.
        val advancePendingRaw = kv.read(
            EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE,
            EnvironmentControlHandler.ADVANCE_PENDING_KEY,
        )?.takeIf { it.isNotEmpty() }
        val maxAuditSeq = audit.maxOfOrNull { it.seq } ?: 0L

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
                callerSignerDigest = blocking?.callerSignerDigest,
            ),
            pendingCallers = pairing.pendingCandidates(),
            pairingStillActive = stillActive,
            auditTail = audit.takeLast(auditTailLimit),
            revokeAudited = revokedAudited,
            cleanShutdownMarkerSet = cleanMarkerSet,
            advancePendingRaw = advancePendingRaw,
            maxAuditSeq = maxAuditSeq,
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
        appendLine("clean-shutdown marker set (§8.4 EXPIRED precondition): ${snapshot.cleanShutdownMarkerSet}")
        appendLine("advance-pending slot: ${snapshot.advancePendingRaw ?: "—"}")
        appendLine("max audit seq: ${snapshot.maxAuditSeq}")
        appendLine("pending callers: ${snapshot.pendingCallers.size}")
        snapshot.auditTail.lastOrNull()?.let {
            appendLine("audit tail (last of ${snapshot.auditTail.size}): seq=${it.seq} ${it.event} ${it.callerApplicationId ?: ""}")
        }
    }
}
