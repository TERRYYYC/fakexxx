package com.example.cellrebelauto.cutover

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

enum class CutoverRestorationMode {
    EXACT,
    HISTORICAL_ONLY
}

enum class CutoverPreferenceType {
    INT,
    BOOLEAN
}

data class CutoverPreferenceEntry(
    val key: String,
    val type: CutoverPreferenceType,
    val present: Boolean,
    val value: String?
)

data class CutoverRowPayload(
    val orderKeyBase64Url: String,
    val canonicalRowBase64Url: String
)

data class CutoverTableSection(
    val name: String,
    val schemaDigest: String,
    val restorationMode: CutoverRestorationMode,
    val rows: List<CutoverRowPayload>
)

data class CutoverArchiveV2(
    val sourcePackage: String,
    val captureId: String,
    val schemaVersion: Int,
    val tables: List<CutoverTableSection>,
    val preferences: List<CutoverPreferenceEntry>
)

data class EncodedCutoverArchiveV2(
    val serialized: String,
    val archiveDigest: String
)

data class DecodedCutoverArchiveV2(
    val archive: CutoverArchiveV2,
    val archiveDigest: String
)

data class CutoverArchiveLimits(
    val maxArchiveBytes: Int = 64 * 1024 * 1024,
    val maxTables: Int = 256,
    val maxRowsPerTable: Int = 1_000_000,
    val maxEncodedFieldChars: Int = 16 * 1024 * 1024
) {
    init {
        require(maxArchiveBytes > 0) { "archive byte limit must be positive" }
        require(maxTables > 0) { "table limit must be positive" }
        require(maxRowsPerTable >= 0) { "row limit cannot be negative" }
        require(maxEncodedFieldChars > 0) { "field limit must be positive" }
    }
}

object CutoverPlanConfigSchema {
    val preferenceTypes: Map<String, CutoverPreferenceType> = mapOf(
        "global_buffer_seconds" to CutoverPreferenceType.INT,
        "test_timeout_seconds" to CutoverPreferenceType.INT,
        "gps_settle_seconds" to CutoverPreferenceType.INT,
        "location_stage_enabled" to CutoverPreferenceType.BOOLEAN,
        "test_stage_enabled" to CutoverPreferenceType.BOOLEAN
    )
}

data class CutoverArchivePolicy(
    val schemaVersion: Int,
    val requiredTableSchemaDigests: Map<String, String>,
    val historicalOnlyTables: Set<String>,
    val preferenceTypes: Map<String, CutoverPreferenceType> = CutoverPlanConfigSchema.preferenceTypes,
    val limits: CutoverArchiveLimits = CutoverArchiveLimits()
) {
    init {
        require(schemaVersion > 0) { "schema version must be positive" }
        require(requiredTableSchemaDigests.isNotEmpty()) { "table census cannot be empty" }
        require(requiredTableSchemaDigests.size <= limits.maxTables) { "table census exceeds limit" }
        require("provider_pairing_records" in requiredTableSchemaDigests) {
            "pairing history table is required"
        }
        require("provider_pairing_records" in historicalOnlyTables) {
            "pairing history must be historical only"
        }
        require(historicalOnlyTables.all { it in requiredTableSchemaDigests }) {
            "historical-only table is outside the census"
        }
        require(preferenceTypes == CutoverPlanConfigSchema.preferenceTypes) {
            "PlanConfig preference schema must contain exactly five owned keys"
        }
    }
}

/**
 * Canonical, content-carrying archive for the application-id cutover.
 *
 * The Auto-owned adapter supplies a versioned table policy and canonical row bytes. This codec
 * independently orders those bytes, derives every content digest, and rejects any archive that is
 * incomplete, non-canonical, oversized, or able to revive an active provider pairing.
 */
class CutoverArchiveV2Codec(
    private val policy: CutoverArchivePolicy
) {
    fun encode(archive: CutoverArchiveV2): EncodedCutoverArchiveV2 {
        validateArchive(archive)

        val body = buildList {
            add(FORMAT)
            add("source=${encodeUtf8(archive.sourcePackage)}")
            add("capture=${encodeUtf8(archive.captureId)}")
            add("schemaVersion=${archive.schemaVersion}")
            archive.preferences.sortedBy { it.key }.forEach { preference ->
                val presence = if (preference.present) PRESENT else ABSENT
                val value = if (preference.present) encodeUtf8(requireNotNull(preference.value)) else "-"
                add("preference=${encodeUtf8(preference.key)}|${preference.type.name}|$presence|$value")
            }
            archive.tables.sortedBy { it.name }.forEach { table ->
                val rows = table.rows.sortedBy { it.orderKeyBase64Url }
                add(
                    "table=${encodeUtf8(table.name)}|${encodeUtf8(table.schemaDigest)}|" +
                        "${table.restorationMode.name}|${rows.size}|${rowDigest(rows)}"
                )
                rows.forEach { row ->
                    add("row=${row.orderKeyBase64Url}|${row.canonicalRowBase64Url}")
                }
            }
        }.joinToString("\n")
        val archiveDigest = sha256(body.toByteArray(Charsets.UTF_8))
        val serialized = "$body\narchiveDigest=$archiveDigest"
        require(byteSize(serialized) <= policy.limits.maxArchiveBytes) { "archive exceeds byte limit" }
        return EncodedCutoverArchiveV2(serialized, archiveDigest)
    }

    fun decode(serialized: String): DecodedCutoverArchiveV2 {
        require(byteSize(serialized) <= policy.limits.maxArchiveBytes) { "archive exceeds byte limit" }
        require(!serialized.endsWith('\n')) { "archive has trailing data" }
        val lines = serialized.split('\n')
        require(lines.size >= 6 && lines.first() == FORMAT) { "unsupported cutover archive format" }
        require(lines.none { it.isEmpty() }) { "archive cannot contain blank lines" }

        val digestLine = lines.last()
        require(digestLine.startsWith(ARCHIVE_DIGEST_PREFIX)) { "missing archive digest" }
        val claimedArchiveDigest = digestLine.removePrefix(ARCHIVE_DIGEST_PREFIX)
        requireDigest(claimedArchiveDigest, "archive digest")
        val body = lines.dropLast(1).joinToString("\n")
        require(sha256(body.toByteArray(Charsets.UTF_8)) == claimedArchiveDigest) {
            "archive digest mismatch"
        }

        val sourcePackage = decodeNamedUtf8(lines[1], "source")
        val captureId = decodeNamedUtf8(lines[2], "capture")
        val schemaVersion = decodeNamed(lines[3], "schemaVersion").toIntOrNull()
            ?: throw IllegalArgumentException("invalid schema version")
        val preferences = mutableListOf<CutoverPreferenceEntry>()
        val tables = mutableListOf<CutoverTableSection>()

        var index = 4
        while (index < lines.lastIndex) {
            when {
                lines[index].startsWith(PREFERENCE_PREFIX) -> {
                    preferences += decodePreference(lines[index])
                    index += 1
                }
                lines[index].startsWith(TABLE_PREFIX) -> {
                    val fields = lines[index].removePrefix(TABLE_PREFIX).split('|')
                    require(fields.size == 5) { "invalid table section" }
                    val tableName = decodeUtf8(fields[0], "table name", allowEmpty = false)
                    val schemaDigest = decodeUtf8(fields[1], "schema digest", allowEmpty = false)
                    val restorationMode = enumValue<CutoverRestorationMode>(fields[2], "restoration mode")
                    val rowCount = fields[3].toIntOrNull()
                        ?: throw IllegalArgumentException("invalid table row count")
                    require(rowCount in 0..policy.limits.maxRowsPerTable) { "table row count exceeds limit" }
                    val claimedRowDigest = fields[4]
                    requireDigest(claimedRowDigest, "row digest")
                    index += 1

                    val rows = ArrayList<CutoverRowPayload>(rowCount)
                    repeat(rowCount) {
                        require(index < lines.lastIndex && lines[index].startsWith(ROW_PREFIX)) {
                            "truncated table rows"
                        }
                        val rowFields = lines[index].removePrefix(ROW_PREFIX).split('|')
                        require(rowFields.size == 2) { "invalid row payload" }
                        requireCanonicalBase64Url(rowFields[0], "row order key", allowEmpty = false)
                        requireCanonicalBase64Url(rowFields[1], "row payload", allowEmpty = true)
                        rows += CutoverRowPayload(rowFields[0], rowFields[1])
                        index += 1
                    }
                    require(rowDigest(rows) == claimedRowDigest) { "table row digest mismatch" }
                    tables += CutoverTableSection(
                        name = tableName,
                        schemaDigest = schemaDigest,
                        restorationMode = restorationMode,
                        rows = rows
                    )
                }
                else -> throw IllegalArgumentException("unknown archive field")
            }
        }

        val archive = CutoverArchiveV2(
            sourcePackage = sourcePackage,
            captureId = captureId,
            schemaVersion = schemaVersion,
            tables = tables,
            preferences = preferences
        )
        val canonical = encode(archive)
        require(canonical.serialized == serialized) { "archive is not canonical" }
        return DecodedCutoverArchiveV2(archive, claimedArchiveDigest)
    }

    private fun validateArchive(archive: CutoverArchiveV2) {
        require(archive.sourcePackage == LEGACY_PACKAGE) { "archive must originate from legacyId" }
        require(archive.captureId.isNotBlank()) { "capture id cannot be blank" }
        require(encodeUtf8(archive.captureId).length <= policy.limits.maxEncodedFieldChars) {
            "capture id exceeds field limit"
        }
        require(archive.schemaVersion == policy.schemaVersion) { "unexpected schema version" }
        require(archive.tables.size <= policy.limits.maxTables) { "table count exceeds limit" }

        val tableNames = archive.tables.map { it.name }
        require(tableNames.distinct().size == tableNames.size) { "duplicate table section" }
        require(tableNames.toSet() == policy.requiredTableSchemaDigests.keys) {
            "table census is incomplete or stale"
        }
        archive.tables.forEach { table ->
            require(table.name.isNotBlank()) { "table name cannot be blank" }
            require(table.schemaDigest == policy.requiredTableSchemaDigests.getValue(table.name)) {
                "unexpected schema digest for ${table.name}"
            }
            val expectedMode = if (table.name in policy.historicalOnlyTables) {
                CutoverRestorationMode.HISTORICAL_ONLY
            } else {
                CutoverRestorationMode.EXACT
            }
            require(table.restorationMode == expectedMode) {
                "unexpected restoration mode for ${table.name}"
            }
            require(table.rows.size <= policy.limits.maxRowsPerTable) { "table row count exceeds limit" }
            val rowKeys = table.rows.map { row ->
                requireCanonicalBase64Url(row.orderKeyBase64Url, "row order key", allowEmpty = false)
                requireCanonicalBase64Url(row.canonicalRowBase64Url, "row payload", allowEmpty = true)
                row.orderKeyBase64Url
            }
            require(rowKeys.distinct().size == rowKeys.size) { "duplicate row order key" }
        }

        val preferenceKeys = archive.preferences.map { it.key }
        require(preferenceKeys.distinct().size == preferenceKeys.size) { "duplicate preference entry" }
        require(preferenceKeys.toSet() == policy.preferenceTypes.keys) {
            "preference census must contain exactly five owned keys"
        }
        archive.preferences.forEach { preference ->
            require(preference.type == policy.preferenceTypes.getValue(preference.key)) {
                "unexpected preference type for ${preference.key}"
            }
            if (preference.present) {
                val value = requireNotNull(preference.value) { "present preference requires a value" }
                validatePreferenceValue(preference.type, value)
            } else {
                require(preference.value == null) { "absent preference cannot carry a value" }
            }
        }
    }

    private fun decodePreference(line: String): CutoverPreferenceEntry {
        val fields = line.removePrefix(PREFERENCE_PREFIX).split('|')
        require(fields.size == 4) { "invalid preference entry" }
        val key = decodeUtf8(fields[0], "preference key", allowEmpty = false)
        val type = enumValue<CutoverPreferenceType>(fields[1], "preference type")
        return when (fields[2]) {
            PRESENT -> CutoverPreferenceEntry(
                key = key,
                type = type,
                present = true,
                value = decodeUtf8(fields[3], "preference value", allowEmpty = true)
            )
            ABSENT -> {
                require(fields[3] == "-") { "absent preference cannot carry a value" }
                CutoverPreferenceEntry(key, type, present = false, value = null)
            }
            else -> throw IllegalArgumentException("invalid preference presence")
        }
    }

    private fun rowDigest(rows: List<CutoverRowPayload>): String {
        val preimage = ByteArrayOutputStream()
        DataOutputStream(preimage).use { output ->
            rows.forEach { row ->
                val orderKey = decodeBase64Url(row.orderKeyBase64Url, "row order key", allowEmpty = false)
                val payload = decodeBase64Url(row.canonicalRowBase64Url, "row payload", allowEmpty = true)
                output.writeInt(orderKey.size)
                output.write(orderKey)
                output.writeInt(payload.size)
                output.write(payload)
            }
        }
        return sha256(preimage.toByteArray())
    }

    private fun validatePreferenceValue(type: CutoverPreferenceType, value: String) {
        when (type) {
            CutoverPreferenceType.INT -> {
                require(CANONICAL_INT.matches(value) && value.toIntOrNull() != null) {
                    "preference integer is not canonical"
                }
            }
            CutoverPreferenceType.BOOLEAN -> require(value == "true" || value == "false") {
                "preference boolean is not canonical"
            }
        }
    }

    private fun decodeNamedUtf8(line: String, name: String): String =
        decodeUtf8(decodeNamed(line, name), name, allowEmpty = false)

    private fun decodeNamed(line: String, name: String): String {
        val prefix = "$name="
        require(line.startsWith(prefix)) { "missing $name" }
        return line.removePrefix(prefix)
    }

    private fun encodeUtf8(value: String): String = ENCODER.encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeUtf8(value: String, field: String, allowEmpty: Boolean): String =
        decodeBase64Url(value, field, allowEmpty).toString(Charsets.UTF_8)

    private fun requireCanonicalBase64Url(value: String, field: String, allowEmpty: Boolean) {
        decodeBase64Url(value, field, allowEmpty)
    }

    private fun decodeBase64Url(value: String, field: String, allowEmpty: Boolean): ByteArray {
        require(value.length <= policy.limits.maxEncodedFieldChars) { "$field exceeds field limit" }
        require((allowEmpty || value.isNotEmpty()) && BASE64_URL.matches(value)) { "invalid $field" }
        val decoded = try {
            DECODER.decode(value)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalid $field")
        }
        require(ENCODER.encodeToString(decoded) == value) { "$field is not canonical Base64URL" }
        return decoded
    }

    private inline fun <reified T : Enum<T>> enumValue(value: String, field: String): T = try {
        enumValueOf<T>(value)
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("invalid $field")
    }

    private fun requireDigest(value: String, field: String) {
        require(DIGEST.matches(value)) { "invalid $field" }
    }

    private fun byteSize(value: String): Int = value.toByteArray(Charsets.UTF_8).size

    private fun sha256(bytes: ByteArray): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val FORMAT = "cutover-archive-v2"
        const val LEGACY_PACKAGE = "com.example.cellrebelauto"
        const val PREFERENCE_PREFIX = "preference="
        const val TABLE_PREFIX = "table="
        const val ROW_PREFIX = "row="
        const val ARCHIVE_DIGEST_PREFIX = "archiveDigest="
        const val PRESENT = "PRESENT"
        const val ABSENT = "ABSENT"
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
        val BASE64_URL = Regex("[A-Za-z0-9_-]*")
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val CANONICAL_INT = Regex("0|-?[1-9][0-9]*")
    }
}
