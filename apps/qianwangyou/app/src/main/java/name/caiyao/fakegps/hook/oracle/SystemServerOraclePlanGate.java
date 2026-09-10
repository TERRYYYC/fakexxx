package name.caiyao.fakegps.hook.oracle;

/**
 * Pure platform/build resolver shared by the system_server installer and host JVM tests.
 *
 * <p>Gate semantics (unchanged from the API-35 pilot, now two-versioned): the oracle installs
 * only when the running SDK is pinned by a plan AND that plan independently attests the exact
 * build. Every unknown SDK, unknown build, or empty allowlist resolves to {@code null}, which
 * makes the installer return before a single hook is registered — the fail-closed behavior
 * observed in the 285 rerun stays the default on every unsupported device.</p>
 */
public final class SystemServerOraclePlanGate {
    private SystemServerOraclePlanGate() {}

    /** Null unless the SDK has a plan and that plan attests this exact build. */
    public static OracleHookPlan resolvePlan(
            int sdkInt,
            String buildFingerprint,
            String buildIncremental) {
        if (sdkInt == Android15OracleHookPlan.API_LEVEL
                && Android15OracleHookPlan.SURFACE.attests(buildFingerprint, buildIncremental)) {
            return Android15OracleHookPlan.SURFACE;
        }
        if (sdkInt == Android16OracleHookPlan.API_LEVEL
                && Android16OracleHookPlan.SURFACE.attests(buildFingerprint, buildIncremental)) {
            return Android16OracleHookPlan.SURFACE;
        }
        return null;
    }
}
