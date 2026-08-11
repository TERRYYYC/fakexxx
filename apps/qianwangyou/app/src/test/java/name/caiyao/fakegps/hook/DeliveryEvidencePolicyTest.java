package name.caiyao.fakegps.hook;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Per-delivery evidence policy: turns every hooked location delivery into BOUNDED,
 * value-free evidence.
 *
 * Why this exists: before it, the only fused evidence was install-time
 * (fused_surface_hooked / fused_delivery_summary), which fires once when listeners
 * register. That makes "no events in the last 60s" indistinguishable between
 * "hook delivered correctly and quietly" and "hook stopped delivering" — the exact
 * ambiguity that forced Issue #15's A/B matrix to a BLOCKED verdict.
 */
public class DeliveryEvidencePolicyTest {

    /** -1 marks "intentionally silent" so the existing gate assertions stay readable. */
    private static int covered(DeliveryEvidencePolicy.Emission e) {
        return e == null ? -1 : e.deliveries;
    }

    // ---- classification ----

    @Test
    public void incomingValueEqualToProfileIsClassifiedEqualsProfile() {
        assertEquals(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 50.45, 30.52));
    }

    @Test
    public void incomingValueDifferentFromProfileIsClassifiedNotEqual() {
        assertEquals(
                DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 37.77, -122.41));
    }

    @Test
    public void absentIncomingValueIsClassifiedNoObserved() {
        assertEquals(
                DeliveryEvidencePolicy.NO_OBSERVED,
                DeliveryEvidencePolicy.classify(50.45, 30.52, null, null));
        assertEquals(
                DeliveryEvidencePolicy.NO_OBSERVED,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 50.45, null));
    }

    @Test
    public void subMillimeterFloatDriftStillCountsAsProfile() {
        // createFakeLocation writes the profile doubles, but values round-trip through
        // float-backed Location accessors. Drift far below any real-vs-mock distance
        // must not masquerade as a revert.
        assertEquals(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.classify(50.45, 30.52, 50.450000001, 30.520000001));
    }

    @Test
    public void classificationNeverEchoesACoordinate() {
        String c = DeliveryEvidencePolicy.classify(50.4512345, 30.5298765, 37.7749295, -122.4194155);
        assertEquals(DeliveryEvidencePolicy.NOT_EQUAL, c);
        // value-free contract: the classification is an enum-like token, never a payload.
        assertNotEquals(-1, "EQUALS_PROFILE|NOT_EQUAL|NO_OBSERVED".indexOf(c));
    }

    // ---- bounded emission gate ----

    @Test
    public void firstDeliveryAlwaysEmits() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 1_000L)));
    }

    @Test
    public void steadyStateDeliveriesStaySilentUntilHeartbeat() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 1_000L)));
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 29_999L)));
    }

    @Test
    public void heartbeatEmitsAndReportsSuppressedDeliveryCount() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 10_000L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 20_000L);
        // 3 deliveries covered by this heartbeat: the two silent ones plus this one.
        assertEquals(3, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 30_000L)));
    }

    @Test
    public void classificationChangeEmitsImmediatelyEvenInsideHeartbeatWindow() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        // A revert must never wait for the heartbeat: this is the snap-back detector.
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.NOT_EQUAL, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 500L)));
    }

    @Test
    public void countResetsAfterEachEmission() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 10_000L);
        assertEquals(2, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 30_000L)));
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 31_000L)));
        assertEquals(2, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 60_000L)));
    }

    @Test
    public void separateSurfacesDoNotShareGateState() {
        DeliveryEvidencePolicy a = new DeliveryEvidencePolicy();
        DeliveryEvidencePolicy b = new DeliveryEvidencePolicy();
        assertEquals(1, covered(a.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L)));
        assertEquals(1, covered(b.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L)));
    }

    // ---- P2-1: the input classification is a SEPARATE, honestly named axis ----
    //
    // Review finding: classifying the pre-replacement input and labelling it
    // EQUALS_PROFILE/NOT_EQUAL inverts the verdict — a healthy interception (real input
    // replaced by the profile) reported NOT_EQUAL, which the A/B harness reads as a
    // snap-back. The delivered value is what the surface promises to describe; the input
    // is separately useful but must never borrow the delivery tokens.

    @Test
    public void interceptedRealInputIsAPositiveFactNotADeliveryFailure() {
        assertEquals(
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 37.77, -122.41));
    }

    @Test
    public void inputAlreadyMatchingProfileIsReportedSeparately() {
        assertEquals(
                DeliveryEvidencePolicy.INPUT_EQUALS_PROFILE,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 50.45, 30.52));
    }

    @Test
    public void absentInputIsItsOwnTokenNotADeliveryToken() {
        assertEquals(
                DeliveryEvidencePolicy.INPUT_ABSENT,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, null, null));
    }

    @Test
    public void inputTokensNeverCollideWithDeliveryTokens() {
        // The whole defect was one vocabulary serving two questions.
        assertNotEquals(
                DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 37.77, -122.41));
        assertNotEquals(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.classifyInput(50.45, 30.52, 50.45, 30.52));
    }

    // ---- P2: a two-axis event needs a two-axis gate ----
    //
    // Review finding: the gate keyed on the delivered axis alone. That axis is near-constant
    // by construction -- the outgoing Location is built from the same snapshot it is
    // compared against -- so the edge trigger was effectively dead on `input`, the axis that
    // actually varies. Worse, the emitted line pairs the CURRENT input with an ACCUMULATED
    // delivery count, so one line could claim N deliveries shared an input state they did
    // not. That is a mis-attribution in the very evidence #15's 4b/4c/4d depend on.

    @Test
    public void reviewerReproductionInputEdgesAreNoLongerSwallowed() {
        // Exactly the sequence reproduced against the previous exact-HEAD artifact:
        //   t=0     EQUALS/DIFFERS -> 1
        //   t=1000  EQUALS/ABSENT  -> was -1 (swallowed)
        //   t=30000 EQUALS/EQUALS  -> was 2  (claimed 2 deliveries shared input=EQUALS,
        //                                     though one of them was ABSENT)
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L)));
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_ABSENT, 1_000L)));
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_EQUALS_PROFILE, 30_000L)));
    }

    @Test
    public void aChangedStateIsVisibleInTheSameCallbackEvenWhenTheOldRunHasPending() {
        // Closing the old run must not cost the new state its immediate visibility. On a
        // sparse or one-shot surface there may be no later delivery and no heartbeat to
        // carry it, so deferring the edge makes it permanently invisible -- rebuilding the
        // very ambiguity this policy exists to remove, and contradicting the class contract
        // that "a classification change is emitted immediately".
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 500L)));

        DeliveryEvidencePolicy.Emission head = p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_ABSENT, 1_000L);
        // The run that ended is reported under its own tokens...
        assertEquals(DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, head.input);
        assertEquals(1, head.deliveries);
        // ...and the state that just began is reported in the SAME callback.
        assertNotNull("the new edge must not wait for a later delivery", head.next);
        assertEquals(DeliveryEvidencePolicy.INPUT_ABSENT, head.next.input);
        assertEquals(DeliveryEvidencePolicy.EQUALS_PROFILE, head.next.delivered);
        assertEquals(1, head.next.deliveries);
    }

    @Test
    public void aOneShotSurfaceStillLeavesEvidenceForItsFinalState() {
        // No heartbeat will ever arrive here. If the edge is not emitted on the spot, the
        // ABSENT state simply never existed as far as the acceptance harness can tell.
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 500L);
        DeliveryEvidencePolicy.Emission last = p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_ABSENT, 1_000L);
        boolean absentIsRepresented = false;
        for (DeliveryEvidencePolicy.Emission e = last; e != null; e = e.next) {
            if (DeliveryEvidencePolicy.INPUT_ABSENT.equals(e.input)) absentIsRepresented = true;
        }
        assertTrue("final state has no evidence line at all", absentIsRepresented);
    }

    @Test
    public void everyLinesTokensDescribeEveryDeliveryThatLineCounts() {
        // The count is only meaningful if every delivery it covers shared BOTH axes, so the
        // tokens must travel WITH the count. Printing the caller's current input against an
        // accumulated count is precisely how one line came to claim N deliveries shared a
        // state they did not. Note this is NOT "a line only ever closes a run": the last
        // assertion below is an OPENING line, naming the state it just began with count 1.
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 1_000L)));
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 2_000L)));

        // Input flips. The two suppressed deliveries were DIFFERS, so the line that fires
        // must be labelled DIFFERS and count exactly those two -- never ABSENT.
        DeliveryEvidencePolicy.Emission closingDiffers = p.record(
                DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_ABSENT, 3_000L);
        assertEquals(2, closingDiffers.deliveries);
        assertEquals(DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, closingDiffers.input);
        assertEquals(DeliveryEvidencePolicy.EQUALS_PROFILE, closingDiffers.delivered);
        // The closing line must not swallow the edge: ABSENT opens in the same callback.
        assertNotNull(closingDiffers.next);
        assertEquals(DeliveryEvidencePolicy.INPUT_ABSENT, closingDiffers.next.input);

        // Flips back. ABSENT's only delivery was already reported when it opened, so there
        // is no leftover run to close -- just the DIFFERS edge, alone and count=1. Asserting
        // a stale ABSENT line here would re-pin the very defect this contract removed.
        DeliveryEvidencePolicy.Emission reopened = p.record(
                DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 4_000L);
        assertEquals(DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, reopened.input);
        assertEquals(1, reopened.deliveries);
        assertNull("nothing left to close", reopened.next);
    }

    @Test
    public void onlyBothAxesUnchangedStaysSilent() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 0L);
        // delivered changes -> emit
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 100L)));
        // input changes -> emit
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.INPUT_ABSENT, 200L)));
        // neither changes -> silent
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.NOT_EQUAL,
                DeliveryEvidencePolicy.INPUT_ABSENT, 300L)));
    }

    // ---- P3: heartbeat must not depend on a settable wall clock ----

    @Test
    public void clockGoingBackwardsStillHeartbeats() {
        // System time sync / manual change can move wall clock backwards. With a plain
        // (now - last >= HEARTBEAT) test that stays negative for as long as the jump,
        // healthy deliveries would go silent and re-create the exact "silence == stopped"
        // ambiguity this policy exists to remove.
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 1_000_000L);
        assertEquals(1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 5_000L)));
    }

    @Test
    public void backwardsJumpRebasesTheWindowInsteadOfLatchingOpen() {
        DeliveryEvidencePolicy p = new DeliveryEvidencePolicy();
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 1_000_000L);
        p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 5_000L);
        // After rebasing, normal suppression resumes; it must not emit on every delivery.
        assertEquals(-1, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 6_000L)));
        assertEquals(2, covered(p.record(DeliveryEvidencePolicy.EQUALS_PROFILE, DeliveryEvidencePolicy.INPUT_DIFFERS_PROFILE, 35_000L)));
    }
}
