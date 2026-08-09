package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveMockLocationResolverTest {

    @Test
    fun `resolves Kyiv from the same published effective profile the hook consumes`() {
        val result = EffectiveMockLocationResolver.resolve(
            config(
                fields = mapOf(
                    "latitude" to "50.4501",
                    "longitude" to "30.5234",
                    "altitude" to "179.0",
                    "accuracy" to "4.5",
                    "tac" to "27101",
                ),
            ),
        )

        assertEquals(
            EffectiveMockLocationResolution.Ready(
                MockLocationConfig(
                    latitude = 50.4501,
                    longitude = 30.5234,
                    accuracyMeters = 4.5f,
                    altitudeMeters = 179.0,
                ),
            ),
            result,
        )
    }

    @Test
    fun `missing accuracy uses explicit product default`() {
        val result = EffectiveMockLocationResolver.resolve(
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234")),
        )

        assertEquals(
            EffectiveMockLocationResolution.Ready(MockLocationConfig(50.4501, 30.5234, 3f)),
            result,
        )
    }

    @Test
    fun `missing altitude remains absent instead of inventing a city constant`() {
        val result = EffectiveMockLocationResolver.resolve(
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234")),
        )

        assertEquals(
            EffectiveMockLocationResolution.Ready(
                MockLocationConfig(50.4501, 30.5234, altitudeMeters = null),
            ),
            result,
        )
    }

    @Test
    fun `nonnumeric or nonfinite altitude is rejected with its own reason`() {
        listOf("Kyiv", "NaN", "Infinity").forEach { altitude ->
            val result = EffectiveMockLocationResolver.resolve(
                config(
                    fields = mapOf(
                        "latitude" to "50.4501",
                        "longitude" to "30.5234",
                        "altitude" to altitude,
                    ),
                ),
            )

            assertEquals(
                EffectiveMockLocationResolution.Invalid("生效档案的海拔不是有效数字"),
                result,
            )
        }
    }

    @Test
    fun `missing incomplete nonnumeric and out of range coordinates are rejected`() {
        val cases = listOf(
            null to "尚未发布生效档案",
            config(fields = emptyMap()) to "生效档案缺少有效纬度",
            config(fields = mapOf("latitude" to "50.4501")) to "生效档案缺少有效经度",
            config(fields = mapOf("latitude" to "Kyiv", "longitude" to "30.5234")) to
                "生效档案缺少有效纬度",
            config(fields = mapOf("latitude" to "91", "longitude" to "30.5234")) to
                "latitude must be finite and within [-90, 90]",
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "181")) to
                "longitude must be finite and within [-180, 180]",
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234", "accuracy" to "0")) to
                "accuracyMeters must be finite and positive",
            config(fields = mapOf("latitude" to "50.4501", "longitude" to "30.5234", "accuracy" to "unknown")) to
                "生效档案的精度不是数字",
        )

        cases.forEach { (published, message) ->
            assertEquals(
                EffectiveMockLocationResolution.Invalid(message),
                EffectiveMockLocationResolver.resolve(published),
            )
        }
    }

    @Test
    fun `structurally incomplete or unsupported payload is rejected`() {
        val noFields = config(fields = emptyMap()).copy(fieldsPresent = false)
        val future = config(fields = mapOf("latitude" to "50", "longitude" to "30"))
            .copy(schemaVersion = 99)

        assertTrue(EffectiveMockLocationResolver.resolve(noFields) is EffectiveMockLocationResolution.Invalid)
        assertTrue(EffectiveMockLocationResolver.resolve(future) is EffectiveMockLocationResolution.Invalid)
    }

    private fun config(fields: Map<String, String>) = PublishedConfig(
        schemaVersion = 4,
        mode = "always_on",
        fields = fields,
        locationDeliveryMode = "hook",
    )
}
