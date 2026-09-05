package name.caiyao.fakegps.ui.screen.settings

import java.util.Locale
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockProviderFailureReason
import name.caiyao.fakegps.mockprovider.MockProviderRecovery
import name.caiyao.fakegps.mockprovider.MockProviderState

data class LocationDeliveryUiModel(
    val systemMockEnabled: Boolean,
    val switchEnabled: Boolean,
    val retryStopVisible: Boolean,
    val mockAppSelectionRequired: Boolean,
    /** The OS reset the mock_location app-op back to deny - render the dev-options guidance (issue #8). */
    val mockLocationAppOpDenied: Boolean = false,
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

        val failure = providerState as? MockProviderState.Failed
        val recovery = failure?.recovery
        val retryStartAfterSelection =
            recovery == MockProviderRecovery.SelectThisAppAndRetryStart
        val mockAppSelectionRequired = recovery != null
        // Issue #8: the typed reason is authoritative — the OS reset the app-op, not merely a
        // cold permission miss. Say so and spell out the developer-options recovery path.
        val mockLocationAppOpDenied =
            failure?.reason == MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED

        return LocationDeliveryUiModel(
            systemMockEnabled = mode == LocationDeliveryMode.SYSTEM_MOCK,
            switchEnabled = providerState is MockProviderState.Idle ||
                providerState is MockProviderState.Running || retryStartAfterSelection,
            retryStopVisible = providerState is MockProviderState.Failed &&
                !retryStartAfterSelection,
            mockAppSelectionRequired = mockAppSelectionRequired,
            mockLocationAppOpDenied = mockLocationAppOpDenied,
            status = status,
            detail = when {
                mockLocationAppOpDenied ->
                    "检测到系统把「选择模拟位置应用」的授权（mock_location AppOps）重置回了拒绝，" +
                        "System Mock 已停止注入，位置回到真实 GPS。" +
                        "请前往 开发者选项 → 选择模拟位置应用，重新选择当前安装的千网游，" +
                        "再回到这里重新打开 System Mock 开关。"
                recovery == MockProviderRecovery.SelectThisAppAndRetryStart ->
                    "当前千网游尚未取得模拟位置权限，System Mock 未启动。" +
                        "请在开发者选项中选择当前千网游，再返回这里重新打开开关。"
                recovery == MockProviderRecovery.ReselectThisAppAndRetryStop ->
                    "当前千网游已失去模拟位置权限，Android 不允许它移除残留位置。" +
                        "请打开开发者选项，重新选择当前千网游，再返回这里点「重试停止」。"
                else ->
                    "此开关只选择位置交付方式；蜂窝/Wi-Fi 等档案字段仍由 Hook 提供。" +
                        "切回 Hook 后，已运行目标进程会在当前刷新周期内读取新模式。"
            },
            effectiveCoordinate = coordinate,
        )
    }
}
