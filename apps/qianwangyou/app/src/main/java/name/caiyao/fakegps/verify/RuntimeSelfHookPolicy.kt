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

    /**
     * Release main stays real so configuration screens never echo spoofed values as truth.
     * The private probe process is the sole release self-hook exception. Other scoped packages
     * keep the module's normal behaviour.
     */
    @JvmStatic
    fun shouldHook(
        debugBuild: Boolean,
        packageName: String?,
        processName: String?,
    ): Boolean {
        if (packageName != MODULE_PACKAGE) return true
        return debugBuild || processName == PROBE_PROCESS
    }
}
