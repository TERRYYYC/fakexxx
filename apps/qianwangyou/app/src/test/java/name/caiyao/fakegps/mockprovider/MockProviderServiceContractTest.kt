package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.data.LocationDeliveryMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockProviderServiceContractTest {

    @Test
    fun `service accepts only profile-backed start and explicit hook stop`() {
        assertEquals(
            MockProviderCommand.StartFromEffectiveProfile,
            MockProviderServiceContract.decode(MockProviderServiceContract.ACTION_START_FROM_EFFECTIVE_PROFILE),
        )
        assertEquals(
            MockProviderCommand.StopAndUseHook,
            MockProviderServiceContract.decode(MockProviderServiceContract.ACTION_STOP_AND_USE_HOOK),
        )
        assertEquals(MockProviderCommand.CleanupRuntimeOnly, MockProviderServiceContract.decode(null))
        assertTrue(MockProviderServiceContract.decode("unknown") is MockProviderCommand.Rejected)
    }

    @Test
    fun `startup reconciliation follows durable location delivery intent`() {
        assertEquals(
            MockProviderCommand.StopAndUseHook,
            LocationDeliveryStartupPlan.commandFor(
                LocationDeliveryMode.SYSTEM_MOCK,
                cleanupRequired = true,
            ),
        )
        assertEquals(
            MockProviderCommand.StartFromEffectiveProfile,
            LocationDeliveryStartupPlan.commandFor(
                LocationDeliveryMode.SYSTEM_MOCK,
                cleanupRequired = false,
            ),
        )
        assertEquals(
            MockProviderCommand.StopAndUseHook,
            LocationDeliveryStartupPlan.commandFor(
                LocationDeliveryMode.HOOK,
                cleanupRequired = true,
            ),
        )
        assertEquals(
            null,
            LocationDeliveryStartupPlan.commandFor(
                LocationDeliveryMode.HOOK,
                cleanupRequired = false,
            ),
        )
    }

    @Test
    fun `provider registration uses modern properties from Android 12`() {
        assertEquals(ProviderApiFamily.Legacy, ProviderApiFamily.forSdk(30))
        assertEquals(ProviderApiFamily.Modern, ProviderApiFamily.forSdk(31))
        assertEquals(ProviderApiFamily.Modern, ProviderApiFamily.forSdk(35))
    }

    @Test
    fun `sample factory always produces a complete Kyiv gps sample`() {
        val sample = MockLocationSampleFactory(
            wallClockMillis = { 1_725_000_000_000L },
            elapsedRealtimeNanos = { 123_456_789L },
        ).create(
            MockLocationConfig(
                latitude = 50.4501,
                longitude = 30.5234,
                accuracyMeters = 3f,
                altitudeMeters = 179.0,
            ),
        )

        assertEquals("gps", sample.provider)
        assertEquals(50.4501, sample.latitude, 0.0)
        assertEquals(30.5234, sample.longitude, 0.0)
        assertEquals(179.0, sample.altitudeMeters)
        assertEquals(3f, sample.accuracyMeters)
        assertEquals(1_725_000_000_000L, sample.timeMillis)
        assertEquals(123_456_789L, sample.elapsedRealtimeNanos)
    }
}
