package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.SpoofModules

/**
 * The persist-then-publish sequence behind a module switch, expressed without Android types so
 * the BEHAVIOUR (not just the policy) is pinned by a JVM test — the same seam as
 * [RefreshIntervalUpdate].
 *
 * A module toggle reaches the target app only through the published payload (transport schema v5
 * `modules` object). Persisting without publishing would show the new switch state while the
 * target process keeps its previous module set until some unrelated change republishes.
 */
object ModuleToggleUpdate {

    /** Outcome of one module toggle. */
    data class Result(
        val module: String,
        val enabled: Boolean,
        /** Whether the new payload reached the hook. */
        val published: Boolean,
    )

    /**
     * @param module canonical wire name ([SpoofModules.ALL]); unknown names throw — a typo must
     *   not silently persist a preference no publish ever reads back
     * @param enabled the switch state to persist
     * @param persist writes the switch; runs BEFORE publish so the payload reflects it
     * @param publish republishes the payload; returns whether it reached the hook
     */
    fun apply(
        module: String,
        enabled: Boolean,
        persist: (String, Boolean) -> Unit,
        publish: () -> Boolean,
    ): Result {
        require(SpoofModules.isKnown(module)) { "unknown module: $module" }
        persist(module, enabled)
        return Result(module = module, enabled = enabled, published = publish())
    }
}
