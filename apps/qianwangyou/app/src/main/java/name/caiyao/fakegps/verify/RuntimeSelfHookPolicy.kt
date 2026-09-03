package name.caiyao.fakegps.verify

import name.caiyao.fakegps.BuildConfig

/**
 * Decides which processes of the module APK may receive its own hooks.
 *
 * [MODULE_PACKAGE] tracks [BuildConfig.APPLICATION_ID] so that bench/debug builds
 * (applicationId "name.caiyao.fakegps.bench") correctly identify their own process
 * and don't accidentally self-hook the production module or vice versa.
 */
object RuntimeSelfHookPolicy {
    @JvmField val MODULE_PACKAGE: String = BuildConfig.APPLICATION_ID
    @JvmField val PROBE_PROCESS: String = "$MODULE_PACKAGE:hook_verify"

    /** Production entry: uses this APK's generated build policy. */
    @JvmStatic
    fun shouldHook(packageName: String?, processName: String?): Boolean =
        shouldHook(BuildConfig.ALLOW_NON_PROBE_SELF_HOOK, packageName, processName)

    /**
     * Release and codexBench ordinary self processes stay unhooked by this module. DEBUG is
     * not an independence policy: ordinary debug deliberately permits a controlled self-hook,
     * while codexBench must preserve its raw-reader process. Only the exact private probe is
     * exempt when non-probe self hooks are disabled. Other scoped packages keep normal behavior.
     */
    @JvmStatic
    fun shouldHook(
        allowNonProbeSelfHook: Boolean,
        packageName: String?,
        processName: String?,
    ): Boolean {
        if (packageName != MODULE_PACKAGE) return true
        return allowNonProbeSelfHook || processName == PROBE_PROCESS
    }
}
