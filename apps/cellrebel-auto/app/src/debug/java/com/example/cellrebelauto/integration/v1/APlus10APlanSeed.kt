package com.example.cellrebelauto.integration.v1

import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import org.json.JSONObject

/**
 * P10DBG-COLLECTOR-V1 — pure seed logic for the Auto side of the G2 §5A
 * 10-address block (fixture FX-G2-10A, docs/acceptance/a-plus-10a-fixture.json).
 *
 * WHAT AUTO CONSUMES, AND WHY COORDINATES ARE IN IT
 * -------------------------------------------------
 * The A-block trust predicate (environment.TrustPolicy) compares the provider's
 * observed effective coordinates against the Auto-side `task.latitude/longitude`.
 * That target is Auto's OWN durable value — the fixture's `autoSideConsumes`
 * line ("coordinates never cross the boundary") is about the v1 WIRE (KB-8:
 * apply passes profileRef/scheduleRef, never raw coordinates), not about what
 * Auto stores as its trust target. So this seeder consumes coordinates; a seed
 * without them false-reds all 10 journeys on the haversine leg.
 *
 * ATTRIBUTION
 * -----------
 * [LocationTask] has no journeyCaseId column. The seed report is therefore the
 * ONLY record binding an executed task back to a fixture journey. It emits the
 * fixtureIndex ↔ taskId ↔ journeyCaseId ↔ requiredSuccesses map so a run
 * outcome is attributable; csvRow follows fixtureIndex so execution order
 * (priority, csvRow) equals fixture order, which is what keeps Auto task[i]
 * aligned with provider schedule item profile-(i+1).
 *
 * src/debug ONLY — production carries none of this.
 */
object APlus10APlanSeed {

    const val MARKER = "P10DBG-COLLECTOR-V1"
    const val EXPECTED_FIXTURE_ID = "FX-G2-10A"
    const val SOURCE_NAME = "FX-G2-10A"
    const val DEFAULT_GLOBAL_BUFFER_SECONDS = 60

    /** The fields Auto consumes: the wire-safe three plus the trust-target coordinates. */
    data class FixtureItem(
        val fixtureIndex: Int,
        val journeyCaseId: String,
        val expectedScheduleItemId: String,
        val requiredSuccesses: Int,
        val latitude: Double,
        val longitude: Double,
    )

    fun parsePayload(json: String): List<FixtureItem> {
        val root = JSONObject(json)
        val fixtureId = root.optString("fixtureId", "")
        require(fixtureId == EXPECTED_FIXTURE_ID) {
            "payload fixtureId must be '$EXPECTED_FIXTURE_ID', got '$fixtureId'"
        }
        val itemsArr = root.optJSONArray("items")
        require(itemsArr != null && itemsArr.length() > 0) {
            "payload has no items — not a seedable fixture"
        }
        return (0 until itemsArr.length()).map { i ->
            val o = itemsArr.getJSONObject(i)
            FixtureItem(
                fixtureIndex = o.getInt("fixtureIndex"),
                journeyCaseId = o.getString("journeyCaseId"),
                expectedScheduleItemId = o.getString("expectedScheduleItemId"),
                requiredSuccesses = o.getInt("requiredSuccesses"),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
            )
        }
    }

    /**
     * The plan header. `totalRequiredSuccesses` is the SUM of per-item quotas
     * (17 for the frozen fixture) — a mismatch against the fixture's declared
     * total would mean the seed and the fixture disagree on the A-block goal.
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
     * fixtureIndex so the engine's (priority ASC, csvRow ASC) execution order is
     * exactly the fixture order.
     */
    fun toTasks(items: List<FixtureItem>): List<LocationTask> =
        items.map { item ->
            LocationTask(
                planId = 0L,
                csvRow = item.fixtureIndex,
                longitude = item.longitude,
                latitude = item.latitude,
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
