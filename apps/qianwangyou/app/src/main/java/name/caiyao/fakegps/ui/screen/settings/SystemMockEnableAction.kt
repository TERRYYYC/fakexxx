package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.mockprovider.EffectiveMockLocationResolution
import name.caiyao.fakegps.mockprovider.EffectiveMockLocationResolver
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderFailureReason
import name.caiyao.fakegps.mockprovider.MockProviderRecovery
import name.caiyao.fakegps.mockprovider.MockProviderState

sealed interface SystemMockEnableOutcome {
    data object PublicationFailed : SystemMockEnableOutcome
    /** The mock_location app-op is currently denied — fail-fast before any mutation (issue #8). */
    data object AppOpDenied : SystemMockEnableOutcome
    data class Invalid(
        val published: PublishedConfig?,
        val message: String,
    ) : SystemMockEnableOutcome
    data class Started(
        val published: PublishedConfig?,
        val config: MockLocationConfig,
    ) : SystemMockEnableOutcome
}

/** Injectable ordering seam for the settings-to-service enable transition. */
object SystemMockEnableAction {
    fun run(
        syncPublishedConfig: () -> Boolean,
        readPublishedConfig: () -> PublishedConfig?,
        publishProviderState: (MockProviderState) -> Unit,
        startService: () -> Unit,
        mockLocationAppOpAllowed: () -> Boolean = { true },
    ): SystemMockEnableOutcome {
        // Issue #8 fail-fast: ask AppOpsManager FIRST. The OS resets the android:mock_location
        // app-op at will (observed overnight on a Moto / Android 15); probing is cheaper and far
        // louder than waiting for addTestProvider's SecurityException inside the service — the
        // typed state lands before any config mutation or foreground-service churn.
        // Default `{ true }` keeps the seam JVM-pure; production binds MockLocationAppOps.
        if (!mockLocationAppOpAllowed()) {
            publishProviderState(
                MockProviderState.Failed(
                    message = "模拟位置权限（mock_location AppOps）已被系统重置为拒绝，" +
                        "当前千网游不被允许执行 MOCK_LOCATION",
                    recovery = MockProviderRecovery.SelectThisAppAndRetryStart,
                    providerCleanupRequired = false,
                    reason = MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED,
                ),
            )
            return SystemMockEnableOutcome.AppOpDenied
        }

        if (!syncPublishedConfig()) return SystemMockEnableOutcome.PublicationFailed

        val published = readPublishedConfig()
        return when (val resolution = EffectiveMockLocationResolver.resolve(published)) {
            is EffectiveMockLocationResolution.Invalid -> {
                publishProviderState(MockProviderState.Failed(resolution.message))
                SystemMockEnableOutcome.Invalid(published, resolution.message)
            }
            is EffectiveMockLocationResolution.Ready -> {
                publishProviderState(MockProviderState.Starting(resolution.config))
                startService()
                SystemMockEnableOutcome.Started(published, resolution.config)
            }
        }
    }
}
