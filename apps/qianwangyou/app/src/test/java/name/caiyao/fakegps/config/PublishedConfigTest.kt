package name.caiyao.fakegps.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-back half of the transport contract.
 *
 * The verify screen reads the SAME payload the hook consumes rather than the DB row: reading the DB
 * would only prove the UI saved something, while the failure that actually shipped was the payload
 * dropping 64 of 87 columns on the way to the hook. Parsing here must therefore be faithful to what
 * ConfigPrefsSync writes.
 */
class PublishedConfigTest {

    @Test
    fun `v4 parses system mock as an orthogonal location delivery mode`() {
        val parsed = PublishedConfig.parse(
            """{"schemaVersion":4,"locationDeliveryMode":"system_mock","fields":{"latitude":50.4501,"longitude":30.5234,"tac":27101}}""",
        )!!

        assertEquals("system_mock", parsed.locationDeliveryMode)
        assertEquals("50.4501", parsed.fields["latitude"])
        assertEquals("27101", parsed.fields["tac"])
    }

    @Test
    fun `v2 and v3 payloads default location delivery to hook`() {
        val v2 = PublishedConfig.parse("""{"schemaVersion":2,"fields":{}}""")!!
        val v3 = PublishedConfig.parse("""{"schemaVersion":3,"fields":{}}""")!!

        assertEquals("hook", v2.locationDeliveryMode)
        assertEquals("hook", v3.locationDeliveryMode)
    }

    @Test
    fun `parses schema version mode and fields`() {
        val p = PublishedConfig.parse(
            """{"schemaVersion":2,"mode":"always_on","fields":{"tac":26999,"operator_name":"CMCC"}}"""
        )!!
        assertEquals(2, p.schemaVersion)
        assertEquals("always_on", p.mode)
        assertEquals("26999", p.fields["tac"])
        assertEquals("CMCC", p.fields["operator_name"])
    }

    @Test
    fun `parses optional refresh interval without changing schema v3 compatibility`() {
        val configured = PublishedConfig.parse(
            """{"schemaVersion":3,"refreshIntervalSec":5,"fields":{},"unavailable":[]}"""
        )!!
        val legacyShape = PublishedConfig.parse(
            """{"schemaVersion":3,"fields":{},"unavailable":[]}"""
        )!!

        assertEquals(5, configured.refreshIntervalSec)
        assertNull(legacyShape.refreshIntervalSec)
    }

    @Test
    fun `parses validated unavailable set separately from spoof fields`() {
        val p = PublishedConfig.parse(
            """{"schemaVersion":3,"mode":"always_on","fields":{"tac":4095},"unavailable":["lac","operator_name"]}"""
        )!!
        assertEquals(setOf("lac", "operator_name"), p.unavailable)
        assertTrue(p.unavailablePresent)
        assertEquals(setOf("tac"), p.fields.keys)
    }

    @Test
    fun `invalid unavailable contract makes payload malformed`() {
        assertNull(PublishedConfig.parse(
            """{"schemaVersion":3,"fields":{"tac":4095},"unavailable":["tac"]}"""
        ))
        assertNull(PublishedConfig.parse(
            """{"schemaVersion":3,"fields":{},"unavailable":["tac","tac"]}"""
        ))
        assertNull(PublishedConfig.parse(
            """{"schemaVersion":3,"fields":{},"unavailable":["is_roaming"]}"""
        ))
        assertNull(PublishedConfig.parse(
            """{"schemaVersion":3,"fields":{},"unavailable":[1]}"""
        ))
    }

    @Test
    fun `schema v3 without unavailable array is structurally incomplete`() {
        val p = PublishedConfig.parse("""{"schemaVersion":3,"fields":{}}""")!!
        assertEquals(false, p.unavailablePresent)
    }

    @Test
    fun `legacy schema v2 does not require the v3 unavailable array`() {
        val p = PublishedConfig.parse("""{"schemaVersion":2,"fields":{"tac":7}}""")!!
        assertEquals(false, p.unavailablePresent)
        assertEquals("7", p.fields["tac"])
    }

    @Test
    fun `integer column does not acquire a decimal point`() {
        // SQLite INTEGER -> JSON long. Rendering 26999 as "26999.0" would make every integer field
        // read as a MISMATCH against the radio's "26999".
        val p = PublishedConfig.parse("""{"schemaVersion":2,"fields":{"ci":28378431}}""")!!
        assertEquals("28378431", p.fields["ci"])
    }

    @Test
    fun `floating point column keeps its precision`() {
        val p = PublishedConfig.parse("""{"schemaVersion":2,"fields":{"latitude":50.615936}}""")!!
        assertEquals("50.615936", p.fields["latitude"])
    }

    @Test
    fun `string column is unquoted`() {
        val p = PublishedConfig.parse("""{"schemaVersion":2,"fields":{"wifi_ssid":"HomeNet"}}""")!!
        assertEquals("HomeNet", p.fields["wifi_ssid"])
    }

    @Test
    fun `absent fields object is flagged incomplete, not treated as an empty config`() {
        // review P1-2. Verified against MainHook#loadSnapshot: a v2-valid payload with no `fields`
        // object is "structurally incomplete, not an instruction to stop spoofing" — the hook keeps
        // its last-known-good Snapshot and carries on spoofing.
        //
        // Reporting that as "0 个字段、全部透传" tells the user nothing is being spoofed while the
        // hook is actively spoofing from a config the UI cannot see. Absent must be distinguishable
        // from empty.
        val p = PublishedConfig.parse("""{"schemaVersion":2,"mode":"off"}""")!!
        assertTrue(p.fields.isEmpty())
        assertEquals(false, p.fieldsPresent)
        assertEquals("off", p.mode)
    }

    @Test
    fun `an explicitly empty fields object really does mean nothing is configured`() {
        val p = PublishedConfig.parse("""{"schemaVersion":2,"fields":{}}""")!!
        assertTrue(p.fields.isEmpty())
        assertEquals(true, p.fieldsPresent)
    }

    @Test
    fun `mode defaults to always_on to match the hook's own default`() {
        val p = PublishedConfig.parse("""{"schemaVersion":2,"fields":{}}""")!!
        assertEquals("always_on", p.mode)
    }

    @Test
    fun `active hours are parsed when present`() {
        val p = PublishedConfig.parse(
            """{"schemaVersion":2,"mode":"time_based","activeHours":{"start":7,"end":22},"fields":{}}"""
        )!!
        assertEquals(7, p.activeHourStart)
        assertEquals(22, p.activeHourEnd)
    }

    @Test
    fun `malformed payload parses to null so the caller can report it instead of crashing`() {
        assertNull(PublishedConfig.parse("not json at all"))
        assertNull(PublishedConfig.parse(""))
    }

    @Test
    fun `missing schema version is reported as unknown rather than assumed compatible`() {
        val p = PublishedConfig.parse("""{"fields":{}}""")!!
        assertEquals(PublishedConfig.SCHEMA_UNKNOWN, p.schemaVersion)
    }

    @Test
    fun `fingerprint matches the write side algorithm so UI log and probe agree`() {
        // ConfigPrefsSync logs sha256(payload) truncated to 16 hex chars; the screen must render the
        // identical string or config provenance cannot be cross-checked against logcat.
        val json = """{"schemaVersion":2,"fields":{"tac":1}}"""
        val fp = PublishedConfig.fingerprint(json)
        assertTrue(fp.startsWith("sha256:"))
        assertEquals("sha256:".length + 16, fp.length)
        assertEquals(fp, PublishedConfig.fingerprint(json))
    }

    @Test
    fun `different payloads produce different fingerprints`() {
        val a = PublishedConfig.fingerprint("""{"schemaVersion":2,"fields":{"tac":1}}""")
        val b = PublishedConfig.fingerprint("""{"schemaVersion":2,"fields":{"tac":2}}""")
        assertTrue(a != b)
    }

    @Test
    fun `parses the exact payload published on the moto g54 test device`() {
        // Captured verbatim from /data/misc/.../spoof_config.xml on the reference device
        // (moto g54 5G / Android 15). Locks the parser against real production output rather than
        // only against payloads this test authored itself.
        val real = """{"schemaVersion":2,"activeHours":{"start":7,"end":22},"mode":"always_on",""" +
            """"fields":{"latitude":50.255807131309595,"longitude":28.65328009396879,""" +
            """"addname":"50.255807, 28.653280","tac":26999}}"""

        val p = PublishedConfig.parse(real)!!
        assertEquals(2, p.schemaVersion)
        assertEquals("always_on", p.mode)
        assertEquals(7, p.activeHourStart)
        assertEquals(22, p.activeHourEnd)

        // Full double precision must survive — a truncated latitude would read as a MISMATCH
        // against the location the app reports back.
        assertEquals("50.255807131309595", p.fields["latitude"])
        assertEquals("28.65328009396879", p.fields["longitude"])
        // Integer column stays integral rather than becoming "26999.0".
        assertEquals("26999", p.fields["tac"])
        // addname rides along in the payload but has no FieldSpec row, so it must surface as an
        // unmapped column rather than silently masquerading as a verifiable field.
        assertEquals("50.255807, 28.653280", p.fields["addname"])
        assertEquals(4, p.fields.size)
    }

    @Test
    fun `nested objects inside fields are skipped rather than stringified`() {
        // Only scalars are spoofable; a nested object would render as unusable JSON noise in the UI.
        val p = PublishedConfig.parse(
            """{"schemaVersion":2,"fields":{"tac":1,"weird":{"a":1}}}"""
        )!!
        assertEquals(setOf("tac"), p.fields.keys)
    }
}
