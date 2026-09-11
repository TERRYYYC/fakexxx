package name.caiyao.fakegps.hook.oracle;

import java.util.Collections;
import java.util.Set;

import name.caiyao.fakegps.oracle.OracleWireHealth;

/** Exact Android-15 pilot resolver. It is deliberately inert on every unattested fingerprint. */
public final class Android15OracleHookPlan {
    private Android15OracleHookPlan() {}

    public static final int API_LEVEL = 35;

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
     * Reserved attribution tag for QWY's own covered mutations. #170 direction A: the
     * oracle attribution predicate treats this tag as a NON-CONTRADICTING CONFIRMATION,
     * not a required match — same uid/pid/package provenance inside an active bracket is
     * attributed even when the tag is absent (HyperOS does not propagate the app-side tag
     * into system_server), while a non-empty foreign tag still fails attribution.
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
    public static final String[] LOCATION_MUTATION_METHODS = {
            "onStateChanged", "onEnabledChanged"
    };
    public static final String[] LOCATION_QWY_MUTATION_ENTRY_METHODS = {
            "addTestProvider", "removeTestProvider", "setTestProviderEnabled"
    };
    public static final String LOCATION_QWY_PROVENANCE_ENTRY_METHOD =
            "setTestProviderLocation";
    public static final String LOCATION_SEMANTIC_MUTATION_METHOD =
            "setProviderLocation";

    public static final long COVERAGE_APP_OPS_WRAPPER = 1L << 0;
    public static final long COVERAGE_ACCESS_CHECKING_DELEGATE = 1L << 1;
    public static final long COVERAGE_ACCESS_CHECKING_LIFECYCLE = 1L << 2;
    public static final long COVERAGE_LOCATION_PROVIDER_STATE = 1L << 3;
    public static final long COVERAGE_LOCATION_EFFECTIVE_ENABLED = 1L << 4;
    public static final long COVERAGE_QWY_SERVICE_GENERATION = 1L << 5;
    public static final long COVERAGE_QWY_SEMANTIC_SESSION = 1L << 6;
    public static final long COVERAGE_BRIDGE_SESSION = 1L << 7;
    public static final long COVERAGE_BUILD_ATTESTED = 1L << 8;
    public static final long COVERAGE_LOCATION_SEMANTIC_COORDINATE = 1L << 9;
    public static final long REQUIRED_COVERAGE_MASK = 0x3ffL;

    /** Populated only by a separately reviewed exact-build evidence change. */
    public static final Set<String> ATTESTED_FINGERPRINTS = Collections.emptySet();

    public static boolean isFingerprintAttested(String fingerprint) {
        return fingerprint != null && ATTESTED_FINGERPRINTS.contains(fingerprint);
    }

    /** The version-neutral surface this plan serves; consumed only through the plan gate. */
    public static final OracleHookPlan SURFACE = new OracleHookPlan() {
        @Override public int apiLevel() { return API_LEVEL; }

        @Override public boolean attests(String buildFingerprint, String buildIncremental) {
            // API-35 attestation stays fingerprint-only (allowlist empty today = inert pilot).
            return isFingerprintAttested(buildFingerprint);
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

    /** Pure fail-closed health policy shared by the Binder and host tests. */
    public static OracleWireHealth classifyHealth(
            boolean supportedPlatform,
            boolean buildAttested,
            boolean bootIdValid,
            boolean invariantFailure,
            boolean callbackPoisoned,
            long installedCoverageMask,
            boolean bridgeConnected,
            boolean qwySessionActive,
            boolean endpointValid) {
        if (!supportedPlatform) return OracleWireHealth.UNSUPPORTED_PLATFORM;
        if (!buildAttested) return OracleWireHealth.BUILD_UNATTESTED;
        if (!bootIdValid) return OracleWireHealth.BOOT_ID_UNAVAILABLE;
        if (invariantFailure) return OracleWireHealth.INVARIANT_FAILURE;
        if (callbackPoisoned) return OracleWireHealth.CALLBACK_POISONED;
        if ((installedCoverageMask & REQUIRED_COVERAGE_MASK) != REQUIRED_COVERAGE_MASK) {
            return OracleWireHealth.HOOKS_INCOMPLETE;
        }
        if (!bridgeConnected) return OracleWireHealth.BRIDGE_UNAVAILABLE;
        if (!qwySessionActive) return OracleWireHealth.SESSION_UNAVAILABLE;
        if (!endpointValid) return OracleWireHealth.ENDPOINT_UNAVAILABLE;
        return OracleWireHealth.HEALTHY;
    }
}
