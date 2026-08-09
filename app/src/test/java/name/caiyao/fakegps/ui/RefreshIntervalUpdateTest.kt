package name.caiyao.fakegps.ui

import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.ui.screen.settings.RefreshIntervalUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of a refresh-interval change: it must persist AND republish, and it must not lose the
 * publish outcome.
 *
 * <p>Reviewer note this answers: the policy tests alone proved only that the numbers are legal,
 * not that selecting one actually stores it and pushes it to the hook.
 */
class RefreshIntervalUpdateTest {

    private class Recorder {
        val persisted = mutableListOf<Int>()
        var publishCalls = 0
        var publishResult = true
        fun publish(): Boolean {
            publishCalls++
            return publishResult
        }
    }

    @Test
    fun selectingAnInterval_persistsItAndRepublishes() {
        val rec = Recorder()

        val result = RefreshIntervalUpdate.apply(60, rec.persisted::add, rec::publish)

        assertEquals("the chosen value must be stored", listOf(60), rec.persisted)
        assertEquals("a change must republish exactly once", 1, rec.publishCalls)
        assertEquals(60, result.storedSec)
        assertTrue(result.published)
    }

    /** Persist happens BEFORE publish, so the payload builder reads the new value. */
    @Test
    fun persistHappensBeforePublish() {
        val order = mutableListOf<String>()
        RefreshIntervalUpdate.apply(
            requestedSec = 10,
            persist = { order += "persist" },
            publish = { order += "publish"; true },
        )
        assertEquals(listOf("persist", "publish"), order)
    }

    /** A failed publish must surface, not vanish — this is the silent-failure guard. */
    @Test
    fun failedPublishIsReported_butThePreferenceIsKept() {
        val rec = Recorder().apply { publishResult = false }

        val result = RefreshIntervalUpdate.apply(5, rec.persisted::add, rec::publish)

        assertFalse("a false from sync() must not be dropped", result.published)
        assertEquals("the user's choice is kept, not discarded", listOf(5), rec.persisted)
    }

    /** An off-policy request is sanitised before it can reach storage. */
    @Test
    fun offPolicyValueIsSanitisedBeforePersisting() {
        val rec = Recorder()

        val result = RefreshIntervalUpdate.apply(999, rec.persisted::add, rec::publish)

        assertEquals(
            listOf(PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC),
            rec.persisted,
        )
        assertEquals(PublishPropagation.DEFAULT_REFRESH_INTERVAL_SEC, result.storedSec)
    }
}
