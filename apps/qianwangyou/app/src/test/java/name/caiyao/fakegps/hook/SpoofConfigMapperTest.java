package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import name.caiyao.fakegps.config.ConfigCodec;
import name.caiyao.fakegps.config.SpoofConfig;
import org.junit.Test;

/**
 * Locks the SpoofConfig -> Snapshot mapping (JVM-only). Configs are built from JSON via
 * ConfigCodec so the test also exercises the real transport shape the hook layer will see.
 */
public class SpoofConfigMapperTest {

    @Test
    public void mapsMigratedFields_restStayPassthrough() {
        SpoofConfig cfg = ConfigCodec.INSTANCE.fromJson(
            "{\"schemaVersion\":1,\"location\":{\"latitude\":35.6895,\"longitude\":139.6917,\"accuracy\":5.0},"
                + "\"lteCell\":{\"tac\":100,\"ci\":12345,\"pci\":234,\"earfcn\":1850,\"rsrp\":-85,\"rsrq\":-9,\"sinr\":18}}");
        Snapshot s = SpoofConfigMapper.toSnapshot(cfg);
        // migrated fields present
        assertEquals(35.6895, s.latitude, 0.0);
        assertEquals(139.6917, s.longitude, 0.0);
        assertEquals(Integer.valueOf(234), s.pci);
        assertEquals(Integer.valueOf(-85), s.lteRsrp);
        assertEquals(Float.valueOf(5f), s.accuracy);
        // not-yet-migrated groups => null => passthrough (no fabricated "perfect" data)
        assertNull(s.mcc);
        assertNull(s.nci);
        assertNull(s.wifiSsid);
        assertNull(s.altitude); // was absent in config
    }

    @Test
    public void nullConfig_isFullPassthrough() {
        Snapshot s = SpoofConfigMapper.toSnapshot(null);
        assertFalse(s.hasLocation());
        assertNull(s.latitude);
        assertNull(s.lteRsrp);
    }

    @Test
    public void partialConfig_onlyMapsPresentGroups() {
        SpoofConfig cfg = ConfigCodec.INSTANCE.fromJson(
            "{\"location\":{\"latitude\":1.0,\"longitude\":2.0}}");
        Snapshot s = SpoofConfigMapper.toSnapshot(cfg);
        assertTrue(s.hasLocation());
        assertNull("LTE absent => passthrough", s.pci);
        assertNull("WiFi absent => passthrough", s.wifiBssid);
    }

    @Test
    public void endToEnd_jsonStringToSnapshot() {
        SpoofConfig cfg = ConfigCodec.INSTANCE.fromJson(
            "{\"schemaVersion\":1,\"mode\":\"always_on\",\"location\":{\"latitude\":48.8566,\"longitude\":2.3522},"
                + "\"lteCell\":{\"pci\":77,\"rsrp\":-90},"
                + "\"wifi\":{\"ssid\":\"cafe\",\"bssid\":\"aa:bb:cc:dd:ee:ff\",\"rssi\":-52,\"frequency\":5180}}");
        Snapshot s = SpoofConfigMapper.toSnapshot(cfg);
        assertEquals(48.8566, s.latitude, 0.0);
        assertEquals(Integer.valueOf(77), s.pci);
        assertEquals(Integer.valueOf(-90), s.lteRsrp);
        assertEquals("cafe", s.wifiSsid);
        assertEquals("aa:bb:cc:dd:ee:ff", s.wifiBssid);
        assertEquals(Integer.valueOf(5180), s.wifiFrequency);
    }
}
