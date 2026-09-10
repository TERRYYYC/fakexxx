package name.caiyao.fakegps.hook.oracle;

/**
 * Version-neutral view of one platform's exact oracle hook surface (classes + methods + build
 * attestation). Hook CLASS/METHOD strings and the ATTESTATION rule are per-platform facts that
 * each Android{{NN}}OracleHookPlan pins and a per-plan test freezes against the researched AOSP
 * sources. Coverage BIT VALUES are deliberately NOT part of this surface: they are the wire
 * contract (protocolVersion=1 installedCoverageMask, see Android15OracleHookPlan and
 * SystemServerOracleState) and must never be forked per API level.
 */
interface OracleHookPlan {
    int apiLevel();

    /**
     * Re-checks this plan's own allowlist inside the producer. The installer's gate alone cannot
     * attest a build: SystemServerOracleBinder.create re-asks the plan so a call-site boolean can
     * never launder an unattested build into the oracle.
     */
    boolean attests(String buildFingerprint, String buildIncremental);

    String appOpsWrapperClass();

    String[] appOpsWrapperMutationMethods();

    String accessCheckingDelegateClass();

    String[] accessCheckingMutationMethods();

    String accessCheckingLifecycleClass();

    String[] accessCheckingLifecycleMethods();

    String locationManagerServiceClass();

    String[] locationQwyMutationEntryMethods();

    String locationQwyProvenanceEntryMethod();

    String locationMockProviderClass();

    String locationSemanticMutationMethod();

    String locationProviderManagerClass();

    String systemServiceManagerClass();
}
