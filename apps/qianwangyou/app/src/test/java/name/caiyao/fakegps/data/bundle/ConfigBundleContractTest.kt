package name.caiyao.fakegps.data.bundle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8: wire contract of the fakexxx configuration bundle (single zip + manifest.json).
 *
 * The manifest is the single source of truth for what the package claims to contain.
 * Import is FAIL-CLOSED: any manifest/zip mismatch, unknown schemaVersion, or undeclared
 * entry rejects the whole bundle instead of importing a partial state.
 * # 配置包线格式契约：清单即包内内容的唯一声明；任何不符整体拒绝，绝不半导入
 */
class ConfigBundleContractTest {

    private fun manifestJson(
        schemaVersion: Int = ConfigBundleContract.BUNDLE_SCHEMA_VERSION,
        sections: String =
            """{"qwy.profiles":{"file":"qwy/profiles.json","count":2},""" +
                """"meta.lane":{"file":"meta/lane.json"}}""",
    ): String = """
        {"schemaVersion":$schemaVersion,
         "createdAtEpochMs":1725686400000,
         "exporter":"qianwangyou",
         "sections":$sections}
    """.trimIndent()

    private fun zipWith(vararg entries: Pair<String, String>): ByteArray =
        ConfigBundleZipWriter.write(
            entries.associate { (path, text) -> path to text.toByteArray(Charsets.UTF_8) },
        )

    // ---- manifest codec round trip ----

    @Test
    fun `manifest round trips schemaVersion created exporter and ordered sections`() {
        val parsed = ConfigBundleContract.parseManifest(manifestJson().toByteArray())

        assertEquals(ConfigBundleContract.BUNDLE_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals(1725686400000L, parsed.createdAtEpochMs)
        assertEquals("qianwangyou", parsed.exporter)
        assertEquals(2, parsed.sections.size)
        assertEquals("qwy/profiles.json", parsed.sections.getValue("qwy.profiles").file)
        assertEquals(2, parsed.sections.getValue("qwy.profiles").count)
        assertEquals("meta/lane.json", parsed.sections.getValue("meta.lane").file)
    }

    @Test
    fun `manifest builder emits all declared sections and the schema version`() {
        val json = ConfigBundleContract.buildManifest(
            exporter = "cellrebel-auto",
            createdAtEpochMs = 42L,
            sections = linkedMapOf(
                "auto.plan" to SectionRef("auto/plan.csv", count = 3),
                "meta.lane" to SectionRef("meta/lane.json"),
            ),
        )
        val parsed = ConfigBundleContract.parseManifest(json.toByteArray())

        assertEquals(ConfigBundleContract.BUNDLE_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals("cellrebel-auto", parsed.exporter)
        assertEquals(42L, parsed.createdAtEpochMs)
        assertEquals(3, parsed.sections.getValue("auto.plan").count)
    }

    // ---- fail-closed validation ----

    @Test
    fun `unknown schemaVersion is rejected with an explicit version error`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(schemaVersion = 99),
                "qwy/profiles.json" to "{}",
                "meta/lane.json" to "{}",
            ),
        )

        assertTrue(parsed is ConfigBundleParseResult.Rejected)
        val reason = (parsed as ConfigBundleParseResult.Rejected).reason
        assertTrue("reason mentions the version: $reason", reason.contains("99"))
        assertTrue("reason is explicit about versions: $reason", reason.contains("版本"))
    }

    @Test
    fun `older readable schemaVersion is still rejected - fail closed`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(schemaVersion = 0),
                "meta/lane.json" to "{}",
            ),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `missing manifest rejects the bundle`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith("meta/lane.json" to "{}"),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `undeclared zip entry rejects the bundle - nothing smuggled in`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(),
                "qwy/profiles.json" to "{}",
                "meta/lane.json" to "{}",
                "stowaway.bin" to "secret",
            ),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `declared but missing section file rejects the bundle`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(),
                // qwy/profiles.json declared but absent
                "meta/lane.json" to "{}",
            ),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `directory traversal entry names are rejected`() {
        val failure = runCatching {
            ConfigBundleContract.parseManifest(
                """
                {"schemaVersion":1,"createdAtEpochMs":1,"exporter":"x",
                 "sections":{"evil":{"file":"../evil.json"}}}
                """.trimIndent().toByteArray(),
            )
        }.exceptionOrNull()

        assertTrue("expected rejection, got success", failure != null)
        assertTrue(
            "message names the offending path: ${failure?.message}",
            failure?.message?.contains("../evil.json") == true,
        )
    }

    @Test
    fun `not a zip at all is rejected`() {
        val parsed = ConfigBundleContract.parseBundle("plain text".toByteArray())
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    @Test
    fun `oversized entry is rejected`() {
        val huge = ByteArray(ConfigBundleContract.MAX_ENTRY_BYTES + 1)
        val parsed = ConfigBundleContract.parseBundle(
            ConfigBundleZipWriter.write(
                mapOf(
                    "manifest.json" to manifestJson().toByteArray(),
                    "qwy/profiles.json" to huge,
                    "meta/lane.json" to "{}".toByteArray(),
                ),
            ),
        )
        assertTrue(parsed is ConfigBundleParseResult.Rejected)
    }

    // ---- happy path ----

    @Test
    fun `well formed bundle parses with files bound to their sections`() {
        val parsed = ConfigBundleContract.parseBundle(
            zip = zipWith(
                "manifest.json" to manifestJson(),
                "qwy/profiles.json" to """{"schemaVersion":1,"profiles":[]}""",
                "meta/lane.json" to "{}",
            ),
        )
        val ok = parsed as ConfigBundleParseResult
        assertTrue("expected Ok, got $ok", parsed is ConfigBundleParseResult.Ok)
        val bundle = (parsed as ConfigBundleParseResult.Ok).bundle
        // manifest.json + the two declared section files.
        assertEquals(3, bundle.files.size)
        assertTrue(bundle.files.keys.contains("qwy/profiles.json"))
        assertTrue(bundle.files.keys.contains("meta/lane.json"))
        assertEquals(
            "qwy/profiles.json",
            bundle.manifest.sections.getValue("qwy.profiles").file,
        )
    }
}
