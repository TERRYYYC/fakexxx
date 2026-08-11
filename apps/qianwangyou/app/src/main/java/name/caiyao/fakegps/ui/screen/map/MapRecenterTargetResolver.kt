package name.caiyao.fakegps.ui.screen.map

import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.verify.HookApplicability

enum class MapRecenterCoordinateSource {
    HOOK,
    SYSTEM_MOCK,
}

sealed interface MapRecenterTarget {
    data class EffectiveCoordinate(
        val latitude: Double,
        val longitude: Double,
        val source: MapRecenterCoordinateSource,
    ) : MapRecenterTarget

    /** No product layer currently owns location, so request a current system fix. */
    data object CurrentDevice : MapRecenterTarget

    /** The runtime may still own a location, but the UI cannot determine it safely. */
    data class Unavailable(val message: String) : MapRecenterTarget
}

/** Projects existing location owners into the map button's one "current effective" meaning. */
object MapRecenterTargetResolver {

    fun resolve(
        read: PayloadRead,
        providerState: MockProviderState,
        currentHour: Int,
    ): MapRecenterTarget {
        when (providerState) {
            is MockProviderState.Running -> return MapRecenterTarget.EffectiveCoordinate(
                latitude = providerState.config.latitude,
                longitude = providerState.config.longitude,
                source = MapRecenterCoordinateSource.SYSTEM_MOCK,
            )
            is MockProviderState.Starting -> {
                return MapRecenterTarget.Unavailable("System Mock 正在启动，请稍后再归位")
            }
            MockProviderState.Stopping -> {
                return MapRecenterTarget.Unavailable("System Mock 正在停止，请稍后再归位")
            }
            is MockProviderState.Failed -> if (providerState.providerCleanupRequired) {
                return MapRecenterTarget.Unavailable(
                    "System Mock 状态未恢复，无法确认当前有效位置",
                )
            }
            MockProviderState.Idle -> Unit
        }

        val parsed = PublishedConfig.parse(read.textOrNull)
        val applicability = HookApplicability.forPayload(read, parsed, currentHour)

        if (parsed?.locationDeliveryMode == SYSTEM_MOCK) {
            return MapRecenterTarget.Unavailable("System Mock 尚未进入运行状态")
        }

        return when (applicability) {
            HookApplicability.APPLYING -> coordinateFromApplyingHook(parsed!!)
            HookApplicability.MODE_OFF,
            HookApplicability.OUTSIDE_ACTIVE_HOURS,
            HookApplicability.NEVER_PUBLISHED,
            -> MapRecenterTarget.CurrentDevice
            HookApplicability.SCHEMA_REJECTED -> unavailable("生效配置版本不受支持")
            HookApplicability.PAYLOAD_INCOMPLETE -> unavailable("生效配置不完整")
            HookApplicability.PAYLOAD_MALFORMED -> unavailable("生效配置无法解析")
            HookApplicability.PAYLOAD_UNREADABLE -> unavailable("无法读取生效配置")
            HookApplicability.PUBLICATION_FAILED -> unavailable("最近一次配置发布失败")
        }
    }

    private fun coordinateFromApplyingHook(published: PublishedConfig): MapRecenterTarget {
        val latitudeRaw = published.fields["latitude"]
        val longitudeRaw = published.fields["longitude"]
        if (latitudeRaw == null && longitudeRaw == null) {
            return MapRecenterTarget.CurrentDevice
        }
        if (latitudeRaw == null || longitudeRaw == null) {
            return unavailable("生效档案的位置字段不完整")
        }

        val latitude = latitudeRaw.toDoubleOrNull()
        val longitude = longitudeRaw.toDoubleOrNull()
        if (
            latitude == null || !latitude.isFinite() || latitude !in -90.0..90.0 ||
            longitude == null || !longitude.isFinite() || longitude !in -180.0..180.0
        ) {
            return unavailable("生效档案的位置字段无效")
        }
        return MapRecenterTarget.EffectiveCoordinate(
            latitude = latitude,
            longitude = longitude,
            source = MapRecenterCoordinateSource.HOOK,
        )
    }

    private fun unavailable(message: String) = MapRecenterTarget.Unavailable(message)

    private const val SYSTEM_MOCK = "system_mock"
}
