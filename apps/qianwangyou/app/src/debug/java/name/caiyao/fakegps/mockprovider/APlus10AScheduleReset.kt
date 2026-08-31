package name.caiyao.fakegps.mockprovider

import org.json.JSONArray

/**
 * P10DBG-COLLECTOR-V1 — PR #62 R3 P1-2 + R4 P1-2: monotonic, owner-quiescent
 * schedule reset for the §5A seed.
 *
 * WHY NOT `clear()` (R3)
 * ----------------------
 * A wholesale clear lets the next boot re-Initialize at scheduleVersion 1 —
 * a rollback violating M-AD-24 / spec L1895-2056 (every reinit advances
 * V → V+1); old (schedule, item, version) identities then collide with the
 * new run.
 *
 * WHY QUIESCENCE + PRIOR-STATE CLASSIFICATION (R4)
 * ------------------------------------------------
 * The seed writes the production store from OUTSIDE
 * EnvironmentControlHandler.withOwnerFence, so it may only run when the
 * owner PROVABLY cannot write concurrently:
 *
 *  - [quiescenceMismatch] refuses while the owner service is running (a live
 *    handler can reinit at construction or advance on completeAndAdvance —
 *    the R4 counterexample: seed reads V7, owner advances to V8, seed writes
 *    its own V8 and the readback matches) or while an IN-FLIGHT lease
 *    (ACQUIRING/ACTIVE/RELEASING) exists (advancePointer requires one).
 *    At-rest lease states (RELEASED/EXPIRED/REVOKED/RELEASE_INCOMPLETE)
 *    cannot drive a schedule write. The seeder brackets the write with this
 *    check BEFORE and AFTER; a fence that went live mid-seed fails the seed.
 *  - [classifyPriorState] refuses PARTIAL stores: an absent version key with
 *    surviving scheduleId/items/pointer was previously defaulted to 0 and
 *    laundered into a fresh version-1 generation. Prior state must be
 *    all-absent (Pristine → V=1) or all-present with a readable version
 *    (Complete → V+1); anything else is corrupt and fail-closed.
 *
 * On the next provider boot, initFromProfileIds sees the same item set with
 * a present scheduleId and NoOps — preserving the V+1 generation (the
 * monotonic write and the production policy compose, not fight).
 *
 * Key literals are duplicated from QwyScheduleStore's private constants;
 * drift is pinned by P10CollectorSurfaceGuardTest.
 *
 * src/debug ONLY — production carries none of this.
 */
object APlus10AScheduleReset {

    const val MARKER = "P10DBG-COLLECTOR-V1"

    /** QwyScheduleStore's private PREFS_NAME + keys (drift-guarded). */
    const val PREFS_NAME = "qwy_schedule_v1"
    const val KEY_SCHEDULE_ID = "scheduleId"
    const val KEY_SCHEDULE_VERSION = "scheduleVersion"
    const val KEY_CURRENT_ITEM_ID = "currentItemId"
    const val KEY_ITEM_IDS = "itemIds"
    const val KEY_EXHAUSTED = "exhausted"
    const val KEY_ADVANCE_COUNT = "advanceCount"

    /** The six keys that constitute one complete generation record. */
    val GENERATION_KEYS: List<String> = listOf(
        KEY_SCHEDULE_ID, KEY_SCHEDULE_VERSION, KEY_CURRENT_ITEM_ID,
        KEY_ITEM_IDS, KEY_EXHAUSTED, KEY_ADVANCE_COUNT,
    )

    /** Last-applied projection keys — stale run residue removed by the same commit. */
    const val KEY_LAST_APPLIED_LAT = "lastAppliedLat"
    const val KEY_LAST_APPLIED_LNG = "lastAppliedLng"
    const val KEY_LAST_APPLIED_AT = "lastAppliedAtMs"
    const val KEY_LAST_APPLIED_VERIFIED = "lastAppliedVerified"

    const val SCHEDULE_ID = "qwy-default-schedule"

    /** Production owner service FQCN (drift-guarded by the surface guard). */
    const val OWNER_SERVICE_FQCN = "name.caiyao.fakegps.integration.v1.EnvironmentControlService"

    /** Classified pre-state of the schedule store. */
    sealed interface PriorState {
        /** No generation key present at all — a genuinely fresh store. */
        data object Pristine : PriorState

        /** All six keys present with a readable version — a complete prior generation. */
        data class Complete(val version: Long) : PriorState

        /** Some keys present, some absent (or version unreadable) — corrupt. */
        data class Partial(val missingKeys: List<String>) : PriorState
    }

    /**
     * Classify what is durably in the store. `storedVersion` is null when the
     * version key is absent OR its value could not be read as a long — both
     * make an otherwise-present store Partial (corrupt), never "version 0".
     */
    fun classifyPriorState(presentKeys: Set<String>, storedVersion: Long?): PriorState {
        if (presentKeys.isEmpty()) return PriorState.Pristine
        val missing = GENERATION_KEYS.filter { it !in presentKeys }
        if (missing.isNotEmpty()) return PriorState.Partial(missingKeys = missing)
        if (storedVersion == null) {
            return PriorState.Partial(missingKeys = listOf(KEY_SCHEDULE_VERSION))
        }
        return PriorState.Complete(storedVersion)
    }

    /**
     * Owner-fence quiescence (R4 P1-2; R5 hardened). Returns null iff the
     * production owner PROVABLY cannot write the schedule store concurrently
     * AND no committed-but-unsettled advance can replay onto the new seed.
     *
     * R5 changes:
     *  - `ownerServiceRunning` is THREE-state: null = liveness UNKNOWN (the
     *    ActivityManager probe failed) and refuses — unknown was previously
     *    fail-open.
     *  - `advancePendingPresent`: a non-empty durable ADVANCE_PENDING slot is
     *    a committed advance whose external mutation has not finished; the
     *    next fenced entry / owner boot REPLAYS it — over a fresh seed too.
     *  - Only a CONVERGED lease state (absent or RELEASED) passes. REVOKED /
     *    RELEASE_INCOMPLETE / EXPIRED still block new applies, reference the
     *    OLD generation's identities, and are mutated by boot recovery —
     *    seeding over them is refused (previously waved through as "at rest").
     */
    fun quiescenceMismatch(
        blockingLeaseState: String?,
        ownerServiceRunning: Boolean?,
        advancePendingPresent: Boolean,
    ): String? {
        when (ownerServiceRunning) {
            null -> return "owner service liveness is unknown (probe failed) — fail closed, " +
                "never assume quiescence from a failed check"
            true -> return "owner service is running ($OWNER_SERVICE_FQCN) — it can reinit/advance the " +
                "schedule concurrently; force-stop and seed in a fresh process"
            false -> { /* provably down */ }
        }
        if (advancePendingPresent) {
            return "durable ADVANCE_PENDING slot is non-empty — a committed advance would replay " +
                "onto the new seed at the next fenced entry/boot; let the owner settle it first"
        }
        if (blockingLeaseState != null && blockingLeaseState !in CONVERGED_LEASE_STATES) {
            return "lease state $blockingLeaseState is not converged (only absent or RELEASED is " +
                "seedable) — it blocks applies, references the old generation, and boot recovery mutates it"
        }
        return null
    }

    private val CONVERGED_LEASE_STATES = setOf("RELEASED")

    /**
     * R5 P2 overflow runway: a full A-block run performs up to 10 advances
     * AFTER the seed's own +1 — the whole runway must fit without wrapping.
     */
    const val POST_SEED_HOP_RUNWAY = 10L

    /** The full generation the seeder writes and must read back verbatim. */
    data class ResetPlan(
        val scheduleId: String,
        val scheduleVersion: Long,
        val itemIds: List<String>,
        val currentItemId: String,
        val exhausted: Boolean,
        val advanceCount: Long,
    )

    /**
     * Compute the new generation from a CLASSIFIED prior state:
     * Pristine → 1; Complete(v) → v+1 (strictly monotonic, overflow
     * fail-closed); Partial → refused, never laundered.
     */
    fun plan(prior: PriorState, itemIds: List<String>): ResetPlan {
        require(itemIds.isNotEmpty()) { "a reset to zero items is not a seedable schedule" }
        val version = when (prior) {
            is PriorState.Pristine -> 1L
            is PriorState.Complete -> {
                require(prior.version >= 1L) {
                    "a COMPLETE store must carry version >= 1, got ${prior.version} — corrupt, refusing"
                }
                // R5 P2: reserve the WHOLE post-seed runway (+1 for this seed,
                // +POST_SEED_HOP_RUNWAY for the run's advances) — not just +1.
                require(prior.version <= Long.MAX_VALUE - 1 - POST_SEED_HOP_RUNWAY) {
                    "stored version ${prior.version} leaves no 10-hop overflow runway " +
                        "(need seed+${POST_SEED_HOP_RUNWAY} bumps below Long.MAX_VALUE); fail closed"
                }
                prior.version + 1
            }
            is PriorState.Partial -> throw IllegalArgumentException(
                "partial pre-state (missing: ${prior.missingKeys.joinToString(",")}) — " +
                    "refusing to launder a corrupt store into a fresh generation",
            )
        }
        return ResetPlan(
            scheduleId = SCHEDULE_ID,
            scheduleVersion = version,
            itemIds = itemIds,
            currentItemId = itemIds.first(),
            exhausted = false,
            advanceCount = 0L,
        )
    }

    /** JSON array codec — the exact shape QwyScheduleStore decodes. */
    fun encodeItemIds(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    /**
     * Compare the read-back generation against the written plan. Returns null
     * on an exact match, else the first divergent field — the seeder turns any
     * non-null into a seed FAILURE.
     */
    fun verifyReadback(read: ResetPlan?, expected: ResetPlan): String? {
        if (read == null) return "schedule store unreadable after commit — partial or failed write"
        if (read.scheduleId != expected.scheduleId) {
            return "scheduleId read '${read.scheduleId}' != written '${expected.scheduleId}'"
        }
        if (read.scheduleVersion != expected.scheduleVersion) {
            return "scheduleVersion read ${read.scheduleVersion} != written ${expected.scheduleVersion} " +
                "(a rollback or stale value survived — M-AD-24 violation)"
        }
        if (read.itemIds != expected.itemIds) {
            return "itemIds read ${read.itemIds.size} entries != written ${expected.itemIds.size} — partial item list"
        }
        if (read.currentItemId != expected.currentItemId) {
            return "currentItemId read '${read.currentItemId}' != written '${expected.currentItemId}' " +
                "(a mid-run pointer survived)"
        }
        if (read.exhausted != expected.exhausted) {
            return "exhausted read ${read.exhausted} != written ${expected.exhausted} (terminal state survived)"
        }
        if (read.advanceCount != expected.advanceCount) {
            return "advanceCount read ${read.advanceCount} != written ${expected.advanceCount}"
        }
        return null
    }
}
