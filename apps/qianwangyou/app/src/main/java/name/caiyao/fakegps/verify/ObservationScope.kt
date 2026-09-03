package name.caiyao.fakegps.verify

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
     * Ordinary debug build: the module hooks its own process, so a reading equal to the configured value
     * proves the chain works end to end. This is the controlled-probe mode scripts/test-hook.sh uses.
     * The flip side is that readings here are NOT the real device values.
     */
    SELF_HOOKED,

    /**
     * Observations came from the private `:hook_verify` process after its sentinel
     * proved that Xposed installed the module in that target classloader.
     */
    HOOK_PROBE,

    /**
     * Release/codexBench configuration process: MainHook deliberately skips non-probe self
     * processes, so this module does not fabricate the baseline. These readings prove nothing
     * about target hook success; other modules or system mock providers may still affect them.
     */
    REAL_BASELINE;

    companion object {
        /** Configuration-process classification, not a runtime framework/sentinel assertion. */
        fun current(): ObservationScope =
            if (RuntimeSelfHookPolicy.shouldHook(
                    RuntimeSelfHookPolicy.MODULE_PACKAGE,
                    RuntimeSelfHookPolicy.MODULE_PACKAGE,
                )) SELF_HOOKED else REAL_BASELINE
    }
}
