package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.mockprovider.EffectiveMockLocationResolution
import name.caiyao.fakegps.mockprovider.EffectiveMockLocationResolver
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState

sealed interface SystemMockEnableOutcome {
    data object PublicationFailed : SystemMockEnableOutcome
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
    ): SystemMockEnableOutcome {
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
