package com.example.cellrebelauto.configbundle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** One manifest section declaration: its zip path and (optionally) the row count it claims. */
data class SectionRef(val file: String, val count: Int? = null)

data class ConfigBundleManifest(
    val schemaVersion: Int,
    val createdAtEpochMs: Long,
    val exporter: String,
    val sections: Map<String, SectionRef>,
)

data class ParsedBundle(
    val manifest: ConfigBundleManifest,
    val files: Map<String, ByteArray>,
)

sealed interface ConfigBundleParseResult {
    data class Ok(val bundle: ParsedBundle) : ConfigBundleParseResult
    data class Rejected(val reason: String) : ConfigBundleParseResult
}

/** Raised by [ConfigBundleContract.parseManifest]; [ConfigBundleContract.parseBundle] converts it into a Rejected. */
class BundleFormatException(reason: String) : IllegalArgumentException(reason)

/**
 * T8 (P0.3): the fakexxx configuration-bundle wire contract — ONE zip carrying a
 * manifest.json plus per-section files. Both apps (qianwangyou / cellrebel-auto)
 * read and write the same format; each app owns and imports only its own sections
 * (QWY: qwy.*, Auto: auto.*); meta.lane is shared provenance.
 *
 * Import is FAIL-CLOSED everywhere: unknown [ConfigBundleManifest.schemaVersion] → explicit
 * rejection; every manifest section must exist in the zip AND every zip entry must be
 * declared in the manifest (nothing can be smuggled in).
 *
 * Section registry (shared wire names — do not rename without bumping schemaVersion):
 *   qwy.profiles      → qwy/profiles.json        (count = profile rows)
 *   qwy.activeProfile → qwy/active_profile.json  (content fingerprint pointer)
 *   qwy.settings      → qwy/settings.json        (lane config: mode/hours/refresh/modules)
 *   qwy.callers       → qwy/callers.json         (approved Auto caller fingerprints, count)
 *   auto.plan         → auto/plan.csv            (WorklistParser CSV semantics, count = rows)
 *   auto.planConfig   → auto/plan_config.json    (buffer + advanced params + stage toggles)
 *   auto.pairing      → auto/pairing.json        (provider fingerprints, count)
 *   meta.lane         → meta/lane.json           (lane identities/versions)
 *
 * # 配置包契约：单一 zip + 清单；版本不识别明确拒绝；清单与包内容一一对应，不认识的一律拒收
 */
object ConfigBundleContract {

    /** Bumped ONLY when an incompatible wire change ships; readers fail closed on newer values. */
    const val BUNDLE_SCHEMA_VERSION = 1

    const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 32

    const val ENTRY_MANIFEST = "manifest.json"

    // ---- manifest codec ----

    fun buildManifest(
        exporter: String,
        createdAtEpochMs: Long,
        sections: Map<String, SectionRef>,
    ): String {
        val sectionJson = JSONObject()
        for ((id, ref) in sections) {
            sectionJson.put(
                id,
                JSONObject().put("file", ref.file).let { json ->
                    if (ref.count != null) json.put("count", ref.count) else json
                },
            )
        }
        return JSONObject()
            .put("schemaVersion", BUNDLE_SCHEMA_VERSION)
            .put("createdAtEpochMs", createdAtEpochMs)
            .put("exporter", exporter)
            .put("sections", sectionJson)
            .toString()
    }

    /** Strict manifest parser — throws [BundleFormatException] on anything this build cannot read. */
    fun parseManifest(bytes: ByteArray): ConfigBundleManifest {
        val root = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BundleFormatException("manifest.json 不是合法 JSON：${e.message}")
        }
        val version = root.optInt("schemaVersion", Int.MIN_VALUE)
        if (version != BUNDLE_SCHEMA_VERSION) {
            // Fail closed: a bundle written by a NEWER build (or a corrupt one) must be
            // rejected explicitly, never partially imported with guessed semantics.
            throw BundleFormatException(
                "不识别的配置包版本 schemaVersion=$version" +
                    "（本应用支持 $BUNDLE_SCHEMA_VERSION）；拒绝导入",
            )
        }
        val sectionsJson = root.optJSONObject("sections")
            ?: throw BundleFormatException("manifest.json 缺少 sections 声明")
        val sections = LinkedHashMap<String, SectionRef>()
        val ids = sectionsJson.keys()
        while (ids.hasNext()) {
            val id = ids.next()
            val ref = sectionsJson.optJSONObject(id)
                ?: throw BundleFormatException("section $id 的声明不是对象")
            val file = ref.optString("file", "")
            if (!isSafeEntryName(file)) {
                throw BundleFormatException("section $id 声明了非法路径：$file")
            }
            sections[id] = SectionRef(file, if (ref.has("count")) ref.getInt("count") else null)
        }
        if (sections.isEmpty()) throw BundleFormatException("manifest.json 的 sections 为空")
        return ConfigBundleManifest(
            schemaVersion = version,
            createdAtEpochMs = root.optLong("createdAtEpochMs", 0L),
            exporter = root.optString("exporter", "unknown"),
            sections = sections,
        )
    }

    // ---- guarded zip traversal ----

    private fun isSafeEntryName(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith("/") && !name.contains("\\") &&
            !name.split("/").any { it == ".." || it.isEmpty() && name.endsWith("/") } &&
            !name.contains("..")

    fun parseBundle(zip: ByteArray): ConfigBundleParseResult {
        return try {
            val files = readZip(zip)
            val manifestBytes = files[ENTRY_MANIFEST]
                ?: return ConfigBundleParseResult.Rejected("包内缺少 manifest.json，拒绝导入")
            val manifest = parseManifest(manifestBytes)
            val declaredFiles = manifest.sections.values.map { it.file }.toSet()
            val actualFiles = files.keys - ENTRY_MANIFEST
            val undeclared = actualFiles - declaredFiles
            if (undeclared.isNotEmpty()) {
                return ConfigBundleParseResult.Rejected(
                    "包内存在清单未声明的文件：${undeclared.sorted().joinToString()}；拒绝导入",
                )
            }
            val missing = declaredFiles - actualFiles
            if (missing.isNotEmpty()) {
                return ConfigBundleParseResult.Rejected(
                    "清单声明了但包内缺失的文件：${missing.sorted().joinToString()}；拒绝导入",
                )
            }
            ConfigBundleParseResult.Ok(ParsedBundle(manifest, files))
        } catch (e: BundleFormatException) {
            ConfigBundleParseResult.Rejected(e.message ?: "配置包清单无效")
        } catch (e: Exception) {
            ConfigBundleParseResult.Rejected("配置包不是合法的 zip：${e.message}")
        }
    }

    private fun readZip(zip: ByteArray): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val name = entry.name
                if (!isSafeEntryName(name)) {
                    throw BundleFormatException("包内出现非法路径条目：$name")
                }
                if (files.size >= MAX_ENTRIES) {
                    throw BundleFormatException("包内条目数超过上限 $MAX_ENTRIES")
                }
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_ENTRY_BYTES) {
                        throw BundleFormatException("包内条目 $name 超过 ${MAX_ENTRY_BYTES} 字节上限")
                    }
                    out.write(buffer, 0, read)
                }
                files[name] = out.toByteArray()
                stream.closeEntry()
            }
        }
        return files
    }

    /** Serialize a manifest back to bytes (used by the writer and by hostile-package tests). */
    fun manifestBytes(manifest: ConfigBundleManifest): ByteArray =
        buildManifest(manifest.exporter, manifest.createdAtEpochMs, manifest.sections)
            .toByteArray(Charsets.UTF_8)

    // ---- zip writer (shared by both exporters) ----

    fun writeZip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun jsonArray(vararg items: JSONObject): JSONArray = JSONArray().apply { items.forEach { put(it) } }
}
