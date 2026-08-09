package name.caiyao.fakegps.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * review P1-1 — the window in which a verdict is not yet trustworthy.
 *
 * ConfigPrefsSync republishes synchronously on every save, but MainHook re-reads the prefs on a
 * ~30s timer and serves the PREVIOUS Snapshot until it fires. Verifying inside that window compares
 * a brand-new config against values produced by the old one, so every changed field reads back
 * "wrong" — a deterministic false red immediately after the exact action (saving) that most users
 * will follow with a verification check.
 */
class PublishPropagationTest {

    @Test
    fun `immediately after publishing the hook has not necessarily re-read`() {
        assertEquals(true, PublishPropagation.isPending(publishedAtMs = 1_000, nowMs = 1_000))
    }

    @Test
    fun `still pending just before the refresh interval elapses`() {
        val justUnder = 1_000L + PublishPropagation.HOOK_REFRESH_INTERVAL_MS - 1
        assertEquals(true, PublishPropagation.isPending(1_000, justUnder))
    }

    @Test
    fun `a previous sixty second cadence stays pending beyond the thirty second default`() {
        val afterDefault = 1_000L + PublishPropagation.HOOK_REFRESH_INTERVAL_MS
        assertEquals(true, PublishPropagation.isPending(1_000, afterDefault))
    }

    @Test
    fun `no longer pending once the longest supported cadence has passed`() {
        val after = 1_000L + PublishPropagation.MAX_PROPAGATION_DELAY_MS
        assertEquals(false, PublishPropagation.isPending(1_000, after))
    }

    @Test
    fun `an unknown publish time is not treated as pending`() {
        // Never published, or written by a build that did not record the timestamp. Suppressing a
        // real failure forever would be worse than showing one spurious red.
        assertEquals(false, PublishPropagation.isPending(publishedAtMs = null, nowMs = 5_000))
    }

    @Test
    fun `a publish timestamp in the future does not pin the screen to pending`() {
        // Clock changes must not be able to disable failure reporting indefinitely.
        assertEquals(false, PublishPropagation.isPending(publishedAtMs = 9_000, nowMs = 1_000))
    }

    @Test
    fun `the window matches the hook's own refresh cadence`() {
        // MainHook: handler.sendEmptyMessageDelayed(1, 30 * 1000)
        assertEquals(30_000L, PublishPropagation.HOOK_REFRESH_INTERVAL_MS)
    }
}
