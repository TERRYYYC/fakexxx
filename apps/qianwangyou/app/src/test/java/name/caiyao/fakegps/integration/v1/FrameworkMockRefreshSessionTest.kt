package name.caiyao.fakegps.integration.v1

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderGateway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameworkMockRefreshSessionTest {

    @Test
    fun `start replaces provider publishes once and schedules periodic refresh`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events)
        val scheduler = RecordingRefreshScheduler(events)
        val session = newSession(gateway, scheduler)

        session.start(CONFIG)

        assertEquals(
            listOf("replace", "publish:1", "schedule:1000:1000"),
            events,
        )
        assertTrue(session.isActive)
    }

    @Test
    fun `scheduled task publishes the active config periodically`() {
        val gateway = RecordingGateway()
        val scheduler = RecordingRefreshScheduler()
        val session = newSession(gateway, scheduler)
        session.start(CONFIG)

        scheduler.fire(0)
        scheduler.fire(0)

        assertEquals(listOf(CONFIG, CONFIG, CONFIG), gateway.published)
    }

    @Test
    fun `refreshNow publishes synchronously and reports whether a session is active`() {
        val gateway = RecordingGateway()
        val session = newSession(gateway, RecordingRefreshScheduler())

        assertFalse(session.refreshNow())
        session.start(CONFIG)

        assertTrue(session.refreshNow())
        assertEquals(listOf(CONFIG, CONFIG), gateway.published)
    }

    @Test
    fun `initial publish failure cancels session cleans provider and reports typed change`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events, failOnPublishNumber = 1)
        val scheduler = RecordingRefreshScheduler(events)
        val reasons = mutableListOf<RevisionBumpReason>()
        val session = newSession(gateway, scheduler, reasons::add)

        assertThrows(IllegalStateException::class.java) {
            session.start(CONFIG)
        }

        assertEquals(
            listOf("replace", "publish:1", "remove"),
            events,
        )
        assertEquals(listOf(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED), reasons)
        assertFalse(session.isActive)
        assertFalse(session.refreshNow())
    }

    @Test
    fun `periodic publish failure cancels future refreshes and reports typed change once`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events, failOnPublishNumber = 2)
        val scheduler = RecordingRefreshScheduler(events)
        val reasons = mutableListOf<RevisionBumpReason>()
        val session = newSession(gateway, scheduler, reasons::add)
        session.start(CONFIG)

        scheduler.fire(0)
        scheduler.fire(0, evenIfCancelled = true)

        assertEquals(
            listOf(
                "replace",
                "publish:1",
                "schedule:1000:1000",
                "publish:2",
                "cancel",
                "remove",
            ),
            events,
        )
        assertEquals(listOf(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED), reasons)
        assertFalse(session.isActive)
    }

    @Test
    fun `refreshNow failure cancels session and rethrows after reporting typed change`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events, failOnPublishNumber = 2)
        val scheduler = RecordingRefreshScheduler(events)
        val reasons = mutableListOf<RevisionBumpReason>()
        val session = newSession(gateway, scheduler, reasons::add)
        session.start(CONFIG)

        val failure = assertThrows(IllegalStateException::class.java) {
            session.refreshNow()
        }

        assertEquals("publish failed at 2", failure.message)
        assertEquals(
            listOf(
                "replace",
                "publish:1",
                "schedule:1000:1000",
                "publish:2",
                "cancel",
                "remove",
            ),
            events,
        )
        assertEquals(listOf(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED), reasons)
        assertFalse(session.isActive)
    }

    @Test
    fun `stop cancels scheduler before removing providers and stale task cannot publish`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events)
        val scheduler = RecordingRefreshScheduler(events)
        val session = newSession(gateway, scheduler)
        session.start(CONFIG)

        session.stop()
        scheduler.fire(0, evenIfCancelled = true)

        assertEquals(
            listOf(
                "replace",
                "publish:1",
                "schedule:1000:1000",
                "cancel",
                "remove",
            ),
            events,
        )
        assertFalse(session.isActive)
    }

    @Test
    fun `session can restart after stop without reviving the old scheduled task`() {
        val gateway = RecordingGateway()
        val scheduler = RecordingRefreshScheduler()
        val session = newSession(gateway, scheduler)
        session.start(CONFIG)
        session.stop()

        session.start(SECOND_CONFIG)
        scheduler.fire(0, evenIfCancelled = true)
        scheduler.fire(1)

        assertEquals(listOf(CONFIG, SECOND_CONFIG, SECOND_CONFIG), gateway.published)
        assertTrue(session.isActive)
    }

    @Test
    fun `shutdown retires scheduled work removes providers and cannot restart`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events)
        val scheduler = RecordingRefreshScheduler(events)
        val session = newSession(gateway, scheduler)
        session.start(CONFIG)

        session.shutdown()
        session.shutdown()
        scheduler.fire(0, evenIfCancelled = true)

        assertEquals(
            listOf(
                "replace",
                "publish:1",
                "schedule:1000:1000",
                "cancel",
                "remove",
                "scheduler-shutdown",
            ),
            events,
        )
        assertFalse(session.isActive)
        assertThrows(IllegalStateException::class.java) {
            session.start(SECOND_CONFIG)
        }
    }

    @Test
    fun `active advanced projection is reconfigured and handed to the next lease`() {
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(events)
        val scheduler = RecordingRefreshScheduler(events)
        val session = newSession(gateway, scheduler)
        session.start(CONFIG)

        session.startOrReconfigure(SECOND_CONFIG)
        scheduler.fire(0)

        assertEquals(
            listOf(
                "replace",
                "publish:1",
                "schedule:1000:1000",
                "publish:2",
                "publish:3",
            ),
            events,
        )
        assertEquals(
            listOf(CONFIG, SECOND_CONFIG, SECOND_CONFIG),
            gateway.published,
        )
        assertEquals("handoff must retain one scheduled loop", 1, scheduler.taskCount)
        assertTrue(session.isActive)
    }

    @Test
    fun `scheduled executor adapter uses fixed delay and cancellation`() {
        val executor = ScheduledThreadPoolExecutor(1)
        try {
            val scheduler = ScheduledExecutorFrameworkMockRefreshScheduler(executor)
            val cancellation = scheduler.scheduleWithFixedDelay(
                initialDelayMillis = 60_000,
                intervalMillis = 60_000,
                action = {},
            )

            assertEquals(1, executor.queue.size)
            cancellation.cancel()
            executor.purge()
            assertTrue(executor.queue.isEmpty())
            scheduler.shutdown()
            assertTrue(executor.isShutdown)
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
    }

    private fun newSession(
        gateway: MockProviderGateway,
        scheduler: FrameworkMockRefreshScheduler,
        onRelevantChange: (RevisionBumpReason) -> Unit = {},
    ) = FrameworkMockRefreshSession(
        gateway = gateway,
        scheduler = scheduler,
        refreshIntervalMillis = 1_000,
        onRelevantChange = onRelevantChange,
    )

    private class RecordingGateway(
        private val events: MutableList<String> = mutableListOf(),
        private val failOnPublishNumber: Int? = null,
    ) : MockProviderGateway {
        val published = mutableListOf<MockLocationConfig>()
        private var publishCount = 0

        override fun replaceGpsProvider() {
            events += "replace"
        }

        override fun publish(config: MockLocationConfig) {
            publishCount += 1
            events += "publish:$publishCount"
            if (publishCount == failOnPublishNumber) {
                throw IllegalStateException("publish failed at $publishCount")
            }
            published += config
        }

        override fun removeGpsProvider() {
            events += "remove"
        }
    }

    private class RecordingRefreshScheduler(
        private val events: MutableList<String> = mutableListOf(),
    ) : FrameworkMockRefreshScheduler {
        private data class Task(
            val action: () -> Unit,
            var cancelled: Boolean = false,
        )

        private val tasks = mutableListOf<Task>()

        override fun scheduleWithFixedDelay(
            initialDelayMillis: Long,
            intervalMillis: Long,
            action: () -> Unit,
        ): FrameworkMockRefreshCancellation {
            events += "schedule:$initialDelayMillis:$intervalMillis"
            val task = Task(action)
            tasks += task
            return FrameworkMockRefreshCancellation {
                if (!task.cancelled) {
                    task.cancelled = true
                    events += "cancel"
                }
            }
        }

        override fun shutdown() {
            events += "scheduler-shutdown"
        }

        fun fire(index: Int, evenIfCancelled: Boolean = false) {
            val task = tasks[index]
            if (!task.cancelled || evenIfCancelled) task.action()
        }

        val taskCount: Int
            get() = tasks.size
    }

    private companion object {
        val CONFIG = MockLocationConfig(latitude = 50.4501, longitude = 30.5234)
        val SECOND_CONFIG = MockLocationConfig(latitude = 49.8397, longitude = 24.0297)
    }
}
