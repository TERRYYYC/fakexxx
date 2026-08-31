package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1

/**
 * Shared value types and seams for the qianwangyou Environment Control provider.
 *
 * Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md
 *   §6.5 pairing / §6.6 revision ownership / §7.2 persistent objects / §8.4 lease
 *   state machine / §8.5 pairing readiness.
 *
 * Frozen field sets (§7.2) are reproduced verbatim — implementers do not invent
 * fields here; contract deltas go back to issue #3.
 */

/** Typed business failure carried across Binder as ServiceSpecificException(code.wire). */
class ContractException(
    val code: ContractErrorCodeV1,
    message: String? = null,
) : Exception(message ?: code.name)

/**
 * Identity resolved in-call from Binder.getCallingUid() (§6.5). Never taken from
 * request parameters (INV-02).
 */
data class CallerIdentity(
    val uid: Int,
    val applicationId: String,
    val signerDigest: String,
)

/**
 * Seam over PackageManager identity lookups so the authorizer is JVM-testable.
 * Production implementation wraps getPackagesForUid + SigningInfo (§6.5.1).
 */
interface PackageIdentityResolver {
    /** All packages for the calling uid; size != 1 must be rejected (shared UID, §6.5.1). */
    fun packagesForUid(uid: Int): List<String>

    /** Current signing state for a package, or null when unresolvable (fail closed). */
    fun signerLookup(applicationId: String): SignerLookup?
}

data class SignerLookup(
    /** Current signer digest set — never "has ever used" (§6.5.1). */
    val currentSignerDigests: List<String>,
    val hasMultipleSigners: Boolean,
    /** true = API 24–27 legacy GET_SIGNATURES degraded path (fail-closed rules). */
    val legacyApi: Boolean,
    /** Audit/diagnostics only; never part of the authorization principal (§6.5.4). */
    val versionCode: Long,
)

/** Monotonic clock seam (§6.4.2): the ONLY clock trust predicates may compare. */
interface MonotonicClock {
    fun elapsedRealtimeMs(): Long

    /** Wall clock, audit/UI only. Never used in lease or trust decisions. */
    fun epochMs(): Long
}

/**
 * Minimal durable key-value seam. Restart semantics in tests: a new store built
 * over the same [DurableKv] is "the process after a restart" — anything not
 * written here does not survive (INV-25 L3/L4).
 *
 * Production backing (single-process DataStore / Room / SQLite) is a free choice
 * per §6.6 — what is banned is multi-process direct writes, not a library.
 */
interface DurableKv {
    fun read(namespace: String, key: String): String?
    fun write(namespace: String, key: String, value: String)
    fun keys(namespace: String): Set<String>

    /** Serialized read-modify-write (§6.6 L3). Block runs exclusively. */
    fun <T> transaction(block: () -> T): T
}

/** §8.4 frozen seven-state lease machine. */
enum class LeaseState {
    ACQUIRING,
    ACTIVE,
    RELEASING,
    RELEASED,
    EXPIRED,
    REVOKED,
    RELEASE_INCOMPLETE,
}

/** Who revoked, when a lease lands in REVOKED (§6.5 / §8.4). */
enum class RevokeSource { QWY_REVOKED_CALLER }

/**
 * §7.2 EnvironmentLease — fields frozen, do not extend without a #3 delta.
 *
 * [earnedScheduleRef] is the v1.75/v1.76 (#18, contract exact 00e2396) delta:
 * provider-INTERNAL storage backing the §6.7.4b step-3b attribution gate.
 * It never travels on the wire — no DTO/AIDL change (START GATE discipline).
 */
data class LeaseRecord(
    val leaseId: String,
    val callerApplicationId: String,
    val callerSignerDigest: String,
    val acceptedIntentHash: String,
    val state: LeaseState,
    val applyIdempotencyKey: String,
    val startingEnvironmentRevision: Long,
    val deadlineElapsedRealtimeMs: Long,
    /**
     * Owner generation captured when apply was admitted (§8.4): monotonic values
     * are only comparable within one generation; generation change ⊇ clock-epoch
     * change, so a mismatch forces EXPIRED for ACQUIRING/ACTIVE.
     */
    val applyOwnerGeneration: Long,
    /**
     * v1.75 §6.7.4b step 3b: the schedule ITEM whose quota this lease earned.
     * Provider-owned at apply time — `scheduleSnapshot()?.currentItemId`, NOT
     * the caller's intent.scheduleRef declaration (F12: Auto declared
     * "task-N" and anchoring attribution to that declaration made every legal
     * release→advance die wrong-item; the receipt/digest still bind the
     * declared intent verbatim, only this internal attribution anchor is
     * provider truth). completeAndAdvance for a different item on this
     * historical reference is `wrong-item` → STALE_LEASE(8).
     *
     * Null when no schedule item is active at apply time (or on pre-#18
     * durable rows written before this internal field was introduced). Either
     * way such a row remains decodable but is `unproven` at step 3b and fails
     * closed → STALE_LEASE(8).
     */
    val earnedScheduleRef: String?,
    val releaseIdempotencyKey: String? = null,
    val residualReasonWires: List<Int> = emptyList(),
    val revokeSource: RevokeSource? = null,
    val recoveryEvidenceRef: String? = null,
)

/** §7.2 PairingRecord — authorization principal is (applicationId, signerDigest). */
data class PairingRecord(
    val applicationId: String,
    val signerDigest: String,
    val approvedAtElapsedRealtimeMs: Long,
    /** null = active; non-null = revoked. Revocation is a transition, not a delete. */
    val revokedAtElapsedRealtimeMs: Long? = null,
    /** Audit only, immutable after approval (§6.5.4). */
    val observedVersionCode: Long? = null,
)

/** §7.2 / §8.5 PendingPairingCandidate — snapshot taken during the Binder call. */
data class PendingPairingCandidate(
    val callerApplicationId: String,
    val currentSignerDigest: String,
    val observedVersionCode: Long?,
    val firstSeenAtElapsedRealtimeMs: Long,
)

/** Reasons the revision owner must bump (§6.4 relevant-change list, §6.6 L6). */
enum class RevisionBumpReason {
    PROFILE_CHANGED,
    MODE_OR_PROVIDER_CHANGED,
    SCHEDULE_BOUNDARY,
    PERMISSION_OR_OWNER_CHANGED,
    GENERATION_DISCONTINUITY,
    OBSERVER_GAP,
}

/**
 * Whether the installed relevant-change sources can prove an uninterrupted
 * observation window rather than only report changes eventually.
 *
 * Endpoint verification and continuity are deliberately orthogonal: a fresh
 * framework readback may be independently verified while an asynchronous
 * watcher still cannot prove that the owner/provider never left and returned.
 */
enum class ContinuityEvidenceCapability {
    COMPLETE,
    INCOMPLETE,
    UNAVAILABLE,
}

/** Continuity snapshot produced by the revision owner (§6.6, INV-25). */
data class RevisionSnapshot(
    val revision: Long,
    /** ContinuityCoverageV1 wire code. */
    val coverageWire: Int,
    /** Owner process generation — new persisted value per owner start (§6.6 L6). */
    val generation: Long,
    /** Continuity window start, elapsedRealtime; null when coverage is not FULL. */
    val continuitySinceElapsedRealtimeMs: Long?,
)

/** §7.2 QwyAuditEvent (append-only; carries no pairing secrets — INV-18). */
data class QwyAuditEvent(
    val seq: Long,
    val atElapsedRealtimeMs: Long,
    val event: String,
    val callerApplicationId: String? = null,
    val leaseId: String? = null,
    val operationId: String? = null,
    val payloadDigest: String? = null,
)
