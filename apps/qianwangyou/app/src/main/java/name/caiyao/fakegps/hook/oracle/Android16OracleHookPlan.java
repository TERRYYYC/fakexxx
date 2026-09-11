package name.caiyao.fakegps.hook.oracle;

import java.util.Collections;
import java.util.Set;

/**
 * Exact Android-16 resolver (docs/oracle-a16-research.md, #149). Deliberately inert on every
 * unattested build.
 *
 * <p>Hook-surface derivation: every class/method constant below was diffed against AOSP
 * {@code android-15.0.0_r1} vs {@code android-16.0.0_r1} (docs/oracle-a16-research.md §2). All
 * seven hook points survive API 36 unchanged (four files byte-identical, three with additive-only
 * deltas), so the strings match the API-35 pilot exactly and a test freezes that lockstep.
 * HyperOS private deltas cannot be proven offline; a missing class/method poisons the oracle
 * (fail-closed NONE) and never blocks system_server boot.</p>
 */
public final class Android16OracleHookPlan {
    private Android16OracleHookPlan() {}

    public static final int API_LEVEL = 36;

    public static final String APP_OPS_WRAPPER_CLASS =
            "com.android.server.appop.AppOpsCheckingServiceTracingDecorator";
    public static final String ACCESS_CHECKING_DELEGATE_CLASS =
            "com.android.server.permission.access.appop.AppOpService";
    public static final String ACCESS_CHECKING_LIFECYCLE_CLASS =
            "com.android.server.permission.access.AccessCheckingService";
    public static final String LOCATION_PROVIDER_MANAGER_CLASS =
            "com.android.server.location.provider.LocationProviderManager";
    public static final String LOCATION_MOCK_PROVIDER_CLASS =
            "com.android.server.location.provider.MockLocationProvider";
    public static final String LOCATION_MANAGER_SERVICE_CLASS =
            "com.android.server.location.LocationManagerService";
    public static final String SYSTEM_SERVICE_MANAGER_CLASS =
            "com.android.server.SystemServiceManager";
    /**
     * Must stay identical to the API-35 tag: QWY's own mutations are correlated by this
     * value. #170 direction A: it is a NON-CONTRADICTING CONFIRMATION in the oracle
     * attribution predicate, not a required match — same uid/pid/package provenance inside
     * an active bracket is attributed even when the tag is absent (HyperOS does not
     * propagate the app-side tag into system_server), while a non-empty foreign tag still
     * fails attribution.
     */
    public static final String QWY_MUTATION_ATTRIBUTION_TAG =
            "qwy_authoritative_continuity";

    public static final String[] APP_OPS_WRAPPER_MUTATION_METHODS = {
            "setUidMode", "setPackageMode", "removePackage", "removeUid", "clearAllModes"
    };
    public static final String[] ACCESS_CHECKING_MUTATION_METHODS = {
            "setUidMode", "setPackageMode", "removePackage", "removeUid"
    };
    public static final String[] ACCESS_CHECKING_LIFECYCLE_METHODS = {
            "onPackageRemoved", "onPackageUninstalled", "onUserRemoved"
    };
    public static final String[] LOCATION_QWY_MUTATION_ENTRY_METHODS = {
            "addTestProvider", "removeTestProvider", "setTestProviderEnabled"
    };
    public static final String LOCATION_QWY_PROVENANCE_ENTRY_METHOD =
            "setTestProviderLocation";
    public static final String LOCATION_SEMANTIC_MUTATION_METHOD =
            "setProviderLocation";

    /**
     * Exact-build attestation (the #149 "attested" process): every entry is one exact OEM OTA
     * build added only by a separately reviewed evidence change. BP2A.250605.031.A3 is the
     * Xiaomi 14 (mi14) HyperOS 3 / Android 16 pilot build from the 285 rerun.
     *
     * <p>Runtime-configurable allowlists (system properties, remote config) were evaluated and
     * rejected: an editable attestation softens fail-closed into a runtime switch. Gradle-time
     * injection stays a future evolution once the device count grows; it must keep the
     * exact-build, review-per-entry property.</p>
     */
    public static final Set<String> ATTESTED_BUILD_IDS =
            Collections.singleton("OS3.0.303.0.WNCCNXM");
    // Device evidence (mi14 e53cfd3d, 2026-09-10): `getprop ro.build.version.incremental`
    // -> OS3.0.303.0.WNCCNXM (HyperOS incremental), NOT the BP2A build-ID form. The prior
    // BP2A value would have left the lane permanently inert — caught by review P2 before
    // the stage-3 device run. ro.build.fingerprint for the pinning channel:
    // Xiaomi/houji/houji:16/BP2A.250605.031.A3/OS3.0.303.0.WNCCNXM:user/release-keys

    /** Exact whole-fingerprint pinning channel; populated only with the same review evidence. */
    public static final Set<String> ATTESTED_FINGERPRINTS = Collections.emptySet();

    public static boolean isBuildIdAttested(String buildIncremental) {
        return buildIncremental != null && ATTESTED_BUILD_IDS.contains(buildIncremental);
    }

    public static boolean isFingerprintAttested(String fingerprint) {
        return fingerprint != null && ATTESTED_FINGERPRINTS.contains(fingerprint);
    }

    /** Pure fail-closed attestation: exact API level AND an exactly attested build. */
    public static boolean isBuildAttested(
            int sdkInt,
            String buildIncremental,
            String buildFingerprint) {
        return sdkInt == API_LEVEL
                && (isBuildIdAttested(buildIncremental) || isFingerprintAttested(buildFingerprint));
    }

    /** The version-neutral surface this plan serves; consumed only through the plan gate. */
    public static final OracleHookPlan SURFACE = new OracleHookPlan() {
        @Override public int apiLevel() { return API_LEVEL; }

        @Override public boolean attests(String buildFingerprint, String buildIncremental) {
            return isBuildAttested(apiLevel(), buildIncremental, buildFingerprint);
        }

        @Override public String appOpsWrapperClass() { return APP_OPS_WRAPPER_CLASS; }
        @Override public String[] appOpsWrapperMutationMethods() {
            return APP_OPS_WRAPPER_MUTATION_METHODS;
        }
        @Override public String accessCheckingDelegateClass() {
            return ACCESS_CHECKING_DELEGATE_CLASS;
        }
        @Override public String[] accessCheckingMutationMethods() {
            return ACCESS_CHECKING_MUTATION_METHODS;
        }
        @Override public String accessCheckingLifecycleClass() {
            return ACCESS_CHECKING_LIFECYCLE_CLASS;
        }
        @Override public String[] accessCheckingLifecycleMethods() {
            return ACCESS_CHECKING_LIFECYCLE_METHODS;
        }
        @Override public String locationManagerServiceClass() {
            return LOCATION_MANAGER_SERVICE_CLASS;
        }
        @Override public String[] locationQwyMutationEntryMethods() {
            return LOCATION_QWY_MUTATION_ENTRY_METHODS;
        }
        @Override public String locationQwyProvenanceEntryMethod() {
            return LOCATION_QWY_PROVENANCE_ENTRY_METHOD;
        }
        @Override public String locationMockProviderClass() { return LOCATION_MOCK_PROVIDER_CLASS; }
        @Override public String locationSemanticMutationMethod() {
            return LOCATION_SEMANTIC_MUTATION_METHOD;
        }
        @Override public String locationProviderManagerClass() {
            return LOCATION_PROVIDER_MANAGER_CLASS;
        }
        @Override public String systemServiceManagerClass() { return SYSTEM_SERVICE_MANAGER_CLASS; }
    };
}
