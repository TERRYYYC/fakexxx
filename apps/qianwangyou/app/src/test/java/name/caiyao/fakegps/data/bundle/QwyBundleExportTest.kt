package name.caiyao.fakegps.data.bundle

import name.caiyao.fakegps.data.db.ProfileEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8 acceptance: export completeness — EVERY section the QWY exporter is responsible
 * for must be enumerated in the manifest AND present in the package; the package must
 * contain exactly the declared files (privacy red line: nothing else, in particular no
 * keystore / token / private-key material of any kind).
 * # 导出完整性：清单枚举的每项必在包内，包内也只允许清单声明过的文件（隐私红线）
 */
class QwyBundleExportTest {

    private val profileA = ProfileEntity(addname = "a", tac = 1, latitude = 1.0, longitude = 2.0)
    private val profileB = ProfileEntity(addname = "b", tac = 2, mcc = 262, mnc = 2)

    private fun snapshot(activeFirst: Boolean = true) = QwyBundleExport(
        profiles = listOf(profileA, profileB),
        activeProfile = QwyBundleExport.ActiveProfileRef(
            fingerprint = QwyProfileFingerprint.of(profileA),
            addname = profileA.addname,
        ),
        settings = QwyBundleSections.SettingsSnapshot(
            spoofMode = "always_on",
            activeHourStart = 7,
            activeHourEnd = 22,
            refreshIntervalSec = 30,
            locationDeliveryMode = "hook",
            modules = mapOf("location" to true, "cell" to false),
        ),
        callers = listOf(
            QwyBundleSections.CallerFingerprint(
                applicationId = "com.example.cellrebelauto",
                signerDigest = "deadbeef",
                observedVersionCode = 15L,
            ),
        ),
        lane = QwyBundleSections.LaneMetadata(
            qwyApplicationId = "name.caiyao.fakegps",
            qwyVersionName = "1.0-test",
            transportSchemaVersion = 5,
            autoApplicationId = null,
            autoVersionName = null,
            providerPrincipal = null,
        ),
        createdAtEpochMs = 1_725_686_400_000L,
    )

    private fun export() = QwyBundleExporter.export(snapshot())

    // ---- completeness: manifest enumeration == package content ----

    @Test
    fun `every manifest section lands in the package`() {
        val bundle = export()

        assertEquals(2, bundle.manifest.sections.getValue("qwy.profiles").count)
        for ((id, ref) in bundle.manifest.sections) {
            assertNotNull("section $id (${ref.file}) missing from the package", bundle.files[ref.file])
        }
    }

    @Test
    fun `qwy exporter declares exactly its own sections`() {
        val bundle = export()

        assertEquals(
            linkedSetOf(
                "qwy.profiles",
                "qwy.activeProfile",
                "qwy.settings",
                "qwy.callers",
                "meta.lane",
            ),
            bundle.manifest.sections.keys,
        )
    }

    @Test
    fun `package files are exactly the declared sections plus the manifest`() {
        val bundle = export()

        val declared = bundle.manifest.sections.values.map { it.file }.toSet()
        assertEquals(declared, bundle.files.keys - "manifest.json")
        // The package must itself pass the import-side fail-closed contract.
        val parsed = ConfigBundleContract.parseBundle(bundle.zipBytes)
        assertTrue(parsed is ConfigBundleParseResult.Ok)
    }

    @Test
    fun `exported bundle is a zip whose round trip preserves every profile`() {
        val bundle = export()
        assertEquals("PK", bundle.zipBytes.copyOfRange(0, 2).toString(Charsets.ISO_8859_1))

        val parsed = ConfigBundleContract.parseBundle(bundle.zipBytes) as ConfigBundleParseResult.Ok
        val decoded = QwyBundleSections.decodeProfiles(parsed.bundle.files.getValue("qwy/profiles.json")) as
            QwyBundleSections.ProfilesResult.Ok
        assertEquals(listOf("a", "b"), decoded.profiles.map { it.addname })
    }

    // ---- privacy red line: no keystore/token/credential material ----

    @Test
    fun `package contains no keystore token or private-key content anywhere`() {
        val bundle = export()
        val forbidden = listOf(
            "PRIVATE KEY", "keystore", "KeyStore", "PKCS12", "jks",
            "password", "secret", "credential", "Bearer ", "Authorization:",
            "signing_key", "session_token", "refresh_token", "access_token",
        )
        val manifestFile = "manifest.json"
        val allEntries = bundle.files + (manifestFile to
            ConfigBundleContract.buildManifest(
                exporter = bundle.manifest.exporter,
                createdAtEpochMs = bundle.manifest.createdAtEpochMs,
                sections = bundle.manifest.sections,
            ).toByteArray())
        for ((path, bytes) in allEntries) {
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
    fun `callers section exposes digests only - never approval or trust state`() {
        val bundle = export()
        val callers = JSONObject(
            bundle.files.getValue("qwy/callers.json").toString(Charsets.UTF_8),
        )
        val caller = callers.getJSONArray("callers").getJSONObject(0)
        val keys = mutableListOf<String>()
        val it = caller.keys()
        while (it.hasNext()) keys.add(it.next())
        assertEquals(
            setOf("applicationId", "signerDigest", "observedVersionCode"),
            keys.toSet(),
        )
    }

    @Test
    fun `suggested export filename carries the date`() {
        assertEquals(
            "fakexxx-config-2026-09-07.zip",
            QwyBundleExporter.suggestedFileName(1_788_739_200_000L), // 2026-09-07T00:00:00Z
        )
    }
}
