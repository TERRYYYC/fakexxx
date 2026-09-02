package name.caiyao.fakegps.integration.v1

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderGateway
import name.caiyao.fakegps.mockprovider.MockProviderOwnership

internal fun interface FrameworkMockRefreshCancellation {
    fun cancel()
}

/** Scheduler seam whose actions must not run before [scheduleWithFixedDelay] returns. */
internal interface FrameworkMockRefreshScheduler {
    fun scheduleWithFixedDelay(
        initialDelayMillis: Long,
        intervalMillis: Long,
        action: () -> Unit,
    ): FrameworkMockRefreshCancellation

    /** Permanently retires scheduler resources owned by this session. */
    fun shutdown()
}

internal enum class FrameworkSemanticRepairResult {
    COMPLETED,
    DEFERRED,
}

internal class ScheduledExecutorFrameworkMockRefreshScheduler(
    private val executor: ScheduledExecutorService,
) : FrameworkMockRefreshScheduler {
    override fun scheduleWithFixedDelay(
        initialDelayMillis: Long,
        intervalMillis: Long,
        action: () -> Unit,
    ): FrameworkMockRefreshCancellation {
        require(initialDelayMillis > 0) { "initialDelayMillis must be positive" }
        require(intervalMillis > 0) { "intervalMillis must be positive" }
        val future = executor.scheduleWithFixedDelay(
            Runnable(action),
            initialDelayMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        return FrameworkMockRefreshCancellation { future.cancel(false) }
    }

    override fun shutdown() {
        executor.shutdownNow()
    }
}

/**
 * Owns the single framework-provider refresh loop for the process's active lease.
 *
 * The handler already serializes one global lease, so this object deliberately
 * exposes no caller-supplied lease token. An internal generation prevents a
 * cancelled task from an earlier lease from publishing into a later session.
 */
internal class FrameworkMockRefreshSession(
    private val gateway: MockProviderGateway,
    private val scheduler: FrameworkMockRefreshScheduler,
    private val refreshIntervalMillis: Long = DEFAULT_REFRESH_INTERVAL_MILLIS,
    private val onRelevantChange: (RevisionBumpReason) -> Unit,
    private val ownership: MockProviderOwnership = MockProviderOwnership.UNRESTRICTED,
    private val projectionMatches: (MockLocationConfig) -> Boolean = { true },
    private val semanticRepair:
        (kind: String, operation: () -> Unit) -> FrameworkSemanticRepairResult =
        { kind, operation ->
            if (QwySemanticWriterRuntime.repairExternalProjection(kind, operation)) {
                FrameworkSemanticRepairResult.COMPLETED
            } else {
                FrameworkSemanticRepairResult.DEFERRED
            }
        },
    private val beforeFailedRefreshCleanup: () -> Unit = {},
) {
    private val lock = Any()
    private var generation = 0L
    private var active: ActiveSession? = null
    private var cleanupUncertain = false
    private var closed = false

    init {
        require(refreshIntervalMillis > 0) { "refreshIntervalMillis must be positive" }
    }

    val isActive: Boolean
        get() = synchronized(lock) { active != null }

    val isProvablyInactive: Boolean
        get() = synchronized(lock) { active == null && !cleanupUncertain }

    /** Replaces the framework providers, publishes immediately, then starts refreshes. */
    fun start(config: MockLocationConfig) {
        startOrReconfigure(config, requireInactive = true)
    }

    /**
     * Starts an inactive publisher or atomically hands an existing projection to
     * a new owner/config without creating a second scheduled loop.
     */
    fun startOrReconfigure(config: MockLocationConfig) {
        startOrReconfigure(config, requireInactive = false)
    }

    private fun startOrReconfigure(
        config: MockLocationConfig,
        requireInactive: Boolean,
    ) {
        var failure: Throwable? = null
        var failureReason: RevisionBumpReason? = null

        synchronized(lock) {
            check(!closed) { "framework mock refresh session is closed" }
            val existing = active
            if (existing != null) {
                check(!requireInactive) { "framework mock refresh session is already active" }
                try {
                    check(ownership.runAsIntegration(existing.ownershipClaim) {
                        gateway.publish(config)
                    }) { "framework mock ownership was superseded" }
                    existing.config = config
                    existing.configEpoch += 1L
                    cleanupUncertain = false
                } catch (caught: Throwable) {
                    cleanupFailedSessionLocked(existing.token, caught)
                    failure = caught
                    failureReason = RevisionBumpReason.MODE_OR_PROVIDER_CHANGED
                }
                return@synchronized
            }

            val token = ++generation
            val ownershipClaim = ownership.claimIntegration()
            active = ActiveSession(
                token = token,
                config = config,
                ownershipClaim = ownershipClaim,
            )

            try {
                check(ownership.runAsIntegration(ownershipClaim) {
                    gateway.replaceGpsProvider()
                    gateway.publish(config)
                }) { "framework mock ownership was superseded" }
                cleanupUncertain = false
            } catch (caught: Throwable) {
                cleanupFailedSessionLocked(token, caught)
                failure = caught
                failureReason = RevisionBumpReason.MODE_OR_PROVIDER_CHANGED
            }

            if (failure == null) {
                try {
                    val cancellation = scheduler.scheduleWithFixedDelay(
                        initialDelayMillis = refreshIntervalMillis,
                        intervalMillis = refreshIntervalMillis,
                    ) {
                        refreshScheduled(token)
                    }
                    val current = active
                    if (current?.token == token) {
                        current.cancellation = cancellation
                    } else {
                        cancellation.cancel()
                    }
                } catch (caught: Throwable) {
                    cleanupFailedSessionLocked(token, caught)
                    failure = caught
                    failureReason = RevisionBumpReason.OBSERVER_GAP
                }
            }
        }

        failure?.let { caught ->
            notifyRelevantChange(failureReason!!, caught)
            throw caught
        }
    }

    /** Publishes before returning; false means there is no active lease session. */
    fun refreshNow(): Boolean {
        val attempt = publishActive(expectedToken = null)
        attempt.failure?.let { caught ->
            beforeFailedRefreshCleanup()
            cleanupFailedRefresh(attempt, caught)
            throw caught
        }
        return attempt.attempted
    }

    /** Cancels refresh work before removing both framework test providers. */
    fun stop() {
        val failure = synchronized(lock) {
            if (closed) return
            stopLocked()
        }
        failure?.let { throw it }
    }

    /**
     * Permanently retires this session after a failed owner startup. Unlike
     * [stop], this also shuts down the process-owned executor and cannot be
     * restarted. Cleanup is idempotent so nested startup failures cannot remove
     * or unregister twice.
     */
    fun shutdown() {
        var failure: Throwable? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            failure = stopLocked()
        }
        try {
            scheduler.shutdown()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        failure?.let { throw it }
    }

    private fun stopLocked(): Throwable? {
        var failure: Throwable? = null
        val current = active
        if (current == null && !cleanupUncertain) return null
        active = null
        generation += 1

        try {
            current?.cancellation?.cancel()
        } catch (caught: Throwable) {
            failure = caught
        }

        val ownershipClaim = current?.ownershipClaim ?: ownership.claimIntegration()
        try {
            check(ownership.releaseIntegration(ownershipClaim, gateway::removeGpsProvider)) {
                "framework mock ownership was superseded during cleanup"
            }
            cleanupUncertain = false
        } catch (caught: Throwable) {
            cleanupUncertain = true
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        return failure
    }

    private fun refreshScheduled(token: Long) {
        val attempt = publishActive(expectedToken = token)
        attempt.failure?.let { caught ->
            beforeFailedRefreshCleanup()
            cleanupFailedRefresh(attempt, caught)
        }
    }

    private fun publishActive(expectedToken: Long?): PublishAttempt =
        QwySemanticWriterRuntime.serializeSelection {
            synchronized(lock) {
                val current = active ?: return@synchronized PublishAttempt.NOT_ACTIVE
                if (expectedToken != null && current.token != expectedToken) {
                    return@synchronized PublishAttempt.NOT_ACTIVE
                }

                try {
                    val publish = {
                        check(ownership.runAsIntegration(current.ownershipClaim) {
                            gateway.publish(current.config)
                        }) { "framework mock ownership was superseded" }
                    }
                    val publishInsideAuthoritativeBracket = {
                        var cleanupPerformed = false
                        val cleanupOnce: (Throwable) -> Unit = { failure ->
                            if (!cleanupPerformed) {
                                cleanupPerformed = true
                                cleanupFailedSessionAndNotifyLocked(
                                    token = current.token,
                                    configEpoch = current.configEpoch,
                                    primaryFailure = failure,
                                )
                            }
                        }
                        QwySemanticWriterRuntime.registerUncertainCompensation {
                            cleanupOnce(
                                IllegalStateException(
                                    "authoritative framework refresh outcome became uncertain",
                                ),
                            )
                        }
                        try {
                            publish()
                        } catch (caught: Throwable) {
                            cleanupOnce(caught)
                            throw caught
                        }
                    }
                    if (projectionMatches(current.config)) {
                        // Exact A→A sample publication changes only timestamp/cadence data, which
                        // is outside the semantic digest. A partial call still writes only A, so
                        // keep the heartbeat out of the oracle. Its failure path brackets the
                        // subsequent semantic provider removal in cleanupFailedRefresh().
                        publish()
                    } else {
                        // B/unavailable→A repairs effective state and must own an exact journal
                        // interval. The outer selection lock keeps the decision and publish atomic.
                        if (
                            semanticRepair("framework-refresh-coordinate-repair") {
                                publishInsideAuthoritativeBracket()
                            } == FrameworkSemanticRepairResult.DEFERRED
                        ) {
                            return@synchronized PublishAttempt.deferred(
                                current.token,
                                current.configEpoch,
                            )
                        }
                    }
                    PublishAttempt.succeeded(current.token, current.configEpoch)
                } catch (caught: Throwable) {
                    PublishAttempt(
                        attempted = true,
                        token = current.token,
                        configEpoch = current.configEpoch,
                        failure = caught,
                    )
                }
            }
        }

    private fun cleanupFailedRefresh(
        attempt: PublishAttempt,
        primaryFailure: Throwable,
    ) {
        val token = attempt.token ?: return
        val configEpoch = attempt.configEpoch ?: return
        var cleanupEntered = false
        try {
            QwySemanticWriterRuntime.repairExternalProjection(
                "framework-refresh-failure-cleanup",
            ) {
                cleanupEntered = true
                synchronized(lock) {
                    cleanupFailedSessionAndNotifyLocked(
                        token = token,
                        configEpoch = configEpoch,
                        primaryFailure = primaryFailure,
                    )
                }
            }
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
        }

        // A selected authoritative lane can disappear before cleanup begins. In that case a raw
        // provider removal would mutate outside the oracle, so retain the active exact projection
        // for a later retry. With no installed lane repairExternalProjection executes the legacy
        // cleanup inline and sets cleanupEntered.
        if (!cleanupEntered) return
    }

    /** Caller owns [lock]; callback and cleanup remain one causal operation. */
    private fun cleanupFailedSessionAndNotifyLocked(
        token: Long,
        configEpoch: Long,
        primaryFailure: Throwable,
    ): Boolean {
        val cleaned = cleanupFailedSessionLocked(
            token = token,
            primaryFailure = primaryFailure,
            expectedConfigEpoch = configEpoch,
        )
        if (cleaned) {
            notifyRelevantChange(
                RevisionBumpReason.MODE_OR_PROVIDER_CHANGED,
                primaryFailure,
            )
        }
        return cleaned
    }

    private fun cleanupFailedSessionLocked(
        token: Long,
        primaryFailure: Throwable,
        expectedConfigEpoch: Long? = null,
    ): Boolean {
        val current = active ?: return false
        if (current.token != token ||
            (expectedConfigEpoch != null && current.configEpoch != expectedConfigEpoch)
        ) {
            return false
        }
        active = null
        generation += 1

        try {
            current.cancellation?.cancel()
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
        try {
            check(ownership.releaseIntegration(
                current.ownershipClaim,
                gateway::removeGpsProvider,
            )) { "framework mock ownership was superseded during cleanup" }
            cleanupUncertain = false
        } catch (cleanupFailure: Throwable) {
            cleanupUncertain = true
            primaryFailure.addSuppressed(cleanupFailure)
        }
        return true
    }

    private fun notifyRelevantChange(reason: RevisionBumpReason, primaryFailure: Throwable) {
        try {
            onRelevantChange(reason)
        } catch (callbackFailure: Throwable) {
            primaryFailure.addSuppressed(callbackFailure)
        }
    }

    private class ActiveSession(
        val token: Long,
        var config: MockLocationConfig,
        val ownershipClaim: Long,
        var configEpoch: Long = 0L,
        var cancellation: FrameworkMockRefreshCancellation? = null,
    )

    private data class PublishAttempt(
        val attempted: Boolean,
        val token: Long?,
        val configEpoch: Long?,
        val failure: Throwable?,
    ) {
        companion object {
            val NOT_ACTIVE = PublishAttempt(
                attempted = false,
                token = null,
                configEpoch = null,
                failure = null,
            )
            fun succeeded(token: Long, configEpoch: Long) = PublishAttempt(
                attempted = true,
                token = token,
                configEpoch = configEpoch,
                failure = null,
            )
            fun deferred(token: Long, configEpoch: Long) = PublishAttempt(
                attempted = false,
                token = token,
                configEpoch = configEpoch,
                failure = null,
            )
        }
    }

    private companion object {
        const val DEFAULT_REFRESH_INTERVAL_MILLIS = 1_000L
    }
}
