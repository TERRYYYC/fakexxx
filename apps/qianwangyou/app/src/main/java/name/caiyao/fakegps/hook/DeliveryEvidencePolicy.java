package name.caiyao.fakegps.hook;

/**
 * Turns every hooked location delivery into BOUNDED, value-free evidence.
 *
 * <p>Before this policy the only fused evidence was install-time
 * ({@code fused_surface_hooked}, {@code fused_delivery_summary}), emitted once when a
 * listener registers. That makes "no events for 60s" ambiguous between "the hook is
 * delivering correctly and quietly" and "the hook stopped delivering" — the ambiguity
 * that forced Issue #15's Maps A/B matrix to a BLOCKED verdict: continuous-update,
 * recenter and foreground/background stability were unobservable.
 *
 * <p>Two rules keep the evidence useful without turning a per-fix callback into a log
 * firehose:
 * <ul>
 *   <li><b>edge-triggered</b> — a classification change is emitted immediately, so a
 *       revert to the real position is never hidden behind a timer;</li>
 *   <li><b>heartbeat</b> — an unchanged classification is re-emitted at most once per
 *       {@link #HEARTBEAT_MS}, carrying the number of deliveries it covers, so silence
 *       means "not delivering" instead of "nothing to say".</li>
 * </ul>
 *
 * <p>Deliberately Android-free: it takes primitive coordinates rather than
 * {@code android.location.Location} so the whole policy is executable under plain JUnit
 * (the project ships no Robolectric, and {@code new Location(...)} throws {@code Stub!}).
 * It never stores or returns a coordinate — only one of three fixed tokens.
 */
final class DeliveryEvidencePolicy {

    /**
     * Delivery axis: what the target app actually received. These tokens describe the
     * OUTGOING value only.
     */
    static final String EQUALS_PROFILE = "EQUALS_PROFILE";
    static final String NOT_EQUAL = "NOT_EQUAL";
    static final String NO_OBSERVED = "NO_OBSERVED";

    /**
     * Interception axis: what the app would have received without the hook. Deliberately a
     * DISJOINT vocabulary.
     *
     * <p>The first cut reused the delivery tokens for this question, which inverted the
     * verdict: a healthy interception (real update displaced by the profile) emitted
     * NOT_EQUAL, and the acceptance harness reads NOT_EQUAL as a snap-back. Differing input
     * is a positive fact — it is the proof the hook is doing work — so it gets a name that
     * cannot be mistaken for a delivery failure.
     */
    static final String INPUT_EQUALS_PROFILE = "INPUT_EQUALS_PROFILE";
    static final String INPUT_DIFFERS_PROFILE = "INPUT_DIFFERS_PROFILE";
    static final String INPUT_ABSENT = "INPUT_ABSENT";

    /**
     * Sub-millimetre at any latitude: comfortably above the drift of round-tripping a
     * double through float-backed Location accessors, and orders of magnitude below any
     * real-versus-spoof separation we could ever need to distinguish.
     */
    private static final double EPSILON_DEG = 1e-6;

    static final long HEARTBEAT_MS = 30_000L;

    /**
     * One evidence line: the tokens it describes and how many deliveries it covers.
     *
     * <p>The tokens travel WITH the count on purpose. Gating on the delivered axis alone
     * and then printing the caller's current input against an accumulated count let one
     * line claim that N deliveries shared an input state they did not — a mis-attribution
     * in exactly the evidence #15's continuous-update / recenter / foreground-background
     * scenarios rely on. The invariant that removes it: a line's tokens describe EVERY
     * delivery that line counts. That holds whether the line closes a finished run
     * (count = its suppressed deliveries), opens a new state (count = 1), or beats an
     * ongoing one — never a count drawn from one state and tokens drawn from another.
     */
    static final class Emission {
        final String delivered;
        final String input;
        final int deliveries;

        /**
         * Non-null only when one callback owes TWO facts: a run that just ended still
         * holding suppressed deliveries, chained to the state that just began. A change
         * whose previous run owed nothing (no suppressed deliveries) reports the opening
         * line alone, with {@code next} null.
         *
         * <p>Chaining rather than returning a collection keeps every emission free of a
         * container allocation, while still making the edge visible in the same callback
         * -- a sparse or one-shot surface may never deliver again, and a deferred edge is
         * an edge that never existed.
         */
        final Emission next;

        Emission(String delivered, String input, int deliveries, Emission next) {
            this.delivered = delivered;
            this.input = input;
            this.deliveries = deliveries;
            this.next = next;
        }
    }

    // Two fields rather than a composite key: unambiguous by construction, with no
    // separator that a future token could collide with.
    private String runDelivered;
    private String runInput;
    private long lastEmitMs;
    private int pending;

    /**
     * Classify the value a delivery was about to carry against the active profile.
     * Returns a fixed token; coordinates are consumed, never retained or echoed.
     */
    static String classify(Double expectedLat, Double expectedLon,
                           Double observedLat, Double observedLon) {
        if (expectedLat == null || expectedLon == null) return NO_OBSERVED;
        if (observedLat == null || observedLon == null) return NO_OBSERVED;
        boolean same = Math.abs(expectedLat - observedLat) <= EPSILON_DEG
                && Math.abs(expectedLon - observedLon) <= EPSILON_DEG;
        return same ? EQUALS_PROFILE : NOT_EQUAL;
    }

    /**
     * Classify what the delivery would have carried WITHOUT the hook. Separate vocabulary
     * from {@link #classify} on purpose — see the INPUT_* constants.
     */
    static String classifyInput(Double expectedLat, Double expectedLon,
                                Double incomingLat, Double incomingLon) {
        if (expectedLat == null || expectedLon == null) return INPUT_ABSENT;
        if (incomingLat == null || incomingLon == null) return INPUT_ABSENT;
        boolean same = Math.abs(expectedLat - incomingLat) <= EPSILON_DEG
                && Math.abs(expectedLon - incomingLon) <= EPSILON_DEG;
        return same ? INPUT_EQUALS_PROFILE : INPUT_DIFFERS_PROFILE;
    }

    /**
     * Record one delivery on this surface.
     *
     * @return the evidence line(s) this delivery produces, or {@code null} when the
     *         delivery is intentionally silent. A state change whose previous run still
     *         held suppressed deliveries returns that closing line chained to the newly
     *         opened one; a change with nothing left to close returns the opening line
     *         alone. See {@link Emission#next}.
     */
    synchronized Emission record(String delivered, String input, long nowMs) {
        if (runDelivered == null) {
            runDelivered = delivered;
            runInput = input;
            lastEmitMs = nowMs;
            pending = 0;
            return new Emission(delivered, input, 1, null);
        }

        boolean changed = !runDelivered.equals(delivered) || !runInput.equals(input);
        if (changed) {
            // Two facts, two lines. The suppressed deliveries belong to the run that just
            // ENDED, so they are reported under that run's tokens -- and the state that
            // just BEGAN is reported here too, never deferred. Returning only the closing
            // line hid the edge until the next change or heartbeat, which on a sparse or
            // one-shot surface means it is never reported at all.
            Emission opening = new Emission(delivered, input, 1, null);
            Emission head = pending > 0
                    ? new Emission(runDelivered, runInput, pending, opening)
                    : opening;
            runDelivered = delivered;
            runInput = input;
            lastEmitMs = nowMs;
            // This delivery is already accounted for by `opening`.
            pending = 0;
            return head;
        }

        pending++;
        // A clock that moved backwards would leave (now - last) negative for as long as the
        // jump, muting healthy deliveries and re-creating the "silence == stopped" ambiguity
        // this policy exists to remove. Callers pass a monotonic clock; treating a backwards
        // step as heartbeat-due keeps the guarantee even if one does not, and rebases the
        // window so suppression resumes immediately afterwards rather than latching open.
        long elapsed = nowMs - lastEmitMs;
        if (elapsed < 0 || elapsed >= HEARTBEAT_MS) {
            Emission beat = new Emission(runDelivered, runInput, pending, null);
            pending = 0;
            lastEmitMs = nowMs;
            return beat;
        }
        return null;
    }
}
