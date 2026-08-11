package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.Test;

/** Regression contracts derived from the API-35 public getter acceptance matrix. */
public class CellPublicGetterMappingTest {

    @Test
    public void wcdmaDbmPrefersRscpAndFallsBackToLegacyRssiAlias() {
        assertEquals(
                Integer.valueOf(-88),
                Snapshot.resolveWcdmaDbm(-88, -85));
        assertEquals(
                Integer.valueOf(-85),
                Snapshot.resolveWcdmaDbm(null, -85));
        assertNull(Snapshot.resolveWcdmaDbm(null, null));
    }

    @Test
    public void physicalUplinkBandwidthUsesChannelBandwidthColumn() {
        assertEquals(
                Integer.valueOf(40_000),
                Snapshot.resolvePhysicalUplinkBandwidth(40_000));
        assertNull(Snapshot.resolvePhysicalUplinkBandwidth(null));
    }

    @Test
    public void displayNetworkTypeUsesConfiguredNetworkType() {
        assertEquals(
                Integer.valueOf(20),
                Snapshot.resolveDisplayNetworkType(20));
        assertNull(Snapshot.resolveDisplayNetworkType(null));
    }

    @Test
    public void signalFluctuationHonorsDisabledFlagAndConfiguredRange() {
        Snapshot snapshot = new Snapshot();
        snapshot.signalFluctuationEnabled = false;
        snapshot.signalFluctuationRangeDb = 6;
        assertEquals(-101, snapshot.fluctuate(-101, new Random(17)));

        snapshot.signalFluctuationEnabled = true;
        Set<Integer> observed = new HashSet<>();
        Random deterministic = new Random(17);
        for (int index = 0; index < 100; index++) {
            int value = snapshot.fluctuate(-101, deterministic);
            assertTrue(value >= -104);
            assertTrue(value <= -98);
            observed.add(value);
        }
        assertTrue("enabled fluctuation must produce more than one value", observed.size() > 1);
    }
}
