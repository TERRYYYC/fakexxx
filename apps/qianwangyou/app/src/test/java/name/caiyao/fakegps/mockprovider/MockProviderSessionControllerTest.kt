package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderSessionControllerTest {

    @Test
    fun `start removes stale provider then registers and publishes immediately`() {
        val gateway = RecordingMockProviderGateway()
        val states = mutableListOf<MockProviderState>()
        val controller = MockProviderSessionController(gateway) { states += it }
        val config = MockLocationConfig(50.4501, 30.5234)

        controller.start(config)

        assertEquals(listOf("remove", "replace", "publish:$config"), gateway.calls)
        assertEquals(MockProviderState.Running(config, emittedCount = 1), controller.state)
        assertEquals(
            listOf(
                MockProviderState.Starting(config),
                MockProviderState.Running(config, emittedCount = 1),
            ),
            states,
        )
    }

    @Test
    fun `fresh controller stop still removes a provider orphaned by a dead process`() {
        val gateway = RecordingMockProviderGateway()
        val states = mutableListOf<MockProviderState>()
        val controller = MockProviderSessionController(gateway) { states += it }

        controller.stop()

        assertEquals(listOf("remove"), gateway.calls)
        assertEquals(MockProviderState.Idle, controller.state)
        assertEquals(listOf(MockProviderState.Stopping, MockProviderState.Idle), states)
    }

    @Test
    fun `publish failure performs best effort cleanup and remains visible`() {
        val gateway = RecordingMockProviderGateway(failAt = "publish")
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        assertEquals(listOf("remove", "replace", "publish", "remove"), gateway.calls)
        assertTrue(controller.state is MockProviderState.Failed)
    }

    @Test
    fun `tick only publishes while running and increments evidence count`() {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)
        val config = MockLocationConfig(50.4501, 30.5234)

        controller.tick()
        controller.start(config)
        controller.tick()

        assertEquals(2, gateway.calls.count { it.startsWith("publish:") })
        assertEquals(MockProviderState.Running(config, emittedCount = 2), controller.state)
    }

    @Test
    fun `repeated start replaces the session and resets emitted evidence`() {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)
        val first = MockLocationConfig(50.4501, 30.5234)
        val second = MockLocationConfig(49.8397, 24.0297)

        controller.start(first)
        controller.tick()
        controller.start(second)

        assertEquals(MockProviderState.Running(second, emittedCount = 1), controller.state)
        assertEquals(
            listOf(
                "remove", "replace", "publish:$first", "publish:$first",
                "remove", "replace", "publish:$second",
            ),
            gateway.calls,
        )
    }

    @Test
    fun `stop is idempotent and still probes for an orphan each time`() {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)

        controller.stop()
        controller.stop()

        assertEquals(listOf("remove", "remove"), gateway.calls)
        assertEquals(MockProviderState.Idle, controller.state)
    }

    @Test
    fun `mock app permission failure carries an actionable recovery`() {
        val gateway = RecordingMockProviderGateway(
            failure = SecurityException("not allowed to perform MOCK_LOCATION"),
            failAt = "remove",
        )
        val controller = MockProviderSessionController(gateway)

        controller.stop()

        assertEquals(
            MockProviderState.Failed(
                message = "not allowed to perform MOCK_LOCATION; cleanup failed: " +
                    "not allowed to perform MOCK_LOCATION",
                recovery = MockProviderRecovery.ReselectThisAppAndRetryStop,
                providerCleanupRequired = true,
                // Issue #8: the denial is TYPED so the settings card renders re-selection guidance.
                reason = MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED,
            ),
            controller.state,
        )
    }

    @Test
    fun `first start permission denial requests selection without inventing cleanup work`() {
        val gateway = RecordingMockProviderGateway(
            failure = SecurityException("not allowed to perform MOCK_LOCATION"),
            failAt = "remove",
        )
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        assertEquals(listOf("remove"), gateway.calls)
        assertEquals(
            MockProviderState.Failed(
                message = "not allowed to perform MOCK_LOCATION",
                recovery = MockProviderRecovery.SelectThisAppAndRetryStart,
                providerCleanupRequired = false,
                // Issue #8: the denial is TYPED so the settings card renders re-selection guidance.
                reason = MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED,
            ),
            controller.state,
        )
    }

    /**
     * Issue #8 RED: the OS resets the android:mock_location app-op at will (observed as
     * "name.caiyao.fakegps.glmbench from uid 10396 not allowed to perform MOCK_LOCATION" on a
     * Moto / Android 15). The failed state must carry the TYPED reason — the settings card keys
     * its guidance off the reason, not off the raw framework message.
     */
    @Test
    fun `mock location app-op denial is typed in the failed state`() {
        val gateway = RecordingMockProviderGateway(
            failure = SecurityException(
                "name.caiyao.fakegps.glmbench from uid 10396 not allowed to perform MOCK_LOCATION",
            ),
            failAt = "remove",
        )
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        val failed = controller.state as MockProviderState.Failed
        assertEquals(MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED, failed.reason)
    }

    /** Issue #8: an unrelated SecurityException must NOT be typed as an app-op denial. */
    @Test
    fun `unrelated security failure is not typed as app-op denial`() {
        val gateway = RecordingMockProviderGateway(
            failure = SecurityException("provider already has a test provider"),
            failAt = "replace",
        )
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        val failed = controller.state as MockProviderState.Failed
        assertEquals(null, failed.reason)
    }

    /** Issue #8: the typed reason survives even when only the CLEANUP leg hit the app-op denial. */
    @Test
    fun `app-op denial during cleanup is typed in the failed state`() {
        val gateway = RecordingMockProviderGateway(
            failure = SecurityException("not allowed to perform MOCK_LOCATION"),
            failAt = "publish",
        )
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        val failed = controller.state as MockProviderState.Failed
        assertEquals(MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED, failed.reason)
        assertEquals(MockProviderRecovery.ReselectThisAppAndRetryStop, failed.recovery)
    }

    @Test
    fun `config accepts geographic boundaries and rejects nonfinite altitude`() {
        MockLocationConfig(-90.0, 180.0, altitudeMeters = -430.0)
        MockLocationConfig(90.0, -180.0, altitudeMeters = 8_848.86)

        listOf(Double.NaN, Double.POSITIVE_INFINITY).forEach { altitude ->
            val failure = runCatching {
                MockLocationConfig(0.0, 0.0, altitudeMeters = altitude)
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
        }
    }
}

internal class RecordingMockProviderGateway(
    private val failAt: String? = null,
    private val failure: Throwable = IllegalStateException("$failAt failed"),
) : MockProviderGateway {
    val calls = mutableListOf<String>()

    override fun replaceGpsProvider() {
        calls += "replace"
        if (failAt == "replace") throw failure
    }

    override fun publish(config: MockLocationConfig) {
        calls += if (failAt == "publish") "publish" else "publish:$config"
        if (failAt == "publish") throw failure
    }

    override fun removeGpsProvider() {
        calls += "remove"
        if (failAt == "remove") throw failure
    }
}
