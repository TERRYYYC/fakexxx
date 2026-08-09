package name.caiyao.fakegps.hook;

/**
 * Pure decision logic for the fingerprint-based snapshot reload skip.
 *
 * Extracted from {@link MainHook} so that the decision can be behaviorally tested
 * without pulling in the Xposed API (which is {@code compileOnly}).
 *
 * <h3>Skip contract</h3>
 * A reload may be skipped ONLY when:
 * <ol>
 *   <li>The current payload fingerprint is non-null (excludes cold-start and parse failures)</li>
 *   <li>The fingerprint matches the previously accepted fingerprint (payload unchanged)</li>
 *   <li>The evaluation hour matches the last evaluated hour (no hour-boundary crossing)</li>
 * </ol>
 *
 * The hour check is essential for {@code time_based} mode: crossing an active-hours
 * boundary with identical config text must re-evaluate, otherwise the stale Snapshot
 * freezes silently. (Review finding #1, Sol)
 */
final class SnapshotSkipDecision {

    /**
     * @param fingerprint          SHA-256 of the current payload; null on parse failure / cold start
     * @param previousFingerprint  last accepted fingerprint; null before first successful load
     * @param evaluationHour       hour-of-day (0-23) at the start of this load attempt
     * @param lastEvaluatedHour    hour-of-day recorded when the current Snapshot was accepted
     * @return true if reload can be safely skipped (payload AND hour unchanged)
     */
    static boolean shouldSkip(String fingerprint, String previousFingerprint,
                              int evaluationHour, int lastEvaluatedHour) {
        return fingerprint != null
                && fingerprint.equals(previousFingerprint)
                && evaluationHour == lastEvaluatedHour;
    }

    private SnapshotSkipDecision() {} // utility class
}
