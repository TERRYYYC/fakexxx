package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeMonotonicClock
import name.caiyao.fakegps.integration.v1.support.InMemoryDurableKv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QwyRelevantChangeMonitorTest {

    @Test
    fun `mock location AppOps callback bumps the production revision reason`() {
        val source = FakeOwnerChangeSource()
        val relay = QwyRelevantChangeMonitor(source)
        val tracker = ContinuityTracker(InMemoryDurableKv(), FakeMonotonicClock())
        val reasons = mutableListOf<RevisionBumpReason>()
        val before = tracker.snapshot().revision

        assertTrue(relay.bind { reason ->
            reasons += reason
            tracker.bump(reason)
        })
        source.emit()

        assertEquals(listOf(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED), reasons)
        assertEquals(before + 1L, tracker.snapshot().revision)
    }

    @Test
    fun `listener rebind does not register a second process watcher`() {
        val source = FakeOwnerChangeSource()
        val relay = QwyRelevantChangeMonitor(source)
        var firstCalls = 0
        var secondCalls = 0

        assertTrue(relay.bind { firstCalls++ })
        assertTrue(relay.bind { secondCalls++ })
        source.emit()

        assertEquals(1, source.startCount)
        assertEquals(0, firstCalls)
        assertEquals(1, secondCalls)
    }

    @Test
    fun `registered watcher does not grant verification while this app is not the current owner`() {
        val source = FakeOwnerChangeSource(currentOwner = false)
        val relay = QwyRelevantChangeMonitor(source)

        assertTrue(relay.bind { })
        assertFalse(relay.canVerifyCurrentOwner())

        source.currentOwner = true
        assertTrue(relay.canVerifyCurrentOwner())
    }

    @Test
    fun `public asynchronous watcher can never claim complete continuity history`() {
        val source = FakeOwnerChangeSource(currentOwner = true)
        val relay = QwyRelevantChangeMonitor(source)

        assertTrue(relay.bind { })
        assertEquals(
            ContinuityEvidenceCapability.INCOMPLETE,
            relay.continuityEvidenceCapability(),
        )
    }

    @Test
    fun `failed watcher registration remains fail closed even when AppOps says owner`() {
        val source = FakeOwnerChangeSource(currentOwner = true, startSucceeds = false)
        val relay = QwyRelevantChangeMonitor(source)

        assertFalse(relay.bind { })
        assertFalse(relay.canVerifyCurrentOwner())
        assertEquals(
            ContinuityEvidenceCapability.UNAVAILABLE,
            relay.continuityEvidenceCapability(),
        )
    }

    @Test
    fun `shutdown unregisters watcher detaches callback and is idempotent`() {
        val source = FakeOwnerChangeSource()
        val relay = QwyRelevantChangeMonitor(source)
        var calls = 0
        assertTrue(relay.bind { calls++ })

        relay.shutdown()
        relay.shutdown()
        source.emit()

        assertEquals(1, source.stopCount)
        assertEquals(0, calls)
        assertFalse(relay.canVerifyCurrentOwner())
        assertEquals(
            ContinuityEvidenceCapability.UNAVAILABLE,
            relay.continuityEvidenceCapability(),
        )
    }

    private class FakeOwnerChangeSource(
        var currentOwner: Boolean = true,
        private val startSucceeds: Boolean = true,
    ) : MockLocationOwnerChangeSource {
        var startCount = 0
        var stopCount = 0
        private var callback: (() -> Unit)? = null

        override fun start(onChanged: () -> Unit): Boolean {
            startCount++
            callback = onChanged
            return startSucceeds
        }

        override fun isCurrentOwner(): Boolean = currentOwner

        override fun stop() {
            stopCount++
            callback = null
        }

        fun emit() {
            callback?.invoke()
        }
    }
}
