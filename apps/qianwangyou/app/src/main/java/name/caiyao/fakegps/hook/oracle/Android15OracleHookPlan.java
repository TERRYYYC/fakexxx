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
    public static final String SYSTEM_SERVICE_MANAGER_CLASS =
            "com.android.server.SystemServiceManager";

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

    public static final long COVERAGE_APP_OPS_WRAPPER = 1L << 0;
    public static final long COVERAGE_ACCESS_CHECKING_DELEGATE = 1L << 1;
    public static final long COVERAGE_ACCESS_CHECKING_LIFECYCLE = 1L << 2;
    public static final long COVERAGE_LOCATION_PROVIDER_STATE = 1L << 3;
    public static final long COVERAGE_LOCATION_EFFECTIVE_ENABLED = 1L << 4;
    public static final long COVERAGE_QWY_SERVICE_GENERATION = 1L << 5;
    public static final long COVERAGE_QWY_SEMANTIC_SESSION = 1L << 6;
    public static final long COVERAGE_BRIDGE_SESSION = 1L << 7;
    public static final long COVERAGE_BUILD_ATTESTED = 1L << 8;
    public static final long REQUIRED_COVERAGE_MASK = 0x1ffL;

    /** Populated only by a separately reviewed exact-build evidence change. */
    public static final Set<String> ATTESTED_FINGERPRINTS = Collections.emptySet();

    public static boolean isFingerprintAttested(String fingerprint) {
        return fingerprint != null && ATTESTED_FINGERPRINTS.contains(fingerprint);
    }

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
