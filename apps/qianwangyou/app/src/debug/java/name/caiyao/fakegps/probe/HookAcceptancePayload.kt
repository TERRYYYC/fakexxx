package name.caiyao.fakegps.probe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.UnavailablePayloadContract

internal object HookAcceptancePayload {
    internal const val schemaVersion = ConfigPrefsSync.SCHEMA_VERSION
    private const val PUBLIC_MARKER_PREFIX = "HOOK-SESSION:"
    private val SESSION_ID = Regex("[A-Za-z0-9._-]{1,80}")

    private val ALLOWED_FIELDS = setOf(
        "mcc",
        "mnc",
        "lac",
        "cid",
        "arfcn",
        "bsic",
        "psc",
        "uarfcn",
        "tac",
        "ci",
        "pci",
        "earfcn",
        "lte_bandwidth",
        "nci",
        "nrarfcn",
        "nr_pci",
        "nr_tac",
        "gsm_rssi",
        "gsm_ber",
        "gsm_ta",
        "wcdma_rssi",
        "wcdma_rscp",
        "wcdma_ecno",
        "lte_rssi",
        "lte_rsrp",
        "lte_rsrq",
        "lte_sinr",
        "lte_cqi",
        "lte_ta",
        "nr_ss_rsrp",
        "nr_ss_rsrq",
        "nr_ss_sinr",
        "nr_csi_rsrp",
        "nr_csi_rsrq",
        "nr_csi_sinr",
        "signal_fluctuation_enabled",
        "signal_fluctuation_range_db",
        "network_type",
        "data_network_type",
        "voice_network_type",
        "operator_name",
        "operator_numeric",
        "sim_operator",
        "sim_operator_name",
        "sim_country_iso",
        "network_country_iso",
        "is_roaming",
        "phone_type",
        "service_state",
        "data_state",
        "data_activity",
        "override_network_type",
        "band",
        "channel_bandwidth",
        "cell_bandwidth_downlink",
        "physical_cell_id",
        "neighbor_cells_json",
    )

    data class Validated(
        val sessionId: String,
        val publicMarker: String,
        val json: String,
    ) {
        fun isLoadedByPublicMarker(observed: String?): Boolean =
            observed == publicMarker
    }

    fun validate(sessionId: String, rawEnvelope: String): Validated {
        require(SESSION_ID.matches(sessionId)) {
            "sessionId must contain 1-80 safe characters"
        }

        val root = Json.parseToJsonElement(rawEnvelope) as? JsonObject
            ?: throw IllegalArgumentException("acceptance payload must be an object")
        require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == schemaVersion) {
            "schemaVersion must be $schemaVersion"
        }
        require(root["mode"]?.jsonPrimitive?.content == "always_on") {
            "mode must be always_on"
        }
        require(
            (root["acceptanceSessionId"] as? JsonPrimitive)?.content == sessionId,
        ) {
            "acceptanceSessionId must match the activity session"
        }

        val fields = root["fields"] as? JsonObject
            ?: throw IllegalArgumentException("fields must be an object")
        require(fields.isNotEmpty()) { "fields must not be empty" }

        val unsupported = fields.keys.minus(ALLOWED_FIELDS).sorted()
        require(unsupported.isEmpty()) {
            "unsupported acceptance fields: ${unsupported.joinToString(",")}"
        }

        val nonScalar = fields
            .filterValues { it !is JsonPrimitive || it === JsonNull }
            .keys
            .sorted()
        require(nonScalar.isEmpty()) {
            "acceptance fields must be non-null scalars: ${nonScalar.joinToString(",")}"
        }

        val publicMarker = PUBLIC_MARKER_PREFIX + sessionId
        require(
            (fields["operator_name"] as? JsonPrimitive)?.content == publicMarker,
        ) {
            "operator_name must carry the acceptance session marker"
        }
        val unavailableArray = root["unavailable"] as? JsonArray
            ?: throw IllegalArgumentException("unavailable must be an array")
        val unavailableNames = unavailableArray.map {
            val primitive = it as? JsonPrimitive
                ?: throw IllegalArgumentException("unavailable fields must be strings")
            require(primitive.isString) { "unavailable fields must be strings" }
            primitive.content
        }
        val unavailable = UnavailablePayloadContract.validate(fields.keys, unavailableNames)

        val canonical = buildJsonObject {
            put("schemaVersion", schemaVersion)
            put("acceptanceSessionId", sessionId)
            put("mode", "always_on")
            put("fields", fields)
            put("unavailable", JsonArray(unavailable.asList().map(::JsonPrimitive)))
        }
        return Validated(
            sessionId = sessionId,
            publicMarker = publicMarker,
            json = canonical.toString(),
        )
    }
}
