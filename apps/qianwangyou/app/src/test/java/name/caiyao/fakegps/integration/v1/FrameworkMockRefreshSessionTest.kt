package name.caiyao.fakegps.integration.v1

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    fun `identical tick stays raw while coordinate repair enters semantic mutation`() {
        val gateway = RecordingGateway()
        var projectionMatches = true
        val semanticRepairs = mutableListOf<String>()
        val session = newSession(
            gateway = gateway,
            scheduler = RecordingRefreshScheduler(),
            projectionMatches = { projectionMatches },
            semanticRepair = { kind, operation ->
                semanticRepairs += kind
                operation()
                FrameworkSemanticRepairResult.COMPLETED
            },
        )
        session.start(CONFIG)

        assertTrue(session.refreshNow())
        assertTrue("A to A refresh must not manufacture semantic history", semanticRepairs.isEmpty())

        projectionMatches = false
        assertTrue(session.refreshNow())
        assertEquals(listOf("framework-refresh-coordinate-repair"), semanticRepairs)
        assertEquals(listOf(CONFIG, CONFIG, CONFIG), gateway.published)
    }

    @Test
    fun `selected identical tick never enters authoritative mutation`() {
        val endpoint = RecordingSemanticEndpoint()
        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider { endpoint },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                QwySemanticClientDeathToken { true }
            },
        )
        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            coordinator.registerCurrentSession("digest-a"),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator = coordinator,
            semanticDigestProvider = QwySemanticDigestProvider { "digest-a" },
            sessionHealth = QwySemanticSessionHealth { it == "digest-a" },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val gateway = RecordingGateway()
            val session = newSession(gateway, RecordingRefreshScheduler())
            session.start(CONFIG)

            assertTrue(session.refreshNow())

            assertEquals(
                listOf(
                    "register:digest-a",
                ),
                endpoint.calls,
            )
            assertEquals(listOf(CONFIG, CONFIG), gateway.published)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `authority mismatch before coordinate repair defers without cleaning active session`() {
        val gateway = RecordingGateway()
        val reasons = mutableListOf<RevisionBumpReason>()
        val session = newSession(
            gateway = gateway,
            scheduler = RecordingRefreshScheduler(),
            onRelevantChange = reasons::add,
            projectionMatches = { false },
            semanticRepair = { _, _ -> FrameworkSemanticRepairResult.DEFERRED },
        )
        session.start(CONFIG)

        assertFalse(session.refreshNow())

        assertTrue("a deferred repair must retain its one active refresh session", session.isActive)
        assertFalse(session.isProvablyInactive)
        assertEquals(listOf(CONFIG), gateway.published)
        assertTrue("deferment is not a provider failure", reasons.isEmpty())
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
    fun `failed provider removal remains uncertain until a later removal succeeds`() {
        val gateway = RecordingGateway(failOnRemoveNumber = 1)
        val session = newSession(gateway, RecordingRefreshScheduler())
        session.start(CONFIG)

        assertThrows(IllegalStateException::class.java) {
            session.stop()
        }

        assertFalse(session.isActive)
        assertFalse("failed removal cannot prove an inactive projection", session.isProvablyInactive)

        session.stop()

        assertTrue("a successful cleanup retry proves the inactive state", session.isProvablyInactive)
    }

    @Test
    fun `periodic exact failure brackets only its semantic cleanup`() {
        var semanticDigest = "digest-a"
        val callbackBrackets = mutableListOf<Boolean>()
        val endpoint = RecordingSemanticEndpoint()
        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider { endpoint },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                QwySemanticClientDeathToken { true }
            },
        )
        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            coordinator.registerCurrentSession("digest-a"),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator = coordinator,
            semanticDigestProvider = QwySemanticDigestProvider { semanticDigest },
            sessionHealth = QwySemanticSessionHealth { it == semanticDigest },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val gateway = RecordingGateway(
                failOnPublishNumber = 2,
                onRemove = { semanticDigest = "digest-b" },
            )
            val scheduler = RecordingRefreshScheduler()
            val session = newSession(gateway, scheduler, onRelevantChange = {
                callbackBrackets +=
                    QwySemanticWriterRuntime.isAuthoritativeMutationInFlightOnCurrentThread()
            })
            session.start(CONFIG)

            scheduler.fire(0)

            assertEquals(
                listOf(
                    "register:digest-a",
                    "begin:test-framework-refresh-failure-cleanup:digest-a",
                    "finish:1:true:false:digest-b",
                ),
                endpoint.calls,
            )
            assertEquals(
                "the cleanup callback belongs to the same authoritative bracket",
                listOf(true),
                callbackBrackets,
            )
            assertFalse(session.isActive)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `drift repair failure cleans provider before uncertain finish inside original bracket`() {
        val trace = mutableListOf<String>()
        var semanticDigest = "digest-a"
        val endpoint = RecordingSemanticEndpoint(trace)
        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider { endpoint },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                QwySemanticClientDeathToken { true }
            },
        )
        assertEquals(
            QwySemanticSessionRegistration.Registered("digest-a"),
            coordinator.registerCurrentSession("digest-a"),
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator = coordinator,
            semanticDigestProvider = QwySemanticDigestProvider { semanticDigest },
            sessionHealth = QwySemanticSessionHealth { it == "digest-a" },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val gateway = RecordingGateway(
                events = trace,
                failOnPublishNumber = 2,
                onRemove = { semanticDigest = "digest-inactive" },
            )
            val session = newSession(
                gateway = gateway,
                scheduler = RecordingRefreshScheduler(trace),
                onRelevantChange = {
                    trace += "callback:${
                        QwySemanticWriterRuntime
                            .isAuthoritativeMutationInFlightOnCurrentThread()
                    }"
                },
                projectionMatches = { false },
                semanticRepair = { kind, operation ->
                    if (QwySemanticWriterRuntime.repairExternalProjection(kind, operation)) {
                        FrameworkSemanticRepairResult.COMPLETED
                    } else {
                        FrameworkSemanticRepairResult.DEFERRED
                    }
                },
            )
            session.start(CONFIG)
            semanticDigest = "digest-drifted"
            trace.clear()

            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                session.refreshNow()
            }

            assertEquals(
                listOf(
                    "begin:test-framework-refresh-coordinate-repair:digest-a",
                    "publish:2",
                    "cancel",
                    "remove",
                    "callback:true",
                    "finish:1:false:true:",
                ),
                trace,
            )
            assertFalse(session.isActive)
            assertTrue(session.isProvablyInactive)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `post publish endpoint loss compensates framework session inside original token`() {
        val trace = mutableListOf<String>()
        var semanticDigest = "digest-a"
        var endpointAvailable = true
        val endpoint = RecordingSemanticEndpoint(trace)
        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider {
                endpoint.takeIf { endpointAvailable }
            },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                QwySemanticClientDeathToken { true }
            },
        )
        assertTrue(
            coordinator.registerCurrentSession("digest-a") is
                QwySemanticSessionRegistration.Registered,
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator = coordinator,
            semanticDigestProvider = QwySemanticDigestProvider { semanticDigest },
            sessionHealth = QwySemanticSessionHealth { it == "digest-a" },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val gateway = RecordingGateway(
                events = trace,
                onPublishSuccess = { publishNumber ->
                    if (publishNumber == 2) {
                        semanticDigest = "digest-a"
                        endpointAvailable = false
                    }
                },
                onRemove = { semanticDigest = "digest-inactive" },
            )
            val session = newSession(
                gateway = gateway,
                scheduler = RecordingRefreshScheduler(trace),
                onRelevantChange = {
                    trace += "callback:${QwySemanticWriterRuntime
                        .isAuthoritativeMutationInFlightOnCurrentThread()}"
                },
                projectionMatches = { false },
                semanticRepair = { kind, operation ->
                    if (QwySemanticWriterRuntime.repairExternalProjection(kind, operation)) {
                        FrameworkSemanticRepairResult.COMPLETED
                    } else {
                        FrameworkSemanticRepairResult.DEFERRED
                    }
                },
            )
            session.start(CONFIG)
            semanticDigest = "digest-drifted"
            trace.clear()

            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                session.refreshNow()
            }

            assertEquals(
                listOf(
                    "begin:test-framework-refresh-coordinate-repair:digest-a",
                    "publish:2",
                    "cancel",
                    "remove",
                    "callback:true",
                    "finish:1:false:true:",
                ),
                trace,
            )
            assertFalse(session.isActive)
            assertEquals(1, trace.count { it.startsWith("begin:") })
        } finally {
            installation.close()
        }
    }

    @Test
    fun `finish failure compensates framework session before uncertain retry`() {
        val trace = mutableListOf<String>()
        var semanticDigest = "digest-a"
        val endpoint = RecordingSemanticEndpoint(trace, failFinishCalls = setOf(1))
        val coordinator = QwySemanticMutationCoordinator(
            endpointProvider = QwySemanticMutationEndpointProvider { endpoint },
            clientDeathTokenFactory = QwySemanticClientDeathTokenFactory {
                QwySemanticClientDeathToken { true }
            },
        )
        assertTrue(
            coordinator.registerCurrentSession("digest-a") is
                QwySemanticSessionRegistration.Registered,
        )
        val installation = QwySemanticWriterRuntime.install(
            coordinator = coordinator,
            semanticDigestProvider = QwySemanticDigestProvider { semanticDigest },
            sessionHealth = QwySemanticSessionHealth { it == "digest-a" },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val gateway = RecordingGateway(
                events = trace,
                onPublishSuccess = { publishNumber ->
                    if (publishNumber == 2) semanticDigest = "digest-a"
                },
                onRemove = { semanticDigest = "digest-inactive" },
            )
            val session = newSession(
                gateway = gateway,
                scheduler = RecordingRefreshScheduler(trace),
                onRelevantChange = {
                    trace += "callback:${QwySemanticWriterRuntime
                        .isAuthoritativeMutationInFlightOnCurrentThread()}"
                },
                projectionMatches = { false },
                semanticRepair = { kind, operation ->
                    if (QwySemanticWriterRuntime.repairExternalProjection(kind, operation)) {
                        FrameworkSemanticRepairResult.COMPLETED
                    } else {
                        FrameworkSemanticRepairResult.DEFERRED
                    }
                },
            )
            session.start(CONFIG)
            semanticDigest = "digest-drifted"
            trace.clear()

            assertThrows(QwySemanticWriterAmbiguityException::class.java) {
                session.refreshNow()
            }

            assertEquals(
                listOf(
                    "begin:test-framework-refresh-coordinate-repair:digest-a",
                    "publish:2",
                    "finish:1:true:false:digest-a",
                    "cancel",
                    "remove",
                    "callback:true",
                    "finish:1:false:true:",
                ),
                trace,
            )
            assertEquals(1, trace.count { it.startsWith("begin:") })
            assertFalse(session.isActive)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `stale failed refresh cannot retire newer reconfiguration`() {
        val failedPublish = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val events = mutableListOf<String>()
        val gateway = RecordingGateway(
            events = events,
            failOnPublishNumber = 2,
            onPublishFailure = failedPublish::countDown,
        )
        val scheduler = RecordingRefreshScheduler(events)
        val session = newSession(
            gateway = gateway,
            scheduler = scheduler,
            beforeFailedRefreshCleanup = {
                check(releaseCleanup.await(5, TimeUnit.SECONDS))
            },
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            session.start(CONFIG)
            val failedRefresh = executor.submit { scheduler.fire(0) }
            assertTrue(failedPublish.await(5, TimeUnit.SECONDS))

            session.startOrReconfigure(SECOND_CONFIG)
            releaseCleanup.countDown()
            failedRefresh.get(5, TimeUnit.SECONDS)

            assertTrue("the newer successful projection must remain active", session.isActive)
            assertEquals(listOf(CONFIG, SECOND_CONFIG), gateway.published)
            assertFalse("stale cleanup must not remove the newer projection", events.contains("remove"))
            assertEquals("the original loop remains the single owner", 0, events.count { it == "cancel" })
            assertEquals(1, scheduler.taskCount)

            scheduler.fire(0)
            assertEquals(listOf(CONFIG, SECOND_CONFIG, SECOND_CONFIG), gateway.published)
        } finally {
            releaseCleanup.countDown()
            executor.shutdownNow()
            executor.awaitTermination(1, TimeUnit.SECONDS)
        }
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
        assertTrue(session.isProvablyInactive)
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
        projectionMatches: (MockLocationConfig) -> Boolean = { true },
        semanticRepair: (String, () -> Unit) -> FrameworkSemanticRepairResult =
            { _, operation ->
                operation()
                FrameworkSemanticRepairResult.COMPLETED
            },
        beforeFailedRefreshCleanup: () -> Unit = {},
    ) = FrameworkMockRefreshSession(
        gateway = gateway,
        scheduler = scheduler,
        refreshIntervalMillis = 1_000,
        onRelevantChange = onRelevantChange,
        projectionMatches = projectionMatches,
        semanticRepair = semanticRepair,
        beforeFailedRefreshCleanup = beforeFailedRefreshCleanup,
    )

    private class RecordingGateway(
        private val events: MutableList<String> = mutableListOf(),
        private val failOnPublishNumber: Int? = null,
        private val failOnRemoveNumber: Int? = null,
        private val onRemove: () -> Unit = {},
        private val onPublishFailure: () -> Unit = {},
        private val onPublishSuccess: (Int) -> Unit = {},
    ) : MockProviderGateway {
        val published = mutableListOf<MockLocationConfig>()
        private var publishCount = 0
        private var removeCount = 0

        override fun replaceGpsProvider() {
            events += "replace"
        }

        override fun publish(config: MockLocationConfig) {
            publishCount += 1
            events += "publish:$publishCount"
            if (publishCount == failOnPublishNumber) {
                onPublishFailure()
                throw IllegalStateException("publish failed at $publishCount")
            }
            published += config
            onPublishSuccess(publishCount)
        }

        override fun removeGpsProvider() {
            events += "remove"
            removeCount += 1
            if (removeCount == failOnRemoveNumber) {
                throw IllegalStateException("remove failed at $removeCount")
            }
            onRemove()
        }
    }

    private class RecordingSemanticEndpoint(
        private val trace: MutableList<String>? = null,
        private val failFinishCalls: Set<Int> = emptySet(),
    ) : QwySemanticMutationEndpoint {
        val calls = mutableListOf<String>()
        private var nextToken = 0L
        private var finishCallCount = 0

        private fun record(call: String) {
            calls += call
            trace?.add(call)
        }

        override fun registerCurrentSession(
            semanticDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ) {
            record("register:$semanticDigest")
        }

        override fun beginMutation(
            mutationId: String,
            beforeDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ): Long {
            record("begin:$mutationId:$beforeDigest")
            return ++nextToken
        }

        override fun finishMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) {
            finishCallCount += 1
            record("finish:$token:$changed:$uncertain:${afterDigest.orEmpty()}")
            if (finishCallCount in failFinishCalls) error("finish failed at $finishCallCount")
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
