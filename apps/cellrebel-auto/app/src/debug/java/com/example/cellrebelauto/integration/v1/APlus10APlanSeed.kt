package com.example.cellrebelauto.integration.v1

import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import org.json.JSONObject

/**
 * P10DBG-COLLECTOR-V1 — pure seed logic for the Auto side of the G2 §5A
 * 10-address block (fixture FX-G2-10A, docs/acceptance/a-plus-10a-fixture.json).
 *
 * WHAT AUTO CONSUMES — AND WHY COORDINATES ARE NOT IN IT (KB-8)
 * -------------------------------------------------------------
 * Canonical spec v1.62 (`KB-8` = A, operator-adjudicated) freezes coordinate
 * ownership with qianwangyou: §2.2 "地址、经纬度不再由 Auto 导入"; Auto holds
 * only `scheduleItemId`/`scheduleVersion` references, the distance comparison
 * is provider-exclusive, and Auto must never claim independent position
 * verification (KB-8 permanent `limit`). So this seeder consumes exactly the
 * wire-safe triple {order, journeyCaseId, requiredSuccesses} and REFUSES to
 * read the fixture's coordinate fields — importing them would recreate the
 * second coordinate holder that the A adjudication exists to eliminate
 * (PR #62 review P1-1).
 *
 * [LocationTask.latitude/longitude] are non-null legacy columns from the CSV
 * import flow; they are seeded with an INERT placeholder. Under the frozen
 * §6.4.1 predicate they are not consumed (the Auto-side legs are structural
 * non-null on the provider's effective coords + the identity leg). NOTE the
 * known product/spec drift: the CURRENT TrustPolicy still evaluates a
 * haversine against these columns (the "旧文" shape spec L1757 retired), so
 * until that product fix lands the A-block cannot produce trusted
 * completions from any seed — tracked as the gap⑥ product-fix prerequisite,
 * not something this seeder may paper over by smuggling coordinates in.
 *
 * ATTRIBUTION
 * -----------
 * [LocationTask] has no journeyCaseId column. The seed report is therefore the
 * ONLY record binding an executed task back to a fixture journey. It emits the
 * fixtureIndex ↔ taskId ↔ journeyCaseId ↔ requiredSuccesses map so a run
 * outcome is attributable; csvRow/priority follow fixtureIndex so execution
 * order (priority, csvRow) equals fixture order, which keeps Auto task[i]
 * aligned with provider schedule item profile-(i+1).
 *
 * src/debug ONLY — production carries none of this.
 */
object APlus10APlanSeed {

    const val MARKER = "P10DBG-COLLECTOR-V1"
    const val EXPECTED_FIXTURE_ID = "FX-G2-10A"
    const val SOURCE_NAME = "FX-G2-10A"
    const val DEFAULT_GLOBAL_BUFFER_SECONDS = 60

    // Registered-fixture structure (frozen 2026-08-26; registration:
    // a-plus-device-matrix.md). PR #62 P1-2: digest and payload share a
    // caller, so the parser binds to the registered structure independently.
    const val EXPECTED_ITEM_COUNT = 10
    const val EXPECTED_TOTAL_REQUIRED_SUCCESSES = 17
    const val EXPECTED_SCHEDULE_ID = "qwy-default-schedule"
    const val SCHEDULE_ITEM_PREFIX = "profile-"

    /**
     * KB-8 sentinel for the legacy non-null coordinate columns.
     *
     * 999.0 is OUTSIDE the legal geographic domain on both axes
     * (lat ∈ [-90,90], lng ∈ [-180,180]) — structurally impossible to mistake
     * for a real target (dispatch hard constraint: the placeholder must not
     * LOOK like a coordinate; 0.0/0.0 is the Gulf of Guinea and was therefore
     * rejected). Any validator in the isFiniteGeo family rejects it outright
     * instead of computing a plausible-looking distance, so code that still
     * consumes these columns (the registered TrustPolicy drift, gap⑥) fails
     * loudly on an impossible value rather than quietly on a far-away one.
     */
    const val COORDINATE_PLACEHOLDER = 999.0

    /** The wire-safe triple Auto is allowed to consume (KB-8). No coordinates. */
    data class FixtureItem(
        val fixtureIndex: Int,
        val journeyCaseId: String,
        val expectedScheduleItemId: String,
        val requiredSuccesses: Int,
    )

    fun parsePayload(json: String): List<FixtureItem> {
        val root = JSONObject(json)
        val fixtureId = root.optString("fixtureId", "")
        require(fixtureId == EXPECTED_FIXTURE_ID) {
            "payload fixtureId must be '$EXPECTED_FIXTURE_ID', got '$fixtureId'"
        }
        val scheduleId = root.optString("scheduleId", "")
        require(scheduleId == EXPECTED_SCHEDULE_ID) {
            "payload scheduleId must be '$EXPECTED_SCHEDULE_ID', got '$scheduleId'"
        }
        val itemsArr = root.optJSONArray("items")
        require(itemsArr != null && itemsArr.length() == EXPECTED_ITEM_COUNT) {
            "fixture must carry exactly $EXPECTED_ITEM_COUNT items, got ${itemsArr?.length() ?: 0}"
        }
        val items = (0 until itemsArr.length()).map { i ->
            val o = itemsArr.getJSONObject(i)
            FixtureItem(
                fixtureIndex = o.getInt("fixtureIndex"),
                journeyCaseId = o.getString("journeyCaseId"),
                expectedScheduleItemId = o.getString("expectedScheduleItemId"),
                requiredSuccesses = o.getInt("requiredSuccesses"),
            )
        }
        items.forEachIndexed { i, item ->
            require(item.fixtureIndex == i + 1) {
                "items must be in fixtureIndex order 1..$EXPECTED_ITEM_COUNT: position $i carries fixtureIndex ${item.fixtureIndex}"
            }
            require(item.expectedScheduleItemId == "$SCHEDULE_ITEM_PREFIX${i + 1}") {
                "item ${i + 1} must target $SCHEDULE_ITEM_PREFIX${i + 1}, got '${item.expectedScheduleItemId}'"
            }
            require(item.requiredSuccesses >= 1) { "item ${i + 1} requiredSuccesses must be >= 1" }
        }
        val quotaSum = items.sumOf { it.requiredSuccesses }
        val declaredTotal = root.optInt("totalRequiredSuccesses", -1)
        require(quotaSum == declaredTotal && quotaSum == EXPECTED_TOTAL_REQUIRED_SUCCESSES) {
            "quota sum $quotaSum must equal the payload's declared total ($declaredTotal) " +
                "and the registered fixture total ($EXPECTED_TOTAL_REQUIRED_SUCCESSES)"
        }
        return items
    }

    /**
     * The plan header. `totalRequiredSuccesses` is the SUM of per-item quotas
     * (17 for the frozen fixture) — a mismatch against the fixture's declared
     * total is rejected in [parsePayload].
     */
    fun toPlan(items: List<FixtureItem>, globalBufferSeconds: Int = DEFAULT_GLOBAL_BUFFER_SECONDS): LocationPlan =
        LocationPlan(
            sourceFileName = SOURCE_NAME,
            importedAt = 0L, // deterministic; the real timestamp is not load-bearing for the seed
            globalBufferSeconds = globalBufferSeconds,
            totalRows = items.size,
            totalRequiredSuccesses = items.sumOf { it.requiredSuccesses },
        )

    /**
     * The task rows. planId=0 is a placeholder — PlanDao.insertPlanWithTasks
     * reassigns it inside the transaction. csvRow AND priority both follow
     * fixtureIndex so the engine's (priority ASC, csvRow ASC) execution order
     * is exactly the fixture order. Coordinates carry the KB-8 placeholder —
     * see the class doc for why they are never taken from the fixture.
     */
    fun toTasks(items: List<FixtureItem>): List<LocationTask> =
        items.map { item ->
            LocationTask(
                planId = 0L,
                csvRow = item.fixtureIndex,
                longitude = COORDINATE_PLACEHOLDER,
                latitude = COORDINATE_PLACEHOLDER,
                priority = item.fixtureIndex,
                requiredSuccesses = item.requiredSuccesses,
            )
        }

    /**
     * Render the seed evidence block. REFUSES to render if the inserted task id
     * count differs from the fixture item count — a partial insert would make
     * the map lie about which journeys are present.
     */
    fun seedReport(
        items: List<FixtureItem>,
        planId: Long,
        taskIds: List<Long>,
        fixtureDigest: String,
    ): String {
        check(taskIds.size == items.size) {
            "inserted task id count ${taskIds.size} != fixture item count ${items.size} — partial seed"
        }
        return buildString {
            appendLine("$MARKER A+ §5A 10-address plan seed")
            appendLine("fixtureId=$EXPECTED_FIXTURE_ID fixtureDigest=$fixtureDigest planId=$planId items=${items.size}")
            appendLine("coordinates: NOT consumed (KB-8 — provider-owned); task columns carry the inert placeholder")
            appendLine("-".repeat(52))
            items.forEachIndexed { i, item ->
                appendLine(
                    "fixtureIndex=${item.fixtureIndex} taskId=${taskIds[i]} csvRow=${item.fixtureIndex} " +
                        "journeyCaseId=${item.journeyCaseId} scheduleItemId=${item.expectedScheduleItemId} " +
                        "requiredSuccesses=${item.requiredSuccesses}",
                )
            }
            appendLine("-".repeat(52))
            appendLine("SEED_OK plan=$planId ${items.size} tasks, totalRequiredSuccesses=${items.sumOf { it.requiredSuccesses }}")
        }
    }
}
