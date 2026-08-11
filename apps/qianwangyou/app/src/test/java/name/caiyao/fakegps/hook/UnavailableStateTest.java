package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import name.caiyao.fakegps.config.UnavailableSpec;
import org.junit.Test;

/**
 * Locks the third profile state ("--" = report as unavailable).
 *
 * <p>Two invariants live here:
 * <ol>
 *   <li>the field capability table classifies each column by how (or whether) it can express
 *       "no data" — a blanket sentinel would emit illegal values for text/boolean/location;</li>
 *   <li>arithmetic on field values must never be applied to a sentinel — jittering
 *       {@code Integer.MAX_VALUE} overflows into a plausible-looking negative reading, i.e.
 *       silently converts "no data" into fake data (reviewer constraint 3).</li>
 * </ol>
 */
public class UnavailableStateTest {

    // --- 1. Field capability table ---

    @Test
    public void cellularIntegerFields_useIntSentinel() {
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("tac"));
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("lte_rsrp"));
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("mcc"));
    }

    @Test
    public void nci_usesLongSentinel() {
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("nci"));
    }

    /** Only the operator/SIM group uses "" — Wi-Fi identity text has different unknowns. */
    @Test
    public void operatorTextFields_useEmptyString() {
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("operator_name"));
        assertEquals(UnavailableSpec.Kind.SUPPORTED, UnavailableSpec.kindOf("sim_operator"));
    }

    /** Location geometry has no "unavailable" form — the UI must not offer "--" there. */
    @Test
    public void locationFields_areUnsupported() {
        assertFalse(UnavailableSpec.supportsUnavailable("latitude"));
        assertFalse(UnavailableSpec.supportsUnavailable("longitude"));
        assertFalse(UnavailableSpec.supportsUnavailable("accuracy"));
    }

    /** A boolean has no third value; accepting "--" would make the UI lie. */
    @Test
    public void booleanFields_areUnsupported() {
        assertFalse(UnavailableSpec.supportsUnavailable("is_roaming"));
        assertFalse(UnavailableSpec.supportsUnavailable("wifi_enabled"));
    }

    @Test
    public void cellularAndTextFields_supportUnavailable() {
        assertTrue(UnavailableSpec.supportsUnavailable("tac"));
        assertTrue(UnavailableSpec.supportsUnavailable("nci"));
        assertTrue(UnavailableSpec.supportsUnavailable("operator_name"));
    }

    /** Non-cellular groups stay unavailable-incapable rather than guessing a sentinel. */
    @Test
    public void unverifiedGroups_failClosed() {
        assertFalse("Wi-Fi RSSI unknown is -127, not MAX_VALUE",
                UnavailableSpec.supportsUnavailable("wifi_rssi"));
        assertFalse("SSID unknown is \"<unknown ssid>\", not \"\"",
                UnavailableSpec.supportsUnavailable("wifi_ssid"));
    }

    // --- 2. Fluctuation must not corrupt a sentinel ---

    /**
     * RED before the fix: {@code MAX_VALUE + jitter} overflows to a large negative number,
     * which reads as a perfectly plausible (and completely fabricated) signal level.
     */
    @Test
    public void fluctuation_leavesUnavailableSentinelIntact() {
        Snapshot s = new Snapshot();
        s.signalFluctuationEnabled = true;
        s.signalFluctuationRangeDb = 10;

        int out = s.fluctuate(UnavailableSpec.UNAVAILABLE_INT, new Random(42));

        assertEquals("a sentinel must pass through fluctuation untouched",
                UnavailableSpec.UNAVAILABLE_INT, out);
        assertTrue("must never overflow into a negative 'measurement'", out > 0);
    }

    /** Real measurements must still jitter — the guard must not disable fluctuation wholesale. */
    @Test
    public void fluctuation_stillAppliesToRealValues() {
        Snapshot s = new Snapshot();
        s.signalFluctuationEnabled = true;
        s.signalFluctuationRangeDb = 10;

        int out = s.fluctuate(-85, new Random(42));

        assertTrue("expected -85 +/- 5, got " + out, out >= -90 && out <= -80);
    }

    @Test
    public void snapshotMaterializesCanonicalHookValuesAndRetainsDecisionSet() {
        Set<String> selected = new LinkedHashSet<>(Arrays.asList(
                "lac", "mcc", "mnc", "operator_name", "network_type", "data_state",
                "band", "physical_cell_id", "lte_rsrp"));

        Snapshot s = Snapshot.from(new EmptySource(), selected);

        assertEquals(Integer.valueOf(Integer.MAX_VALUE), s.lac);
        assertEquals("", s.operatorName);
        assertEquals(Integer.valueOf(0), s.networkType);
        assertEquals(Integer.valueOf(0), s.dataState);
        assertEquals(Integer.valueOf(0), s.band);
        assertEquals(Integer.valueOf(-1), s.physicalCellId);
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), s.lteRsrp);
        assertTrue(s.isUnavailable("lac"));
        assertTrue(s.isUnavailable("operator_name"));

        selected.clear();
        assertTrue("snapshot must defensively copy the decision set", s.isUnavailable("lac"));
    }

    @Test
    public void unavailableOnly_doesNotActivateCellReconstruction() {
        Snapshot s = Snapshot.from(new EmptySource(), new LinkedHashSet<>(Arrays.asList(
                "lac", "tac", "nci", "band")));

        assertFalse("unavailable is a getter decision, not a request to fabricate GSM",
                s.hasGsmRatConstruction());
        assertFalse("unavailable is a getter decision, not a request to fabricate WCDMA",
                s.hasWcdmaRatConstruction());
        assertFalse("unavailable is a getter decision, not a request to fabricate LTE",
                s.hasLteRatConstruction());
        assertFalse("unavailable is a getter decision, not a request to fabricate NR",
                s.hasNrRatConstruction());
        assertFalse(s.hasCellReconstructionDecision());
        assertFalse("unavailable must not synthesize a PhysicalChannelConfig",
                s.hasPhysicalChannelConfig());
    }

    @Test
    public void unavailableOnly_activatesExistingGsmCellLocationSurface() {
        Snapshot s = Snapshot.from(new EmptySource(), new LinkedHashSet<>(Arrays.asList(
                "lac", "cid", "psc")));

        assertTrue("existing CellLocation must receive the unavailable projection",
                s.hasGsmCellLocationDecision());
        assertFalse("unavailable-only must still not fabricate a CellInfo RAT",
                s.hasCellReconstructionDecision());
    }

    @Test
    public void configuredPscOnly_activatesExistingGsmCellLocationSurface() {
        Snapshot s = new Snapshot();
        s.psc = 321;

        assertTrue("PSC is exposed by GsmCellLocation even without GSM reconstruction fields",
                s.hasGsmCellLocationDecision());
        assertFalse("PSC must not select GSM", s.hasGsmRatConstruction());
        assertTrue("PSC is WCDMA-specific and may select WCDMA construction",
                s.hasWcdmaRatConstruction());
    }

    @Test
    public void emptyProfile_doesNotActivateGsmCellLocationSurface() {
        assertFalse(new Snapshot().hasGsmCellLocationDecision());
    }

    @Test
    public void emptyCellConstruction_failsSafeToPassthrough() {
        assertNull(Snapshot.acceptBuiltCellListOrPassthrough(new ArrayList<>()));
        ArrayList<Object> built = new ArrayList<>();
        built.add(new Object());
        assertEquals(built, Snapshot.acceptBuiltCellListOrPassthrough(built));
    }

    @Test
    public void blankNeighborConfigPreservesRealNeighborsAndUntouchedRats() {
        assertTrue(Snapshot.shouldPreserveRealCell(false, true, false));
        assertTrue(Snapshot.shouldPreserveRealCell(true, false, false));
        assertFalse(Snapshot.shouldPreserveRealCell(true, true, false));
        assertFalse(Snapshot.shouldPreserveRealCell(false, false, true));
        assertTrue("configured neighbors must not delete the real serving cell",
                Snapshot.shouldPreserveRealCell(true, false, true));
    }

    @Test
    public void preservedCellBypass_dependsOnRegistrationAndServingReconstruction() {
        assertTrue("real neighbor always bypasses serving getter hooks",
                Snapshot.shouldBypassPreservedRealCell(false, false));
        assertFalse("neighbor-only mutation keeps the framework serving object registered",
                Snapshot.shouldBypassPreservedRealCell(true, false));
        assertTrue("an old serving RAT becomes a bypassed neighbor when a new RAT is built",
                Snapshot.shouldBypassPreservedRealCell(true, true));
    }

    @Test(expected = IllegalArgumentException.class)
    public void snapshotRejectsUnsupportedUnavailableField() {
        Snapshot.from(new EmptySource(), java.util.Collections.singleton("is_roaming"));
    }

    @Test
    public void exceptionalSurfacesDoNotReuseSnapshotSentinel() {
        assertEquals(Integer.valueOf(-1),
                Snapshot.resolveGsmCellLocationField("lac", Integer.MAX_VALUE, 42, true));
        assertEquals(Integer.valueOf(42),
                Snapshot.resolveGsmCellLocationField("cid", null, 42, false));
        assertEquals(Integer.valueOf(-1),
                Snapshot.resolveGsmCellLocationField("psc", Integer.MAX_VALUE, 42, true));
        org.junit.Assert.assertNull(
                Snapshot.resolvePlmnString("mcc", Integer.MAX_VALUE, "460", true));
        assertEquals("460", Snapshot.resolvePlmnString("mcc", null, "460", false));
        assertEquals("310", Snapshot.resolvePlmnString("mcc", 310, "460", false));
        assertEquals("00", Snapshot.resolvePlmnString("mnc", 0, "00", false));
        assertEquals("03", Snapshot.resolvePlmnStringValue("mnc", 3, "260"));
        assertEquals("003", Snapshot.resolvePlmnStringValue("mnc", "003", "260"));
    }

    private static final class EmptySource implements Snapshot.FieldSource {
        @Override public Double getDouble(String col) { return null; }
        @Override public Float getFloat(String col) { return null; }
        @Override public Integer getInt(String col) { return null; }
        @Override public Long getLong(String col) { return null; }
        @Override public String getString(String col) { return null; }
        @Override public Boolean getBool(String col) { return null; }
    }
}
