package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import org.junit.Test;

/**
 * Locks the LAST-KNOWN-GOOD invariant on the CURRENT production config path.
 *
 * <p>Context (review FC-2 / FC-7): the earlier JVM suite only covered the typed
 * SpoofConfig/ConfigHolder path, which is no longer on the runtime path — so when the
 * transport moved to flat `fields` JSON, the invariant silently regressed in two places
 * (missing prefs payload, and a compatible payload with no `fields` object). Both reverted an
 * ACTIVE spoof to real device data, which leaks the true environment to the app under test.
 *
 * <p>These tests fail if that regression is ever reintroduced.
 */
public class LastKnownGoodTest {

    /** An active spoof must survive an unreadable/incomplete refresh — never revert to real data. */
    @Test
    public void activeSpoof_isKeptWhenPayloadUnavailable() {
        Snapshot active = new Snapshot();
        active.latitude = 50.9462;
        active.longitude = 30.5238;

        Snapshot resolved = Snapshot.keepLastKnownGoodOr(active);

        assertSame("active spoof must be preserved, not reverted to real device data",
                active, resolved);
        assertNotSame("must not fall back to passthrough while a spoof is active",
                Snapshot.PASSTHROUGH, resolved);
    }

    /** First launch (nothing ever published): real values are the safe default. */
    @Test
    public void neverPublished_passesThrough() {
        assertSame("no config has ever been published -> passthrough is safe",
                Snapshot.PASSTHROUGH, Snapshot.keepLastKnownGoodOr(Snapshot.PASSTHROUGH));
    }

    /** Null current state is treated as "never published", not as a crash. */
    @Test
    public void nullState_passesThrough() {
        assertSame(Snapshot.PASSTHROUGH, Snapshot.keepLastKnownGoodOr(null));
    }

    /**
     * A spoof carrying only cellular fields (no location) still counts as ACTIVE.
     * Guards against a naive `hasLocation()`-based "is it active" check.
     */
    @Test
    public void cellularOnlySpoof_countsAsActive() {
        Snapshot cellOnly = new Snapshot();
        cellOnly.tac = 26999;

        assertSame("a cellular-only spoof is still an active spoof",
                cellOnly, Snapshot.keepLastKnownGoodOr(cellOnly));
    }
}
