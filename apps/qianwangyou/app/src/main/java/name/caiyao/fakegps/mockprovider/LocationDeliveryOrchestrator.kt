package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.integration.v1.QwySemanticWriterRuntime

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
    private val ownership: MockProviderOwnership = ProcessMockProviderOwnership,
    private val semanticMutation: (
        kind: String,
        operation: (authoritativeLaneSelected: Boolean) -> MockProviderState,
    ) -> MockProviderState = { kind, operation ->
        QwySemanticWriterRuntime.mutate(kind, operation)
    },
    private val projectionMatchesExactly: (MockLocationConfig) -> Boolean = { true },
    private val semanticProjectionRepair: (kind: String, operation: () -> Unit) -> Boolean =
        QwySemanticWriterRuntime::repairExternalProjection,
) {
    fun enable(): MockProviderState = semanticMutation("mock-provider-enable") {
        authoritative -> enableInternal(authoritative)
    }

    private fun enableInternal(authoritative: Boolean): MockProviderState {
        val resolution = EffectiveMockLocationResolver.resolve(readPublished())
        val ready = resolution as? EffectiveMockLocationResolution.Ready
        if (ready == null) {
            val reason = (resolution as EffectiveMockLocationResolution.Invalid).message
            if (readMode() == LocationDeliveryMode.SYSTEM_MOCK || readCleanupRequired()) {
                val recovered = disableInternal(authoritative)
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

        controller.start(ready.config, providerMayAlreadyExist)
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
            return rollbackToHook("无法保存 System Mock 位置模式", authoritative)
        }
        if (!authoritative && !publishConfig()) {
            return rollbackToHook(
                "无法发布 System Mock 的 Hook 位置旁路配置",
                authoritative,
            )
        }
        if (!persistCleanupRequired(false)) {
            return rollbackToHook("System Mock 未进入可恢复的稳定状态", authoritative)
        }
        return controller.state
    }

    fun disable(): MockProviderState = semanticMutation("mock-provider-disable") {
        authoritative -> disableInternal(authoritative)
    }

    private fun disableInternal(authoritative: Boolean): MockProviderState {
        val marked = persistCleanupRequired(true)
        val persisted = persistMode(LocationDeliveryMode.HOOK)
        val published = persisted && (authoritative || publishConfig())
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

    fun refresh(): MockProviderState = QwySemanticWriterRuntime.serializeSelection {
        if (readMode() != LocationDeliveryMode.SYSTEM_MOCK) {
            return@serializeSelection semanticMutation(
                "mock-provider-refresh-disable",
            ) { authoritative -> disableInternal(authoritative) }
        }

        val resolution = EffectiveMockLocationResolver.resolve(readPublished())
        val ready = resolution as? EffectiveMockLocationResolution.Ready
        if (ready == null) {
            val reason = (resolution as EffectiveMockLocationResolution.Invalid).message
            val stopped = semanticMutation(
                "mock-provider-refresh-invalid",
            ) { authoritative -> disableInternal(authoritative) }
            return@serializeSelection if (stopped is MockProviderState.Failed) {
                stopped
            } else {
                MockProviderState.Failed(reason)
            }
        }

        val running = controller.state as? MockProviderState.Running
        val serviceStillOwnsProjection =
            ownership.projectionOwnershipSnapshot() ==
                MockProviderProjectionOwnership.ServiceActive(ready.config)
        if (running?.config == ready.config && serviceStillOwnsProjection) {
            if (projectionMatchesExactly(ready.config)) {
                // Re-publishing the exact already-active A coordinates can only replace A with A;
                // timestamps/cadence are outside the semantic digest. Keep this ordinary heartbeat
                // out of the oracle entirely. If the call fails, only the subsequent provider
                // removal is semantic, so that cleanup receives its own PRE bracket.
                controller.publishTickSample()?.let { failure ->
                    semanticProjectionRepair("mock-provider-refresh-failure-cleanup") {
                        controller.finishFailedTick(failure)
                    }
                }
            } else {
                val repaired = semanticProjectionRepair(
                    "mock-provider-refresh-coordinate-repair",
                ) {
                    publishTickInsideAuthoritativeBracket()
                }
                if (!repaired) return@serializeSelection controller.state
            }
        } else {
            semanticMutation("mock-provider-refresh-reconfigure") { _ ->
                controller.start(ready.config, providerMayAlreadyExist = true)
                controller.state
            }
        }
        controller.state
    }

    private fun publishTickInsideAuthoritativeBracket() {
        var cleanupPerformed = false
        fun cleanupOnce(failure: Throwable) {
            if (cleanupPerformed) return
            cleanupPerformed = true
            controller.finishFailedTick(failure)
        }

        QwySemanticWriterRuntime.registerUncertainCompensation {
            cleanupOnce(
                IllegalStateException(
                    "authoritative mock-provider tick outcome became uncertain",
                ),
            )
        }
        controller.publishTickSample()?.let { failure ->
            cleanupOnce(failure)
            throw failure
        }
    }

    /** Best-effort provider cleanup without changing persisted user intent. */
    fun cleanupRuntimeOnly(): MockProviderState = QwySemanticWriterRuntime.serializeSelection {
        if (readMode() != LocationDeliveryMode.SYSTEM_MOCK && !readCleanupRequired()) {
            return@serializeSelection controller.state
        }
        semanticMutation("mock-provider-runtime-cleanup") { _ ->
            controller.stop()
            controller.state
        }
    }

    private fun rollbackToHook(reason: String, authoritative: Boolean): MockProviderState {
        val persisted = persistMode(LocationDeliveryMode.HOOK)
        val published = persisted && (authoritative || publishConfig())
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
