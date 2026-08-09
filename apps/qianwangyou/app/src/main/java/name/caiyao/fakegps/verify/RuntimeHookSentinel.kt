package name.caiyao.fakegps.verify

/**
 * Returns false in ordinary app execution.
 *
 * MainHook replaces this method on the probe process' target classloader. That makes the answer
 * evidence of a real Xposed installation rather than a static shared between unrelated
 * classloaders.
 */
object RuntimeHookSentinel {
    @JvmStatic
    fun isHookActive(): Boolean = false

    /**
     * Reloads the snapshot owned by the Xposed module classloader and returns its active
     * fingerprint. The unhooked implementation has no authority over hook state.
     */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun reloadHookSnapshot(expectedFingerprint: String): String? = null
}
