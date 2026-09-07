package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.motion.RoutePlayer
import name.caiyao.fakegps.motion.RouteSpec

enum class MockProviderRecovery {
    SelectThisAppAndRetryStart,
    ReselectThisAppAndRetryStop,
}

/**
 * Typed causes a [MockProviderState.Failed] can carry (issue #8). The OS can silently reset the
 * android:mock_location app-op back to deny (observed overnight on a Moto / Android 15): the
 * framework surfaces that as a SecurityException whose message mentions MOCK_LOCATION. Mapping it
 * to a typed reason lets the settings card render the re-selection guidance without string
 * matching the raw message.
 */
enum class MockProviderFailureReason {
    MOCK_LOCATION_APP_OP_DENIED,
}

sealed interface MockProviderState {
    data object Idle : MockProviderState
    data class Starting(val config: MockLocationConfig) : MockProviderState
    data class Running(
        val config: MockLocationConfig,
        val emittedCount: Long,
        /**
         * P3.1 运动链: the streaming route player for THIS session, null for a static-point
         * session. Each tick() advances it by one 1 Hz beat and publishes its emission instead
         * of re-publishing [config]; [config] stays the session's STATIC base (the departure
         * fix) so refresh comparison and UI projections remain meaningful while moving.
         */
        val routePlayer: RoutePlayer? = null,
        /** Identity of the playing route ([RouteSpec.sessionKey]); null = static session. */
        val routeKey: String? = null,
        /** Flips true exactly once, on the tick that publishes the arrival fix. */
        val routeCompleted: Boolean = false,
        /**
         * The fix published on ticks AFTER completion: the exact final waypoint. Publishing the
         * static [config] again would visibly teleport the vehicle back to the route origin.
         */
        val routeArrival: MockLocationConfig? = null,
    ) : MockProviderState
    data object Stopping : MockProviderState
    data class Failed(
        val message: String,
        val recovery: MockProviderRecovery? = null,
        val providerCleanupRequired: Boolean = false,
        val reason: MockProviderFailureReason? = null,
    ) : MockProviderState
}

class MockProviderSessionController(
    private val gateway: MockProviderGateway,
    private val onStateChanged: (MockProviderState) -> Unit = {},
    /** P3.1: fired ONCE when a playing route reaches its final waypoint (日程推进挂钩点). */
    private val onRouteCompleted: (RouteSpec) -> Unit = {},
) {
    var state: MockProviderState = MockProviderState.Idle
        private set

    fun start(
        config: MockLocationConfig,
        providerMayAlreadyExist: Boolean = state is MockProviderState.Running,
        route: RoutePlayer? = null,
    ) {
        var providerMutationStarted = providerMayAlreadyExist
        updateState(MockProviderState.Starting(config))
        transition(
            sideEffect = {
                // System test providers can survive process death. Always repair stale state first.
                gateway.removeGpsProvider()
                // From this boundary onward registration may have partially changed system state,
                // even when replaceGpsProvider itself throws before returning.
                providerMutationStarted = true
                gateway.replaceGpsProvider()
                gateway.publish(config)
            },
            success = MockProviderState.Running(
                config,
                emittedCount = 1,
                routePlayer = route,
                routeKey = route?.spec?.sessionKey(),
                routeArrival = route?.spec?.waypoints?.lastOrNull()?.let { waypoint ->
                    MockLocationConfig(
                        waypoint.latitude,
                        waypoint.longitude,
                        accuracyMeters = config.accuracyMeters,
                        altitudeMeters = config.altitudeMeters,
                    )
                },
            ),
            cleanupRequiredOnFailure = { providerMutationStarted },
        )
    }

    fun tick() {
        val running = state as? MockProviderState.Running ?: return
        val emission = running.routePlayer?.tick()
        val publishedConfig = when {
            emission != null -> emission.toMockLocationConfig(running.config)
            running.routeArrival != null -> running.routeArrival
            else -> running.config
        }
        transition(
            sideEffect = { gateway.publish(publishedConfig) },
            success = running.copy(
                emittedCount = running.emittedCount + 1,
                routeCompleted = running.routeCompleted || running.routePlayer?.completed == true,
            ),
        )
        val next = state as? MockProviderState.Running
        if (next?.routeCompleted == true && !running.routeCompleted) {
            next.routePlayer?.let { onRouteCompleted(it.spec) }
        }
    }

    fun stop() {
        updateState(MockProviderState.Stopping)
        transition(
            // Never short-circuit on in-memory Idle: a previous process may own the real residue.
            sideEffect = gateway::removeGpsProvider,
            success = MockProviderState.Idle,
        )
    }

    private fun transition(
        sideEffect: () -> Unit,
        success: MockProviderState,
        cleanupRequiredOnFailure: () -> Boolean = { true },
    ) {
        try {
            sideEffect()
            updateState(success)
        } catch (failure: Throwable) {
            val providerCleanupRequired = cleanupRequiredOnFailure()
            val cleanupFailure = if (providerCleanupRequired) {
                runCatching(gateway::removeGpsProvider).exceptionOrNull()
            } else {
                null
            }
            val primary = failure.message ?: failure.javaClass.simpleName
            val cleanup = cleanupFailure?.let {
                "; cleanup failed: ${it.message ?: it.javaClass.simpleName}"
            }.orEmpty()
            val recovery = if (
                failure is SecurityException || cleanupFailure is SecurityException
            ) {
                if (providerCleanupRequired) {
                    MockProviderRecovery.ReselectThisAppAndRetryStop
                } else {
                    MockProviderRecovery.SelectThisAppAndRetryStart
                }
            } else {
                null
            }
            updateState(
                MockProviderState.Failed(
                    primary + cleanup,
                    recovery,
                    providerCleanupRequired = providerCleanupRequired,
                    reason = mockLocationAppOpDeniedReason(failure, cleanupFailure),
                ),
            )
        }
    }

    /**
     * Issue #8: the framework raises the reset app-op as a SecurityException whose message
     * mentions MOCK_LOCATION ("... from uid N not allowed to perform MOCK_LOCATION"). The
     * primary failure AND the cleanup leg can each carry it — either typing the state is enough
     * for the settings card to render the re-selection guidance.
     */
    private fun mockLocationAppOpDeniedReason(
        vararg failures: Throwable?,
    ): MockProviderFailureReason? =
        failures.filterIsInstance<SecurityException>()
            .firstOrNull { it.message?.contains("MOCK_LOCATION") == true }
            ?.let { MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED }

    private fun updateState(next: MockProviderState) {
        state = next
        onStateChanged(next)
    }
}
