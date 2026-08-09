package name.caiyao.fakegps.ui.screen.settings

import java.util.Locale
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockProviderRecovery
import name.caiyao.fakegps.mockprovider.MockProviderState

data class LocationDeliveryUiModel(
    val systemMockEnabled: Boolean,
    val switchEnabled: Boolean,
    val retryStopVisible: Boolean,
    val mockAppSelectionRequired: Boolean,
    val status: String,
    val detail: String,
    val effectiveCoordinate: String,
)

object LocationDeliveryUiContract {
    fun model(
        mode: LocationDeliveryMode,
        providerState: MockProviderState,
        published: PublishedConfig?,
    ): LocationDeliveryUiModel {
        val status = when (providerState) {
            MockProviderState.Idle -> when (mode) {
                LocationDeliveryMode.HOOK -> "Hook 位置注入"
                LocationDeliveryMode.SYSTEM_MOCK -> "等待 System Mock 服务恢复"
            }
            is MockProviderState.Starting -> "System Mock 启动中"
            is MockProviderState.Running ->
                "System Mock 运行中 · ${providerState.emittedCount} 次"
            MockProviderState.Stopping -> "正在停止 System Mock"
            is MockProviderState.Failed -> "失败 · ${providerState.message}"
        }
        val latitude = published?.fields?.get("latitude")?.toDoubleOrNull()
        val longitude = published?.fields?.get("longitude")?.toDoubleOrNull()
        val coordinate = if (latitude != null && longitude != null) {
            String.format(Locale.US, "%.6f, %.6f", latitude, longitude)
        } else {
            "生效中档案未配置有效经纬度"
        }

        val recovery = (providerState as? MockProviderState.Failed)?.recovery
        val retryStartAfterSelection =
            recovery == MockProviderRecovery.SelectThisAppAndRetryStart
        val mockAppSelectionRequired = recovery != null

        return LocationDeliveryUiModel(
            systemMockEnabled = mode == LocationDeliveryMode.SYSTEM_MOCK,
            switchEnabled = providerState is MockProviderState.Idle ||
                providerState is MockProviderState.Running || retryStartAfterSelection,
            retryStopVisible = providerState is MockProviderState.Failed &&
                !retryStartAfterSelection,
            mockAppSelectionRequired = mockAppSelectionRequired,
            status = status,
            detail = when (recovery) {
                MockProviderRecovery.SelectThisAppAndRetryStart ->
                    "当前千网游尚未取得模拟位置权限，System Mock 未启动。" +
                        "请在开发者选项中选择当前千网游，再返回这里重新打开开关。"
                MockProviderRecovery.ReselectThisAppAndRetryStop ->
                    "当前千网游已失去模拟位置权限，Android 不允许它移除残留位置。" +
                    "请打开开发者选项，重新选择当前千网游，再返回这里点“重试停止”。"
                null ->
                    "此开关只选择位置交付方式；蜂窝/Wi-Fi 等档案字段仍由 Hook 提供。" +
                        "切回 Hook 后，已运行目标进程会在当前刷新周期内读取新模式。"
            },
            effectiveCoordinate = coordinate,
        )
    }
}
