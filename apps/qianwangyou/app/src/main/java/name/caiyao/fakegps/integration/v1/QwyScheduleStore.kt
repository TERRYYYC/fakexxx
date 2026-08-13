package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Durable schedule state for qianwangyou's v1 provider (§6.7.1).
 *
 * qianwangyou owns schedule identity: scheduleId, ordered scheduleItemIds,
 * scheduleVersion, currentItemId, and the exhausted discriminator. These are
 * the fields [QwyEnvironmentController.scheduleSnapshot] returns and that
 * [EnvironmentControlHandler.completeAndAdvance] gates on.
 *
 * BACKEND: SharedPreferences with commit() (synchronous, durable). This matches
 * the synchronous handler API. Room is the right home for an operator-maintained
 * plan long-term (see FileDurableKv's comment), but the schedule state here is
 * a small fixed-shape record, not a queryable collection, and SharedPreferences
 * is the existing pattern for synchronous durable state in this app
 * (ConfigPrefsSync, MockProviderStatusStore).
 *
 * ITEM IDENTITY: scheduleItemIds are "profile-{dbId}" strings derived from the
 * existing ProfileEntity table. They are stable across reorders because the dbId
 * never changes. The order is the DB's natural insertion order (id ASC) — a
 * projection of the operator's implicit plan until an explicit schedule editor
 * lands (known boundary, tracked separately).
 */
class QwyScheduleStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "qwy_schedule_v1"
        private const val KEY_SCHEDULE_ID = "scheduleId"
        private const val KEY_SCHEDULE_VERSION = "scheduleVersion"
        private const val KEY_CURRENT_ITEM_ID = "currentItemId"
        private const val KEY_ITEM_IDS = "itemIds"
        private const val KEY_EXHAUSTED = "exhausted"
        private const val KEY_ADVANCE_COUNT = "advanceCount"
        private const val KEY_LAST_APPLIED_LAT = "lastAppliedLat"
        private const val KEY_LAST_APPLIED_LNG = "lastAppliedLng"
        private const val KEY_LAST_APPLIED_AT = "lastAppliedAtMs"

        const val DEFAULT_SCHEDULE_ID = "qwy-default-schedule"
    }

    /**
     * Initialize the schedule from a list of profile DB ids. Idempotent: if
     * the schedule already exists with the same item set, this is a no-op.
     * If the item set changed, version increments.
     */
    fun initFromProfileIds(profileDbIds: List<Long>) {
        val itemIds = profileDbIds.map { "profile-$it" }
        val existing = getItemIds()
        if (existing == itemIds) return

        val version = if (getScheduleId() == null) 1L else getScheduleVersion() + 1
        prefs.edit()
            .putString(KEY_SCHEDULE_ID, DEFAULT_SCHEDULE_ID)
            .putLong(KEY_SCHEDULE_VERSION, version)
            .putString(KEY_ITEM_IDS, encodeItemIds(itemIds))
            .putString(KEY_CURRENT_ITEM_ID, itemIds.firstOrNull() ?: "")
            .putBoolean(KEY_EXHAUSTED, false)
            .putLong(KEY_ADVANCE_COUNT, 0L)
            .commit()
    }

    fun getScheduleId(): String? =
        prefs.getString(KEY_SCHEDULE_ID, null)

    fun getScheduleVersion(): Long =
        prefs.getLong(KEY_SCHEDULE_VERSION, 0L)

    fun getCurrentItemId(): String? =
        prefs.getString(KEY_CURRENT_ITEM_ID, null)?.takeIf { it.isNotEmpty() }

    fun getItemIds(): List<String> =
        decodeItemIds(prefs.getString(KEY_ITEM_IDS, "[]") ?: "[]")

    fun isExhausted(): Boolean =
        prefs.getBoolean(KEY_EXHAUSTED, false)

    fun getAdvanceCount(): Long =
        prefs.getLong(KEY_ADVANCE_COUNT, 0L)

    /**
     * Advance the pointer to the next item. Returns the outcome: either the
     * next itemId (Advanced) or null (Exhausted — last item retained).
     *
     * Called by [QwyEnvironmentController.advancePointer] which is invoked by
     * the handler's single-commit protocol after the receipt is durable.
     */
    fun advancePointer(fromItemId: String): AdvancePointerOutcome {
        val itemIds = getItemIds()
        val idx = itemIds.indexOf(fromItemId)
        if (idx < 0) {
            throw IllegalStateException(
                "advancePointer: fromItemId=$fromItemId not in schedule items $itemIds"
            )
        }
        val newVersion = getScheduleVersion() + 1
        val newAdvanceCount = getAdvanceCount() + 1

        return if (idx == itemIds.lastIndex) {
            // M-AD-10: retain the pointer on the last item; only flip exhausted
            prefs.edit()
                .putBoolean(KEY_EXHAUSTED, true)
                .putLong(KEY_SCHEDULE_VERSION, newVersion)
                .putLong(KEY_ADVANCE_COUNT, newAdvanceCount)
                .commit()
            AdvancePointerOutcome.Exhausted(versionAfter = newVersion)
        } else {
            val toItemId = itemIds[idx + 1]
            prefs.edit()
                .putString(KEY_CURRENT_ITEM_ID, toItemId)
                .putLong(KEY_SCHEDULE_VERSION, newVersion)
                .putLong(KEY_ADVANCE_COUNT, newAdvanceCount)
                .commit()
            AdvancePointerOutcome.Advanced(toItemId = toItemId, versionAfter = newVersion)
        }
    }

    /**
     * Persist the last-applied intent coordinates (dsf P2 fix).
     *
     * The mock provider publishes intent coords, but ConfigPrefsSync publishes
     * DB active-profile coords. observeEffective must return what the mock
     * provider actually has (intent coords), so we persist them here.
     */
    fun recordLastApplied(latitude: Double, longitude: Double, elapsedRealtimeMs: Long) {
        prefs.edit()
            .putFloat(KEY_LAST_APPLIED_LAT, latitude.toFloat())
            .putFloat(KEY_LAST_APPLIED_LNG, longitude.toFloat())
            .putLong(KEY_LAST_APPLIED_AT, elapsedRealtimeMs)
            .commit()
    }

    data class LastApplied(val latitude: Double, val longitude: Double, val atMs: Long)

    fun getLastApplied(): LastApplied? {
        val atMs = prefs.getLong(KEY_LAST_APPLIED_AT, 0L)
        if (atMs == 0L) return null
        return LastApplied(
            latitude = prefs.getFloat(KEY_LAST_APPLIED_LAT, 0f).toDouble(),
            longitude = prefs.getFloat(KEY_LAST_APPLIED_LNG, 0f).toDouble(),
            atMs = atMs,
        )
    }

    private fun encodeItemIds(ids: List<String>): String {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        return arr.toString()
    }

    private fun decodeItemIds(json: String): List<String> {
        if (json.isEmpty()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
