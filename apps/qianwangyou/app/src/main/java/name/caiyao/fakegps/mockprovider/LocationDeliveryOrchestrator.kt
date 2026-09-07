package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode

/**
 * Orders provider state and the persisted Hook/System-Mock intent as one product transition.
 * Android bindings live in the service; this class stays pure so crash-window decisions are tested.
 */
class LocationDeliveryOrchestrator(
    private val controller: MockProviderSessionController,
    private val readPublished: () -> PublishedConfig?,
    private val readMode: () -> LocationDeliveryMode,
    private val readCleanupRequired: () -> Boolean,
    private val persistMode: (LocationDeliveryMode) -> Boolean,
    private val publishConfig: () -> Boolean,
    private val persistCleanupRequired: (Boolean) -> Boolean,
) {
    fun enable(): MockProviderState {
        val published = readPublished()
        val resolution = EffectiveMockLocationResolver.resolve(published)
        val ready = resolution as? EffectiveMockLocationResolution.Ready
        if (ready == null) {
            val reason = (resolution as EffectiveMockLocationResolution.Invalid).message
            if (readMode() == LocationDeliveryMode.SYSTEM_MOCK || readCleanupRequired()) {
                val recovered = disable()
                return if (recovered is MockProviderState.Failed) recovered
                else MockProviderState.Failed(reason)
            }
            return MockProviderState.Failed(reason)
        }

        val providerMayAlreadyExist =
            readMode() == LocationDeliveryMode.SYSTEM_MOCK || readCleanupRequired()

        // Durable before the first system mutation: if the process dies after addTestProvider but
        // before mode publication, the next app launch still knows cleanup is required.
        if (!persistCleanupRequired(true)) {
            return MockProviderState.Failed("无法保存 Mock Provider 恢复标记")
        }

        controller.start(ready.config, providerMayAlreadyExist, RoutePlaybackPlanner.player(published))
        if (controller.state !is MockProviderState.Running) {
            val failure = controller.state as? MockProviderState.Failed
            if (failure?.providerCleanupRequired == false && !persistCleanupRequired(false)) {
                return failure.copy(
                    message = "${failure.message}；System Mock 未启动，但无法清除恢复标记",
                )
            }
            return controller.state
        }

        if (!persistMode(LocationDeliveryMode.SYSTEM_MOCK)) {
            return rollbackToHook("无法保存 System Mock 位置模式")
        }
        if (!publishConfig()) {
            return rollbackToHook("无法发布 System Mock 的 Hook 位置旁路配置")
        }
        if (!persistCleanupRequired(false)) {
            return rollbackToHook("System Mock 未进入可恢复的稳定状态")
        }
        return controller.state
    }

    fun disable(): MockProviderState {
        val marked = persistCleanupRequired(true)
        val persisted = persistMode(LocationDeliveryMode.HOOK)
        val published = persisted && publishConfig()
        controller.stop()

        if (controller.state is MockProviderState.Failed) return controller.state
        if (!marked) return MockProviderState.Failed("GPS 已停止，但无法保存 Mock Provider 恢复标记")
        if (!persisted) return MockProviderState.Failed("GPS 已停止，但无法保存 Hook 位置模式")
        if (!published) return MockProviderState.Failed("GPS 已停止，但 Hook 配置发布失败")
        if (!persistCleanupRequired(false)) {
            return MockProviderState.Failed("GPS 已停止，但无法清除 Mock Provider 恢复标记")
        }
        return MockProviderState.Idle
    }

    fun refresh(): MockProviderState {
        if (readMode() != LocationDeliveryMode.SYSTEM_MOCK) return disable()

        // Read ONCE: the fixture-free contract is one publish per refresh; reading twice would
        // consume queued payloads twice and could tick against a different config than resolved.
        val published = readPublished()
        val resolution = EffectiveMockLocationResolver.resolve(published)
        val ready = resolution as? EffectiveMockLocationResolution.Ready
        if (ready == null) {
            val reason = (resolution as EffectiveMockLocationResolution.Invalid).message
            val stopped = disable()
            return if (stopped is MockProviderState.Failed) stopped else MockProviderState.Failed(reason)
        }

        val routePlayer = RoutePlaybackPlanner.player(published)
        val routeKey = routePlayer?.spec?.sessionKey()
        val running = controller.state as? MockProviderState.Running
        if (running?.config == ready.config && running.routeKey == routeKey) {
            // Same static base AND same route identity: keep playing. Comparing the MOVING
            // current point instead would treat every 1 Hz tick as a config change and rebuild
            // the test provider once per second.
            controller.tick()
        } else {
            controller.start(ready.config, providerMayAlreadyExist = true, routePlayer)
        }
        maybeRebuildDroppedProvider(ready.config)
        return controller.state
    }

    /**
     * Rebuild the system test provider once when a routine tick discovers the framework
     * dropped it behind our back.
     *
     * OEM background-location management clears test providers of apps that have no visible
     * activity (observed on HyperOS 3 / Android 16: a backgrounded session loses "gps provider
     * is not a test provider" within a minute of the app leaving the foreground). The failure
     * used to be terminal: the service stopped itself, the process lost its location-type
     * foreground service, and the OEM freezer suspended the whole process within ~1.4 s —
     * after which every provider binder call (discover/apply/completeAndAdvance) black-holes
     * until the process is killed. One immediate rebuild keeps the session Running and the
     * foreground service alive; only failures that indicate a real permission loss
     * ([MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED]) skip the rebuild, because
     * re-adding the provider cannot succeed without the mock-location app-op.
     */
    private fun maybeRebuildDroppedProvider(config: MockLocationConfig) {
        val failure = controller.state as? MockProviderState.Failed ?: return
        if (failure.reason == MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED) return
        controller.start(config, providerMayAlreadyExist = true)
    }

    /** Best-effort provider cleanup without changing persisted user intent. */
    fun cleanupRuntimeOnly(): MockProviderState {
        if (readMode() != LocationDeliveryMode.SYSTEM_MOCK && !readCleanupRequired()) {
            return controller.state
        }
        controller.stop()
        return controller.state
    }

    private fun rollbackToHook(reason: String): MockProviderState {
        val persisted = persistMode(LocationDeliveryMode.HOOK)
        val published = persisted && publishConfig()
        controller.stop()
        val stopped = controller.state is MockProviderState.Idle
        val cleanupFailure = controller.state as? MockProviderState.Failed
        val markerCleared = persisted && published && stopped && persistCleanupRequired(false)

        val detail = when {
            !persisted -> "$reason；回滚时无法保存 Hook 位置模式"
            !published -> "$reason；回滚时 Hook 配置发布失败"
            !stopped -> "$reason；回滚时 GPS provider 清理失败"
            !markerCleared -> "$reason；回滚完成但无法清除恢复标记"
            else -> "System Mock 已回滚：$reason"
        }
        return MockProviderState.Failed(detail, cleanupFailure?.recovery)
    }

}
