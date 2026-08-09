package name.caiyao.fakegps.ui.screen.map

import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapRecenterTargetResolverTest {

    @Test
    fun `cold start requests the current device position`() {
        assertEquals(
            MapRecenterTarget.CurrentDevice,
            resolve(PayloadRead.Absent),
        )
    }

    @Test
    fun `applying Hook uses the exact published effective coordinate`() {
        assertEquals(
            MapRecenterTarget.EffectiveCoordinate(
                latitude = 50.4501,
                longitude = 30.5234,
                source = MapRecenterCoordinateSource.HOOK,
            ),
            resolve(payload()),
        )
    }

    @Test
    fun `Hook with no configured location is real-position passthrough`() {
        assertEquals(
            MapRecenterTarget.CurrentDevice,
            resolve(payload(fields = "")),
        )
    }

    @Test
    fun `Hook mode off and outside active hours both request current device`() {
        assertEquals(
            MapRecenterTarget.CurrentDevice,
            resolve(payload(mode = "off")),
        )
        assertEquals(
            MapRecenterTarget.CurrentDevice,
            resolve(
                payload(
                    mode = "time_based",
                    activeHours = ",\"activeHours\":{\"start\":7,\"end\":22}",
                ),
                currentHour = 23,
            ),
        )
    }

    @Test
    fun `running System Mock config owns the coordinate even if payload read is unavailable`() {
        val running = MockProviderState.Running(
            MockLocationConfig(49.8397, 24.0297),
            emittedCount = 4,
        )

        assertEquals(
            MapRecenterTarget.EffectiveCoordinate(
                latitude = 49.8397,
                longitude = 24.0297,
                source = MapRecenterCoordinateSource.SYSTEM_MOCK,
            ),
            resolve(PayloadRead.ReadError("temporarily unreadable"), running),
        )
    }

    @Test
    fun `System Mock transitions and failures never guess a coordinate`() {
        val starting = resolve(
            payload(deliveryMode = "system_mock"),
            MockProviderState.Starting(MockLocationConfig(50.4501, 30.5234)),
        )
        val stopping = resolve(payload(), MockProviderState.Stopping)
        val failed = resolve(
            payload(),
            MockProviderState.Failed("provider cleanup failed", providerCleanupRequired = true),
        )

        assertTrue(starting is MapRecenterTarget.Unavailable)
        assertTrue(stopping is MapRecenterTarget.Unavailable)
        assertTrue(failed is MapRecenterTarget.Unavailable)
    }

    @Test
    fun `cleanup-free System Mock failure falls through to the applying Hook owner`() {
        assertEquals(
            MapRecenterTarget.EffectiveCoordinate(
                latitude = 50.4501,
                longitude = 30.5234,
                source = MapRecenterCoordinateSource.HOOK,
            ),
            resolve(
                payload(),
                MockProviderState.Failed(
                    "provider already cleaned up",
                    providerCleanupRequired = false,
                ),
            ),
        )
    }

    @Test
    fun `persisted System Mock intent without a running provider is not treated as effective`() {
        assertTrue(
            resolve(payload(deliveryMode = "system_mock")) is MapRecenterTarget.Unavailable,
        )
    }

    @Test
    fun `malformed incompatible and last-known-good payload states fail visibly`() {
        assertTrue(resolve(PayloadRead.Raw("{oops")) is MapRecenterTarget.Unavailable)
        assertTrue(resolve(payload(schemaVersion = 99)) is MapRecenterTarget.Unavailable)
        assertTrue(
            resolve(
                PayloadRead.Raw(
                    """{"schemaVersion":4,"mode":"always_on","unavailable":[]}""",
                ),
            ) is MapRecenterTarget.Unavailable,
        )
    }

    @Test
    fun `partial or invalid configured coordinates fail instead of falling back`() {
        assertTrue(
            resolve(payload(fields = "\"latitude\":50.4501"))
                is MapRecenterTarget.Unavailable,
        )
        assertTrue(
            resolve(payload(fields = "\"latitude\":91,\"longitude\":30.5234"))
                is MapRecenterTarget.Unavailable,
        )
    }

    private fun resolve(
        read: PayloadRead,
        providerState: MockProviderState = MockProviderState.Idle,
        currentHour: Int = 12,
    ): MapRecenterTarget = MapRecenterTargetResolver.resolve(read, providerState, currentHour)

    private fun payload(
        schemaVersion: Int = 4,
        mode: String = "always_on",
        deliveryMode: String = "hook",
        fields: String = "\"latitude\":50.4501,\"longitude\":30.5234",
        activeHours: String = "",
    ) = PayloadRead.Raw(
        """{"schemaVersion":$schemaVersion,"mode":"$mode","locationDeliveryMode":"$deliveryMode","fields":{$fields},"unavailable":[]$activeHours}""",
    )
}
