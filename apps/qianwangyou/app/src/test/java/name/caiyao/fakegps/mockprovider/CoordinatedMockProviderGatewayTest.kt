package name.caiyao.fakegps.mockprovider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinatedMockProviderGatewayTest {

    @Test
    fun `framework and fused providers form one ordered session`() {
        val events = mutableListOf<String>()
        val gateway = CoordinatedMockProviderGateway(
            framework = RecordingFrameworkGateway(events),
            fused = RecordingFusedMockProviderGateway(events),
        )
        val controller = MockProviderSessionController(gateway)
        val config = MockLocationConfig(50.4501, 30.5234)

        controller.start(config)

        assertEquals(
            listOf(
                "framework:remove", "fused:disable",
                "framework:replace", "fused:enable",
                "fused:publish", "framework:publish",
            ),
            events,
        )
        assertEquals(MockProviderState.Running(config, emittedCount = 1), controller.state)
    }

    @Test
    fun `framework success followed by fused start failure cleans both layers`() {
        val events = mutableListOf<String>()
        val gateway = CoordinatedMockProviderGateway(
            framework = RecordingFrameworkGateway(events),
            fused = RecordingFusedMockProviderGateway(events, failAt = "enable"),
        )
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        assertEquals(
            listOf(
                "framework:remove", "fused:disable",
                "framework:replace", "fused:enable",
                "framework:remove", "fused:disable",
            ),
            events,
        )
        val failed = controller.state as MockProviderState.Failed
        assertTrue(failed.providerCleanupRequired)
    }

    @Test
    fun `fused publish success followed by framework failure cleans both layers`() {
        val events = mutableListOf<String>()
        val gateway = CoordinatedMockProviderGateway(
            framework = RecordingFrameworkGateway(events, failAt = "publish"),
            fused = RecordingFusedMockProviderGateway(events),
        )
        val controller = MockProviderSessionController(gateway)

        controller.start(MockLocationConfig(50.4501, 30.5234))

        assertEquals(
            listOf(
                "framework:remove", "fused:disable",
                "framework:replace", "fused:enable",
                "fused:publish", "framework:publish",
                "framework:remove", "fused:disable",
            ),
            events,
        )
        val failed = controller.state as MockProviderState.Failed
        assertTrue(failed.providerCleanupRequired)
    }

    @Test
    fun `fused stop failure never reports idle and retries every cleanup layer`() {
        val events = mutableListOf<String>()
        val gateway = CoordinatedMockProviderGateway(
            framework = RecordingFrameworkGateway(events),
            fused = RecordingFusedMockProviderGateway(events, failAt = "disable"),
        )
        val controller = MockProviderSessionController(gateway)

        controller.stop()

        assertEquals(
            listOf(
                "framework:remove", "fused:disable",
                "framework:remove", "fused:disable",
            ),
            events,
        )
        assertTrue(controller.state is MockProviderState.Failed)
        assertTrue((controller.state as MockProviderState.Failed).providerCleanupRequired)
    }

    @Test
    fun `framework stop failure still disables fused before reporting failure`() {
        val events = mutableListOf<String>()
        val gateway = CoordinatedMockProviderGateway(
            framework = RecordingFrameworkGateway(events, failAt = "remove"),
            fused = RecordingFusedMockProviderGateway(events),
        )
        val controller = MockProviderSessionController(gateway)

        controller.stop()

        assertEquals(
            listOf(
                "framework:remove", "fused:disable",
                "framework:remove", "fused:disable",
            ),
            events,
        )
        assertTrue(controller.state is MockProviderState.Failed)
    }
}

private class RecordingFrameworkGateway(
    private val events: MutableList<String>,
    private val failAt: String? = null,
) : MockProviderGateway {
    override fun replaceGpsProvider() {
        events += "framework:replace"
        if (failAt == "replace") error("framework replace failed")
    }

    override fun publish(config: MockLocationConfig) {
        events += "framework:publish"
        if (failAt == "publish") error("framework publish failed")
    }

    override fun removeGpsProvider() {
        events += "framework:remove"
        if (failAt == "remove") error("framework remove failed")
    }
}

private class RecordingFusedMockProviderGateway(
    private val events: MutableList<String>,
    private val failAt: String? = null,
) : FusedMockProviderGateway {
    override fun enable() {
        events += "fused:enable"
        if (failAt == "enable") error("fused enable failed")
    }

    override fun publish(config: MockLocationConfig) {
        events += "fused:publish"
        if (failAt == "publish") error("fused publish failed")
    }

    override fun disable() {
        events += "fused:disable"
        if (failAt == "disable") error("fused disable failed")
    }
}
