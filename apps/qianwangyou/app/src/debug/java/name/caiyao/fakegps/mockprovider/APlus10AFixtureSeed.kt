package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.data.db.ProfileEntity
import org.json.JSONObject

/**
 * P10DBG-COLLECTOR-V1 — pure seed logic for the G2 §5A 10-address block
 * (fixture FX-G2-10A, docs/acceptance/a-plus-10a-fixture.json).
 *
 * WHY THIS OBJECT IS PURE
 * -----------------------
 * The one thing this seeder must get right is that every seeded profile row
 * carries its FIXTURE-DERIVED id, never the `@PrimaryKey(autoGenerate = true)`
 * sentinel. [ProfileEntity] autoincrements, and `deleteAll()` (`DELETE FROM
 * temp`) does NOT reset SQLite's sequence, so an implicit `id = 0` insert on a
 * device that has ever held profiles yields profile-11, profile-12, … while
 * the fixture froze profile-1..profile-10. The seed would look green and every
 * A-block journey would bind the WRONG schedule item — a silent
 * misattribution. Keeping the id derivation in a pure object means
 * "explicit id, no drift" is a JVM unit-test failure
 * (APlus10AFixtureSeedTest), not a runbook hope.
 *
 * WHY THE PAYLOAD IS THE FIXTURE FILE ITSELF
 * ------------------------------------------
 * [parsePayload] consumes the exact JSON shape of a-plus-10a-fixture.json, so
 * an executor pipes the frozen file straight in (base64) and records its
 * SHA-256 as the fixture digest. What reaches the device is byte-identical to
 * what the digest covers — there is no separate "payload schema" that could
 * drift from the fixture.
 *
 * src/debug ONLY — production carries none of this (the release-APK scan and
 * P10CollectorSurfaceGuardTest pin it).
 */
object APlus10AFixtureSeed {

    const val MARKER = "P10DBG-COLLECTOR-V1"
    const val EXPECTED_FIXTURE_ID = "FX-G2-10A"
    const val SCHEDULE_ITEM_PREFIX = "profile-"

    // Registered-fixture structure (a-plus-10a-fixture.json, frozen 2026-08-26;
    // registration: a-plus-device-matrix.md). PR #62 P1-2: the parser binds to
    // these independently of the caller-supplied digest.
    const val EXPECTED_ITEM_COUNT = 10
    const val EXPECTED_TOTAL_REQUIRED_SUCCESSES = 17
    val EXPECTED_SCHEDULE_ID: String = name.caiyao.fakegps.integration.v1.QwyScheduleStore.DEFAULT_SCHEDULE_ID

    /** One frozen fixture row — the full field set the two apps split between them. */
    data class FixtureItem(
        val fixtureIndex: Int,
        val journeyCaseId: String,
        val expectedScheduleItemId: String,
        val requiredSuccesses: Int,
        val addname: String,
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val accuracy: Double,
        val tac: Int,
        val wifiSsid: String,
    )

    /**
     * "profile-7" → 7. Rejects anything that is not `profile-<positive int>`.
     *
     * `profile-0` is rejected on purpose: 0 is the autoGenerate sentinel, so a
     * fixture id that decoded to 0 would collide with "let Room assign" — the
     * very drift this seeder exists to prevent. Leading zeros / signs / suffixes
     * are rejected so a malformed id can never silently seed the wrong row.
     */
    fun scheduleItemDbId(expectedScheduleItemId: String): Long {
        require(expectedScheduleItemId.startsWith(SCHEDULE_ITEM_PREFIX)) {
            "expectedScheduleItemId must start with '$SCHEDULE_ITEM_PREFIX': '$expectedScheduleItemId'"
        }
        val suffix = expectedScheduleItemId.removePrefix(SCHEDULE_ITEM_PREFIX)
        require(suffix.isNotEmpty() && suffix.all { it in '0'..'9' }) {
            "schedule item id suffix must be a decimal integer: '$expectedScheduleItemId'"
        }
        // Reject leading zeros ("profile-01") so the textual id and the numeric
        // id are one-to-one — otherwise two spellings could map to one row.
        require(suffix == suffix.toLong().toString()) {
            "schedule item id suffix must be canonical (no leading zeros): '$expectedScheduleItemId'"
        }
        val dbId = suffix.toLong()
        require(dbId >= 1L) { "schedule item db id must be >= 1 (0 is the autoGenerate sentinel): '$expectedScheduleItemId'" }
        return dbId
    }

    /**
     * Parse the frozen fixture JSON. Validates the envelope (fixtureId +
     * non-empty items), not just the rows, so a truncated or foreign payload
     * fails loud instead of seeding a partial schedule.
     */
    fun parsePayload(json: String): List<FixtureItem> {
        val root = JSONObject(json)
        val fixtureId = root.optString("fixtureId", "")
        require(fixtureId == EXPECTED_FIXTURE_ID) {
            "payload fixtureId must be '$EXPECTED_FIXTURE_ID', got '$fixtureId'"
        }
        // PR #62 P1-2: the digest and the payload arrive from the SAME caller,
        // so the digest alone cannot stop a hand-crafted "FX-G2-10A" with the
        // wrong shape. The parser independently binds to the REGISTERED
        // fixture's frozen structure: exactly 10 items, contiguous
        // fixtureIndex 1..10 in array order, profile-N aligned to its index,
        // the registered schedule id, and the quota sum (17, and equal to the
        // payload's own declared total). A truncated, reordered, re-quota'd or
        // re-targeted payload fails HERE, digest or no digest.
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
                addname = o.getString("addname"),
                latitude = o.getDouble("latitude"),
                longitude = o.getDouble("longitude"),
                altitude = o.getDouble("altitude"),
                accuracy = o.getDouble("accuracy"),
                tac = o.getInt("tac"),
                wifiSsid = o.getString("wifiSsid"),
            )
        }
        items.forEachIndexed { i, item ->
            require(item.fixtureIndex == i + 1) {
                "items must be in fixtureIndex order 1..$EXPECTED_ITEM_COUNT: position $i carries fixtureIndex ${item.fixtureIndex}"
            }
            require(item.expectedScheduleItemId == "$SCHEDULE_ITEM_PREFIX${i + 1}") {
                "item ${i + 1} must target ${SCHEDULE_ITEM_PREFIX}${i + 1}, got '${item.expectedScheduleItemId}'"
            }
            require(item.requiredSuccesses >= 1) {
                "item ${i + 1} requiredSuccesses must be >= 1"
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
     * Build the profile rows with EXPLICIT fixture-derived ids. Rejects
     * duplicate schedule item ids (two rows on one item is a corrupt fixture).
     * Every field the provider's apply path and the A-block trust predicate
     * read is carried verbatim from the fixture.
     */
    fun toProfileRows(items: List<FixtureItem>): List<ProfileEntity> {
        val seen = mutableSetOf<Long>()
        return items.map { item ->
            val dbId = scheduleItemDbId(item.expectedScheduleItemId)
            require(seen.add(dbId)) {
                "duplicate schedule item id ${item.expectedScheduleItemId} — two profiles cannot share one schedule item"
            }
            ProfileEntity(
                id = dbId, // EXPLICIT — never the autoGenerate sentinel
                addname = item.addname,
                latitude = item.latitude,
                longitude = item.longitude,
                altitude = item.altitude,
                accuracy = item.accuracy.toFloat(),
                tac = item.tac,
                wifiSsid = item.wifiSsid,
            )
        }
    }

    /**
     * Render the seed evidence block: the fixtureIndex ↔ seeded dbId ↔
     * journeyCaseId ↔ requiredSuccesses map an executor needs to attribute a
     * later journey outcome back to a fixture row (LocationTask carries no
     * journeyCaseId, so ordering is the only link and the map makes it
     * explicit). REFUSES to render if any inserted id diverged from the
     * explicit fixture id — a divergence is the AUTOINCREMENT drift bug and
     * must be a loud failure, never a green mapping.
     */
    fun seedReport(
        items: List<FixtureItem>,
        insertedIds: List<Long>,
        fixtureDigest: String,
    ): String {
        check(insertedIds.size == items.size) {
            "inserted id count ${insertedIds.size} != fixture item count ${items.size}"
        }
        return buildString {
            appendLine("$MARKER A+ §5A 10-address seed")
            appendLine("fixtureId=$EXPECTED_FIXTURE_ID fixtureDigest=$fixtureDigest items=${items.size}")
            appendLine("-".repeat(52))
            items.forEachIndexed { i, item ->
                val expected = scheduleItemDbId(item.expectedScheduleItemId)
                val actual = insertedIds[i]
                check(actual == expected) {
                    "inserted id $actual != explicit fixture id $expected for ${item.expectedScheduleItemId} " +
                        "— AUTOINCREMENT drift; seed is NOT byte-exact"
                }
                appendLine(
                    "fixtureIndex=${item.fixtureIndex} scheduleItemId=${item.expectedScheduleItemId} " +
                        "dbId=$actual journeyCaseId=${item.journeyCaseId} requiredSuccesses=${item.requiredSuccesses}",
                )
            }
            appendLine("-".repeat(52))
            appendLine("SEED_OK ${items.size} rows, explicit ids ${insertedIds.joinToString(",")}")
        }
    }
}
