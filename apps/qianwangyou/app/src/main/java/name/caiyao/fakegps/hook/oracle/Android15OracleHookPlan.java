package name.caiyao.fakegps.hook.oracle;

import java.util.Collections;
import java.util.Set;

import name.caiyao.fakegps.oracle.OracleWireHealth;

/** Exact Android-15 pilot resolver. It is deliberately inert on every unattested fingerprint. */
public final class Android15OracleHookPlan {
    private Android15OracleHookPlan() {}

    /**
     * Exact-build admission is deliberately split: evidence hooks may run
     * without granting the build-attestation bit or authoritative health.
     */
    public enum BuildAdmission {
        UNLISTED,
        EVIDENCE_ONLY,
        ATTESTED,
    }

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
    public static final long REQUIRED_EVIDENCE_COVERAGE_MASK =
            REQUIRED_COVERAGE_MASK & ~COVERAGE_BUILD_ATTESTED;
    private static final long SESSION_STATE_COVERAGE_MASK =
            COVERAGE_QWY_SERVICE_GENERATION
                    | COVERAGE_QWY_SEMANTIC_SESSION
                    | COVERAGE_BRIDGE_SESSION;
    private static final long REQUIRED_PLATFORM_HOOK_COVERAGE_MASK =
            REQUIRED_EVIDENCE_COVERAGE_MASK & ~SESSION_STATE_COVERAGE_MASK;

    /**
     * Populated first by a separately reviewed, live-fingerprint evidence change.
     * This tier may install observation hooks but can never become authoritative.
     */
    public static final Set<String> EVIDENCE_ONLY_FINGERPRINTS = Collections.emptySet();

    /** Populated only by a later, independently reviewed exact-build promotion. */
    public static final Set<String> ATTESTED_FINGERPRINTS = Collections.emptySet();

    public static BuildAdmission classifyFingerprint(String fingerprint) {
        return classifyFingerprint(
                fingerprint, EVIDENCE_ONLY_FINGERPRINTS, ATTESTED_FINGERPRINTS);
    }

    /** Pure seam for exact-match and conflicting-list tests; production passes only constants. */
    static BuildAdmission classifyFingerprint(
            String fingerprint,
            Set<String> evidenceOnlyFingerprints,
            Set<String> attestedFingerprints) {
        if (fingerprint == null
                || evidenceOnlyFingerprints == null
                || attestedFingerprints == null) {
            return BuildAdmission.UNLISTED;
        }
        boolean evidenceOnly = evidenceOnlyFingerprints.contains(fingerprint);
        boolean attested = attestedFingerprints.contains(fingerprint);
        // Overlap is a configuration error. Fail closed instead of silently
        // preferring the more privileged tier.
        if (evidenceOnly == attested) {
            return BuildAdmission.UNLISTED;
        }
        return attested ? BuildAdmission.ATTESTED : BuildAdmission.EVIDENCE_ONLY;
    }

    public static boolean mayInstallEvidenceHooks(String fingerprint) {
        return classifyFingerprint(fingerprint) != BuildAdmission.UNLISTED;
    }

    public static boolean isFingerprintAttested(String fingerprint) {
        return classifyFingerprint(fingerprint) == BuildAdmission.ATTESTED;
    }

    /** The authority bit is minted only from the exhaustive admission state machine. */
    static long initialCoverageMask(BuildAdmission buildAdmission) {
        return buildAdmission == BuildAdmission.ATTESTED ? COVERAGE_BUILD_ATTESTED : 0L;
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
        return classifyHealth(
                supportedPlatform,
                buildAttested ? BuildAdmission.ATTESTED : BuildAdmission.UNLISTED,
                bootIdValid,
                invariantFailure,
                callbackPoisoned,
                installedCoverageMask,
                bridgeConnected,
                qwySessionActive,
                endpointValid);
    }

    /**
     * Tier-aware health policy. Evidence-only exposes the real runtime blocker,
     * but its strongest terminal state is EVIDENCE_ONLY_READY, never HEALTHY.
     */
    public static OracleWireHealth classifyHealth(
            boolean supportedPlatform,
            BuildAdmission buildAdmission,
            boolean bootIdValid,
            boolean invariantFailure,
            boolean callbackPoisoned,
            long installedCoverageMask,
            boolean bridgeConnected,
            boolean qwySessionActive,
            boolean endpointValid) {
        if (!supportedPlatform) return OracleWireHealth.UNSUPPORTED_PLATFORM;
        if (buildAdmission == null || buildAdmission == BuildAdmission.UNLISTED) {
            return OracleWireHealth.BUILD_UNATTESTED;
        }
        if (buildAdmission != BuildAdmission.EVIDENCE_ONLY
                && buildAdmission != BuildAdmission.ATTESTED) {
            return OracleWireHealth.INVARIANT_FAILURE;
        }
        if (buildAdmission == BuildAdmission.EVIDENCE_ONLY
                && (installedCoverageMask & COVERAGE_BUILD_ATTESTED) != 0L) {
            return OracleWireHealth.INVARIANT_FAILURE;
        }
        if (!bootIdValid) return OracleWireHealth.BOOT_ID_UNAVAILABLE;
        if (invariantFailure) return OracleWireHealth.INVARIANT_FAILURE;
        if (callbackPoisoned) return OracleWireHealth.CALLBACK_POISONED;
        if (buildAdmission == BuildAdmission.EVIDENCE_ONLY) {
            if ((installedCoverageMask & REQUIRED_PLATFORM_HOOK_COVERAGE_MASK)
                    != REQUIRED_PLATFORM_HOOK_COVERAGE_MASK) {
                return OracleWireHealth.HOOKS_INCOMPLETE;
            }
            if (!bridgeConnected
                    || (installedCoverageMask & COVERAGE_BRIDGE_SESSION) == 0L) {
                return OracleWireHealth.BRIDGE_UNAVAILABLE;
            }
            long qwySessionMask =
                    COVERAGE_QWY_SERVICE_GENERATION | COVERAGE_QWY_SEMANTIC_SESSION;
            if (!qwySessionActive
                    || (installedCoverageMask & qwySessionMask) != qwySessionMask) {
                return OracleWireHealth.SESSION_UNAVAILABLE;
            }
            if (!endpointValid) return OracleWireHealth.ENDPOINT_UNAVAILABLE;
            return OracleWireHealth.EVIDENCE_ONLY_READY;
        }
        // Preserve the established attested-health ordering: missing coverage is
        // HOOKS_INCOMPLETE even when the corresponding live signal is also absent.
        if ((installedCoverageMask & REQUIRED_COVERAGE_MASK) != REQUIRED_COVERAGE_MASK) {
            return OracleWireHealth.HOOKS_INCOMPLETE;
        }
        if (!bridgeConnected) return OracleWireHealth.BRIDGE_UNAVAILABLE;
        if (!qwySessionActive) return OracleWireHealth.SESSION_UNAVAILABLE;
        if (!endpointValid) return OracleWireHealth.ENDPOINT_UNAVAILABLE;
        return OracleWireHealth.HEALTHY;
    }
}
