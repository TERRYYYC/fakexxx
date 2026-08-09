package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderRecovery
import name.caiyao.fakegps.mockprovider.MockProviderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationDeliveryUiContractTest {

    @Test
    fun `hook mode explains that only location delivery changes`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Idle,
            published(),
        )

        assertFalse(model.systemMockEnabled)
        assertTrue(model.status.contains("Hook"))
        assertTrue(model.detail.contains("蜂窝/Wi-Fi"))
        assertTrue(model.detail.contains("刷新周期"))
        assertEquals("50.450100, 30.523400", model.effectiveCoordinate)
    }

    @Test
    fun `running system mock shows exact effective profile coordinate`() {
        val config = MockLocationConfig(50.4501, 30.5234)
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.SYSTEM_MOCK,
            MockProviderState.Running(config, emittedCount = 9),
            published(),
        )

        assertTrue(model.systemMockEnabled)
        assertTrue(model.switchEnabled)
        assertFalse(model.retryStopVisible)
        assertTrue(model.status.contains("运行中"))
        assertTrue(model.status.contains("9"))
        assertEquals("50.450100, 30.523400", model.effectiveCoordinate)
    }

    @Test
    fun `failure reason stays visible and invalid profile is not presented as zero zero`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Failed("mock location app-op denied"),
            published(fields = emptyMap()),
        )

        assertTrue(model.status.contains("mock location app-op denied"))
        assertFalse(model.switchEnabled)
        assertTrue(model.retryStopVisible)
        assertEquals("生效中档案未配置有效经纬度", model.effectiveCoordinate)
    }

    @Test
    fun `mock app permission failure explains how to recover before retrying stop`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Failed(
                "not allowed to perform MOCK_LOCATION",
                MockProviderRecovery.ReselectThisAppAndRetryStop,
            ),
            published(),
        )

        assertTrue(model.mockAppSelectionRequired)
        assertTrue(model.detail.contains("重新选择当前千网游"))
        assertTrue(model.detail.contains("重试停止"))
        assertTrue(model.retryStopVisible)
        assertFalse(model.switchEnabled)
    }

    @Test
    fun `first start permission failure offers selection then switch retry without residue claim`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Failed(
                "not allowed to perform MOCK_LOCATION",
                MockProviderRecovery.SelectThisAppAndRetryStart,
            ),
            published(),
        )

        assertTrue(model.mockAppSelectionRequired)
        assertTrue(model.switchEnabled)
        assertFalse(model.retryStopVisible)
        assertTrue(model.detail.contains("重新打开开关"))
        assertFalse(model.detail.contains("残留位置"))
        assertFalse(model.detail.contains("重试停止"))
    }

    @Test
    fun `system mock idle state remains enabled and explains service recovery`() {
        val model = LocationDeliveryUiContract.model(
            LocationDeliveryMode.SYSTEM_MOCK,
            MockProviderState.Idle,
            published(),
        )

        assertTrue(model.systemMockEnabled)
        assertTrue(model.switchEnabled)
        assertTrue(model.status.contains("等待"))
    }

    @Test
    fun `transition states disable repeated switch interaction`() {
        val config = MockLocationConfig(50.4501, 30.5234)

        val starting = LocationDeliveryUiContract.model(
            LocationDeliveryMode.HOOK,
            MockProviderState.Starting(config),
            published(),
        )
        val stopping = LocationDeliveryUiContract.model(
            LocationDeliveryMode.SYSTEM_MOCK,
            MockProviderState.Stopping,
            published(),
        )

        assertFalse(starting.switchEnabled)
        assertFalse(stopping.switchEnabled)
        assertFalse(starting.retryStopVisible)
        assertFalse(stopping.retryStopVisible)
    }

    private fun published(
        fields: Map<String, String> = mapOf(
            "latitude" to "50.4501",
            "longitude" to "30.5234",
        ),
    ) = PublishedConfig(
        schemaVersion = 4,
        mode = "always_on",
        fields = fields,
        locationDeliveryMode = "hook",
    )
}
