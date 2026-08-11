package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class LocationDeliveryPolicyTest {

    @Test
    public void systemMockClearsOnlyLocationFields() {
        Snapshot snapshot = configuredSnapshot();

        Snapshot result = LocationDeliveryPolicy.apply(snapshot, "system_mock");

        assertFalse(result.hasLocation());
        assertEquals(null, result.latitude);
        assertEquals(null, result.longitude);
        assertEquals(null, result.altitude);
        assertEquals(null, result.speed);
        assertEquals(null, result.bearing);
        assertEquals(null, result.accuracy);
        assertEquals(Integer.valueOf(27101), result.tac);
        assertEquals("Kyiv-Lab", result.wifiSsid);
    }

    @Test
    public void hookAndUnknownModesKeepTheConfiguredSnapshot() {
        Snapshot hook = configuredSnapshot();
        Snapshot unknown = configuredSnapshot();

        LocationDeliveryPolicy.apply(hook, "hook");
        LocationDeliveryPolicy.apply(unknown, "future_mode");

        assertConfiguredLocation(hook);
        assertConfiguredLocation(unknown);
    }

    private static void assertConfiguredLocation(Snapshot snapshot) {
        assertEquals(Double.valueOf(50.4501), snapshot.latitude);
        assertEquals(Double.valueOf(30.5234), snapshot.longitude);
        assertEquals(Double.valueOf(179.0), snapshot.altitude);
        assertEquals(Float.valueOf(0.0f), snapshot.speed);
        assertEquals(Float.valueOf(0.0f), snapshot.bearing);
        assertEquals(Float.valueOf(3.0f), snapshot.accuracy);
    }

    private static Snapshot configuredSnapshot() {
        Snapshot snapshot = new Snapshot();
        snapshot.latitude = 50.4501;
        snapshot.longitude = 30.5234;
        snapshot.altitude = 179.0;
        snapshot.speed = 0.0f;
        snapshot.bearing = 0.0f;
        snapshot.accuracy = 3.0f;
        snapshot.tac = 27101;
        snapshot.wifiSsid = "Kyiv-Lab";
        return snapshot;
    }
}
