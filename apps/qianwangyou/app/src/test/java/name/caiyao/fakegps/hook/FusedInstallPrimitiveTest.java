package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Sol R7 red-first tests for the task tracker's identity semantics (registry transaction
 * semantics live in {@link FusedInstallTransactionTest}).
 *
 * R7 #3: the task tracker must use reference identity — two equals-equal but
 * non-identical tasks must NOT pollute each other.
 */
public class FusedInstallPrimitiveTest {

    /** Two equals-equal but non-identical objects must not share tracking. */
    @Test
    public void trackerUsesReferenceIdentityNotEquals() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        String a = new String("coord");
        String b = new String("coord");
        assertEquals(a, b); // equals-equal, non-identical
        tracker.mark(a);
        assertTrue(tracker.isTracked(a));
        assertFalse("equals-equal but non-identical object must NOT be tracked",
                tracker.isTracked(b));
    }

    /** Objects with mutable hashCode remain findable (hash captured at insert). */
    @Test
    public void trackerSurvivesMutableHashCode() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        StringBuilder mutable = new StringBuilder("x");
        tracker.mark(mutable);
        mutable.append("-mutated"); // changes hashCode()
        assertTrue(tracker.isTracked(mutable));
    }

    /** Cleared referents do not accumulate forever (weak semantics smoke test). */
    @Test
    public void trackerDoesNotResurrectClearedReferences() {
        FusedTaskTracker tracker = new FusedTaskTracker();
        Object temp = new Object();
        tracker.mark(temp);
        assertTrue(tracker.isTracked(temp));
        temp = null;
        assertFalse(tracker.isTracked(new Object()));
    }
}
