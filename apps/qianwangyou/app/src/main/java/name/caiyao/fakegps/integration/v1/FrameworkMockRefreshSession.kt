package name.caiyao.fakegps.integration.v1

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderGateway

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
) {
    private val lock = Any()
    private var generation = 0L
    private var active: ActiveSession? = null
    private var closed = false

    init {
        require(refreshIntervalMillis > 0) { "refreshIntervalMillis must be positive" }
    }

    val isActive: Boolean
        get() = synchronized(lock) { active != null }

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
                    gateway.publish(config)
                    existing.config = config
                } catch (caught: Throwable) {
                    cleanupFailedSessionLocked(existing.token, caught)
                    failure = caught
                    failureReason = RevisionBumpReason.MODE_OR_PROVIDER_CHANGED
                }
                return@synchronized
            }

            val token = ++generation
            active = ActiveSession(token = token, config = config)

            try {
                gateway.replaceGpsProvider()
                gateway.publish(config)
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
            notifyRelevantChange(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED, caught)
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
        active = null
        generation += 1

        try {
            current?.cancellation?.cancel()
        } catch (caught: Throwable) {
            failure = caught
        }

        try {
            gateway.removeGpsProvider()
        } catch (caught: Throwable) {
            failure?.addSuppressed(caught) ?: run { failure = caught }
        }
        return failure
    }

    private fun refreshScheduled(token: Long) {
        val attempt = publishActive(expectedToken = token)
        attempt.failure?.let { caught ->
            notifyRelevantChange(RevisionBumpReason.MODE_OR_PROVIDER_CHANGED, caught)
        }
    }

    private fun publishActive(expectedToken: Long?): PublishAttempt = synchronized(lock) {
        val current = active ?: return@synchronized PublishAttempt.NOT_ACTIVE
        if (expectedToken != null && current.token != expectedToken) {
            return@synchronized PublishAttempt.NOT_ACTIVE
        }

        try {
            gateway.publish(current.config)
            PublishAttempt.SUCCEEDED
        } catch (caught: Throwable) {
            cleanupFailedSessionLocked(current.token, caught)
            PublishAttempt(attempted = true, failure = caught)
        }
    }

    private fun cleanupFailedSessionLocked(token: Long, primaryFailure: Throwable) {
        val current = active ?: return
        if (current.token != token) return
        active = null
        generation += 1

        try {
            current.cancellation?.cancel()
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
        try {
            gateway.removeGpsProvider()
        } catch (cleanupFailure: Throwable) {
            primaryFailure.addSuppressed(cleanupFailure)
        }
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
        var cancellation: FrameworkMockRefreshCancellation? = null,
    )

    private data class PublishAttempt(
        val attempted: Boolean,
        val failure: Throwable?,
    ) {
        companion object {
            val NOT_ACTIVE = PublishAttempt(attempted = false, failure = null)
            val SUCCEEDED = PublishAttempt(attempted = true, failure = null)
        }
    }

    private companion object {
        const val DEFAULT_REFRESH_INTERVAL_MILLIS = 1_000L
    }
}
