package name.caiyao.fakegps.data.bundle

import java.security.MessageDigest
import java.util.Locale
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Content fingerprint of one profile: SHA-256 over the canonical draft (sorted columns)
 * plus the archive name — equal canonical profiles hash equally, so the active-profile
 * pointer resolves after Room id regeneration on the importing device.
 * # 档案内容指纹：跨设备、跨 id 重生成稳定，用于生效档案指针的解析
 */
object QwyProfileFingerprint {
    fun of(entity: ProfileEntity): String {
        val draft = ProfileEntityCodec.toDraft(entity).toSortedMap()
        val canonical = buildString {
            for ((column, value) in draft) {
                append(column)
                append('\u0001')
                append(value)
                append('\u0000')
            }
            append("addname")
            append('\u0001')
            append(entity.addname ?: "")
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }
}

/**
 * QWY section codecs for the config bundle.
 *
 * WHY the profiles section is a DB-export JSON (draft-map semantics) rather than the
 * ProfileArchiveParser CSV: the CSV path re-runs ProfileFieldValueValidator's per-field
 * normalization and caps (2 MiB / cell-length / row limits) at IMPORT time — a device-
 * collected profile (e.g. a long neighbor_cells_json, or values at validator edges) could
 * be REJECTED on the way back in, which defeats "档案全集迁移". The draft map travels
 * every Room column losslessly (unavailable columns as the "--" marker) and decodes via
 * the same [ProfileEntityCodec] the T2 chain already trusts; idempotency reuses the T2
 * canonical identity (ProfileImportPlanner semantics), so the T2 import/anchor chain is
 * layered onto, not modified.
 * # 档案区段选 DB 导出 JSON（draft 语义）：全列无损、导入不重跑 CSV 校验；
 * # 幂等判重复用 T2 的 canonical 身份
 */
object QwyBundleSections {

    // ---- qwy/profiles.json ----

    data class ProfileRecord(val addname: String?, val draft: Map<String, String>)

    sealed interface ProfilesResult {
        data class Ok(val profiles: List<ProfileEntity>) : ProfilesResult
        data class Error(val reason: String) : ProfilesResult
    }

    fun encodeProfiles(profiles: List<ProfileEntity>): String {
        val array = JSONArray()
        for (profile in profiles) {
            val draftJson = JSONObject()
            for ((column, value) in ProfileEntityCodec.toDraft(profile)) {
                draftJson.put(column, value)
            }
            array.put(
                JSONObject()
                    .put("addname", profile.addname ?: JSONObject.NULL)
                    .put("draft", draftJson),
            )
        }
        return JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("profiles", array)
            .toString()
    }

    fun decodeProfiles(bytes: ByteArray): ProfilesResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val array = root.getJSONArray("profiles")
        val profiles = ArrayList<ProfileEntity>(array.length())
        for (i in 0 until array.length()) {
            val record = array.getJSONObject(i)
            val draft = LinkedHashMap<String, String>()
            val draftJson = record.getJSONObject("draft")
            val columns = draftJson.keys()
            while (columns.hasNext()) {
                val column = columns.next()
                draft[column] = draftJson.getString(column)
            }
            val addname = if (record.isNull("addname")) null else record.getString("addname")
            profiles += ProfileEntityCodec.canonical(
                ProfileEntityCodec.fromDraft(draft, addname = addname),
            )
        }
        ProfilesResult.Ok(profiles)
    } catch (e: Exception) {
        ProfilesResult.Error("档案区段无法解码：${e.message}")
    }

    // ---- qwy/active_profile.json ----

    sealed interface PointerResult {
        data class Ok(val fingerprint: String, val addname: String?) : PointerResult
        data class Error(val reason: String) : PointerResult
    }

    fun encodeActiveProfile(fingerprint: String, addname: String?): String =
        JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("fingerprint", fingerprint)
            .put("addname", addname ?: JSONObject.NULL)
            .toString()

    fun decodeActiveProfile(bytes: ByteArray): PointerResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val fingerprint = root.optString("fingerprint", "")
        if (fingerprint.isBlank()) {
            PointerResult.Error("生效档案指针缺少 fingerprint")
        } else {
            PointerResult.Ok(
                fingerprint = fingerprint,
                addname = if (root.isNull("addname")) null else root.getString("addname"),
            )
        }
    } catch (e: Exception) {
        PointerResult.Error("生效档案指针无法解码：${e.message}")
    }

    // ---- content fingerprint (stable across devices and regenerated Room ids) ----
    // ---- qwy/callers.json (fingerprints ONLY — the privacy red line) ----

    data class CallerFingerprint(
        val applicationId: String,
        val signerDigest: String,
        val observedVersionCode: Long?,
    )

    sealed interface CallersResult {
        data class Ok(val callers: List<CallerFingerprint>) : CallersResult
        data class Error(val reason: String) : CallersResult
    }

    fun encodeCallers(callers: List<CallerFingerprint>): String {
        val array = JSONArray()
        for (caller in callers) {
            val json = JSONObject()
                .put("applicationId", caller.applicationId)
                .put("signerDigest", caller.signerDigest)
            if (caller.observedVersionCode != null) {
                json.put("observedVersionCode", caller.observedVersionCode)
            }
            array.put(json)
        }
        return JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("callers", array)
            .toString()
    }

    fun decodeCallers(bytes: ByteArray): CallersResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val array = root.getJSONArray("callers")
        val callers = ArrayList<CallerFingerprint>(array.length())
        for (i in 0 until array.length()) {
            val json = array.getJSONObject(i)
            callers += CallerFingerprint(
                applicationId = json.getString("applicationId"),
                signerDigest = json.getString("signerDigest"),
                observedVersionCode = if (json.has("observedVersionCode")) json.getLong("observedVersionCode") else null,
            )
        }
        CallersResult.Ok(callers)
    } catch (e: Exception) {
        CallersResult.Error("调用方指纹区段无法解码：${e.message}")
    }

    // ---- qwy/settings.json (lane config: hook-facing settings + module switches) ----

    data class SettingsSnapshot(
        val spoofMode: String,
        val activeHourStart: Int,
        val activeHourEnd: Int,
        val refreshIntervalSec: Int,
        val locationDeliveryMode: String,
        val modules: Map<String, Boolean>,
    )

    sealed interface SettingsResult {
        data class Ok(val settings: SettingsSnapshot) : SettingsResult
        data class Error(val reason: String) : SettingsResult
    }

    fun encodeSettings(settings: SettingsSnapshot): String {
        val modules = JSONObject()
        for ((name, enabled) in settings.modules) modules.put(name, enabled)
        return JSONObject()
            .put("schemaVersion", ConfigBundleContract.BUNDLE_SCHEMA_VERSION)
            .put("spoofMode", settings.spoofMode)
            .put("activeHourStart", settings.activeHourStart)
            .put("activeHourEnd", settings.activeHourEnd)
            .put("refreshIntervalSec", settings.refreshIntervalSec)
            .put("locationDeliveryMode", settings.locationDeliveryMode)
            .put("modules", modules)
            .toString()
    }

    fun decodeSettings(bytes: ByteArray): SettingsResult = try {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        val modules = LinkedHashMap<String, Boolean>()
        val modulesJson = root.optJSONObject("modules") ?: JSONObject()
        val names = modulesJson.keys()
        while (names.hasNext()) {
            val name = names.next()
            modules[name] = modulesJson.getBoolean(name)
        }
        SettingsResult.Ok(
            SettingsSnapshot(
                spoofMode = root.getString("spoofMode"),
                activeHourStart = root.getInt("activeHourStart"),
                activeHourEnd = root.getInt("activeHourEnd"),
                refreshIntervalSec = root.getInt("refreshIntervalSec"),
                locationDeliveryMode = root.getString("locationDeliveryMode"),
                modules = modules,
            ),
        )
    } catch (e: Exception) {
        SettingsResult.Error("车道配置区段无法解码：${e.message}")
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
                qwyApplicationId = qwy.optStringOrNull("applicationId"),
                qwyVersionName = qwy.optStringOrNull("versionName"),
                transportSchemaVersion = if (qwy.has("transportSchemaVersion")) qwy.getInt("transportSchemaVersion") else null,
                autoApplicationId = auto.optStringOrNull("applicationId"),
                autoVersionName = auto.optStringOrNull("versionName"),
                providerPrincipal = auto.optStringOrNull("providerPrincipal"),
            ),
        )
    } catch (e: Exception) {
        LaneResult.Error("车道元数据区段无法解码：${e.message}")
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key)) return null
        val value = optString(key, "")
        return value.takeIf { it.isNotEmpty() }
    }
}
