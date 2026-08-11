package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.PublishPropagation

/**
 * The persist-then-publish sequence behind changing the refresh interval, expressed without
 * Android types so the BEHAVIOUR (not just the policy) can be pinned by a JVM test.
 *
 * <p>Two failures this exists to make impossible:
 * <ul>
 *   <li>persisting without publishing — the hook keeps its old cadence while the screen shows the
 *       new one, i.e. the setting appears to apply and changes nothing;</li>
 *   <li>dropping the publish result — [name.caiyao.fakegps.config.ConfigPrefsSync.sync] returns a
 *       Boolean, and discarding a `false` lets the UI present an undelivered setting as in effect.
 *       That is the same silent-failure class as a save that reports success without reaching the
 *       hook.</li>
 * </ul>
 *
 * <p>The preference is written even when publication fails: the user's choice is not discarded,
 * it is reported as not-yet-delivered.
 */
object RefreshIntervalUpdate {

    /** Outcome of a refresh-interval change. */
    data class Result(
        /** What was actually stored — always a value the policy sanctions. */
        val storedSec: Int,
        /** Whether the new payload reached the hook. */
        val published: Boolean,
    )

    /**
     * @param requestedSec what the user picked
     * @param persist writes the sanitised value; returns nothing
     * @param publish republishes the payload; returns whether it reached the hook
     */
    fun apply(
        requestedSec: Int,
        persist: (Int) -> Unit,
        publish: () -> Boolean,
    ): Result {
        val sanitized = PublishPropagation.sanitizeInterval(requestedSec)
        persist(sanitized)
        return Result(storedSec = sanitized, published = publish())
    }
}
