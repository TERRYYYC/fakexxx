package name.caiyao.fakegps.mockprovider

enum class MockProviderRecovery {
    SelectThisAppAndRetryStart,
    ReselectThisAppAndRetryStop,
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
    ) : MockProviderState
}

class MockProviderSessionController(
    private val gateway: MockProviderGateway,
    private val ownership: MockProviderOwnership = MockProviderOwnership.UNRESTRICTED,
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
                val admitted = ownership.runAsService {
                    // System test providers can survive process death. Always repair stale state first.
                    ownership.markServiceProjectionUncertain()
                    gateway.removeGpsProvider()
                    // From this boundary onward registration may have partially changed system state,
                    // even when replaceGpsProvider itself throws before returning.
                    providerMutationStarted = true
                    gateway.replaceGpsProvider()
                    gateway.publish(config)
                    ownership.markServiceProjectionActive(config)
                }
                if (!admitted) throw IntegrationProviderOwnsDevice()
            },
            success = MockProviderState.Running(config, emittedCount = 1),
            cleanupRequiredOnFailure = { providerMutationStarted },
        )
    }

    fun tick() {
        publishTickSample()?.let(::finishFailedTick)
    }

    /** Identical sample publication only; failure cleanup is a semantic writer. */
    internal fun publishTickSample(): Throwable? {
        val running = state as? MockProviderState.Running ?: return null
        return try {
            val admitted = ownership.runAsService {
                gateway.publish(running.config)
                ownership.markServiceProjectionActive(running.config)
            }
            if (!admitted) {
                updateState(
                    MockProviderState.Failed(
                        "Environment Control owns the system mock provider",
                        providerCleanupRequired = false,
                    ),
                )
                return null
            }
            updateState(running.copy(emittedCount = running.emittedCount + 1))
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    /** Must be called inside the outer semantic mutation selected by the orchestrator. */
    internal fun finishFailedTick(failure: Throwable) {
        recordFailure(failure, providerCleanupRequired = true)
    }

    fun stop() {
        updateState(MockProviderState.Stopping)
        transition(
            // Never short-circuit on in-memory Idle: a previous process may own the real residue.
            // A stale service controller must not remove a newer integration
            // lease's provider; denial retires only this in-memory owner.
            sideEffect = {
                ownership.runAsService {
                    ownership.markServiceProjectionUncertain()
                    gateway.removeGpsProvider()
                    ownership.markServiceProjectionInactive()
                }
            },
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
            recordFailure(failure, cleanupRequiredOnFailure())
        }
    }

    private fun recordFailure(failure: Throwable, providerCleanupRequired: Boolean) {
        val cleanupFailure = if (providerCleanupRequired) {
            runCatching {
                ownership.runAsService {
                    ownership.markServiceProjectionUncertain()
                    gateway.removeGpsProvider()
                    ownership.markServiceProjectionInactive()
                }
            }.exceptionOrNull()
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
            ),
        )
    }

    private fun updateState(next: MockProviderState) {
        state = next
        onStateChanged(next)
    }

    private class IntegrationProviderOwnsDevice : IllegalStateException(
        "Environment Control owns the system mock provider",
    )
}
