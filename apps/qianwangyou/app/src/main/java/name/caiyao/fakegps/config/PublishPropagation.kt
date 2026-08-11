package name.caiyao.fakegps.config

/**
 * How long a freshly published config may take to reach the hook.
 *
 * [ConfigPrefsSync.sync] writes the payload synchronously on every save, but MainHook re-reads the
 * prefs file on a periodic timer and keeps serving the PREVIOUS Snapshot until it fires. Verifying
 * inside that window compares a brand-new config against values produced by the old one, so every
 * changed field reads back "wrong".
 *
 * That matters because saving a profile is precisely what a user does immediately before opening
 * the verify screen — so the false red is not a rare race, it is the default first experience.
 */
object PublishPropagation {

    /** Seconds; the cadence used when nothing has been configured. */
    const val DEFAULT_REFRESH_INTERVAL_SEC = 30

    /** Runtime safety bounds; payload values are clamped here even if they bypass the UI. */
    const val MIN_RUNTIME_REFRESH_INTERVAL_SEC = 5
    const val MAX_RUNTIME_REFRESH_INTERVAL_SEC = 60

    /**
     * Mirrors MainHook's default refresh cadence, DERIVED from
     * [DEFAULT_REFRESH_INTERVAL_SEC] so the number exists exactly once.
     */
    const val HOOK_REFRESH_INTERVAL_MS = DEFAULT_REFRESH_INTERVAL_SEC * 1000L

    /**
     * Conservative UI window for a hook that may still be sleeping on the previous cadence.
     * The current payload cannot reveal that previous delay, so the maximum supported cadence is
     * the smallest bound that never turns a legitimate 60s handoff into a false failure.
     */
    const val MAX_PROPAGATION_DELAY_MS = MAX_RUNTIME_REFRESH_INTERVAL_SEC * 1000L

    // ---------------------------------------------------------------------------------------
    // Refresh interval policy
    //
    // The Settings screen used to render a hardcoded literal "60 秒" next to a row with no
    // clickable modifier: a number that was both wrong (the hook re-reads every 30 s) and
    // unchangeable. A screen stating a cadence the runtime does not use is the same class of
    // defect as a verify screen reporting a spoof that was never applied — the UI lies.
    //
    // The cure is not a corrected literal but a single source: the picker's choices, its
    // default, and the pending window below all resolve HERE, so a screen physically cannot
    // display a cadence the policy does not sanction. This object is deliberately free of
    // Android types, so the hook side can share it verbatim.
    // ---------------------------------------------------------------------------------------

    /**
     * The intervals a user may pick, ascending. Fixed by the plan: `5 / 10 / 30 / 60`.
     *
     * Bounded on both ends on purpose: below ~5 s the periodic prefs re-read and SHA-256
     * fingerprint comparison stop being negligible, and beyond a minute the "did my change
     * apply?" feedback loop becomes indistinguishable from a broken hook, which is exactly the
     * confusion this setting exists to remove. (The timer now serves as a safety-net heartbeat
     * behind the event-driven FileObserver — Phase B.)
     */
    val REFRESH_INTERVAL_CHOICES_SEC: List<Int> = listOf(5, 10, 30, 60)

    /** Whether [seconds] is a cadence the hook is allowed to run at. */
    fun isValidInterval(seconds: Int): Boolean = seconds in REFRESH_INTERVAL_CHOICES_SEC

    /**
     * Coerce a stored/incoming value into a cadence the hook can honour.
     *
     * Fails towards the DEFAULT rather than towards "no refresh": a corrupt preference must not be
     * able to silently freeze the hook on a stale snapshot, which would present exactly like the
     * "I changed the config and nothing happened" failure this feature addresses.
     */
    fun sanitizeInterval(seconds: Int): Int =
        if (isValidInterval(seconds)) seconds else DEFAULT_REFRESH_INTERVAL_SEC

    /**
     * Delay accepted by the hook process from an optional transport value.
     *
     * Missing keeps the schema-v3-compatible 30-second default. A hostile numeric value is
     * clamped rather than trusted: unlike the persisted UI preference, the cross-process payload
     * is an input boundary and may not have passed through [isValidInterval].
     */
    @JvmStatic
    fun runtimeIntervalMs(rawSeconds: Int?): Long {
        val seconds = rawSeconds
            ?.coerceIn(MIN_RUNTIME_REFRESH_INTERVAL_SEC, MAX_RUNTIME_REFRESH_INTERVAL_SEC)
            ?: DEFAULT_REFRESH_INTERVAL_SEC
        return seconds * 1000L
    }

    /**
     * What the stored publish timestamp must become when publication fails: absent.
     *
     * Lives here, not on ConfigPrefsSync, for two reasons: this object is free of Android types so
     * the rule is reachable from a JVM test, and the rule belongs to the propagation contract rather
     * than to the writer.
     *
     * Crucially the failing path must CLEAR the key, not merely skip writing it — SharedPreferences
     * is persistent, so skipping leaves the timestamp from the last SUCCESSFUL publish on disk, and
     * a subsequent unreadable payload would borrow that still-open window.
     */
    fun timestampOnFailedPublish(): Long? = null

    /**
     * Whether the hook may still be running the previous config.
     *
     * Returns false whenever the answer is not knowable (no recorded publish time) or the clock is
     * inconsistent (timestamp in the future): suppressing a genuine failure indefinitely is a worse
     * outcome than showing one spurious red, so this only ever softens a verdict inside a bounded,
     * provable window.
     */
    fun isPending(publishedAtMs: Long?, nowMs: Long): Boolean {
        if (publishedAtMs == null) return false
        val elapsed = nowMs - publishedAtMs
        if (elapsed < 0) return false
        return elapsed < MAX_PROPAGATION_DELAY_MS
    }

    // NOTE — deliberately NOT parameterised by only the newly configured interval.
    //
    // A "use the newly chosen interval" overload looks like the obvious completion of this policy,
    // and it is wrong on the transition: a hook process that is mid-sleep on the OLD 60 s timer
    // does not learn about a new 5 s cadence until that timer fires. Ending the pending window
    // after 5 s would report a false red for the following 55 s — inventing exactly the failure
    // mode this window exists to suppress.
    //
    // Correctly narrowing it needs BOTH the old and the new cadence (and the scheduler's own
    // handoff semantics), which the UI does not persist as a pair. Until then the maximum allowed
    // cadence is conservative but bounded: it cannot hide a real mismatch beyond 60 seconds.
}
