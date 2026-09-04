package name.caiyao.fakegps.ui.screen.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.mockprovider.MockLocationAppOps
import name.caiyao.fakegps.mockprovider.MockProviderRuntime
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SpoofSettings.getInstance(app)

    val spoofMode: StateFlow<String> = settings.spoofMode
    val activeHourStart: StateFlow<Int> = settings.activeHourStart
    val activeHourEnd: StateFlow<Int> = settings.activeHourEnd
    val locationDeliveryMode: StateFlow<LocationDeliveryMode> = settings.locationDeliveryMode
    val mockProviderState = MockProviderStatusStore.state

    private val _publishedConfig = MutableStateFlow(readPublishedConfig())
    val publishedConfig: StateFlow<PublishedConfig?> = _publishedConfig

    /** Hook refresh cadence, in seconds. Always a value [PublishPropagation] sanctions. */
    val refreshIntervalSec: StateFlow<Int> = settings.refreshIntervalSec

    /** The choices the picker may offer — from the policy, never from the screen. */
    val refreshIntervalChoicesSec: List<Int> = PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC

    /**
     * Every setting mutation must re-publish the transport payload (review FC-1).
     * Writing only to SpoofSettings leaves the hook side reading a stale snapshot until
     * the app restarts or a profile is edited — i.e. switching to `off` would NOT actually
     * stop spoofing in the target process.
     */
    fun setSpoofMode(mode: String) {
        settings.setSpoofMode(mode)
        publish()
    }

    fun setActiveHourStart(hour: Int) {
        settings.setActiveHourStart(hour)
        publish()
    }

    fun setActiveHourEnd(hour: Int) {
        settings.setActiveHourEnd(hour)
        publish()
    }

    /**
     * Non-null when the last settings change was persisted but NOT delivered to the hook.
     *
     * The preference is deliberately kept (the user's intent is not discarded), but the screen
     * must not present it as in effect: the hook is still running the previous payload, so a
     * silently-accepted change would read exactly like the "I changed it and nothing happened"
     * failure this feature exists to eliminate.
     */
    private val _publishFailure = MutableStateFlow<String?>(null)
    val publishFailure: StateFlow<String?> = _publishFailure

    fun dismissPublishFailure() {
        _publishFailure.value = null
    }

    fun reportSystemMockPermissionFailure(message: String) {
        _publishFailure.value = message
    }

    fun setSystemMockEnabled(enabled: Boolean) {
        if (mockProviderState.value is MockProviderState.Starting ||
            mockProviderState.value is MockProviderState.Stopping
        ) return

        if (enabled) {
            // The service resolves coordinates from these exact bytes. Never pass a parallel UI
            // coordinate through an Intent, and never start from a stale publication.
            when (
                val outcome = SystemMockEnableAction.run(
                    syncPublishedConfig = { ConfigPrefsSync.sync(getApplication()) },
                    readPublishedConfig = ::readPublishedConfig,
                    publishProviderState = MockProviderStatusStore::publish,
                    startService = { MockProviderRuntime.enableSystemMock(getApplication()) },
                    // Issue #8: ask AppOpsManager before any mutation (fail-open inside).
                    mockLocationAppOpAllowed = {
                        MockLocationAppOps.isMockLocationAllowed(getApplication())
                    },
                )
            ) {
                SystemMockEnableOutcome.PublicationFailed -> {
                    _publishFailure.value =
                        "无法发布生效中档案，System Mock 未启动；Hook 仍保持当前状态"
                }
                SystemMockEnableOutcome.AppOpDenied -> {
                    // The typed Failed state (with the dev-options guidance) is already published
                    // to MockProviderStatusStore — the System Mock card renders it; no banner.
                }
                is SystemMockEnableOutcome.Invalid -> {
                    _publishedConfig.value = outcome.published
                }
                is SystemMockEnableOutcome.Started -> {
                    _publishedConfig.value = outcome.published
                    _publishFailure.value = null
                }
            }
        } else {
            retryStopSystemMock()
        }
    }

    fun retryStopSystemMock() {
        if (mockProviderState.value is MockProviderState.Starting ||
            mockProviderState.value is MockProviderState.Stopping
        ) return
        MockProviderStatusStore.publish(MockProviderState.Stopping)
        MockProviderRuntime.useHookAndStopSystemMock(getApplication())
    }

    /**
     * Changing the cadence must re-publish like any other setting: the interval is part of the
     * payload the hook reads, so persisting it without publishing would leave the hook running the
     * OLD cadence — the setting would appear to apply while changing nothing.
     */
    fun setRefreshIntervalSec(seconds: Int) {
        // Goes through the same seam the JVM test pins, so the tested sequence IS the shipped one.
        val result = RefreshIntervalUpdate.apply(
            requestedSec = seconds,
            persist = settings::setRefreshIntervalSec,
            publish = { ConfigPrefsSync.sync(getApplication()) },
        )
        _publishFailure.value =
            if (result.published) null
            else "刷新间隔已保存为 ${result.storedSec} 秒，但未发布给 Hook —— " +
                "目标 App 仍在使用上一份配置"
    }

    /** Publishes and records the outcome; a `false` from [ConfigPrefsSync.sync] must never be dropped. */
    private fun publish() {
        val published = ConfigPrefsSync.sync(getApplication())
        _publishFailure.value =
            if (published) null
            else "设置已保存，但未发布给 Hook —— 目标 App 仍在使用上一份配置"
    }

    private fun readPublishedConfig(): PublishedConfig? = PublishedConfig.parse(
        ConfigPrefsSync.readPublished(getApplication()).textOrNull,
    )
}
