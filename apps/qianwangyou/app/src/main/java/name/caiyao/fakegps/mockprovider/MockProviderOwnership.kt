package name.caiyao.fakegps.mockprovider

sealed interface MockProviderProjectionOwnership {
    /** Process startup has not yet reconciled durable intent with OS provider state. */
    data object RecoveryUnknown : MockProviderProjectionOwnership
    data object ProvablyInactive : MockProviderProjectionOwnership
    data class ServiceActive(val config: MockLocationConfig) : MockProviderProjectionOwnership
    data class IntegrationActive(val claim: Long) : MockProviderProjectionOwnership
    data object Uncertain : MockProviderProjectionOwnership
}

/**
 * Serializes long-lived ownership of the device-global test providers.
 *
 * The foreground service and Environment Control use different in-memory
 * controllers in the same process. A transition lock alone cannot stop an old
 * service tick or onDestroy cleanup from removing a provider that a later
 * integration lease owns, so integration claims carry a monotonically unique
 * epoch and service operations are refused while one is live.
 */
interface MockProviderOwnership {
    fun claimIntegration(): Long
    fun runAsIntegration(claim: Long, operation: () -> Unit): Boolean
    fun releaseIntegration(claim: Long, operation: () -> Unit): Boolean
    fun runAsService(operation: () -> Unit): Boolean
    fun markServiceProjectionUncertain() {}
    fun markServiceProjectionActive(config: MockLocationConfig) {}
    fun markServiceProjectionInactive() {}
    fun projectionOwnershipSnapshot(): MockProviderProjectionOwnership =
        MockProviderProjectionOwnership.Uncertain

    companion object {
        /** Isolated controller tests do not model the second production owner. */
        val UNRESTRICTED: MockProviderOwnership = object : MockProviderOwnership {
            private var nextClaim = 0L

            override fun claimIntegration(): Long = ++nextClaim
            override fun runAsIntegration(claim: Long, operation: () -> Unit): Boolean {
                operation()
                return true
            }

            override fun releaseIntegration(claim: Long, operation: () -> Unit): Boolean {
                operation()
                return true
            }

            override fun runAsService(operation: () -> Unit): Boolean {
                operation()
                return true
            }
        }
    }
}

internal class InProcessMockProviderOwnership : MockProviderOwnership {
    private var nextClaim = 0L
    private var integrationClaim: Long? = null
    private var projectionOwnership: MockProviderProjectionOwnership =
        MockProviderProjectionOwnership.RecoveryUnknown

    @Synchronized
    override fun claimIntegration(): Long {
        check(nextClaim < Long.MAX_VALUE) { "mock-provider ownership epoch overflow" }
        return (++nextClaim).also {
            integrationClaim = it
            projectionOwnership = MockProviderProjectionOwnership.IntegrationActive(it)
        }
    }

    @Synchronized
    override fun runAsIntegration(claim: Long, operation: () -> Unit): Boolean {
        if (integrationClaim != claim) return false
        operation()
        return true
    }

    @Synchronized
    override fun releaseIntegration(claim: Long, operation: () -> Unit): Boolean {
        if (integrationClaim != claim) return false
        projectionOwnership = MockProviderProjectionOwnership.Uncertain
        return try {
            operation()
            integrationClaim = null
            projectionOwnership = MockProviderProjectionOwnership.ProvablyInactive
            true
        } catch (failure: Throwable) {
            // Retain the claim so a stale service cannot touch possibly-live
            // providers before this exact owner retries removal.
            throw failure
        }
    }

    @Synchronized
    override fun runAsService(operation: () -> Unit): Boolean {
        if (integrationClaim != null) return false
        operation()
        return true
    }

    @Synchronized
    override fun markServiceProjectionUncertain() {
        if (integrationClaim == null) {
            projectionOwnership = MockProviderProjectionOwnership.Uncertain
        }
    }

    @Synchronized
    override fun markServiceProjectionActive(config: MockLocationConfig) {
        if (integrationClaim == null) {
            projectionOwnership = MockProviderProjectionOwnership.ServiceActive(config)
        }
    }

    @Synchronized
    override fun markServiceProjectionInactive() {
        if (integrationClaim == null) {
            projectionOwnership = MockProviderProjectionOwnership.ProvablyInactive
        }
    }

    @Synchronized
    override fun projectionOwnershipSnapshot(): MockProviderProjectionOwnership =
        projectionOwnership
}

object ProcessMockProviderOwnership : MockProviderOwnership by InProcessMockProviderOwnership()

/**
 * Establishes a cold-process inactive baseline by changing the real system,
 * never by inferring provider absence from nullable last-known-location caches.
 */
internal class MockProviderStartupProjectionReconciler(
    private val ownership: MockProviderOwnership,
    private val gateway: MockProviderGateway,
) {
    fun reconcile(): Boolean {
        if (ownership.projectionOwnershipSnapshot() !=
            MockProviderProjectionOwnership.RecoveryUnknown
        ) {
            return true
        }
        return try {
            val admitted = ownership.runAsService {
                ownership.markServiceProjectionUncertain()
                gateway.removeGpsProvider()
                ownership.markServiceProjectionInactive()
            }
            admitted && ownership.projectionOwnershipSnapshot() ==
                MockProviderProjectionOwnership.ProvablyInactive
        } catch (_: Throwable) {
            false
        }
    }
}
