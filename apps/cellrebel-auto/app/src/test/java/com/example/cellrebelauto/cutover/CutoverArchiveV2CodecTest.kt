package com.example.cellrebelauto.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class CutoverArchiveV2CodecTest {
    private val codec = CutoverArchiveV2Codec(policy())

    @Test
    fun `same logical snapshot has one canonical encoding and verified digest`() {
        val canonical = archive()
        val shuffled = canonical.copy(
            tables = canonical.tables.reversed().map { it.copy(rows = it.rows.reversed()) },
            preferences = canonical.preferences.reversed()
        )

        val first = codec.encode(canonical)
        val second = codec.encode(shuffled)

        assertEquals(first, second)
        assertEquals(first, codec.encode(codec.decode(first.serialized).archive))
        assertEquals(first.archiveDigest, codec.decode(first.serialized).archiveDigest)
        assertTrue(first.archiveDigest.matches(Regex("sha256:[0-9a-f]{64}")))
        assertFalse(first.serialized.contains("synthetic-secret-row"))
    }

    @Test
    fun `every successful decode is an exact canonical round trip`() {
        listOf(
            archive(),
            archive().copy(captureId = "capture-捕获-📦")
        ).forEach { candidate ->
            assertCanonicalRoundTrip(codec, candidate)
        }
    }

    @Test
    fun `small legal policy round trips an empty table longer than the row grammar bound`() {
        val fieldLimit = 40
        val smallPolicy = CutoverArchivePolicy(
            schemaVersion = 9,
            requiredTableSchemaDigests = mapOf("provider_pairing_records" to "sha256:pairing"),
            historicalOnlyTables = setOf("provider_pairing_records"),
            limits = CutoverArchiveLimits(
                maxArchiveBytes = 10_000,
                maxEncodedFieldChars = fieldLimit
            )
        )
        val smallCodec = CutoverArchiveV2Codec(smallPolicy)
        val candidate = CutoverArchiveV2(
            sourcePackage = "com.example.cellrebelauto",
            captureId = "capture-捕获-📦",
            schemaVersion = 9,
            tables = listOf(
                CutoverTableSection(
                    name = "provider_pairing_records",
                    schemaDigest = "sha256:pairing",
                    restorationMode = CutoverRestorationMode.HISTORICAL_ONLY,
                    rows = emptyList()
                )
            ),
            preferences = preferences()
        )
        val encoded = smallCodec.encode(candidate)
        val tableLine = encoded.serialized.lines().single { it.startsWith("table=") }

        assertTrue(tableLine.length > 2 * fieldLimit + "row=|".length)
        assertCanonicalRoundTrip(smallCodec, candidate)
    }

    @Test
    fun `maximum legal table metadata round trips with five preference states`() {
        val fieldLimit = 40
        val maximumName = "t".repeat(30)
        val maximumSchema = "s".repeat(30)
        assertEquals(fieldLimit, b64(maximumName).length)
        assertEquals(fieldLimit, b64(maximumSchema).length)
        val maximumPolicy = CutoverArchivePolicy(
            schemaVersion = 9,
            requiredTableSchemaDigests = mapOf(
                maximumName to maximumSchema,
                "provider_pairing_records" to "sha256:pairing"
            ),
            historicalOnlyTables = setOf(maximumName, "provider_pairing_records"),
            limits = CutoverArchiveLimits(
                maxArchiveBytes = 10_000,
                maxEncodedFieldChars = fieldLimit
            )
        )
        val candidate = archive().copy(
            captureId = "capture-捕获-📦",
            tables = archive().tables.mapNotNull { table ->
                if (table.name == "alpha") null else table.copy(rows = emptyList())
            } + CutoverTableSection(
                name = maximumName,
                schemaDigest = maximumSchema,
                restorationMode = CutoverRestorationMode.HISTORICAL_ONLY,
                rows = emptyList()
            )
        )

        assertTrue(candidate.preferences.any { !it.present })
        assertTrue(candidate.preferences.any { it.present && it.type == CutoverPreferenceType.INT })
        assertTrue(candidate.preferences.any { it.present && it.type == CutoverPreferenceType.BOOLEAN })
        assertCanonicalRoundTrip(CutoverArchiveV2Codec(maximumPolicy), candidate)
    }

    @Test
    fun `five raw preferences preserve absence separately from an explicit default`() {
        val encodedAbsent = codec.encode(archive()).serialized
        val explicitDefault = archive().copy(
            preferences = preferences().map { preference ->
                if (preference.key == "global_buffer_seconds") {
                    preference.copy(present = true, value = "0")
                } else {
                    preference
                }
            }
        )
        val encodedDefault = codec.encode(explicitDefault).serialized

        assertNotEquals(encodedAbsent, encodedDefault)
        val decoded = codec.decode(encodedAbsent).archive.preferences.associateBy { it.key }
        assertEquals(
            CutoverPreferenceEntry(
                key = "global_buffer_seconds",
                type = CutoverPreferenceType.INT,
                present = false,
                value = null
            ),
            decoded.getValue("global_buffer_seconds")
        )
        assertEquals(5, decoded.size)
    }

    @Test
    fun `missing duplicate unknown or incorrectly typed preference fails closed`() {
        val baseline = archive()
        val global = preferences().first { it.key == "global_buffer_seconds" }

        assertRejected(baseline.copy(preferences = baseline.preferences - global))
        assertRejected(baseline.copy(preferences = baseline.preferences + global))
        assertRejected(
            baseline.copy(
                preferences = baseline.preferences + CutoverPreferenceEntry(
                    key = "unknown_key",
                    type = CutoverPreferenceType.INT,
                    present = false,
                    value = null
                )
            )
        )
        assertRejected(
            baseline.copy(
                preferences = baseline.preferences.map {
                    if (it.key == "test_timeout_seconds") it.copy(type = CutoverPreferenceType.BOOLEAN)
                    else it
                }
            )
        )
        assertRejected(
            baseline.copy(
                preferences = baseline.preferences.map {
                    if (it.key == "test_timeout_seconds") it.copy(value = "090") else it
                }
            )
        )
        assertRejected(
            baseline.copy(
                preferences = baseline.preferences.map {
                    if (it.key == "test_stage_enabled") it.copy(value = "TRUE") else it
                }
            )
        )
    }

    @Test
    fun `table census schema digest and unique row key are policy enforced`() {
        val baseline = archive()
        assertRejected(baseline.copy(tables = baseline.tables.dropLast(1)))
        assertRejected(baseline.copy(tables = baseline.tables + baseline.tables.first()))
        assertRejected(
            baseline.copy(
                tables = baseline.tables.map {
                    if (it.name == "alpha") it.copy(schemaDigest = "sha256:wrong") else it
                }
            )
        )
        assertRejected(
            baseline.copy(
                tables = baseline.tables.map { table ->
                    if (table.name == "alpha") table.copy(rows = table.rows + table.rows.first())
                    else table
                }
            )
        )
    }

    @Test
    fun `policy rejects blank table name or schema digest after a legal baseline`() {
        val baselinePolicy = policy(
            limits = CutoverArchiveLimits(
                maxArchiveBytes = 10_000,
                maxEncodedFieldChars = 40
            )
        )
        assertCanonicalRoundTrip(CutoverArchiveV2Codec(baselinePolicy), archive())

        listOf<() -> CutoverArchivePolicy>(
            {
                baselinePolicy.copy(
                    requiredTableSchemaDigests = baselinePolicy.requiredTableSchemaDigests +
                        ("" to "sha256:blank-name")
                )
            },
            {
                baselinePolicy.copy(
                    requiredTableSchemaDigests = baselinePolicy.requiredTableSchemaDigests +
                        (" \t" to "sha256:blank-name")
                )
            },
            {
                baselinePolicy.copy(
                    requiredTableSchemaDigests = baselinePolicy.requiredTableSchemaDigests +
                        ("provider_pairing_records" to "")
                )
            },
            {
                baselinePolicy.copy(
                    requiredTableSchemaDigests = baselinePolicy.requiredTableSchemaDigests +
                        ("provider_pairing_records" to " \t")
                )
            }
        ).forEach { invalidPolicy ->
            assertThrows(IllegalArgumentException::class.java) { invalidPolicy() }
        }
    }

    @Test
    fun `field empty rules are symmetric across encode and decode`() {
        val emptyPayload = archive().copy(
            tables = archive().tables.map { table ->
                if (table.name == "alpha") {
                    table.copy(
                        rows = table.rows.mapIndexed { index, row ->
                            if (index == 0) row.copy(canonicalRowBase64Url = "") else row
                        }
                    )
                } else {
                    table
                }
            }
        )
        assertCanonicalRoundTrip(codec, emptyPayload)

        assertRejected(archive().copy(sourcePackage = ""))
        assertRejected(archive().copy(captureId = " \t"))
        assertRejected(
            archive().copy(
                tables = archive().tables.map { table ->
                    if (table.name == "alpha") {
                        table.copy(rows = listOf(CutoverRowPayload("", "")))
                    } else {
                        table
                    }
                }
            )
        )
        assertRejected(
            archive().copy(
                preferences = archive().preferences.map { preference ->
                    if (preference.key == "test_timeout_seconds") preference.copy(value = "")
                    else preference
                }
            )
        )
    }

    @Test
    fun `zero rows and canonical integer minima remain legal`() {
        assertThrows(IllegalArgumentException::class.java) { policy().copy(schemaVersion = 0) }
        val zeroRowPolicy = CutoverArchivePolicy(
            schemaVersion = 1,
            requiredTableSchemaDigests = mapOf("provider_pairing_records" to "sha256:pairing"),
            historicalOnlyTables = setOf("provider_pairing_records"),
            limits = CutoverArchiveLimits(
                maxArchiveBytes = 10_000,
                maxRowsPerTable = 0,
                maxTotalRows = 0,
                maxEncodedFieldChars = 40
            )
        )
        val candidate = CutoverArchiveV2(
            sourcePackage = "com.example.cellrebelauto",
            captureId = "capture-minimum",
            schemaVersion = 1,
            tables = listOf(
                CutoverTableSection(
                    name = "provider_pairing_records",
                    schemaDigest = "sha256:pairing",
                    restorationMode = CutoverRestorationMode.HISTORICAL_ONLY,
                    rows = emptyList()
                )
            ),
            preferences = preferences().map { preference ->
                if (preference.key == "test_timeout_seconds") preference.copy(value = Int.MIN_VALUE.toString())
                else preference
            }
        )

        assertCanonicalRoundTrip(CutoverArchiveV2Codec(zeroRowPolicy), candidate)
    }

    @Test
    fun `pairing records are historical only and exact restore is forbidden`() {
        val baseline = archive()
        val activePairing = baseline.copy(
            tables = baseline.tables.map { table ->
                if (table.name == "provider_pairing_records") {
                    table.copy(restorationMode = CutoverRestorationMode.EXACT)
                } else {
                    table
                }
            }
        )

        assertRejected(activePairing)
        assertEquals(
            CutoverRestorationMode.HISTORICAL_ONLY,
            codec.decode(codec.encode(baseline).serialized).archive.tables
                .single { it.name == "provider_pairing_records" }
                .restorationMode
        )
    }

    @Test
    fun `payload bit flip and forged table proof are rejected after recomputation`() {
        val serialized = codec.encode(archive()).serialized
        val rowLine = serialized.lines().first { it.startsWith("row=") }
        val flipped = serialized.replace(rowLine, rowLine.dropLast(1) + alternate(rowLine.last()))
        val tableLine = serialized.lines().first { it.startsWith("table=") }
        val tableFields = tableLine.split('|').toMutableList()
        tableFields[3] = (tableFields[3].toLong() + 1L).toString()
        val forgedCount = resign(serialized.replace(tableLine, tableFields.joinToString("|")))
        val forgedRowDigest = resign(
            serialized.replace(
                tableLine,
                tableFieldsFor(tableLine).also {
                    it[4] = "sha256:" + "0".repeat(64)
                }.joinToString("|")
            )
        )

        assertDecodeRejected(flipped)
        assertDecodeRejected(forgedCount)
        assertDecodeRejected(forgedRowDigest)
    }

    @Test
    fun `noncanonical row order is rejected even with a recomputed archive digest`() {
        val lines = codec.encode(archive()).serialized.lines().toMutableList()
        val rowIndexes = lines.indices.filter { lines[it].startsWith("row=") }.take(2)
        val first = lines[rowIndexes[0]]
        lines[rowIndexes[0]] = lines[rowIndexes[1]]
        lines[rowIndexes[1]] = first

        assertDecodeRejected(resign(lines.joinToString("\n")))
    }

    @Test
    fun `bad Base64 oversized input CSV and trailing fields are rejected`() {
        val serialized = codec.encode(archive()).serialized
        val badBase64 = resign(serialized.replace("source=", "source=*"))
        val tinyCodec = CutoverArchiveV2Codec(policy(limits = CutoverArchiveLimits(maxArchiveBytes = 32)))
        val trailing = serialized + "\ntrailing=field"

        assertDecodeRejected(badBase64)
        assertThrows(IllegalArgumentException::class.java) { tinyCodec.decode(serialized) }
        assertDecodeRejected("id,result\n1,success")
        assertDecodeRejected(trailing)
    }

    @Test
    fun `valid Base64URL containing malformed UTF-8 is rejected`() {
        val serialized = codec.encode(archive()).serialized
        val malformedCapture = serialized.replace("capture=Y2FwdHVyZS0wMDE", "capture=_w")

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(resign(malformedCapture))
        }
        assertTrue(error.message.orEmpty().contains("invalid UTF-8 in capture"))
    }

    @Test
    fun `isolated UTF-16 surrogate is rejected before encoding`() {
        assertRejected(archive().copy(captureId = "capture-\uD800"))
    }

    @Test
    fun `encoder applies the encoded field limit to table metadata`() {
        val oversizedSchemaDigest = "x".repeat(31)
        val limits = CutoverArchiveLimits(
            maxArchiveBytes = 10_000,
            maxEncodedFieldChars = 40
        )
        val baselinePolicy = policy(limits)
        val boundedPolicy = baselinePolicy.copy(
            requiredTableSchemaDigests = baselinePolicy.requiredTableSchemaDigests +
                ("alpha" to oversizedSchemaDigest)
        )
        assertCanonicalRoundTrip(CutoverArchiveV2Codec(baselinePolicy), archive())
        val error = assertThrows(IllegalArgumentException::class.java) {
            CutoverArchiveV2Codec(boundedPolicy)
        }
        assertTrue(error.message.orEmpty().contains("schema digest exceeds field limit"))
    }

    @Test
    fun `blank line immediately before archive digest is rejected`() {
        val serialized = codec.encode(archive()).serialized
        val withBlankLine = serialized.replace("\narchiveDigest=", "\n\narchiveDigest=")

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(resign(withBlankLine))
        }
        assertTrue(error.message.orEmpty().contains("blank lines"))
    }

    @Test
    fun `line amplification below byte limit is rejected by a preallocation bound`() {
        val lineBoundedCodec = CutoverArchiveV2Codec(
            policy(
                limits = CutoverArchiveLimits(
                    maxArchiveBytes = 1_000_000,
                    maxArchiveLines = 16
                )
            )
        )
        val lineBomb = buildString {
            append("cutover-archive-v2\n")
            repeat(100) { append("x\n") }
            append("x")
        }

        val error = assertThrows(IllegalArgumentException::class.java) {
            lineBoundedCodec.decode(lineBomb)
        }

        assertTrue(error.message.orEmpty().contains("line count exceeds limit"))
    }

    @Test
    fun `reviewer eight megabyte line bomb is rejected without materializing four million lines`() {
        val lineBomb = "cutover-archive-v2\n" + "x\n".repeat(4_000_000) + "x"
        assertEquals(8_000_020, lineBomb.length)

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(lineBomb)
        }

        assertTrue(error.message.orEmpty().contains("line count exceeds limit"))
    }

    @Test
    fun `total row bound rejects an allocation-valid table census`() {
        val rowBoundedCodec = CutoverArchiveV2Codec(
            policy(limits = CutoverArchiveLimits(maxTotalRows = 2))
        )
        val encodedWithDefaultLimits = codec.encode(archive()).serialized

        assertThrows(IllegalArgumentException::class.java) {
            rowBoundedCodec.encode(archive())
        }
        assertThrows(IllegalArgumentException::class.java) {
            rowBoundedCodec.decode(encodedWithDefaultLimits)
        }
    }

    @Test
    fun `field delimiter amplification is rejected without unbounded splitting`() {
        val serialized = codec.encode(archive()).serialized
        val rowLine = serialized.lines().first { it.startsWith("row=") }
        val delimiterBomb = "row=" + "|".repeat(1_000_000)

        val error = assertThrows(IllegalArgumentException::class.java) {
            codec.decode(resign(serialized.replace(rowLine, delimiterBomb)))
        }

        assertTrue(error.message.orEmpty().contains("invalid row payload"))
    }

    @Test
    fun `wrong source package schema version and malformed fields are rejected`() {
        assertRejected(archive().copy(sourcePackage = "come.xx.fakeaauto"))
        assertRejected(archive().copy(schemaVersion = 10))
        assertRejected(
            archive().copy(
                tables = archive().tables.map { table ->
                    if (table.name == "alpha") {
                        table.copy(rows = listOf(CutoverRowPayload("not base64", b64("payload"))))
                    } else {
                        table
                    }
                }
            )
        )
    }

    private fun assertRejected(candidate: CutoverArchiveV2) {
        assertThrows(IllegalArgumentException::class.java) { codec.encode(candidate) }
    }

    private fun assertDecodeRejected(serialized: String) {
        assertThrows(IllegalArgumentException::class.java) { codec.decode(serialized) }
    }

    private fun assertCanonicalRoundTrip(
        targetCodec: CutoverArchiveV2Codec,
        candidate: CutoverArchiveV2
    ) {
        val encoded = targetCodec.encode(candidate)
        val decoded = targetCodec.decode(encoded.serialized)
        val reencoded = targetCodec.encode(decoded.archive)

        assertEquals(encoded.serialized, reencoded.serialized)
        assertEquals(encoded.archiveDigest, decoded.archiveDigest)
        assertEquals(decoded.archiveDigest, reencoded.archiveDigest)
    }

    private fun archive() = CutoverArchiveV2(
        sourcePackage = "com.example.cellrebelauto",
        captureId = "capture-001",
        schemaVersion = 9,
        tables = listOf(
            CutoverTableSection(
                name = "alpha",
                schemaDigest = "sha256:alpha",
                restorationMode = CutoverRestorationMode.EXACT,
                rows = listOf(
                    CutoverRowPayload(b64("2"), b64("synthetic-secret-row-2")),
                    CutoverRowPayload(b64("1"), b64("synthetic-secret-row-1"))
                )
            ),
            CutoverTableSection(
                name = "provider_pairing_records",
                schemaDigest = "sha256:pairing",
                restorationMode = CutoverRestorationMode.HISTORICAL_ONLY,
                rows = listOf(CutoverRowPayload(b64("pair-1"), b64("historical-pairing")))
            )
        ),
        preferences = preferences()
    )

    private fun preferences() = listOf(
        CutoverPreferenceEntry("global_buffer_seconds", CutoverPreferenceType.INT, false, null),
        CutoverPreferenceEntry("test_timeout_seconds", CutoverPreferenceType.INT, true, "90"),
        CutoverPreferenceEntry("gps_settle_seconds", CutoverPreferenceType.INT, true, "60"),
        CutoverPreferenceEntry("location_stage_enabled", CutoverPreferenceType.BOOLEAN, true, "true"),
        CutoverPreferenceEntry("test_stage_enabled", CutoverPreferenceType.BOOLEAN, true, "false")
    )

    private fun policy(limits: CutoverArchiveLimits = CutoverArchiveLimits()) = CutoverArchivePolicy(
        schemaVersion = 9,
        requiredTableSchemaDigests = mapOf(
            "alpha" to "sha256:alpha",
            "provider_pairing_records" to "sha256:pairing"
        ),
        historicalOnlyTables = setOf("provider_pairing_records"),
        preferenceTypes = mapOf(
            "global_buffer_seconds" to CutoverPreferenceType.INT,
            "test_timeout_seconds" to CutoverPreferenceType.INT,
            "gps_settle_seconds" to CutoverPreferenceType.INT,
            "location_stage_enabled" to CutoverPreferenceType.BOOLEAN,
            "test_stage_enabled" to CutoverPreferenceType.BOOLEAN
        ),
        limits = limits
    )

    private fun b64(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun alternate(value: Char): Char = if (value == 'A') 'B' else 'A'

    private fun tableFieldsFor(line: String): MutableList<String> = line.split('|').toMutableList()

    private fun resign(serialized: String): String {
        val body = serialized.lines().dropLast(1).joinToString("\n")
        return body + "\narchiveDigest=" + sha256(body.toByteArray(Charsets.UTF_8))
    }

    private fun sha256(bytes: ByteArray): String = "sha256:" +
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
