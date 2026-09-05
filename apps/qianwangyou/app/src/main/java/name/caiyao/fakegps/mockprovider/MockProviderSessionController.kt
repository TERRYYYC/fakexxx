package name.caiyao.fakegps.mockprovider

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
) {
    var state: MockProviderState = MockProviderState.Idle
        private set

    fun start(
        config: MockLocationConfig,
        providerMayAlreadyExist: Boolean = state is MockProviderState.Running,
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
            success = MockProviderState.Running(config, emittedCount = 1),
            cleanupRequiredOnFailure = { providerMutationStarted },
        )
    }

    fun tick() {
        val running = state as? MockProviderState.Running ?: return
        transition(
            sideEffect = { gateway.publish(running.config) },
            success = running.copy(emittedCount = running.emittedCount + 1),
        )
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
