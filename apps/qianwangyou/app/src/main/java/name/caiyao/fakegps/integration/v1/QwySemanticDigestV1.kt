package name.caiyao.fakegps.integration.v1

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Canonical, total framing for the QWY-owned semantic state carried by the
 * system-server oracle. Refresh cadence and sample timestamps are deliberately
 * absent: republishing the same effective coordinates is not a semantic change.
 */
object QwySemanticDigestV1 {
    fun compute(
        ownerGeneration: Long,
        activeMode: String?,
        activeProfileRef: String?,
        schedule: ScheduleSnapshot?,
        effectiveLatitude: Double?,
        effectiveLongitude: Double?,
        projectionActive: Boolean,
        effectiveProjectionFingerprint: String? = null,
        publishedConfigDigest: String? = null,
    ): String {
        val framed = DurableFieldCodec.encode(
            listOf(
                "qwy-semantic-v1",
                ownerGeneration.toString(),
                activeMode,
                activeProfileRef,
                schedule?.scheduleId,
                schedule?.scheduleVersion?.toString(),
                schedule?.currentItemId,
                schedule?.exhausted?.toString(),
                effectiveLatitude?.toBits()?.toString(),
                effectiveLongitude?.toBits()?.toString(),
                projectionActive.toString(),
                effectiveProjectionFingerprint,
                publishedConfigDigest,
            ),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(framed.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Fingerprint of the effective hook payload. The refresh interval is removed
 * deliberately: cadence changes publication timing, not the semantic device
 * environment whose continuity issue #66 protects. All active profile fields,
 * mode details, and delivery mode remain covered by the payload bytes.
 */
object QwyPublishedConfigSemanticV1 {
    fun digest(rawPublishedJson: String): String {
        require(rawPublishedJson.isNotBlank()) { "published config payload is blank" }
        val root = Json.parseToJsonElement(rawPublishedJson).jsonObject
        val semanticPayload = canonical(
            JsonObject(root.filterKeys { it != "refreshIntervalSec" }),
        )
        return MessageDigest.getInstance("SHA-256")
            .digest(semanticPayload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun canonical(element: JsonElement): String = when (element) {
        JsonNull -> DurableFieldCodec.encode(listOf("null"))
        is JsonPrimitive -> DurableFieldCodec.encode(
            listOf(if (element.isString) "string" else "primitive", element.content),
        )
        is JsonArray -> DurableFieldCodec.encode(
            listOf("array") + element.map(::canonical),
        )
        is JsonObject -> DurableFieldCodec.encode(
            listOf("object") + element.keys.sorted().flatMap { key ->
                listOf(key, canonical(checkNotNull(element[key])))
            },
        )
    }
}
