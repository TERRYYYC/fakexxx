package name.caiyao.fakegps.ui.screen.settings

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import org.junit.Assert.assertEquals
import org.junit.Test

class SystemMockEnableActionTest {
    @Test
    fun `publication failure stops before profile read or service start`() {
        val events = mutableListOf<String>()

        val result = SystemMockEnableAction.run(
            syncPublishedConfig = { events += "sync"; false },
            readPublishedConfig = { events += "read"; published() },
            publishProviderState = { events += "state:$it" },
            startService = { events += "start" },
        )

        assertEquals(SystemMockEnableOutcome.PublicationFailed, result)
        assertEquals(listOf("sync"), events)
    }

    @Test
    fun `invalid profile publishes actual failure and never starts service`() {
        val events = mutableListOf<String>()
        val invalid = published().copy(fields = emptyMap())

        val result = SystemMockEnableAction.run(
            syncPublishedConfig = { events += "sync"; true },
            readPublishedConfig = { events += "read"; invalid },
            publishProviderState = { events += "state:$it" },
            startService = { events += "start" },
        )

        assertEquals(
            SystemMockEnableOutcome.Invalid(invalid, "生效档案缺少有效纬度"),
            result,
        )
        assertEquals(
            listOf(
                "sync",
                "read",
                "state:Failed(message=生效档案缺少有效纬度, recovery=null, " +
                    "providerCleanupRequired=false, reason=null)",
            ),
            events,
        )
    }

    @Test
    fun `valid profile publishes starting before service command`() {
        val events = mutableListOf<String>()
        val published = published()
        val config = MockLocationConfig(50.4501, 30.5234, altitudeMeters = 179.0)

        val result = SystemMockEnableAction.run(
            syncPublishedConfig = { events += "sync"; true },
            readPublishedConfig = { events += "read"; published },
            publishProviderState = { events += "state:$it" },
            startService = { events += "start" },
        )

        assertEquals(SystemMockEnableOutcome.Started(published, config), result)
        assertEquals(
            listOf("sync", "read", "state:Starting(config=$config)", "start"),
            events,
        )
    }

    /**
     * Issue #8 RED: the OS can reset the android:mock_location app-op back to deny at any time.
     * The enable transition must ask AppOpsManager FIRST — fail-fast with the typed state BEFORE
     * the config sync, the profile read, or the service start (never wait for addTestProvider's
     * SecurityException inside the service).
     */
    @Test
    fun `app-op denied fail-fasts with a typed state before any publication or service start`() {
        val events = mutableListOf<String>()

        val result = SystemMockEnableAction.run(
            syncPublishedConfig = { events += "sync"; true },
            readPublishedConfig = { events += "read"; published() },
            publishProviderState = { events += "state:$it" },
            startService = { events += "start" },
            mockLocationAppOpAllowed = { events += "appops"; false },
        )

        assertEquals(SystemMockEnableOutcome.AppOpDenied, result)
        assertEquals(
            listOf(
                "appops",
                "state:Failed(message=模拟位置权限（mock_location AppOps）已被系统重置为拒绝，" +
                    "当前千网游不被允许执行 MOCK_LOCATION, recovery=SelectThisAppAndRetryStart, " +
                    "providerCleanupRequired=false, reason=MOCK_LOCATION_APP_OP_DENIED)",
            ),
            events,
        )
    }

    /** Issue #8: with the app-op allowed the enable journey is byte-for-byte unchanged. */
    @Test
    fun `app-op allowed keeps the enable journey unchanged`() {
        val events = mutableListOf<String>()
        val published = published()
        val config = MockLocationConfig(50.4501, 30.5234, altitudeMeters = 179.0)

        val result = SystemMockEnableAction.run(
            syncPublishedConfig = { events += "sync"; true },
            readPublishedConfig = { events += "read"; published },
            publishProviderState = { events += "state:$it" },
            startService = { events += "start" },
            mockLocationAppOpAllowed = { events += "appops"; true },
        )

        assertEquals(SystemMockEnableOutcome.Started(published, config), result)
        assertEquals(
            listOf("appops", "sync", "read", "state:Starting(config=$config)", "start"),
            events,
        )
    }

    private fun published() = PublishedConfig(
        schemaVersion = 4,
        mode = "always_on",
        fields = mapOf(
            "latitude" to "50.4501",
            "longitude" to "30.5234",
            "altitude" to "179.0",
        ),
        locationDeliveryMode = "hook",
    )
}
