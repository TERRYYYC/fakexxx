package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.TransportSchemaContract

sealed interface EffectiveMockLocationResolution {
    data class Ready(val config: MockLocationConfig) : EffectiveMockLocationResolution
    data class Invalid(val message: String) : EffectiveMockLocationResolution
}

object EffectiveMockLocationResolver {

    fun resolve(published: PublishedConfig?): EffectiveMockLocationResolution {
        if (published == null) {
            return EffectiveMockLocationResolution.Invalid("尚未发布生效档案")
        }
        if (!TransportSchemaContract.supports(published.schemaVersion)) {
            return EffectiveMockLocationResolution.Invalid(
                "不支持的档案配置版本 ${published.schemaVersion}",
            )
        }
        if (!published.fieldsPresent) {
            return EffectiveMockLocationResolution.Invalid("生效档案配置不完整")
        }

        val latitude = published.fields["latitude"]?.toDoubleOrNull()
            ?: return EffectiveMockLocationResolution.Invalid("生效档案缺少有效纬度")
        val longitude = published.fields["longitude"]?.toDoubleOrNull()
            ?: return EffectiveMockLocationResolution.Invalid("生效档案缺少有效经度")
        val accuracy = published.fields["accuracy"]?.let { raw ->
            raw.toFloatOrNull()
                ?: return EffectiveMockLocationResolution.Invalid("生效档案的精度不是数字")
        } ?: MockLocationConfig.DEFAULT_ACCURACY_METERS
        val altitude = published.fields["altitude"]?.let { raw ->
            raw.toDoubleOrNull()?.takeIf(Double::isFinite)
                ?: return EffectiveMockLocationResolution.Invalid("生效档案的海拔不是有效数字")
        }

        return runCatching {
            MockLocationConfig(latitude, longitude, accuracy, altitude)
        }.fold(
            onSuccess = EffectiveMockLocationResolution::Ready,
            onFailure = {
                EffectiveMockLocationResolution.Invalid(
                    it.message ?: "生效档案的位置字段无效",
                )
            },
        )
    }
}
