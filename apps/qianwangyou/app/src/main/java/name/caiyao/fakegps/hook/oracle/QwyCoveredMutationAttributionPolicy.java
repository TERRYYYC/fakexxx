package name.caiyao.fakegps.hook.oracle;

/** Pure fail-closed causal gate for covered LocationManager entry calls. */
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
                && Android15OracleHookPlan.QWY_MUTATION_ATTRIBUTION_TAG.equals(attributionTag);
    }
}
