package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.integration.v1.QwySemanticClientDeathToken
import name.caiyao.fakegps.integration.v1.QwySemanticClientDeathTokenFactory
import name.caiyao.fakegps.integration.v1.QwySemanticDigestProvider
import name.caiyao.fakegps.integration.v1.QwySemanticMutationCoordinator
import name.caiyao.fakegps.integration.v1.QwySemanticMutationEndpoint
import name.caiyao.fakegps.integration.v1.QwySemanticMutationEndpointProvider
import name.caiyao.fakegps.integration.v1.QwySemanticSessionHealth
import name.caiyao.fakegps.integration.v1.QwySemanticSessionRegistration
import name.caiyao.fakegps.integration.v1.QwySemanticWriterAmbiguityException
import name.caiyao.fakegps.integration.v1.QwySemanticWriterRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDeliveryOrchestratorTest {

    @Test
    fun `enable starts provider before publishing system mock intent`() {
        val fixture = Fixture()

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Running)
        assertEquals(
            listOf(
                "cleanup:true",
                "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "cleanup:false",
            ),
            fixture.events,
        )
        assertFalse(fixture.cleanupRequired)
    }

    @Test
    fun `config publication failure rolls intent back to hook and removes provider`() {
        val fixture = Fixture(publishResults = ArrayDeque(listOf(false, true)))

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish",
                "mode:hook", "config:publish", "provider:remove",
                "cleanup:false",
            ),
            fixture.events,
        )
    }

    @Test
    fun `rollback persistence failure leaves transaction marker for safe startup cleanup`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.HOOK,
            publishResults = ArrayDeque(listOf(false)),
            persistModeResults = ArrayDeque(listOf(true, false)),
        )

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(LocationDeliveryMode.SYSTEM_MOCK, fixture.mode)
        assertTrue(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "mode:hook", "provider:remove",
            ),
            fixture.events,
        )
    }

    @Test
    fun `enable never mutates system state when durable cleanup marker cannot be saved`() {
        val fixture = Fixture(cleanupResults = ArrayDeque(listOf(false)))

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(listOf("cleanup:true"), fixture.events)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
    }

    @Test
    fun `provider start failure leaves durable cleanup marker for next launch`() {
        val fixture = Fixture(providerFailure = "replace")

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertTrue(fixture.cleanupRequired)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertEquals(
            listOf(
                "cleanup:true",
                "provider:remove", "provider:replace", "provider:remove",
            ),
            fixture.events,
        )
    }

    @Test
    fun `first enable permission denial clears marker because no provider mutation occurred`() {
        val denied = SecurityException("not allowed to perform MOCK_LOCATION")
        val fixture = Fixture(
            providerFailure = "remove",
            providerFailureThrowable = denied,
        )

        val result = fixture.orchestrator.enable()

        assertEquals(
            MockProviderState.Failed(
                "not allowed to perform MOCK_LOCATION",
                MockProviderRecovery.SelectThisAppAndRetryStart,
            ),
            result,
        )
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `disable persists hook intent then cleans orphan even from fresh controller`() {
        val fixture = Fixture(initialMode = LocationDeliveryMode.SYSTEM_MOCK)

        val result = fixture.orchestrator.disable()

        assertEquals(MockProviderState.Idle, result)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `invalid profile while system mock is selected rolls back intent and provider`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `invalid profile in ordinary hook mode does not touch system provider`() {
        val fixture = Fixture(
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Failed)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `stale cleanup marker in hook mode repairs provider before reporting invalid profile`() {
        val fixture = Fixture(
            initialCleanupRequired = true,
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.enable()

        assertEquals(MockProviderState.Failed("生效档案缺少有效纬度"), result)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `stale cleanup repair failure is returned instead of hiding it behind profile error`() {
        val fixture = Fixture(
            initialCleanupRequired = true,
            cleanupResults = ArrayDeque(listOf(true, false)),
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.enable()

        assertEquals(
            MockProviderState.Failed("GPS 已停止，但无法清除 Mock Provider 恢复标记"),
            result,
        )
        assertTrue(fixture.cleanupRequired)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
    }

    @Test
    fun `mode persistence failure after provider start rolls back every product state`() {
        val fixture = Fixture(persistModeResults = ArrayDeque(listOf(false, true)))

        val result = fixture.orchestrator.enable()

        assertEquals(
            MockProviderState.Failed("System Mock 已回滚：无法保存 System Mock 位置模式"),
            result,
        )
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "mode:hook", "config:publish", "provider:remove",
                "cleanup:false",
            ),
            fixture.events,
        )
    }

    @Test
    fun `stable marker clear failure after enable rolls back provider and intent`() {
        val fixture = Fixture(cleanupResults = ArrayDeque(listOf(true, false, true)))

        val result = fixture.orchestrator.enable()

        assertEquals(
            MockProviderState.Failed("System Mock 已回滚：System Mock 未进入可恢复的稳定状态"),
            result,
        )
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "cleanup:false",
                "mode:hook", "config:publish", "provider:remove", "cleanup:false",
            ),
            fixture.events,
        )
    }

    @Test
    fun `disable still removes provider when hook publication fails`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            publishResults = ArrayDeque(listOf(false, true)),
        )

        val result = fixture.orchestrator.disable()

        assertTrue(result is MockProviderState.Failed)
        assertTrue(fixture.cleanupRequired)
        assertTrue(fixture.events.contains("provider:remove"))

        fixture.events.clear()
        val retried = fixture.orchestrator.disable()

        assertEquals(MockProviderState.Idle, retried)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `disable reports provider cleanup failure and keeps recovery marker`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            providerFailure = "remove",
        )

        val result = fixture.orchestrator.disable()

        assertEquals(
            MockProviderState.Failed(
                "remove failed; cleanup failed: remove failed",
                providerCleanupRequired = true,
            ),
            result,
        )
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertTrue(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "mode:hook", "config:publish",
                "provider:remove", "provider:remove",
            ),
            fixture.events,
        )
    }

    @Test
    fun `disable reports each persistence boundary without skipping provider cleanup`() {
        val markerFailure = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            cleanupResults = ArrayDeque(listOf(false)),
        )
        assertEquals(
            MockProviderState.Failed("GPS 已停止，但无法保存 Mock Provider 恢复标记"),
            markerFailure.orchestrator.disable(),
        )
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove"),
            markerFailure.events,
        )

        val modeFailure = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            persistModeResults = ArrayDeque(listOf(false)),
        )
        assertEquals(
            MockProviderState.Failed("GPS 已停止，但无法保存 Hook 位置模式"),
            modeFailure.orchestrator.disable(),
        )
        assertEquals(LocationDeliveryMode.SYSTEM_MOCK, modeFailure.mode)
        assertTrue(modeFailure.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "provider:remove"),
            modeFailure.events,
        )

        val markerClearFailure = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            cleanupResults = ArrayDeque(listOf(true, false)),
        )
        assertEquals(
            MockProviderState.Failed("GPS 已停止，但无法清除 Mock Provider 恢复标记"),
            markerClearFailure.orchestrator.disable(),
        )
        assertTrue(markerClearFailure.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false",
            ),
            markerClearFailure.events,
        )
    }

    @Test
    fun `refresh publishes same profile and replaces provider when effective profile changes`() {
        val kyiv = published(50.4501, 30.5234)
        val lviv = published(49.8397, 24.0297)
        val profiles = ArrayDeque(listOf(kyiv, kyiv, lviv))
        val fixture = Fixture(readPublished = { profiles.removeFirst() })

        fixture.orchestrator.enable()
        fixture.events.clear()
        fixture.orchestrator.refresh()
        fixture.orchestrator.refresh()

        assertEquals(
            listOf(
                "provider:publish",
                "provider:remove", "provider:replace", "provider:publish",
            ),
            fixture.events,
        )
    }

    @Test
    fun `same desired config uses semantic repair when exact OS projection drifted`() {
        var projectionMatches = true
        val repairKinds = mutableListOf<String>()
        val fixture = Fixture(
            projectionMatchesExactly = { projectionMatches },
            semanticProjectionRepair = { kind, operation ->
                repairKinds += kind
                operation()
                true
            },
        )
        fixture.orchestrator.enable()
        fixture.events.clear()

        projectionMatches = false
        val result = fixture.orchestrator.refresh()

        assertTrue(result is MockProviderState.Running)
        assertEquals(listOf("mock-provider-refresh-coordinate-repair"), repairKinds)
        assertEquals(listOf("provider:publish"), fixture.events)
    }

    @Test
    fun `drift repair publish failure cleans provider inside the repair interval`() {
        var projectionMatches = true
        val ownership = InProcessMockProviderOwnership()
        val repairEvents = mutableListOf<String>()
        val fixture = Fixture(
            ownership = ownership,
            publishFailureCalls = setOf(2),
            projectionMatchesExactly = { projectionMatches },
            semanticProjectionRepair = { _, operation ->
                repairEvents += "repair:begin"
                try {
                    operation()
                } finally {
                    repairEvents +=
                        "repair:end:${ownership.projectionOwnershipSnapshot()::class.simpleName}"
                }
                true
            },
        )
        fixture.orchestrator.enable()
        fixture.events.clear()
        projectionMatches = false

        assertThrows(IllegalStateException::class.java) {
            fixture.orchestrator.refresh()
        }

        assertEquals(
            listOf("provider:publish", "provider:remove"),
            fixture.events,
        )
        assertEquals(
            listOf("repair:begin", "repair:end:ProvablyInactive"),
            repairEvents,
        )
        assertEquals(
            MockProviderProjectionOwnership.ProvablyInactive,
            ownership.projectionOwnershipSnapshot(),
        )
        assertTrue(fixture.controller.state is MockProviderState.Failed)
    }

    @Test
    fun `exact tick failure enters oracle only before semantic cleanup`() {
        val trace = mutableListOf<String>()
        var semanticDigest = "digest-a"
        var removeCount = 0
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
            sessionHealth = QwySemanticSessionHealth { it == semanticDigest },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val fixture = Fixture(
                publishFailureCalls = setOf(2),
                ownership = InProcessMockProviderOwnership(),
                eventSink = trace,
                onProviderRemove = {
                    removeCount += 1
                    if (removeCount > 1) semanticDigest = "digest-inactive"
                },
                semanticMutation = QwySemanticWriterRuntime::mutate,
                projectionMatchesExactly = { true },
                semanticProjectionRepair =
                    QwySemanticWriterRuntime::repairExternalProjection,
            )
            fixture.orchestrator.enable()
            trace.clear()

            val result = fixture.orchestrator.refresh()

            assertEquals(
                listOf(
                    "provider:publish",
                    "begin:test-mock-provider-refresh-failure-cleanup:digest-a",
                    "provider:remove",
                    "finish:2:true:false:digest-inactive",
                ),
                trace,
            )
            assertTrue(result is MockProviderState.Failed)
        } finally {
            installation.close()
        }
    }

    @Test
    fun `exact tick publishes raw without semantic begin`() {
        val trace = mutableListOf<String>()
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
            semanticDigestProvider = QwySemanticDigestProvider { "digest-a" },
            sessionHealth = QwySemanticSessionHealth { it == "digest-a" },
            mutationIdFactory = { kind -> "test-$kind" },
        )
        try {
            val fixture = Fixture(
                ownership = InProcessMockProviderOwnership(),
                eventSink = trace,
                semanticMutation = QwySemanticWriterRuntime::mutate,
                projectionMatchesExactly = { true },
                semanticProjectionRepair =
                    QwySemanticWriterRuntime::repairExternalProjection,
            )
            fixture.orchestrator.enable()
            trace.clear()

            val result = fixture.orchestrator.refresh()

            assertTrue(result is MockProviderState.Running)
            assertEquals(
                listOf(
                    "provider:publish",
                ),
                trace,
            )
        } finally {
            installation.close()
        }
    }

    @Test
    fun `integration handback makes stale running service perform ordinary semantic restart`() {
        val mutationKinds = mutableListOf<String>()
        val repairKinds = mutableListOf<String>()
        val ownership = InProcessMockProviderOwnership()
        val fixture = Fixture(
            ownership = ownership,
            projectionMatchesExactly = { false },
            semanticMutation = { kind, operation ->
                mutationKinds += kind
                operation(false)
            },
            semanticProjectionRepair = { kind, operation ->
                repairKinds += kind
                operation()
                true
            },
        )
        try {
            fixture.orchestrator.enable()
            assertTrue(fixture.controller.state is MockProviderState.Running)
            assertTrue(
                ownership.projectionOwnershipSnapshot() is
                    MockProviderProjectionOwnership.ServiceActive,
            )

            val claim = ownership.claimIntegration()
            ownership.releaseIntegration(claim) {
                fixture.events += "integration:remove"
            }
            fixture.events.clear()
            mutationKinds.clear()

            val result = fixture.orchestrator.refresh()

            assertTrue(result is MockProviderState.Running)
            assertEquals(listOf("mock-provider-refresh-reconfigure"), mutationKinds)
            assertTrue(repairKinds.isEmpty())
            assertEquals(
                listOf("provider:remove", "provider:replace", "provider:publish"),
                fixture.events,
            )
            assertEquals(
                MockProviderProjectionOwnership.ServiceActive(
                    (result as MockProviderState.Running).config,
                ),
                ownership.projectionOwnershipSnapshot(),
            )
        } finally {
            val cleanup = ownership.claimIntegration()
            ownership.releaseIntegration(cleanup) {}
        }
    }

    @Test
    fun `refresh after persisted hook switch disables runtime instead of restarting provider`() {
        val fixture = Fixture(initialMode = LocationDeliveryMode.HOOK)

        val result = fixture.orchestrator.refresh()

        assertEquals(MockProviderState.Idle, result)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `refresh permission denial keeps cleanup recovery for an existing system mock session`() {
        val denied = SecurityException("not allowed to perform MOCK_LOCATION")
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            providerFailure = "remove",
            providerFailureThrowable = denied,
        )

        val result = fixture.orchestrator.refresh()

        assertEquals(
            MockProviderState.Failed(
                "not allowed to perform MOCK_LOCATION; cleanup failed: " +
                    "not allowed to perform MOCK_LOCATION",
                MockProviderRecovery.ReselectThisAppAndRetryStop,
                providerCleanupRequired = true,
            ),
            result,
        )
        assertEquals(LocationDeliveryMode.SYSTEM_MOCK, fixture.mode)
        assertEquals(listOf("provider:remove", "provider:remove"), fixture.events)
    }

    @Test
    fun `refresh invalid profile stops provider then preserves the profile failure reason`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            readPublished = { published(50.4501, 30.5234).copy(fields = emptyMap()) },
        )

        val result = fixture.orchestrator.refresh()

        assertEquals(MockProviderState.Failed("生效档案缺少有效纬度"), result)
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertFalse(fixture.cleanupRequired)
        assertEquals(
            listOf("cleanup:true", "mode:hook", "config:publish", "provider:remove", "cleanup:false"),
            fixture.events,
        )
    }

    @Test
    fun `runtime cleanup removes orphan without changing persisted user intent or marker`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.SYSTEM_MOCK,
            initialCleanupRequired = true,
        )

        val result = fixture.orchestrator.cleanupRuntimeOnly()

        assertEquals(MockProviderState.Idle, result)
        assertEquals(LocationDeliveryMode.SYSTEM_MOCK, fixture.mode)
        assertTrue(fixture.cleanupRequired)
        assertEquals(listOf("provider:remove"), fixture.events)
    }

    @Test
    fun `runtime cleanup does not invent an orphan in ordinary hook mode`() {
        val fixture = Fixture(
            initialMode = LocationDeliveryMode.HOOK,
            initialCleanupRequired = false,
        )

        val result = fixture.orchestrator.cleanupRuntimeOnly()

        assertEquals(MockProviderState.Idle, result)
        assertTrue(fixture.events.isEmpty())
    }

    @Test
    fun `rollback reports hook publication failure and retains recovery marker`() {
        val fixture = Fixture(publishResults = ArrayDeque(listOf(false, false)))

        val result = fixture.orchestrator.enable()

        assertEquals(
            MockProviderState.Failed(
                "无法发布 System Mock 的 Hook 位置旁路配置；回滚时 Hook 配置发布失败",
            ),
            result,
        )
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertTrue(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "mode:hook", "config:publish",
                "provider:remove",
            ),
            fixture.events,
        )
    }

    @Test
    fun `rollback reports late provider removal failure and retains recovery marker`() {
        val fixture = Fixture(
            publishResults = ArrayDeque(listOf(false, true)),
            removeFailureCalls = setOf(2),
        )

        val result = fixture.orchestrator.enable()

        assertEquals(
            MockProviderState.Failed(
                "无法发布 System Mock 的 Hook 位置旁路配置；回滚时 GPS provider 清理失败",
            ),
            result,
        )
        assertEquals(LocationDeliveryMode.HOOK, fixture.mode)
        assertTrue(fixture.cleanupRequired)
        assertEquals(
            listOf(
                "cleanup:true", "provider:remove", "provider:replace", "provider:publish",
                "mode:system_mock", "config:publish", "mode:hook", "config:publish",
                "provider:remove", "provider:remove",
            ),
            fixture.events,
        )
    }

    private class Fixture(
        initialMode: LocationDeliveryMode = LocationDeliveryMode.HOOK,
        initialCleanupRequired: Boolean = false,
        private val publishResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        private val persistModeResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        private val cleanupResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true, true, true)),
        private val providerFailure: String? = null,
        private val providerFailureThrowable: Throwable? = null,
        private val publishFailureCalls: Set<Int> = emptySet(),
        private val removeFailureCalls: Set<Int> = emptySet(),
        readPublished: () -> PublishedConfig? = { published(50.4501, 30.5234) },
        ownership: MockProviderOwnership = FixtureOwnership(),
        semanticMutation: (
            String,
            (Boolean) -> MockProviderState,
        ) -> MockProviderState = { _, operation -> operation(false) },
        projectionMatchesExactly: (MockLocationConfig) -> Boolean = { true },
        semanticProjectionRepair: (String, () -> Unit) -> Boolean = { _, operation ->
            operation()
            true
        },
        eventSink: MutableList<String> = mutableListOf(),
        private val onProviderPublishFailure: () -> Unit = {},
        private val onProviderRemove: () -> Unit = {},
    ) {
        val events = eventSink
        var mode = initialMode
        var cleanupRequired = initialCleanupRequired
        private var removeCallCount = 0
        private var publishCallCount = 0
        private val gateway = object : MockProviderGateway {
            override fun replaceGpsProvider() {
                events += "provider:replace"
                if (providerFailure == "replace") {
                    throw providerFailureThrowable ?: error("replace failed")
                }
            }
            override fun publish(config: MockLocationConfig) {
                events += "provider:publish"
                publishCallCount += 1
                if (providerFailure == "publish") {
                    onProviderPublishFailure()
                    throw providerFailureThrowable ?: error("publish failed")
                }
                if (publishCallCount in publishFailureCalls) {
                    onProviderPublishFailure()
                    error("publish $publishCallCount failed")
                }
            }
            override fun removeGpsProvider() {
                events += "provider:remove"
                removeCallCount += 1
                if (providerFailure == "remove") {
                    throw providerFailureThrowable ?: error("remove failed")
                }
                if (removeCallCount in removeFailureCalls) error("remove $removeCallCount failed")
                onProviderRemove()
            }
        }
        val controller = MockProviderSessionController(gateway, ownership = ownership)
        val orchestrator = LocationDeliveryOrchestrator(
            controller = controller,
            readPublished = readPublished,
            readMode = { mode },
            readCleanupRequired = { cleanupRequired },
            persistMode = {
                events += "mode:${it.wireValue}"
                val result = persistModeResults.removeFirstOrNull() ?: true
                if (result) mode = it
                result
            },
            publishConfig = {
                events += "config:publish"
                publishResults.removeFirstOrNull() ?: true
            },
            persistCleanupRequired = {
                events += "cleanup:$it"
                val result = cleanupResults.removeFirstOrNull() ?: true
                if (result) cleanupRequired = it
                result
            },
            ownership = ownership,
            semanticMutation = semanticMutation,
            projectionMatchesExactly = projectionMatchesExactly,
            semanticProjectionRepair = semanticProjectionRepair,
        )
    }

    private class RecordingSemanticEndpoint(
        private val trace: MutableList<String>,
    ) : QwySemanticMutationEndpoint {
        private var nextToken = 0L

        override fun registerCurrentSession(
            semanticDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ) {
            trace += "register:$semanticDigest"
        }

        override fun beginMutation(
            mutationId: String,
            beforeDigest: String,
            clientDeathToken: QwySemanticClientDeathToken,
        ): Long {
            trace += "begin:$mutationId:$beforeDigest"
            return ++nextToken
        }

        override fun finishMutation(
            token: Long,
            changed: Boolean,
            uncertain: Boolean,
            afterDigest: String?,
        ) {
            trace += "finish:$token:$changed:$uncertain:${afterDigest.orEmpty()}"
        }
    }

    private class FixtureOwnership : MockProviderOwnership {
        private var nextClaim = 0L
        private var integrationClaim: Long? = null
        private var projection: MockProviderProjectionOwnership =
            MockProviderProjectionOwnership.ProvablyInactive

        override fun claimIntegration(): Long = (++nextClaim).also {
            integrationClaim = it
            projection = MockProviderProjectionOwnership.IntegrationActive(it)
        }

        override fun runAsIntegration(claim: Long, operation: () -> Unit): Boolean {
            if (integrationClaim != claim) return false
            operation()
            return true
        }

        override fun releaseIntegration(claim: Long, operation: () -> Unit): Boolean {
            if (integrationClaim != claim) return false
            projection = MockProviderProjectionOwnership.Uncertain
            operation()
            integrationClaim = null
            projection = MockProviderProjectionOwnership.ProvablyInactive
            return true
        }

        override fun runAsService(operation: () -> Unit): Boolean {
            if (integrationClaim != null) return false
            operation()
            return true
        }

        override fun markServiceProjectionUncertain() {
            projection = MockProviderProjectionOwnership.Uncertain
        }

        override fun markServiceProjectionActive(config: MockLocationConfig) {
            projection = MockProviderProjectionOwnership.ServiceActive(config)
        }

        override fun markServiceProjectionInactive() {
            projection = MockProviderProjectionOwnership.ProvablyInactive
        }

        override fun projectionOwnershipSnapshot(): MockProviderProjectionOwnership = projection
    }

    companion object {
        private fun published(latitude: Double, longitude: Double) = PublishedConfig(
            schemaVersion = 4,
            mode = "always_on",
            fields = mapOf(
                "latitude" to latitude.toString(),
                "longitude" to longitude.toString(),
            ),
            locationDeliveryMode = "hook",
        )
    }
}
