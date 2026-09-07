package com.example.cellrebelauto.configbundle

import com.example.cellrebelauto.model.plan.PlanConfig
import com.example.cellrebelauto.model.plan.ParseResult
import com.example.cellrebelauto.model.plan.WorklistParser
import com.example.cellrebelauto.model.plan.WorklistRow
import org.json.JSONArray
import org.json.JSONObject

/** Export-side row shape; csvRow is assigned by the section codec (data row ordinal). */
data class WorklistRowData(
    val longitude: Double,
    val latitude: Double,
    val priority: Int,
    val requiredSuccesses: Int,
)

/** One provider pairing fingerprint — identity material for HUMAN verification only. */
data class ProviderFingerprint(
    val applicationId: String,
    val signerDigest: String,
    val approvedVersionCode: Int?,
)

/**
 * Auto section codecs for the config bundle.
 *
 * The plan section REUSES the T2 CSV semantics verbatim ([WorklistParser.HEADER] +
 * atomic parse on the way back in) — a bundle plan and a hand-written worklist are the
 * same artifact, so the untouched T2 parser is the single validation point. Plan params
 * and pairing fingerprints travel as small JSON sections.
 * # 计划区段逐字复用 T2 的 CSV 语义；参数/指纹走小 JSON 区段
 */
object AutoBundleSections {

    // ---- auto/plan.csv (T2 CSV semantics) ----

    fun encodePlanCsv(rows: List<WorklistRowData>): String = buildString {
        appendLine(WorklistParser.HEADER)
        // csvRow is the 1-based DATA row number — reconstructed from position on export
        // so a bundle plan is indistinguishable from a hand-written worklist.
        for (row in rows) {
            appendLine("${row.longitude},${row.latitude},${row.priority},${row.requiredSuccesses}")
        }
    }

    fun decodePlanCsv(text: String): ParseResult = WorklistParser.parse(text)

    // ---- auto/plan_config.json ----

    sealed interface PlanConfigResult {
        data class Ok(val config: PlanConfig, val planSourceFileName: String?) : PlanConfigResult
        data class Error(val reason: String) : PlanConfigResult
    }

    fun encodePlanConfig(config: PlanConfig, planSourceFileName: String): String {
        val root = JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("globalBufferSeconds", config.globalBufferSeconds ?: JSONObject.NULL)
            .put("testTimeoutSeconds", config.testTimeoutSeconds)
            .put("gpsSettleSeconds", config.gpsSettleSeconds)
            .put("locationStageEnabled", config.locationStageEnabled)
            .put("testStageEnabled", config.testStageEnabled)
            .put("planSourceFileName", planSourceFileName)
        return root.toString()
    }

    fun decodePlanConfig(bytes: ByteArray): PlanConfigResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        PlanConfigResult.Ok(
            config = PlanConfig(
                globalBufferSeconds = if (root.has("globalBufferSeconds") && !root.isNull("globalBufferSeconds")) {
                    root.getInt("globalBufferSeconds")
                } else {
                    null
                },
                testTimeoutSeconds = root.optInt("testTimeoutSeconds", 90),
                gpsSettleSeconds = root.optInt("gpsSettleSeconds", 60),
                locationStageEnabled = root.optBoolean("locationStageEnabled", true),
                testStageEnabled = root.optBoolean("testStageEnabled", true),
            ),
            planSourceFileName = root.optString("planSourceFileName", "").takeIf { it.isNotEmpty() },
        )
    } catch (e: Exception) {
        PlanConfigResult.Error("计划参数区段无法解码：${e.message}")
    }

    // ---- auto/pairing.json (fingerprints ONLY — the privacy red line) ----

    sealed interface PairingResult {
        data class Ok(val providers: List<ProviderFingerprint>) : PairingResult
        data class Error(val reason: String) : PairingResult
    }

    fun encodePairing(providers: List<ProviderFingerprint>): String {
        val array = JSONArray()
        for (provider in providers) {
            val json = JSONObject()
                .put("applicationId", provider.applicationId)
                .put("currentSignerDigest", provider.signerDigest)
            if (provider.approvedVersionCode != null) {
                json.put("approvedVersionCode", provider.approvedVersionCode)
            }
            array.put(json)
        }
        return JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("providers", array)
            .toString()
    }

    fun decodePairing(bytes: ByteArray): PairingResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val array = root.getJSONArray("providers")
        val providers = ArrayList<ProviderFingerprint>(array.length())
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            providers += ProviderFingerprint(
                applicationId = json.getString("applicationId"),
                signerDigest = json.getString("currentSignerDigest"),
                approvedVersionCode = if (json.has("approvedVersionCode")) json.getInt("approvedVersionCode") else null,
            )
        }
        PairingResult.Ok(providers)
    } catch (e: Exception) {
        PairingResult.Error("配对指纹区段无法解码：${e.message}")
    }

    // ---- meta/lane.json ----

    data class LaneMetadata(
        val qwyApplicationId: String?,
        val qwyVersionName: String?,
        val transportSchemaVersion: Int?,
        val autoApplicationId: String?,
        val autoVersionName: String?,
        val providerPrincipal: String?,
    )

    sealed interface LaneResult {
        data class Ok(val lane: LaneMetadata) : LaneResult
        data class Error(val reason: String) : LaneResult
    }

    fun encodeLane(lane: LaneMetadata): String {
        val qwy = JSONObject()
        if (lane.qwyApplicationId != null) qwy.put("applicationId", lane.qwyApplicationId)
        if (lane.qwyVersionName != null) qwy.put("versionName", lane.qwyVersionName)
        if (lane.transportSchemaVersion != null) qwy.put("transportSchemaVersion", lane.transportSchemaVersion)
        val auto = JSONObject()
        if (lane.autoApplicationId != null) auto.put("applicationId", lane.autoApplicationId)
        if (lane.autoVersionName != null) auto.put("versionName", lane.autoVersionName)
        if (lane.providerPrincipal != null) auto.put("providerPrincipal", lane.providerPrincipal)
        return JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("qwy", qwy)
            .put("auto", auto)
            .toString()
    }

    fun decodeLane(bytes: ByteArray): LaneResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val qwy = root.optJSONObject("qwy") ?: JSONObject()
        val auto = root.optJSONObject("auto") ?: JSONObject()
        LaneResult.Ok(
            LaneMetadata(
                qwyApplicationId = optStringOrNull(qwy, "applicationId"),
                qwyVersionName = optStringOrNull(qwy, "versionName"),
                transportSchemaVersion = if (qwy.has("transportSchemaVersion")) qwy.getInt("transportSchemaVersion") else null,
                autoApplicationId = optStringOrNull(auto, "applicationId"),
                autoVersionName = optStringOrNull(auto, "versionName"),
                providerPrincipal = optStringOrNull(auto, "providerPrincipal"),
            ),
        )
    } catch (e: Exception) {
        LaneResult.Error("车道元数据区段无法解码：${e.message}")
    }

    private fun optStringOrNull(json: JSONObject, key: String): String? {
        if (!json.has(key)) return null
        return json.optString(key, "").takeIf { it.isNotEmpty() }
    }
}
