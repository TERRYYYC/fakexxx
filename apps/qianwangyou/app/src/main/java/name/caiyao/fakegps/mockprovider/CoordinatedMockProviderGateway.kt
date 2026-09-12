package name.caiyao.fakegps.mockprovider

/**
 * Presents framework test providers and Google FLP mock mode as one controller transaction.
 * The durable cleanup marker therefore has one owner even when either layer fails halfway.
 */
class CoordinatedMockProviderGateway(
    private val framework: MockProviderGateway,
    private val fused: FusedMockProviderGateway,
) : MockProviderGateway {
    override fun replaceGpsProvider() {
        framework.replaceGpsProvider()
        fused.enable()
    }

    override fun publish(config: MockLocationConfig) {
        // Make the user-visible fused source fresh first. A later framework failure is still
        // inside the controller mutation boundary and triggers cleanup of both layers.
        fused.publish(config)
        framework.publish(config)
    }

    override fun removeGpsProvider() {
        var firstFailure: Throwable? = null
        listOf<() -> Unit>(
            framework::removeGpsProvider,
            fused::disable,
        ).forEach { cleanup ->
            try {
                cleanup()
            } catch (failure: Throwable) {
                firstFailure?.addSuppressed(failure) ?: run { firstFailure = failure }
            }
        }
        firstFailure?.let { throw it }
    }

    /**
     * 读回委托给 framework 层：FLP mock 模式没有同步读回 API，而 framework 层的
     * LocationManager 事实源（gps/network test provider）是 CellRebel 实际消费的底层。
     */
    override fun readbackLastLocation(): MockReadback? = framework.readbackLastLocation()
}
