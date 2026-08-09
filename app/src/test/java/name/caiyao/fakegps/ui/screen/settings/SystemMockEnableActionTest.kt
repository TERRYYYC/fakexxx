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
                    "providerCleanupRequired=false)",
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
