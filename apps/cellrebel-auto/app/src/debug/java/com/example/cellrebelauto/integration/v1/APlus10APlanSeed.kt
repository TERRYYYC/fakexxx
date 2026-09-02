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
     * The frozen per-item quota VECTOR (a-plus-10a-fixture.json), in
     * fixtureIndex order. R4 P1-4: binding only the sum (17) accepts a
     * same-total redistribution (e.g. swapping items 1↔2 quotas), which starts
     * a plan whose per-address acceptance attribution is wrong. Every ordered
     * quota is bound to this vector.
     */
    val EXPECTED_QUOTA_VECTOR: List<Int> = listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2)

    /**
     * PR #62 P1-1: the registered digest of a-plus-10a-fixture.json (frozen
     * 2026-08-26; registration: a-plus-device-matrix.md). The parser's
     * structure bind covers count/order/profile-N/schedule/quota-sum but NOT
     * the per-item vector (coordinate / name / altitude / journeyCaseId), and a
     * same-total quota swap survives it. Only a byte-covering digest closes
     * those, so the seed path pins to this constant rather than trusting a
     * caller-supplied digest.
     */
    const val REGISTERED_FIXTURE_DIGEST =
        "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852"

    /**
     * PR #62 P1-1: pin BOTH the bytes-recomputed digest AND the caller-declared
     * digest to [REGISTERED_FIXTURE_DIGEST]. Digest and payload arrive from the
     * same caller, so a `computed == declared` check passes a fabricated
     * payload carrying its own recomputed digest. Pinning the recomputed digest
     * makes any byte edit fail; pinning the declared digest forbids the caller
     * from substituting its own registration.
     */
    fun requireRegisteredDigest(computedDigest: String, declaredDigest: String) {
        require(computedDigest.equals(REGISTERED_FIXTURE_DIGEST, ignoreCase = true)) {
            "seeded bytes hash to $computedDigest, not the registered fixture digest " +
                "$REGISTERED_FIXTURE_DIGEST — the payload is not the frozen fixture"
        }
        require(declaredDigest.equals(REGISTERED_FIXTURE_DIGEST, ignoreCase = true)) {
            "declared digest $declaredDigest must equal the registered fixture digest — " +
                "the caller may not substitute its own"
        }
    }

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
            // R4 P1-4: bind the EXACT ordered quota, not just >=1 — a same-total
            // redistribution (items 1↔2 swapped) otherwise passes and mis-attributes.
            require(item.requiredSuccesses == EXPECTED_QUOTA_VECTOR[i]) {
                "item ${i + 1} requiredSuccesses ${item.requiredSuccesses} != registered ${EXPECTED_QUOTA_VECTOR[i]} " +
                    "(the frozen quota vector is load-bearing — no same-total redistribution)"
            }
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
     * PR #62 R3 P2: start_run must be bound to the SEEDED FX-G2-10A plan, not
     * to any arbitrary planId. Given a loaded plan + its tasks, return null iff
     * the plan is the registered §5A topology (source name, 10 rows, quota sum
     * 17, csvRow 1..10 contiguous, per-item quotas summing to the total, and
     * every legacy coordinate column still the out-of-domain placeholder — a
     * real coordinate would mean the plan came from a CSV import, not this
     * seeder). Otherwise return a human-readable mismatch reason.
     */
    fun verifyPlanTopology(plan: LocationPlan, tasks: List<LocationTask>): String? {
        if (plan.sourceFileName != SOURCE_NAME) {
            return "plan sourceFileName '${plan.sourceFileName}' != '$SOURCE_NAME' — not the §5A seed plan"
        }
        if (plan.totalRows != EXPECTED_ITEM_COUNT) {
            return "plan totalRows ${plan.totalRows} != $EXPECTED_ITEM_COUNT"
        }
        if (plan.totalRequiredSuccesses != EXPECTED_TOTAL_REQUIRED_SUCCESSES) {
            return "plan totalRequiredSuccesses ${plan.totalRequiredSuccesses} != $EXPECTED_TOTAL_REQUIRED_SUCCESSES"
        }
        if (tasks.size != EXPECTED_ITEM_COUNT) {
            return "plan carries ${tasks.size} tasks, expected $EXPECTED_ITEM_COUNT"
        }
        val byOrder = tasks.sortedWith(compareBy({ it.priority }, { it.csvRow }))
        byOrder.forEachIndexed { i, t ->
            if (t.csvRow != i + 1) return "task at order $i has csvRow ${t.csvRow}, expected ${i + 1}"
            // R4 P1-4: bind the EXACT ordered quota, not >=1 or just the sum —
            // a same-total redistribution otherwise starts a mis-attributing plan.
            if (t.requiredSuccesses != EXPECTED_QUOTA_VECTOR[i]) {
                return "task csvRow ${t.csvRow} requiredSuccesses ${t.requiredSuccesses} != " +
                    "registered ${EXPECTED_QUOTA_VECTOR[i]} (no same-total redistribution)"
            }
            if (t.latitude != COORDINATE_PLACEHOLDER || t.longitude != COORDINATE_PLACEHOLDER) {
                return "task csvRow ${t.csvRow} carries real coordinates (${t.latitude},${t.longitude}) — " +
                    "not a KB-8 seed plan (looks like a CSV import)"
            }
        }
        return null
    }

    /**
     * R6 P1-2 — request-owned durable-start generation. startAutomation is a
     * Unit fire-and-forget and the global isRunning flag can be flipped by a
     * DIFFERENT request (or already be true while this one is silently ignored
     * as "Already running"), so acceptance must bind to a durable transition
     * this request caused: a NEW RunSession row (id > the pre-command max)
     * whose planId equals the requested plan. A stale nonterminal same-plan
     * attempt does NOT create a new session and therefore can never satisfy
     * this verdict.
     */
    sealed interface StartRunVerdict {
        data class Started(val sessionId: Long, val planId: Long) : StartRunVerdict
        data class WrongPlanSession(val sessionId: Long, val sessionPlanId: Long?, val requestedPlanId: Long) : StartRunVerdict
        data object NoNewSession : StartRunVerdict
    }

    fun startRunVerdict(
        preMaxSessionId: Long,
        latestSessionId: Long?,
        latestSessionPlanId: Long?,
        requestedPlanId: Long,
    ): StartRunVerdict {
        if (latestSessionId == null || latestSessionId <= preMaxSessionId) {
            return StartRunVerdict.NoNewSession
        }
        if (latestSessionPlanId != requestedPlanId) {
            return StartRunVerdict.WrongPlanSession(latestSessionId, latestSessionPlanId, requestedPlanId)
        }
        return StartRunVerdict.Started(latestSessionId, requestedPlanId)
    }

    /**
     * R6 P1-2 "mismatched task/session plan legs": a running attempt binds a
     * task (→ task.planId) AND a session (→ session.planId). If the two legs
     * disagree (or the session leg is null), the row is mis-attributed and
     * must be flagged loudly in cmd=state, never silently accepted.
     */
    fun planBindingMismatch(taskPlanId: Long?, sessionPlanId: Long?): String? = when {
        taskPlanId == null -> "task leg unresolvable (task missing)"
        sessionPlanId == null -> "session leg null (legacy/unbound session) vs task plan $taskPlanId"
        taskPlanId != sessionPlanId -> "task plan $taskPlanId != session plan $sessionPlanId"
        else -> null
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
