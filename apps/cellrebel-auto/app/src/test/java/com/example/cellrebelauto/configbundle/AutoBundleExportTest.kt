package com.example.cellrebelauto.configbundle

import com.example.cellrebelauto.model.plan.PlanConfig
import com.example.cellrebelauto.model.plan.ParseResult
import com.example.cellrebelauto.model.plan.WorklistParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8 acceptance: Auto export completeness — every section the Auto exporter owns is
 * enumerated in the manifest and present in the package; the package holds exactly the
 * declared files (privacy: no keystore/token/credential material; pairing section is
 * fingerprints only).
 * # 导出完整性：清单每项必在包内；包内只允许清单声明过的文件（隐私红线）
 */
class AutoBundleExportTest {

    private val rows = listOf(
        WorklistRowData(30.11, 50.11, priority = 1, requiredSuccesses = 2),
        WorklistRowData(30.12, 50.12, priority = 0, requiredSuccesses = 1),
    )

    private fun snapshot() = AutoBundleExport(
        planRows = rows,
        planSourceFileName = "worklist.csv",
        planBufferSeconds = 42,
        planConfig = PlanConfig(
            globalBufferSeconds = 42,
            testTimeoutSeconds = 120,
            gpsSettleSeconds = 30,
            locationStageEnabled = true,
            testStageEnabled = false,
        ),
        pairingFingerprints = listOf(
            ProviderFingerprint(
                applicationId = "name.caiyao.fakegps",
                signerDigest = "deadbeef",
                approvedVersionCode = 7,
            ),
        ),
        lane = AutoBundleSections.LaneMetadata(
            qwyApplicationId = null,
            qwyVersionName = null,
            transportSchemaVersion = null,
            autoApplicationId = "com.example.cellrebelauto",
            autoVersionName = "2.0-test",
            providerPrincipal = "name.caiyao.fakegps.glmbench",
        ),
        createdAtEpochMs = 42L,
    )

    private fun export() = AutoBundleExporter.export(snapshot())

    // ---- completeness ----

    @Test
    fun `auto exporter declares exactly its own sections`() {
        assertEquals(
            linkedSetOf(
                "auto.plan",
                "auto.planConfig",
                "auto.pairing",
                "meta.lane",
            ),
            export().manifest.sections.keys,
        )
    }

    @Test
    fun `every manifest section lands in the package`() {
        val bundle = export()

        assertEquals(2, bundle.manifest.sections.getValue("auto.plan").count)
        assertEquals(1, bundle.manifest.sections.getValue("auto.pairing").count)
        for ((id, ref) in bundle.manifest.sections) {
            assertNotNull("section $id (${ref.file}) missing from the package", bundle.files[ref.file])
        }
    }

    @Test
    fun `package files are exactly the declared sections plus the manifest`() {
        val bundle = export()

        val declared = bundle.manifest.sections.values.map { it.file }.toSet()
        assertEquals(declared, bundle.files.keys - "manifest.json")
        assertTrue(ConfigBundleContract.parseBundle(bundle.zipBytes) is ConfigBundleParseResult.Ok)
    }

    @Test
    fun `exported plan csv is readable by the untouched T2 parser`() {
        val bundle = export()

        val csv = bundle.files.getValue("auto/plan.csv").toString(Charsets.UTF_8)
        val parsed = WorklistParser.parse(csv)
        assertTrue(parsed is ParseResult.Success)
        assertEquals(2, (parsed as ParseResult.Success).rows.size)
    }

    @Test
    fun `suggested export filename carries the date`() {
        assertEquals(
            "fakexxx-config-2026-09-07.zip",
            AutoBundleExporter.suggestedFileName(1_788_739_200_000L), // 2026-09-07T00:00:00Z
        )
    }

    // ---- privacy red line ----

    @Test
    fun `package contains no keystore token or private-key content anywhere`() {
        val bundle = export()
        val forbidden = listOf(
            "PRIVATE KEY", "keystore", "KeyStore", "PKCS12", "jks",
            "password", "secret", "credential", "Bearer ", "Authorization:",
            "signing_key", "session_token", "refresh_token", "access_token",
        )
        for ((path, bytes) in bundle.files) {
            val text = bytes.toString(Charsets.UTF_8)
            for (marker in forbidden) {
                assertTrue(
                    "privacy red line: $path contains forbidden marker '$marker'",
                    !text.contains(marker),
                )
            }
        }
    }

    @Test
    fun `pairing section exposes digests only`() {
        val bundle = export()
        val text = bundle.files.getValue("auto/pairing.json").toString(Charsets.UTF_8)
        assertTrue(text.contains("currentSignerDigest"))
        assertTrue(!text.contains("revokedAt"))
        assertTrue(!text.contains("approvedAt"))
    }
}
