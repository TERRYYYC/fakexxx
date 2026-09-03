package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.UnavailableFieldSet
import name.caiyao.fakegps.config.UnavailablePayloadContract
import name.caiyao.fakegps.data.db.ProfileEntity
import org.json.JSONArray
import org.json.JSONException
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

    /**
     * Frozen per-item quota VECTOR (a-plus-10a-fixture.json), fixtureIndex
     * order. R4 P1-4: bind the exact ordered quota, not just the sum — a
     * same-total redistribution otherwise seeds a mis-attributing schedule.
     */
    val EXPECTED_QUOTA_VECTOR: List<Int> = listOf(2, 1, 3, 1, 2, 1, 1, 3, 1, 2)
    val EXPECTED_SCHEDULE_ID: String = name.caiyao.fakegps.integration.v1.QwyScheduleStore.DEFAULT_SCHEDULE_ID

    /**
     * PR #62 P1-1: the registered digest of a-plus-10a-fixture.json (frozen
     * 2026-08-26; registration: a-plus-device-matrix.md). The parser's
     * structure bind above covers count/order/profile-N/schedule/quota-sum but
     * NOT the per-item vector — a same-total quota swap, a coordinate / name /
     * altitude edit, or a changed journeyCaseId all survive it. Only a
     * byte-covering digest closes those, so both runtime seed paths pin to this
     * constant instead of trusting a caller-supplied digest.
     */
    const val REGISTERED_FIXTURE_DIGEST =
        "cab16da8f7776b208a2bcf25acbd22ef9ca8e8ec9a08169d5f5f3ce3e8027852"

    /**
     * PR #62 P1-1: pin BOTH the bytes-recomputed digest AND the caller-declared
     * digest to [REGISTERED_FIXTURE_DIGEST]. The digest and the payload arrive
     * from the same adb caller, so a fabricated payload with a self-consistent
     * recomputed digest would pass a `computed == declared` check. Requiring the
     * recomputed digest to equal the registered constant makes any byte edit
     * fail; requiring the declared digest to equal it too forbids the caller
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
            // R4 P1-4: bind the EXACT ordered quota, not just >=1 — a same-total
            // redistribution otherwise seeds a mis-attributing schedule.
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

    // ------------------------------------------------------------------
    // R9 P1 (Sol) — exact canonical transport envelope
    // ------------------------------------------------------------------

    /**
     * The settings legs ConfigPrefsSync.buildFieldMapJson folds into the published
     * envelope, read from the SAME SpoofSettings the seed just wrote (under the fence):
     *  - [locationDeliveryMode]: LocationDeliveryMode.wireValue ("hook" | "system_mock");
     *  - [spoofMode]: the ROOT "mode" — the spoof schedule mode (always_on | time_based |
     *    off), NOT the delivery mode (R9: the old verifier compared root mode to "hook",
     *    so a valid canonical payload deterministically failed);
     *  - [activeHourStart]/[activeHourEnd]: always present — the settings cursor exposes
     *    non-null ints, so the writer always emits `activeHours`.
     */
    data class TransportSettingsSnapshot(
        val refreshIntervalSec: Int,
        val locationDeliveryMode: String,
        val spoofMode: String,
        val activeHourStart: Int,
        val activeHourEnd: Int,
    )

    /**
     * Pure mirror of ConfigPrefsSync.buildFieldMapJson for ONE seeded profile row.
     *
     * Root keys: schemaVersion (the writer's constant, not a literal) / refreshIntervalSec /
     * locationDeliveryMode / activeHours{start,end} / mode (spoof mode) / fields / unavailable.
     * `fields` = every NON-NULL profile column except `id` and `unavailable_fields`, keyed by
     * the DB COLUMN NAME (`wifi_ssid`, not the Kotlin property `wifiSsid`) and typed as the
     * cursor delivers it (INTEGER→long, REAL→double — a Float is widened exactly as Room
     * stores it — TEXT→string). The seeder sets exactly addname / latitude / longitude /
     * altitude / accuracy / tac / wifi_ssid; every other column is NULL and skipped by the
     * writer. Should the writer ever emit another column, the exact key-set comparison in
     * [transportEnvelopeMismatch] fails loudly rather than silently accepting it.
     * `unavailable` follows the writer verbatim: decode the row's stored set, validate it
     * against the emitted field names. Every literal here is pinned against the writer by
     * P10CollectorSurfaceGuardTest.r9_transportEnvelopeMirrorIsPinnedToTheCanonicalWriter.
     */
    fun expectedTransportEnvelope(row: ProfileEntity, settings: TransportSettingsSnapshot): JSONObject {
        val fields = JSONObject()
        row.addname?.let { fields.put("addname", it) }
        row.latitude?.let { fields.put("latitude", it) }
        row.longitude?.let { fields.put("longitude", it) }
        row.altitude?.let { fields.put("altitude", it) }
        row.accuracy?.let { fields.put("accuracy", it.toDouble()) }
        row.tac?.let { fields.put("tac", it.toLong()) }
        row.wifiSsid?.let { fields.put("wifi_ssid", it) }
        val fieldNames = buildSet {
            val keys = fields.keys()
            while (keys.hasNext()) add(keys.next())
        }
        val requested = UnavailableFieldSet.decode(row.unavailableFields).toList()
        val unavailable = UnavailablePayloadContract.validate(fieldNames, requested)
        return JSONObject()
            .put("schemaVersion", ConfigPrefsSync.SCHEMA_VERSION)
            .put("refreshIntervalSec", settings.refreshIntervalSec)
            .put("locationDeliveryMode", settings.locationDeliveryMode)
            .put("activeHours", JSONObject().put("start", settings.activeHourStart).put("end", settings.activeHourEnd))
            .put("mode", settings.spoofMode)
            .put("fields", fields)
            .put("unavailable", JSONArray(unavailable.asList()))
    }

    /**
     * Structural equality of the published transport against [expected]: same root key set,
     * same `fields` key set, every value equal (numbers compared NUMERICALLY, so a REAL 3.0
     * rendered as `3` still matches), arrays element-wise. Returns null when identical,
     * otherwise a path-qualified reason. Extra, missing, re-keyed or retyped legs are all
     * mismatches — the verifier must never accept a payload that merely CONTAINS the seeded
     * values.
     */
    fun transportEnvelopeMismatch(publishedJson: String, expected: JSONObject): String? {
        val published = try {
            JSONObject(publishedJson)
        } catch (e: JSONException) {
            return "published transport is not a JSON object: ${e.message}"
        }
        return jsonMismatch("$", published, expected)
    }

    private fun jsonMismatch(path: String, actual: Any?, expected: Any?): String? {
        when (expected) {
            is JSONObject -> {
                if (actual !is JSONObject) return "$path: expected an object, published ${describe(actual)}"
                val expectedKeys = expected.keys().asSequence().toSortedSet()
                val actualKeys = actual.keys().asSequence().toSortedSet()
                if (actualKeys != expectedKeys) {
                    return "$path: key set differs (missing=${expectedKeys - actualKeys} extra=${actualKeys - expectedKeys})"
                }
                for (key in expectedKeys) {
                    jsonMismatch("$path.$key", actual.get(key), expected.get(key))?.let { return it }
                }
                return null
            }
            is JSONArray -> {
                if (actual !is JSONArray) return "$path: expected an array, published ${describe(actual)}"
                if (actual.length() != expected.length()) {
                    return "$path: array length ${actual.length()} != expected ${expected.length()}"
                }
                for (i in 0 until expected.length()) {
                    jsonMismatch("$path[$i]", actual.get(i), expected.get(i))?.let { return it }
                }
                return null
            }
            is Number -> {
                if (actual !is Number) return "$path: expected number $expected, published ${describe(actual)}"
                if (java.math.BigDecimal(actual.toString()).compareTo(java.math.BigDecimal(expected.toString())) != 0) {
                    return "$path: published $actual != expected $expected"
                }
                return null
            }
            else -> {
                if (actual != expected) return "$path: published ${describe(actual)} != expected ${describe(expected)}"
                return null
            }
        }
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is String -> "'$value'"
        is JSONObject -> "object${value.keys().asSequence().toSortedSet()}"
        is JSONArray -> "array(len=${value.length()})"
        else -> "${value::class.java.simpleName} $value"
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
