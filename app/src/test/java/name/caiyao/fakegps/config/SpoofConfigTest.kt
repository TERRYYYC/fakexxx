package name.caiyao.fakegps.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Config-channel contract tests (JVM-only, no device required).
 * Locks the three semantics the reviewer's gate cares about:
 *   1. round-trip + NULL=passthrough
 *   2. stable fingerprint
 *   3. last-known-good on failed hot-reload (no real-data leak mid-test)
 */
class SpoofConfigTest {

    // --- 1. Serialization round-trip + NULL = passthrough ---

    @Test
    fun roundTrip_preservesValuesAndNulls() {
        val cfg = SpoofConfig(
            mode = SpoofConfig.Mode.always_on,
            location = SpoofConfig.Location(latitude = 35.6895, longitude = 139.6917, accuracy = 5f),
            lteCell = SpoofConfig.LteCell(pci = 234, rsrp = -85),
            // wifi intentionally left null => passthrough
        )
        val back = ConfigCodec.fromJson(ConfigCodec.toJson(cfg))
        assertEquals(cfg, back)
        assertNull("wifi not set => passthrough", back.wifi)
        assertNull("altitude not set => passthrough", back.location?.altitude)
    }

    @Test
    fun absentFields_decodeAsNull_meaningPassthrough() {
        // Only latitude/longitude present; everything else must come back null.
        val json = """{"location":{"latitude":1.0,"longitude":2.0}}"""
        val cfg = ConfigCodec.fromJson(json)
        assertNull(cfg.lteCell)
        assertNull(cfg.wifi)
        assertNull(cfg.location?.accuracy)
    }

    // --- 2. Fingerprint stability & sensitivity ---

    @Test
    fun fingerprint_isStableForEqualConfigs() {
        val a = SpoofConfig(location = SpoofConfig.Location(latitude = 1.0, longitude = 2.0))
        val b = SpoofConfig(location = SpoofConfig.Location(latitude = 1.0, longitude = 2.0))
        assertEquals(ConfigCodec.fingerprint(a), ConfigCodec.fingerprint(b))
    }

    @Test
    fun fingerprint_changesWhenAnyValueChanges() {
        val a = SpoofConfig(location = SpoofConfig.Location(latitude = 1.0, longitude = 2.0))
        val b = SpoofConfig(location = SpoofConfig.Location(latitude = 1.0, longitude = 2.5))
        assertNotEquals(ConfigCodec.fingerprint(a), ConfigCodec.fingerprint(b))
    }

    // --- 3. ConfigHolder: last-known-good semantics ---

    @Test
    fun holder_startsNull_meaningPassthrough() {
        assertNull(ConfigHolder().current())
    }

    @Test
    fun holder_validUpdate_replacesCurrent() {
        val holder = ConfigHolder()
        val json = ConfigCodec.toJson(
            SpoofConfig(location = SpoofConfig.Location(latitude = 10.0, longitude = 20.0))
        )
        val res = holder.update(json)
        assertTrue(res.isSuccess)
        assertEquals(10.0, holder.current()?.location?.latitude!!, 0.0)
    }

    @Test
    fun holder_invalidUpdate_keepsLastKnownGood_neverRevertsToReal() {
        val holder = ConfigHolder()
        holder.update(
            ConfigCodec.toJson(SpoofConfig(location = SpoofConfig.Location(latitude = 10.0, longitude = 20.0)))
        )
        // A corrupt hot-reload arrives while a spoof is active.
        val res = holder.update("{ this is not valid json ]")
        assertTrue("corrupt update must fail", res.isFailure)
        // CRITICAL: still the spoofed config, NOT null/passthrough — no real-environment leak mid-test.
        assertEquals(10.0, holder.current()?.location?.latitude!!, 0.0)
    }

    @Test
    fun holder_incompatibleSchemaVersion_keepsLastKnownGood() {
        val holder = ConfigHolder()
        holder.update(
            ConfigCodec.toJson(SpoofConfig(location = SpoofConfig.Location(latitude = 10.0, longitude = 20.0)))
        )
        // A SYNTACTICALLY VALID but semantically incompatible (future-version) snapshot.
        // last-known-good must reject this too — not just corrupt JSON (reviewer P1).
        val futureJson = """{"schemaVersion":999,"mode":"always_on","location":{"latitude":50.0,"longitude":60.0}}"""
        val res = holder.update(futureJson)
        assertTrue("unknown schemaVersion must fail", res.isFailure)
        // still v1's spoofed config, NOT the 999 payload => no silent takeover by incompatible config.
        assertEquals(10.0, holder.current()?.location?.latitude!!, 0.0)
    }
}
