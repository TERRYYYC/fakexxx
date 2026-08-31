package name.caiyao.fakegps.mockprovider

import org.json.JSONArray

/**
 * P10DBG-COLLECTOR-V1 — PR #62 R3 P1-2: monotonic schedule reset for the
 * §5A seed.
 *
 * WHY NOT `clear()`
 * -----------------
 * The previous seed cleared qwy_schedule_v1 wholesale; the next
 * ScheduleReinitPolicy initialization then wrote scheduleVersion **1** — a
 * version ROLLBACK. Canonical spec L1895/2056 and M-AD-24 require every
 * schedule reinitialization (including a same-topology exhausted reset) to
 * advance V → V+1: after a rollback, old (schedule, item, version)
 * identities from the previous run collide with the new one, so stale CAS
 * preconditions and completion proofs become reusable across generations.
 *
 * HOW THIS COMPOSES WITH PRODUCTION (not a bypass)
 * ------------------------------------------------
 * The seeder reads the CURRENT stored version, computes this pure plan
 * (V+1, pointer=profile-1, exhausted=false, advanceCount=0), writes every
 * field in ONE atomic SharedPreferences commit, then READS BACK and fails
 * the seed on any mismatch. On the next provider boot,
 * QwyScheduleStore.initFromProfileIds sees the same item set with a present
 * scheduleId and takes ScheduleReinitPolicy's NoOp rule — PRESERVING the
 * V+1 generation. The debug write performs exactly the "new generation"
 * transition the policy's Initialize rule performs for a topology change
 * (bump + pointer reset + exhausted clear as one write, the M-AD-24
 * pairing), applied to the same-topology seed case the policy deliberately
 * refuses to touch on its own.
 *
 * Key literals are duplicated from QwyScheduleStore's private constants;
 * drift is pinned by P10CollectorSurfaceGuardTest (source scan of the
 * production file for every literal below).
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

    /** Last-applied projection keys — stale run residue removed by the same commit. */
    const val KEY_LAST_APPLIED_LAT = "lastAppliedLat"
    const val KEY_LAST_APPLIED_LNG = "lastAppliedLng"
    const val KEY_LAST_APPLIED_AT = "lastAppliedAtMs"
    const val KEY_LAST_APPLIED_VERIFIED = "lastAppliedVerified"

    const val SCHEDULE_ID = "qwy-default-schedule"

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
     * Compute the new generation: STRICTLY monotonic (`existingVersion + 1`
     * for every legal input — a fresh/absent store reads 0 and becomes 1, a
     * mid-run 7 becomes 8, an exhausted 12 becomes 13; never back to 1 over
     * existing state). Corrupt stored versions are rejected, not laundered.
     */
    fun plan(existingVersion: Long, itemIds: List<String>): ResetPlan {
        require(existingVersion >= 0L) {
            "stored scheduleVersion $existingVersion is corrupt — refusing to compute a generation over it"
        }
        require(itemIds.isNotEmpty()) { "a reset to zero items is not a seedable schedule" }
        return ResetPlan(
            scheduleId = SCHEDULE_ID,
            scheduleVersion = existingVersion + 1,
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
     * on an exact match, else a human-readable mismatch naming the first
     * divergent field — the seeder turns any non-null into a seed FAILURE
     * (a commit that "succeeded" but did not stick is a partial write).
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
