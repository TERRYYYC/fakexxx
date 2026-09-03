package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/** Small injectable mirror of the SharedPreferences durability boundary. */
internal interface QwySchedulePreferences {
    fun snapshot(): Map<String, Any?>
    fun commit(changes: Map<String, Any?>): Boolean
}

internal interface QwyScheduleItemCodec {
    fun encode(ids: List<String>): String
    fun decode(raw: String): List<String>
}

private object JsonQwyScheduleItemCodec : QwyScheduleItemCodec {
    override fun encode(ids: List<String>): String {
        val array = JSONArray()
        ids.forEach(array::put)
        return array.toString()
    }

    override fun decode(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map(array::getString)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

private class AndroidQwySchedulePreferences(
    private val prefs: SharedPreferences,
) : QwySchedulePreferences {
    override fun snapshot(): Map<String, Any?> = prefs.all.toMap()

    override fun commit(changes: Map<String, Any?>): Boolean {
        val editor = prefs.edit()
        changes.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                else -> error("unsupported schedule preference value ${value::class.java.name}")
            }
        }
        return editor.commit()
    }
}

internal enum class ProjectionPurpose {
    LEASE,
    POST_ADVANCE,
}

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
class QwyScheduleStore internal constructor(
    private val prefs: QwySchedulePreferences,
    private val itemCodec: QwyScheduleItemCodec = JsonQwyScheduleItemCodec,
) {

    constructor(context: Context) : this(
        AndroidQwySchedulePreferences(
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        ),
    )

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
        private const val KEY_LAST_APPLIED_VERIFIED = "lastAppliedVerified"
        private const val KEY_LAST_APPLIED_ITEM_ID = "lastAppliedItemId"
        private const val KEY_LAST_APPLIED_SCHEDULE_VERSION = "lastAppliedScheduleVersion"
        private const val KEY_LAST_APPLIED_PURPOSE = "lastAppliedPurpose"

        const val DEFAULT_SCHEDULE_ID = "qwy-default-schedule"
    }

    /**
     * Initialize the schedule from a list of profile DB ids. Decision logic
     * lives in [ScheduleReinitPolicy] (M-AD-24: a reinit that clears exhausted
     * must also bump scheduleVersion — the clear and the bump are one atomic
     * write here). Same item set → no-op; changed item set → version + 1,
     * pointer reset, exhausted cleared.
     */
    fun initFromProfileIds(profileDbIds: List<Long>) {
        val itemIds = profileDbIds.map { "profile-$it" }
        val plan = ScheduleReinitPolicy.decide(
            existing = ScheduleReinitPolicy.ExistingState(
                scheduleId = getScheduleId(),
                scheduleVersion = getScheduleVersion(),
                itemIds = getItemIds(),
                exhausted = isExhausted(),
            ),
            newItemIds = itemIds,
        )
        when (plan) {
            is ScheduleReinitPolicy.ReinitPlan.NoOp -> return
            is ScheduleReinitPolicy.ReinitPlan.Initialize -> {
                val changes = mapOf(
                    KEY_SCHEDULE_ID to plan.scheduleId,
                    KEY_SCHEDULE_VERSION to plan.scheduleVersion,
                    KEY_ITEM_IDS to itemCodec.encode(plan.itemIds),
                    KEY_CURRENT_ITEM_ID to (plan.currentItemId ?: ""),
                    KEY_EXHAUSTED to plan.exhausted,
                    KEY_ADVANCE_COUNT to plan.advanceCount,
                )
                commitOrThrow("schedule initialization", changes)
                val readback = readPersistentScheduleState()
                check(
                    readback.scheduleId == plan.scheduleId &&
                        readback.scheduleVersion == plan.scheduleVersion &&
                        readback.itemIds == plan.itemIds &&
                        readback.currentItemId == plan.currentItemId &&
                        readback.exhausted == plan.exhausted &&
                        readback.advanceCount == plan.advanceCount
                ) { "schedule initialization readback diverged" }
            }
        }
    }

    fun getScheduleId(): String? =
        prefs.snapshot()[KEY_SCHEDULE_ID] as? String

    fun getScheduleVersion(): Long =
        prefs.snapshot()[KEY_SCHEDULE_VERSION] as? Long ?: 0L

    fun getCurrentItemId(): String? =
        (prefs.snapshot()[KEY_CURRENT_ITEM_ID] as? String)?.takeIf { it.isNotEmpty() }

    fun getItemIds(): List<String> =
        itemCodec.decode(prefs.snapshot()[KEY_ITEM_IDS] as? String ?: "[]")

    fun isExhausted(): Boolean =
        prefs.snapshot()[KEY_EXHAUSTED] as? Boolean ?: false

    fun getAdvanceCount(): Long =
        prefs.snapshot()[KEY_ADVANCE_COUNT] as? Long ?: 0L

    internal fun readScheduleState(): ScheduleSnapshot? {
        val state = readPersistentScheduleState()
        val scheduleId = state.scheduleId ?: return null
        return ScheduleSnapshot(
            scheduleId = scheduleId,
            scheduleVersion = state.scheduleVersion,
            currentItemId = state.currentItemId,
            itemIds = state.itemIds,
            exhausted = state.exhausted,
        )
    }

    /**
     * Advance the pointer to the next item. Returns the outcome: either the
     * next itemId (Advanced) or null (Exhausted — last item retained).
     *
     * Called by [convergeAdvance] which is invoked by the handler's
     * single-commit protocol after the receipt is durable.
     */
    fun advancePointer(fromItemId: String): AdvancePointerOutcome {
        val before = readPersistentScheduleState()
        val itemIds = before.itemIds
        val idx = itemIds.indexOf(fromItemId)
        if (idx < 0) {
            throw IllegalStateException(
                "advancePointer: fromItemId=$fromItemId not in schedule items $itemIds"
            )
        }
        check(!before.exhausted && before.currentItemId == fromItemId) {
            "advancePointer precondition diverged: current=${before.currentItemId} exhausted=${before.exhausted}"
        }
        val newVersion = before.scheduleVersion + 1
        val newAdvanceCount = before.advanceCount + 1

        return if (idx == itemIds.lastIndex) {
            // M-AD-10: retain the pointer on the last item; only flip exhausted
            commitOrThrow(
                "terminal schedule advance",
                mapOf(
                    KEY_EXHAUSTED to true,
                    KEY_SCHEDULE_VERSION to newVersion,
                    KEY_ADVANCE_COUNT to newAdvanceCount,
                ),
            )
            assertScheduleMutationReadback(
                expectedItemId = fromItemId,
                expectedVersion = newVersion,
                expectedExhausted = true,
                expectedAdvanceCount = newAdvanceCount,
            )
            AdvancePointerOutcome.Exhausted(versionAfter = newVersion)
        } else {
            val toItemId = itemIds[idx + 1]
            commitOrThrow(
                "schedule advance",
                mapOf(
                    KEY_CURRENT_ITEM_ID to toItemId,
                    KEY_SCHEDULE_VERSION to newVersion,
                    KEY_ADVANCE_COUNT to newAdvanceCount,
                ),
            )
            assertScheduleMutationReadback(
                expectedItemId = toItemId,
                expectedVersion = newVersion,
                expectedExhausted = false,
                expectedAdvanceCount = newAdvanceCount,
            )
            AdvancePointerOutcome.Advanced(toItemId = toItemId, versionAfter = newVersion)
        }
    }

    /**
     * Idempotent schedule half of post-release advance convergence.
     *
     * A process may die after [advancePointer] commits but before the new
     * framework projection is verified. Recovery presents the receipt's exact
     * `(from, to, versionAfter)` tuple: an already matching pointer is accepted
     * without a second version bump, while every other divergence fails loud.
     */
    fun convergeAdvance(
        fromItemId: String,
        expectedToItemId: String?,
        expectedVersionAfter: Long,
    ): AdvancePointerOutcome {
        val state = readPersistentScheduleState()
        val itemIds = state.itemIds
        val fromIndex = itemIds.indexOf(fromItemId)
        check(fromIndex >= 0) {
            "convergeAdvance: fromItemId=$fromItemId not in schedule items $itemIds"
        }
        val actualNextItemId = itemIds.getOrNull(fromIndex + 1)
        // Validate the committed receipt tuple before any external mutation.
        // A divergent expected target must never move the schedule and only
        // then fail its integrity check.
        check(actualNextItemId == expectedToItemId) {
            "advance target diverged: expected=$expectedToItemId actual=$actualNextItemId"
        }
        val currentItemId = state.currentItemId
        val currentVersion = state.scheduleVersion
        val exhausted = state.exhausted
        val alreadyConverged = if (expectedToItemId == null) {
            exhausted && currentItemId == fromItemId
        } else {
            !exhausted && currentItemId == expectedToItemId
        }
        if (alreadyConverged) {
            check(currentVersion == expectedVersionAfter) {
                "advanced pointer version diverged: expected=$expectedVersionAfter actual=$currentVersion"
            }
            return if (expectedToItemId == null) {
                AdvancePointerOutcome.Exhausted(currentVersion)
            } else {
                AdvancePointerOutcome.Advanced(expectedToItemId, currentVersion)
            }
        }

        check(!exhausted && currentItemId == fromItemId) {
            "cannot converge advance from=$fromItemId current=$currentItemId exhausted=$exhausted"
        }
        check(currentVersion + 1L == expectedVersionAfter) {
            "advance version precondition diverged: expectedAfter=$expectedVersionAfter current=$currentVersion"
        }
        val outcome = advancePointer(fromItemId)
        val (actualToItemId, actualVersionAfter) = when (outcome) {
            is AdvancePointerOutcome.Advanced -> outcome.toItemId to outcome.versionAfter
            is AdvancePointerOutcome.Exhausted -> null to outcome.versionAfter
        }
        check(actualToItemId == expectedToItemId && actualVersionAfter == expectedVersionAfter) {
            "advance outcome diverged: expected=($expectedToItemId,$expectedVersionAfter) " +
                "actual=($actualToItemId,$actualVersionAfter)"
        }
        return outcome
    }

    /**
     * Persist the last apply COMMAND for audit plus its pre-publish monotonic
     * freshness anchor.
     *
     * This is deliberately not an effective-environment observation. The
     * desired coordinates must never be replayed as actual coordinates;
     * [QwyEnvironmentController.observeEffective] reads the OS provider state.
     * `transportPublished` only records ConfigPrefsSync's command-side outcome.
     */
    internal fun recordLastApplied(
        latitude: Double,
        longitude: Double,
        publishNotBeforeElapsedRealtimeMs: Long,
        transportPublished: Boolean,
        scheduleItemId: String,
        scheduleVersion: Long,
        purpose: ProjectionPurpose,
    ) {
        require(scheduleItemId.isNotBlank()) { "scheduleItemId must not be blank" }
        require(scheduleVersion > 0L) { "scheduleVersion must be positive" }
        require(publishNotBeforeElapsedRealtimeMs > 0L) {
            "publishNotBeforeElapsedRealtimeMs must be positive"
        }
        val expected = LastApplied(
            latitude = latitude.toFloat().toDouble(),
            longitude = longitude.toFloat().toDouble(),
            publishNotBeforeElapsedRealtimeMs = publishNotBeforeElapsedRealtimeMs,
            transportPublished = transportPublished,
            scheduleItemId = scheduleItemId,
            scheduleVersion = scheduleVersion,
            purpose = purpose,
        )
        commitOrThrow(
            "projection anchor",
            mapOf(
                KEY_LAST_APPLIED_LAT to latitude.toFloat(),
                KEY_LAST_APPLIED_LNG to longitude.toFloat(),
                KEY_LAST_APPLIED_AT to publishNotBeforeElapsedRealtimeMs,
                KEY_LAST_APPLIED_VERIFIED to transportPublished,
                KEY_LAST_APPLIED_ITEM_ID to scheduleItemId,
                KEY_LAST_APPLIED_SCHEDULE_VERSION to scheduleVersion,
                KEY_LAST_APPLIED_PURPOSE to purpose.name,
            ),
        )
        check(getLastApplied() == expected) { "projection anchor readback diverged" }
    }

    /** Clear last-applied state on cleanup/release (P2-2 fix). */
    fun clearLastApplied() {
        commitOrThrow(
            "projection anchor clear",
            mapOf(
                KEY_LAST_APPLIED_LAT to null,
                KEY_LAST_APPLIED_LNG to null,
                KEY_LAST_APPLIED_AT to null,
                KEY_LAST_APPLIED_VERIFIED to null,
                KEY_LAST_APPLIED_ITEM_ID to null,
                KEY_LAST_APPLIED_SCHEDULE_VERSION to null,
                KEY_LAST_APPLIED_PURPOSE to null,
            ),
        )
        check(getLastApplied() == null) { "projection anchor clear readback diverged" }
    }

    internal data class LastApplied(
        /** Desired command coordinate; audit only, never effective readback. */
        val latitude: Double,
        /** Desired command coordinate; audit only, never effective readback. */
        val longitude: Double,
        val publishNotBeforeElapsedRealtimeMs: Long,
        val transportPublished: Boolean,
        val scheduleItemId: String,
        val scheduleVersion: Long,
        val purpose: ProjectionPurpose,
    )

    internal fun getLastApplied(): LastApplied? {
        val snapshot = prefs.snapshot()
        val atMs = snapshot[KEY_LAST_APPLIED_AT] as? Long ?: 0L
        if (atMs == 0L) return null
        val latitude = snapshot[KEY_LAST_APPLIED_LAT] as? Float ?: return null
        val longitude = snapshot[KEY_LAST_APPLIED_LNG] as? Float ?: return null
        val transportPublished = snapshot[KEY_LAST_APPLIED_VERIFIED] as? Boolean ?: return null
        val itemId = (snapshot[KEY_LAST_APPLIED_ITEM_ID] as? String)
            ?.takeIf { it.isNotBlank() } ?: return null
        val scheduleVersion = snapshot[KEY_LAST_APPLIED_SCHEDULE_VERSION] as? Long
            ?: return null
        val purpose = (snapshot[KEY_LAST_APPLIED_PURPOSE] as? String)
            ?.let { runCatching { ProjectionPurpose.valueOf(it) }.getOrNull() }
            ?: return null
        return LastApplied(
            latitude = latitude.toDouble(),
            longitude = longitude.toDouble(),
            publishNotBeforeElapsedRealtimeMs = atMs,
            transportPublished = transportPublished,
            scheduleItemId = itemId,
            scheduleVersion = scheduleVersion,
            purpose = purpose,
        )
    }

    internal fun postAdvanceProjectionFor(schedule: ScheduleSnapshot): LastApplied? =
        getLastApplied()?.takeIf { projection ->
            projection.purpose == ProjectionPurpose.POST_ADVANCE &&
                projection.scheduleItemId == schedule.currentItemId &&
                projection.scheduleVersion == schedule.scheduleVersion
        }

    private fun commitOrThrow(operation: String, changes: Map<String, Any?>) {
        check(prefs.commit(changes)) { "$operation SharedPreferences commit failed" }
    }

    private fun assertScheduleMutationReadback(
        expectedItemId: String,
        expectedVersion: Long,
        expectedExhausted: Boolean,
        expectedAdvanceCount: Long,
    ) {
        val readback = readPersistentScheduleState()
        check(
            readback.currentItemId == expectedItemId &&
                readback.scheduleVersion == expectedVersion &&
                readback.exhausted == expectedExhausted &&
                readback.advanceCount == expectedAdvanceCount
        ) {
            "schedule mutation readback diverged: expected=" +
                "($expectedItemId,$expectedVersion,$expectedExhausted,$expectedAdvanceCount) " +
                "actual=(${readback.currentItemId},${readback.scheduleVersion}," +
                "${readback.exhausted},${readback.advanceCount})"
        }
    }

    private fun readPersistentScheduleState(): PersistentScheduleState {
        val snapshot = prefs.snapshot()
        return PersistentScheduleState(
            scheduleId = snapshot[KEY_SCHEDULE_ID] as? String,
            scheduleVersion = snapshot[KEY_SCHEDULE_VERSION] as? Long ?: 0L,
            currentItemId = (snapshot[KEY_CURRENT_ITEM_ID] as? String)
                ?.takeIf { it.isNotEmpty() },
            itemIds = itemCodec.decode(snapshot[KEY_ITEM_IDS] as? String ?: "[]"),
            exhausted = snapshot[KEY_EXHAUSTED] as? Boolean ?: false,
            advanceCount = snapshot[KEY_ADVANCE_COUNT] as? Long ?: 0L,
        )
    }

    private data class PersistentScheduleState(
        val scheduleId: String?,
        val scheduleVersion: Long,
        val currentItemId: String?,
        val itemIds: List<String>,
        val exhausted: Boolean,
        val advanceCount: Long,
    )

}
