package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.motion.RoutePayload
import name.caiyao.fakegps.motion.RouteWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.1: the orchestrator's refresh contract with routes — keep playing on identity match,
 * restart when the route (or its params) changed, play static again when the route is gone.
 */
class LocationDeliveryRouteTest {

    private val start = RouteWaypoint(50.4501, 30.5234)
    private val end = RouteWaypoint(50.4600, 30.5400)

    private fun published(seed: Long, withRoute: Boolean = true) = PublishedConfig(
        schemaVersion = 5,
        mode = "always_on",
        fields = mapOf(
            "latitude" to start.latitude.toString(),
            "longitude" to start.longitude.toString(),
        ),
        locationDeliveryMode = "hook",
        route = if (withRoute) {
            RoutePayload.fromWaypoints(
                source = RoutePayload.SOURCE_PLAN,
                waypoints = listOf(start, end),
                cruiseSpeedMps = 10.0,
                seed = seed,
            )
        } else {
            null
        },
    )

    @Test
    fun `refresh with unchanged route keeps playing instead of rebuilding the provider`() {
        val kyiv = published(seed = 5)
        val profiles = ArrayDeque(listOf(kyiv, kyiv, kyiv))
        val fixture = RouteFixture(readPublished = { profiles.removeFirst() })

        fixture.orchestrator.enable()
        fixture.gateway.calls.clear()
        val publishesBefore = fixture.gateway.publishedConfigs.size

        fixture.orchestrator.refresh()
        fixture.orchestrator.refresh()

        // Two ticks, ZERO provider rebuilds: the moving current point must not read as a
        // config change, or the test provider would be replaced once per second.
        assertEquals(2, fixture.gateway.calls.count { it.startsWith("publish") })
        assertEquals(0, fixture.gateway.calls.count { it.startsWith("remove") })
        assertEquals(0, fixture.gateway.calls.count { it.startsWith("replace") })
        assertTrue(fixture.gateway.publishedConfigs.size > publishesBefore)
        val running = fixture.controller.state as MockProviderState.Running
        assertTrue(running.routePlayer != null)
    }

    @Test
    fun `refresh with a changed route restarts the session`() {
        val profiles = ArrayDeque(listOf(published(seed = 5), published(seed = 6)))
        val fixture = RouteFixture(readPublished = { profiles.removeFirst() })

        fixture.orchestrator.enable()
        val firstKey = (fixture.controller.state as MockProviderState.Running).routeKey

        fixture.orchestrator.refresh()

        val running = fixture.controller.state as MockProviderState.Running
        assertTrue(running.routeKey != firstKey)
        // enable() = fresh provider registration; refresh() = identity mismatch → restart.
        assertEquals(
            listOf("publish", "remove", "replace", "publish"),
            fixture.gateway.calls.map { it.substringBefore(':') }.drop(2),
        )
    }

    @Test
    fun `refresh with the route removed falls back to the static session`() {
        val profiles = ArrayDeque(
            listOf(published(seed = 5), published(seed = 5, withRoute = false)),
        )
        val fixture = RouteFixture(readPublished = { profiles.removeFirst() })

        fixture.orchestrator.enable()
        fixture.orchestrator.refresh()

        val running = fixture.controller.state as MockProviderState.Running
        assertNull(running.routeKey)
        assertNull(running.routePlayer)
    }

    @Test
    fun `enable without a route produces the historical static session`() {
        val fixture = RouteFixture(readPublished = { published(seed = 1, withRoute = false) })

        val result = fixture.orchestrator.enable()

        assertTrue(result is MockProviderState.Running)
        val running = result as MockProviderState.Running
        assertNull(running.routeKey)
        assertNull(running.routePlayer)
        assertEquals(start.latitude, running.config.latitude, 0.0)
    }

    private class RouteFixture(
        readPublished: () -> PublishedConfig?,
    ) {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)
        val orchestrator = LocationDeliveryOrchestrator(
            controller = controller,
            readPublished = readPublished,
            readMode = { LocationDeliveryMode.SYSTEM_MOCK },
            readCleanupRequired = { false },
            persistMode = { true },
            publishConfig = { true },
            persistCleanupRequired = { true },
        )
    }
}
