package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Locks the cellular fail-safe semantics that review FC-3/FC-4/FC-5 found broken.
 *
 * These paths had ZERO test coverage (FC-7), which is precisely why the defects survived: the suite
 * stayed green while unconfigured fields were filled with constants (mcc=460) and a callback could
 * throw an NPE inside the target app. The rules asserted here are the ones a reviewer had to catch
 * by eye; from now on they fail the build instead.
 */
public class CellBaselineFailSafeTest {

    // ---- isUsableCellBaseline: "a CellInfo existed" is NOT a usable baseline ----------------

    @Test
    public void allUnknownIdentity_isNotUsableBaseline() {
        // The platform can return CellInfo entries whose fields are all "unknown"
        // (Integer.MAX_VALUE -> normalised to null). Accepting these let the builder fall back to
        // mcc=460/mnc=0 — a Chinese operator on a Ukrainian SIM, trivially inconsistent.
        assertFalse(Snapshot.isUsableCellBaseline(null, null, null, null, null, null, null));
    }

    @Test
    public void anySingleIdentityField_makesBaselineUsable() {
        assertTrue("ci alone", Snapshot.isUsableCellBaseline(28378431, null, null, null, null, null, null));
        assertTrue("tac alone", Snapshot.isUsableCellBaseline(null, 26999, null, null, null, null, null));
        assertTrue("pci alone", Snapshot.isUsableCellBaseline(null, null, 53, null, null, null, null));
        assertTrue("earfcn alone", Snapshot.isUsableCellBaseline(null, null, null, 39300, null, null, null));
        assertTrue("lac alone", Snapshot.isUsableCellBaseline(null, null, null, null, 1234, null, null));
        assertTrue("cid alone", Snapshot.isUsableCellBaseline(null, null, null, null, null, 5678, null));
        assertTrue("mcc alone", Snapshot.isUsableCellBaseline(null, null, null, null, null, null, 255));
    }

    // ---- resolveCellField: configured wins, real passes through, neither => null ------------

    @Test
    public void configuredValueWinsOverReal() {
        assertEquals(Integer.valueOf(99999), Snapshot.resolveCellField(99999, 1234));
    }

    @Test
    public void unconfiguredFieldKeepsRealValue() {
        // NULL = passthrough: a field the user never touched must keep tracking the device, not
        // get replaced by a constant.
        assertEquals(Integer.valueOf(1234), Snapshot.resolveCellField(null, 1234));
    }

    @Test
    public void neitherConfiguredNorReal_yieldsNullSoCallerPassesThrough() {
        // Must stay null rather than defaulting: the caller uses null to decide "pass the real
        // CellLocation through", and unboxing a null here was the FC-4 NPE inside the target app.
        assertNull(Snapshot.resolveCellField(null, null));
    }

    // ---- group predicates: NULL = passthrough contract (FC-1/B1) ----------------------------

    @Test
    public void lteGroupIsConfiguredWhenOnlyTacIsSet() {
        // Regression: LTE construction used to demand `ci`, so a tac-only profile was silently treated
        // as "no LTE configured" and every LTE hook no-op'd.
        Snapshot s = new Snapshot();
        s.tac = 26999;
        assertTrue(s.hasLteRatConstruction());
    }

    @Test
    public void emptySnapshotConfiguresNoCellGroup() {
        Snapshot s = new Snapshot();
        assertFalse(s.hasLteRatConstruction());
        assertFalse(s.hasGsmRatConstruction());
        assertFalse(s.hasWcdmaRatConstruction());
        assertFalse(s.hasNrRatConstruction());
        assertFalse(s.hasCellReconstructionDecision());
    }
}
