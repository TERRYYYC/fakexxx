package name.caiyao.fakegps.verify

import name.caiyao.fakegps.BuildConfig

/**
 * What a reading taken inside THIS process is evidence of.
 *
 * The distinction is not cosmetic: identical numbers mean opposite things depending on whether the
 * module hooks itself, and getting it wrong makes the UI lie in the most damaging direction —
 * either telling the user the hook failed when it works, or presenting spoofed values as the real
 * network they are supposed to differ from.
 */
enum class ObservationScope {
    /**
     * Debug build: the module hooks its own process, so a reading equal to the configured value
     * proves the chain works end to end. This is the controlled-probe mode scripts/test-hook.sh uses.
     * The flip side is that readings here are NOT the real device values.
     */
    SELF_HOOKED,

    /**
     * Stable release: observations came from the private `:hook_verify` process after its sentinel
     * proved that Xposed installed the module in that target classloader.
     */
    HOOK_PROBE,

    /**
     * Release build: MainHook deliberately skips its own package so the configuration UI can never
     * display fake values back as though they were real. Readings prove nothing about the hook, but
     * they ARE the genuine device baseline — which is what the user needs in order to pick a value
     * that visibly differs from the real network.
     */
    REAL_BASELINE;

    companion object {
        /** Mirrors the self-hook condition in MainHook#handleLoadPackage. */
        fun current(): ObservationScope =
            if (BuildConfig.DEBUG) SELF_HOOKED else REAL_BASELINE
    }
}
