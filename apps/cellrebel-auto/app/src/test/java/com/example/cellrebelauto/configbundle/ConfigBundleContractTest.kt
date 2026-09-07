package com.example.cellrebelauto.configbundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8: wire contract of the fakexxx configuration bundle (single zip + manifest.json),
 * Auto-side copy of the shared format. The manifest is the single source of truth for
 * what the package claims to contain; import is FAIL-CLOSED.
 * # 配置包线格式契约（Auto 侧同源副本）：清单不符整体拒绝，绝不半导入
 */
class AutoConfigBundleContractTest {

    private fun manifestJson(
        schemaVersion: Int = ConfigBundleContract.BUNDLE_SCHEMA_VERSION,
        sections: String =
            """{"auto.plan":{"file":"auto/plan.csv","count":2},""" +
                """"meta.lane":{"file":"meta/lane.json"}}""",
    ): String = """
        {"schemaVersion":$schemaVersion,
         "createdAtEpochMs":1725686400000,
         "exporter":"cellrebel-auto",
         "sections":$sections}
    """.trimIndent()

    private fun zipWith(vararg entries: Pair<String, String>): ByteArray =
        ConfigBundleZipWriter.write(
            entries.associate { (path, text) -> path to text.toByteArray(Charsets.UTF_8) },
        )

    @Test
    fun `manifest round trips schemaVersion created exporter and ordered sections`() {
        val parsed = ConfigBundleContract.parseManifest(manifestJson().toByteArray())

        assertEquals(ConfigBundleContract.BUNDLE_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals("cellrebel-auto", parsed.exporter)
        assertEquals(2, parsed.sections.getValue("auto.plan").count)
    }

    @Test
    fun `unknown schemaVersion is rejected with an explicit version error`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(schemaVersion = 99),
                "auto/plan.csv" to "x",
                "meta/lane.json" to "{}",
            ),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
        val reason = (parsed as ConfigBundleParseResult.Rejected).reason
        assertTrue("reason mentions the version: $reason", reason.contains("99"))
        assertTrue("reason is explicit about versions: $reason", reason.contains("版本"))
    }

    @Test
    fun `missing manifest rejects the bundle`() {
        assertTrue(
            ConfigBundleContract.parseBundle(zip = zipWith("auto/plan.csv" to "x"))
                is ConfigBundleParseResult.Rejected,
        )
    }

    @Test
    fun `undeclared zip entry rejects the bundle - nothing smuggled in`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(),
                "auto/plan.csv" to "x",
                "meta/lane.json" to "{}",
                "stowaway.bin" to "secret",
            ),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `declared but missing section file rejects the bundle`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith("manifest.json" to manifestJson()),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `not a zip at all is rejected`() {
        assertTrue(
            ConfigBundleContract.parseBundle("plain text".toByteArray())
                is ConfigBundleParseResult.Rejected,
        )
    }

    @Test
    fun `well formed bundle parses with files bound to their sections`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(),
                "auto/plan.csv" to "longitude,latitude,priority,required_successes",
                "meta/lane.json" to "{}",
            ),
        )
        assertTrue("expected Ok, got $parsed", parsed is ConfigBundleParseResult.Ok)
        val bundle = (parsed as ConfigBundleParseResult.Ok).bundle
        assertEquals(
            setOf("manifest.json", "auto/plan.csv", "meta/lane.json"),
            bundle.files.keys,
        )
    }
}
