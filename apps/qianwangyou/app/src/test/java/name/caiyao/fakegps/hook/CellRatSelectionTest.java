package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import org.junit.Test;

/** Locks the difference between shared identity projection and serving-RAT construction. */
public class CellRatSelectionTest {

    @Test
    public void sharedIdentity_doesNotSelectAnyServingRat() {
        Snapshot s = new Snapshot();
        s.mcc = 255;
        s.mnc = 3;
        s.lac = 1234;
        s.cid = 5678;

        assertFalse(s.hasGsmRatConstruction());
        assertFalse(s.hasWcdmaRatConstruction());
        assertFalse(s.hasLteRatConstruction());
        assertFalse(s.hasNrRatConstruction());
        assertFalse(s.hasCellReconstructionDecision());
    }

    @Test
    public void lteProfileWithSharedPlmn_selectsOnlyLte() {
        Snapshot s = new Snapshot();
        s.mcc = 255;
        s.mnc = 3;
        s.tac = 26999;
        s.ci = 28378431;

        assertFalse("shared PLMN must not fabricate GSM", s.hasGsmRatConstruction());
        assertFalse(s.hasWcdmaRatConstruction());
        assertTrue(s.hasLteRatConstruction());
        assertFalse(s.hasNrRatConstruction());
        assertTrue(s.hasCellReconstructionDecision());
    }

    @Test
    public void wcdmaProfileWithSharedArea_selectsOnlyWcdma() {
        Snapshot s = new Snapshot();
        s.lac = 1234;
        s.cid = 5678;
        s.psc = 321;

        assertFalse("shared LAC/CID must not fabricate GSM", s.hasGsmRatConstruction());
        assertTrue(s.hasWcdmaRatConstruction());
        assertFalse(s.hasLteRatConstruction());
        assertFalse(s.hasNrRatConstruction());
    }

    @Test
    public void gsmSpecificIdentity_selectsGsm() {
        Snapshot s = new Snapshot();
        s.arfcn = 512;

        assertTrue(s.hasGsmRatConstruction());
        assertTrue(s.hasCellReconstructionDecision());
    }

    @Test
    public void explicitMultipleRats_selectExactlyThoseRats() {
        Snapshot s = new Snapshot();
        s.bsic = 42;
        s.uarfcn = 10564;
        s.earfcn = 1650;
        s.nrarfcn = 640000;

        assertTrue(s.hasGsmRatConstruction());
        assertTrue(s.hasWcdmaRatConstruction());
        assertTrue(s.hasLteRatConstruction());
        assertTrue(s.hasNrRatConstruction());
    }

    @Test
    public void unavailableOnly_doesNotSelectAnyServingRat() {
        Snapshot s = Snapshot.from(new EmptySource(), new LinkedHashSet<>(Arrays.asList(
                "arfcn", "psc", "tac", "nci")));

        assertFalse(s.hasGsmRatConstruction());
        assertFalse(s.hasWcdmaRatConstruction());
        assertFalse(s.hasLteRatConstruction());
        assertFalse(s.hasNrRatConstruction());
        assertFalse(s.hasCellReconstructionDecision());
    }

    @Test
    public void signalOnly_doesNotFabricateIdentityObject() {
        Snapshot s = new Snapshot();
        s.gsmRssi = -85;
        s.wcdmaRscp = -90;
        s.lteRsrp = -100;
        s.nrSsRsrp = -105;

        assertFalse(s.hasCellReconstructionDecision());
    }

    @Test
    public void neighborOnly_mutatesListWithoutSelectingServingRat() {
        Snapshot s = new Snapshot();
        s.neighborCellsJson = "[]";

        assertFalse(s.hasCellReconstructionDecision());
        assertTrue(s.hasCellListMutationDecision());
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
