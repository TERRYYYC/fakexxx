package name.caiyao.fakegps.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest

/**
 * The published transport payload, read back.
 *
 * This is the READ side of what [ConfigPrefsSync] writes — the exact bytes the hook consumes from
 * the world-readable prefs file. The verify screen reconciles against THIS rather than the DB row
 * on purpose: a DB read would only prove the editor saved something, whereas the defect that
 * actually shipped was the payload carrying 23 of the profile table's 87 columns, so the DB and the
 * hook disagreed while every screen looked correct.
 *
 * Parsed with kotlinx.serialization rather than org.json because the latter is a stub in JVM unit
 * tests, and this contract is worth testing without a device.
 */
data class PublishedConfig(
    val schemaVersion: Int,
    val mode: String,
    val fields: Map<String, String>,
    /** Orthogonal to global spoof mode: chooses only how location reaches the target app. */
    val locationDeliveryMode: String = DEFAULT_LOCATION_DELIVERY_MODE,
    /** Optional in schema v3 so already-published payloads keep the 30-second default. */
    val refreshIntervalSec: Int? = null,
    val unavailable: Set<String> = emptySet(),
    /**
     * Whether the payload carried a `fields` object at all.
     *
     * Absent is NOT the same as empty. MainHook treats a v3-valid payload with no `fields` as
     * structurally incomplete and keeps its last-known-good Snapshot — it goes on spoofing from a
     * config this payload does not describe. Collapsing the two would make the UI announce
     * "没有配置、全部透传" while the hook is actively spoofing.
     */
    val fieldsPresent: Boolean = true,
    val unavailablePresent: Boolean = true,
    val activeHourStart: Int? = null,
    val activeHourEnd: Int? = null,
) {
    companion object {
        /** No `schemaVersion` key at all — an older or corrupt payload, never assumed compatible. */
        const val SCHEMA_UNKNOWN = -1

        /** Mirrors the hook's own fallback in MainHook#loadSnapshot (`optString("mode","always_on")`). */
        private const val DEFAULT_MODE = "always_on"
        private const val DEFAULT_LOCATION_DELIVERY_MODE = "hook"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * Parse a payload, or return null if it cannot be read at all.
         *
         * Null means "we cannot tell what the hook is running on", which the UI must show as an
         * error state — distinct from a valid payload that simply configures nothing.
         */
        fun parse(text: String?): PublishedConfig? {
            if (text.isNullOrBlank()) return null
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null

            val fieldsObject = root["fields"] as? JsonObject
            val fields = mutableMapOf<String, String>()
            fieldsObject?.forEach { (key, value) ->
                // Only scalars are spoofable; a nested object/array has no field row to compare
                // against and would render as noise.
                (value as? JsonPrimitive)?.let { fields[key] = it.content }
            }

            val hours = root["activeHours"] as? JsonObject
            val unavailableArray = root["unavailable"] as? JsonArray
            val unavailable = if (unavailableArray == null) {
                emptySet()
            } else {
                val names = mutableListOf<String>()
                for (element in unavailableArray) {
                    val primitive = element as? JsonPrimitive ?: return null
                    if (!primitive.isString) return null
                    names += primitive.content
                }
                runCatching {
                    UnavailablePayloadContract.validate(fields.keys, names).asSet()
                }.getOrNull() ?: return null
            }

            return PublishedConfig(
                schemaVersion = root["schemaVersion"]?.intOrNull() ?: SCHEMA_UNKNOWN,
                mode = (root["mode"] as? JsonPrimitive)?.content ?: DEFAULT_MODE,
                fields = fields,
                locationDeliveryMode =
                    (root["locationDeliveryMode"] as? JsonPrimitive)?.content
                        ?: DEFAULT_LOCATION_DELIVERY_MODE,
                refreshIntervalSec = root["refreshIntervalSec"]?.intOrNull(),
                unavailable = unavailable,
                fieldsPresent = fieldsObject != null,
                unavailablePresent = unavailableArray != null,
                activeHourStart = hours?.get("start")?.intOrNull(),
                activeHourEnd = hours?.get("end")?.intOrNull(),
            )
        }

        /**
         * SHA-256 of the raw payload, truncated to 16 hex chars.
         *
         * Must stay byte-identical to [ConfigPrefsSync]'s own fingerprint so the value shown in the
         * UI can be compared against the one in logcat — that is what makes config provenance
         * checkable across UI / hook log / probe instead of taken on faith.
         */
        fun fingerprint(payload: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            return "sha256:" + d.joinToString("") { "%02x".format(it) }.take(16)
        }

        private fun kotlinx.serialization.json.JsonElement.intOrNull(): Int? =
            (this as? JsonPrimitive)?.content?.toIntOrNull()
    }
}
