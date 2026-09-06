package com.example.cellrebelauto.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

class CutoverBundleCodecTest {

    @Test
    fun `Room v8 digests are independently recomputed from the frozen schema projection`() {
        assertEquals(fixtureSchemaDigests, CutoverSchemaV8.expectedSchemaDigests)
    }

    @Test
    fun `changing a frozen Room column changes its schema digest`() {
        val columns = schemaFixture().getValue("location_tasks")
        val changedColumn = columns.toMutableList().also {
            it[it.indexOf("priority|INTEGER|true")] = "priority|TEXT|true"
        }

        assertNotEquals(
            fixtureSchemaDigests.getValue("location_tasks"),
            digest("location_tasks", changedColumn)
        )
    }

    @Test
    fun `canonical bundle matches the frozen Room v8 fixture carrier`() {
        assertEquals(GOLDEN, CutoverBundleCodecV1.encode(goldenBundle()))
        assertEquals(goldenBundle(), CutoverBundleCodecV1.decode(GOLDEN))
    }

    @Test
    fun `encoder rejects an incomplete Room v8 table census`() {
        val missingOne = goldenBundle().copy(
            tables = goldenBundle().tables - "advance_receipts"
        )

        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.encode(missingOne)
        }
    }

    @Test
    fun `encoder refuses to restore a pairing as active`() {
        val activePairing = goldenBundle().copy(
            pairingHistory = listOf(CutoverPairingHistory("sha256-legacy-pairing", active = true))
        )

        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.encode(activePairing)
        }
    }

    @Test
    fun `decoder rejects a carrier without the restore visibility fence`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.decode(GOLDEN.replace("visibilityFence=true", "visibilityFence=false"))
        }
    }

    @Test
    fun `decoder rejects a table outside the Room v8 census`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.decode(
                GOLDEN.replace(
                    "pairing=sha256-legacy-pairing|historical",
                    "table=unowned_table|0|schema-unowned|rows-unowned\npairing=sha256-legacy-pairing|historical"
                )
            )
        }
    }

    @Test
    fun `decoder rejects a changed Room column schema digest`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverBundleCodecV1.decode(
                GOLDEN.replace(
                    fixtureSchemaDigests.getValue("location_tasks"),
                    "sha256:0000000000000000000000000000000000000000000000000000000000000000"
                )
            )
        }
    }

    private fun goldenBundle() = CutoverBundleV1(
        sourcePackage = "com.example.cellrebelauto",
        captureId = "capture-001",
        dataStoreDigest = "data-store-001",
        visibilityFenceRequired = true,
        eligibilityRecheckRequired = true,
        tables = fixtureSchemaDigests.keys.associateWith { table ->
            CutoverTableSnapshot(
                rowCount = 1,
                schemaDigest = fixtureSchemaDigests.getValue(table),
                rowDigest = "rows-$table"
            )
        },
        pairingHistory = listOf(CutoverPairingHistory("sha256-legacy-pairing", active = false))
    )

    private companion object {
        val GOLDEN: String by lazy {
            buildList {
                add("cutover-bundle-v1")
                add("source=com.example.cellrebelauto")
                add("capture=capture-001")
                add("roomVersion=8")
                add("dataStoreDigest=data-store-001")
                add("visibilityFence=true")
                add("eligibilityRecheck=true")
                fixtureSchemaDigests.toSortedMap().forEach { (table, digest) ->
                    add("table=$table|1|$digest|rows-$table")
                }
                add("pairing=sha256-legacy-pairing|historical")
            }.joinToString("\n")
        }

        val fixtureSchemaDigests: Map<String, String> by lazy {
            schemaFixture().mapValues { (table, columns) -> digest(table, columns) }
        }

        fun schemaFixture(): Map<String, List<String>> = requireNotNull(
            requireNotNull(CutoverBundleCodecTest::class.java.classLoader).getResourceAsStream(
                "cutover/room-v8-e2444fd-table-columns.tsv"
            )
        ).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.associate { line ->
                val (table, fields) = line.split('\t', limit = 2)
                table to fields.split(',')
            }
        }

        fun digest(table: String, columns: List<String>): String {
            val preimage = buildString {
                append("table=").append(table).append('\n')
                columns.forEach { append("column=").append(it).append('\n') }
            }.toByteArray(Charsets.UTF_8)
            return "sha256:" + MessageDigest.getInstance("SHA-256").digest(preimage)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
