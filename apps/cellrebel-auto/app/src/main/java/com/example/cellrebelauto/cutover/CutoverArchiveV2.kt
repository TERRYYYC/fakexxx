package com.example.cellrebelauto.cutover

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
    val maxRowsPerTable: Int = 100_000,
    val maxTotalRows: Int = 100_000,
    val maxEncodedFieldChars: Int = 4 * 1024 * 1024,
    val maxArchiveLines: Int = 100_512
) {
    init {
        require(maxArchiveBytes > 0) { "archive byte limit must be positive" }
        require(maxTables > 0) { "table limit must be positive" }
        require(maxRowsPerTable >= 0) { "row limit cannot be negative" }
        require(maxTotalRows >= 0) { "total row limit cannot be negative" }
        require(maxEncodedFieldChars > 0) { "field limit must be positive" }
        require(maxArchiveLines > 0) { "line limit must be positive" }
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
        val totalRows = archive.tables.sumOf { it.rows.size.toLong() }
        val totalLines = HEADER_LINE_COUNT + archive.preferences.size.toLong() +
            archive.tables.size.toLong() + totalRows + ARCHIVE_DIGEST_LINE_COUNT
        require(totalLines <= policy.limits.maxArchiveLines) { "archive line count exceeds limit" }

        val builder = StringBuilder()
        fun appendLine(line: String) {
            val delimiterChars = if (builder.isEmpty()) 0L else 1L
            require(builder.length.toLong() + delimiterChars + line.length <= policy.limits.maxArchiveBytes) {
                "archive exceeds byte limit"
            }
            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(line)
        }

        appendLine(FORMAT)
        appendLine("source=${encodeUtf8(archive.sourcePackage)}")
        appendLine("capture=${encodeUtf8(archive.captureId)}")
        appendLine("schemaVersion=${archive.schemaVersion}")
        archive.preferences.sortedBy { it.key }.forEach { preference ->
            val presence = if (preference.present) PRESENT else ABSENT
            val value = if (preference.present) encodeUtf8(requireNotNull(preference.value)) else "-"
            appendLine("preference=${encodeUtf8(preference.key)}|${preference.type.name}|$presence|$value")
        }
        archive.tables.sortedBy { it.name }.forEach { table ->
            val rows = table.rows.sortedBy { it.orderKeyBase64Url }
            appendLine(
                "table=${encodeUtf8(table.name)}|${encodeUtf8(table.schemaDigest)}|" +
                    "${table.restorationMode.name}|${rows.size}|${rowDigest(rows)}"
            )
            rows.forEach { row ->
                appendLine("row=${row.orderKeyBase64Url}|${row.canonicalRowBase64Url}")
            }
        }
        val archiveDigest = sha256Ascii(builder)
        appendLine("$ARCHIVE_DIGEST_PREFIX$archiveDigest")
        val serialized = builder.toString()
        return EncodedCutoverArchiveV2(serialized, archiveDigest)
    }

    fun decode(serialized: String): DecodedCutoverArchiveV2 {
        validateSerializedBounds(serialized)
        require(!serialized.endsWith('\n')) { "archive has trailing data" }
        val digestSeparator = serialized.lastIndexOf('\n')
        require(digestSeparator > 0) { "missing archive digest" }
        val digestLineLength = serialized.length - digestSeparator - 1
        require(digestLineLength == ARCHIVE_DIGEST_PREFIX.length + DIGEST_LENGTH) {
            "missing archive digest"
        }
        val digestLine = serialized.substring(digestSeparator + 1)
        require(digestLine.startsWith(ARCHIVE_DIGEST_PREFIX)) { "missing archive digest" }
        val claimedArchiveDigest = digestLine.removePrefix(ARCHIVE_DIGEST_PREFIX)
        requireDigest(claimedArchiveDigest, "archive digest")
        require(sha256Ascii(serialized, digestSeparator) == claimedArchiveDigest) {
            "archive digest mismatch"
        }

        val reader = BoundedLineReader(
            source = serialized,
            endExclusive = digestSeparator,
            maxLineChars = maxLineChars()
        )
        require(reader.nextLine() == FORMAT) { "unsupported cutover archive format" }
        val sourcePackage = decodeNamedUtf8(reader.nextLine(), "source")
        val captureId = decodeNamedUtf8(reader.nextLine(), "capture")
        val schemaVersionValue = decodeNamed(reader.nextLine(), "schemaVersion")
        val schemaVersion = schemaVersionValue.toIntOrNull()
            ?: throw IllegalArgumentException("invalid schema version")
        require(schemaVersionValue == schemaVersion.toString()) { "schema version is not canonical" }
        val preferences = mutableListOf<CutoverPreferenceEntry>()
        val tables = mutableListOf<CutoverTableSection>()

        repeat(policy.preferenceTypes.size) {
            val line = reader.nextLine()
            require(line.startsWith(PREFERENCE_PREFIX)) { "expected preference entry" }
            preferences += decodePreference(line)
        }
        require(preferences.map { it.key } == policy.preferenceTypes.keys.sorted()) {
            "preference entries are not canonical"
        }

        var totalRows = 0L
        repeat(policy.requiredTableSchemaDigests.size) {
            val line = reader.nextLine()
            require(line.startsWith(TABLE_PREFIX)) { "expected table section" }
            val fields = splitExact(line, TABLE_PREFIX, expectedFields = 5, field = "table section")
            val tableName = decodeUtf8(fields[0], "table name", allowEmpty = false)
            val schemaDigest = decodeUtf8(fields[1], "schema digest", allowEmpty = false)
            val restorationMode = enumValue<CutoverRestorationMode>(fields[2], "restoration mode")
            val rowCount = fields[3].toIntOrNull()
                ?: throw IllegalArgumentException("invalid table row count")
            require(fields[3] == rowCount.toString()) { "table row count is not canonical" }
            require(rowCount in 0..policy.limits.maxRowsPerTable) { "table row count exceeds limit" }
            totalRows += rowCount
            require(totalRows <= policy.limits.maxTotalRows) { "total row count exceeds limit" }
            val claimedRowDigest = fields[4]
            requireDigest(claimedRowDigest, "row digest")

            val rows = ArrayList<CutoverRowPayload>(rowCount)
            var previousOrderKey: String? = null
            repeat(rowCount) {
                val rowLine = reader.nextLine()
                require(rowLine.startsWith(ROW_PREFIX)) { "truncated table rows" }
                val rowFields = splitExact(rowLine, ROW_PREFIX, expectedFields = 2, field = "row payload")
                requireCanonicalBase64Url(rowFields[0], "row order key", allowEmpty = false)
                requireCanonicalBase64Url(rowFields[1], "row payload", allowEmpty = true)
                require(previousOrderKey == null || previousOrderKey!! < rowFields[0]) {
                    "row order keys are not canonical"
                }
                previousOrderKey = rowFields[0]
                rows += CutoverRowPayload(rowFields[0], rowFields[1])
            }
            require(rowDigest(rows) == claimedRowDigest) { "table row digest mismatch" }
            tables += CutoverTableSection(
                name = tableName,
                schemaDigest = schemaDigest,
                restorationMode = restorationMode,
                rows = rows
            )
        }
        require(tables.map { it.name } == policy.requiredTableSchemaDigests.keys.sorted()) {
            "table sections are not canonical"
        }
        require(!reader.hasNext()) { "unknown archive field" }

        val archive = CutoverArchiveV2(
            sourcePackage = sourcePackage,
            captureId = captureId,
            schemaVersion = schemaVersion,
            tables = tables,
            preferences = preferences
        )
        validateArchive(archive)
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
        require(archive.tables.sumOf { it.rows.size.toLong() } <= policy.limits.maxTotalRows) {
            "total row count exceeds limit"
        }

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
            val rowKeys = HashSet<String>()
            table.rows.forEach { row ->
                requireCanonicalBase64Url(row.orderKeyBase64Url, "row order key", allowEmpty = false)
                requireCanonicalBase64Url(row.canonicalRowBase64Url, "row payload", allowEmpty = true)
                require(rowKeys.add(row.orderKeyBase64Url)) { "duplicate row order key" }
            }
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
        val fields = splitExact(line, PREFERENCE_PREFIX, expectedFields = 4, field = "preference entry")
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
        val digest = MessageDigest.getInstance("SHA-256")
        rows.forEach { row ->
            val orderKey = decodeBase64Url(row.orderKeyBase64Url, "row order key", allowEmpty = false)
            val payload = decodeBase64Url(row.canonicalRowBase64Url, "row payload", allowEmpty = true)
            updateLength(digest, orderKey.size)
            digest.update(orderKey)
            updateLength(digest, payload.size)
            digest.update(payload)
        }
        return formatDigest(digest.digest())
    }

    private fun updateLength(digest: MessageDigest, value: Int) {
        digest.update((value ushr 24).toByte())
        digest.update((value ushr 16).toByte())
        digest.update((value ushr 8).toByte())
        digest.update(value.toByte())
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

    private fun splitExact(
        line: String,
        prefix: String,
        expectedFields: Int,
        field: String
    ): List<String> {
        require(line.startsWith(prefix)) { "invalid $field" }
        val fields = ArrayList<String>(expectedFields)
        var start = prefix.length
        repeat(expectedFields - 1) {
            val separator = line.indexOf('|', start)
            require(separator >= 0) { "invalid $field" }
            fields += line.substring(start, separator)
            start = separator + 1
        }
        require(line.indexOf('|', start) == -1) { "invalid $field" }
        fields += line.substring(start)
        return fields
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

    private fun validateSerializedBounds(serialized: String) {
        require(serialized.length <= policy.limits.maxArchiveBytes) { "archive exceeds byte limit" }
        var lineCount = 1
        serialized.forEach { character ->
            require(character.code <= ASCII_MAX) { "archive must be canonical ASCII" }
            if (character == '\n') {
                lineCount += 1
                require(lineCount <= policy.limits.maxArchiveLines) { "archive line count exceeds limit" }
            }
        }
    }

    private fun maxLineChars(): Int = minOf(
        policy.limits.maxArchiveBytes,
        (policy.limits.maxEncodedFieldChars.toLong() * 2L + ROW_PREFIX.length + 1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    )

    private fun sha256Ascii(value: CharSequence, endExclusive: Int = value.length): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DIGEST_BUFFER_BYTES)
        var cursor = 0
        while (cursor < endExclusive) {
            val chunkSize = minOf(buffer.size, endExclusive - cursor)
            repeat(chunkSize) { offset ->
                val character = value[cursor + offset]
                require(character.code <= ASCII_MAX) { "archive must be canonical ASCII" }
                buffer[offset] = character.code.toByte()
            }
            digest.update(buffer, 0, chunkSize)
            cursor += chunkSize
        }
        return formatDigest(digest.digest())
    }

    private fun formatDigest(bytes: ByteArray): String = "sha256:" +
        bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val FORMAT = "cutover-archive-v2"
        const val LEGACY_PACKAGE = "com.example.cellrebelauto"
        const val PREFERENCE_PREFIX = "preference="
        const val TABLE_PREFIX = "table="
        const val ROW_PREFIX = "row="
        const val ARCHIVE_DIGEST_PREFIX = "archiveDigest="
        const val PRESENT = "PRESENT"
        const val ABSENT = "ABSENT"
        const val HEADER_LINE_COUNT = 4L
        const val ARCHIVE_DIGEST_LINE_COUNT = 1L
        const val DIGEST_LENGTH = 71
        const val DIGEST_BUFFER_BYTES = 8 * 1024
        const val ASCII_MAX = 0x7f
        val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val DECODER: Base64.Decoder = Base64.getUrlDecoder()
        val BASE64_URL = Regex("[A-Za-z0-9_-]*")
        val DIGEST = Regex("sha256:[0-9a-f]{64}")
        val CANONICAL_INT = Regex("0|-?[1-9][0-9]*")
    }
}

private class BoundedLineReader(
    private val source: String,
    private val endExclusive: Int,
    private val maxLineChars: Int
) {
    private var cursor = 0

    fun nextLine(): String {
        require(hasNext()) { "truncated cutover archive" }
        val separator = source.indexOf('\n', cursor).let { found ->
            if (found == -1 || found >= endExclusive) endExclusive else found
        }
        require(separator - cursor <= maxLineChars) { "archive line exceeds limit" }
        val line = source.substring(cursor, separator)
        require(line.isNotEmpty()) { "archive cannot contain blank lines" }
        cursor = if (separator < endExclusive) separator + 1 else endExclusive
        return line
    }

    fun hasNext(): Boolean = cursor < endExclusive
}
