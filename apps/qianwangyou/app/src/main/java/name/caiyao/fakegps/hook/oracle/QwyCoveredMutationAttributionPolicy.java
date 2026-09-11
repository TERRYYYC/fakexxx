package name.caiyao.fakegps.hook.oracle;

/**
 * Pure fail-closed causal gate for covered LocationManager entry calls.
 *
 * <p><strong>#170 direction A revision — the attribution tag leg is demoted from a required
 * match to a non-contradicting confirmation.</strong> Original design required
 * {@code attributionTag == "qwy_authoritative_continuity"} on top of the identity triple.
 * Two rounds of mi14 (HyperOS) device evidence showed the app-side tag never reaches the
 * system_server provenance on the LocationManager binder path — #171's
 * {@code createAttributionContext} patch did not fix it — so QWY's own nested platform
 * calls (the contract gateway and the app's 1Hz refresh, both same uid/pid/package, tag
 * null) were judged foreign inside the release bracket: lastCompletedQwyMutationId was
 * cleared, the #167 fifth ack guard skipped, unacked cursors piled up, apply/advance then
 * died on the fourth guard, and every row-boundary verify mismatched.
 *
 * <p>Why the triple alone is sufficient inside an active bracket: while
 * {@code qwySessionActive && qwyMutationActive} holds, the only same-uid/pid/package LM
 * callers that can exist are the QWY contract gateway and QWY's own refresh tick (a no-op
 * or first injection) — no external app can share the triple, and QWY runs no other LM
 * path during a bracket, so the "non-oracle QWY call" threat the tag leg originally
 * guarded against cannot arise while the window is open. The trust boundary is therefore
 * the identity triple plus the active window; the tag only needs to not contradict it.
 *
 * <p>Semantics: attribution holds when the session/mutation window is active and the
 * triple matches exactly, and the provenance tag either matches the reserved tag or is
 * absent ({@code null} or empty). A non-empty, non-matching tag still fails attribution —
 * it proves a different attribution context, so the tag is retained as a strong
 * discrimination signal, just no longer a mandatory one.
 */
final class QwyCoveredMutationAttributionPolicy {
    private QwyCoveredMutationAttributionPolicy() {}

    static boolean isAttributed(
            Integer expectedUid,
            Integer expectedPid,
            String expectedPackage,
            int callingUid,
            int callingPid,
            String callingPackage,
            String attributionTag,
            boolean qwySessionActive,
            boolean qwyMutationActive) {
        return qwySessionActive
                && qwyMutationActive
                && expectedUid != null
                && expectedPid != null
                && expectedPackage != null
                && expectedUid == callingUid
                && expectedPid == callingPid
                && expectedPackage.equals(callingPackage)
                && attributionTagDoesNotContradict(attributionTag);
    }

    /**
     * Non-contradicting confirmation (#170 direction A): absent ({@code null}/empty) or
     * exactly the reserved tag both confirm the owner triple; any other non-empty tag
     * contradicts it and fails attribution.
     */
    private static boolean attributionTagDoesNotContradict(String attributionTag) {
        return attributionTag == null
                || attributionTag.isEmpty()
                || Android15OracleHookPlan.QWY_MUTATION_ATTRIBUTION_TAG.equals(attributionTag);
    }
}
