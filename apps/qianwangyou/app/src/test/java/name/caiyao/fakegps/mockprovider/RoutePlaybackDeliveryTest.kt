package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.motion.GeoMath
import name.caiyao.fakegps.motion.RoutePayload
import name.caiyao.fakegps.motion.RouteSpec
import name.caiyao.fakegps.motion.RouteWaypoint
import name.caiyao.fakegps.motion.TrajectoryPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.1 投递调度器（System Mock 车道）契约：路线播放器接管 1 Hz 投递。
 *
 * 虚拟时钟：controller.tick() 的一拍 = [RouteSpec.TICK_SECONDS] 虚拟秒（MockProviderService 的
 * TICK_MILLIS=1000 驱动），所以测试按 tick 计数推演时间，无需真实时钟。
 */
class RoutePlaybackDeliveryTest {

    private val start = RouteWaypoint(50.4501, 30.5234)
    private val end = RouteWaypoint(50.4600, 30.5400)

    /** ~1.6 km straight route at 10 m/s → ~167 s of playback. */
    private fun routePayload(): RoutePayload = RoutePayload.fromWaypoints(
        source = RoutePayload.SOURCE_PLAN,
        waypoints = listOf(start, end),
        cruiseSpeedMps = 10.0,
        seed = 5,
    )

    private fun publishedWithRoute(route: RoutePayload? = routePayload()) = PublishedConfig(
        schemaVersion = 5,
        mode = "always_on",
        fields = mapOf(
            "latitude" to start.latitude.toString(),
            "longitude" to start.longitude.toString(),
        ),
        locationDeliveryMode = "hook",
        route = route,
    )

    @Test
    fun `planner resolves a playable route and rejects absence`() {
        val ready = RoutePlaybackPlanner.resolve(publishedWithRoute())
        assertTrue(ready is RoutePlaybackResolution.Ready)
        assertEquals(2, (ready as RoutePlaybackResolution.Ready).spec.waypoints.size)

        assertEquals(
            RoutePlaybackResolution.None,
            RoutePlaybackPlanner.resolve(publishedWithRoute(route = null)),
        )
        assertEquals(RoutePlaybackResolution.None, RoutePlaybackPlanner.resolve(null))
    }

    @Test
    fun `static session publishes the same base point every tick - legacy path unchanged`() {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)
        val config = MockLocationConfig(50.4501, 30.5234)

        controller.start(config)
        controller.tick()

        assertEquals(
            listOf("remove", "replace", "publish:$config", "publish:$config"),
            gateway.calls,
        )
        assertEquals(MockProviderState.Running(config, emittedCount = 2), controller.state)
    }

    @Test
    fun `route session keeps the static base and completes once at 1 Hz`() {
        val gateway = RecordingMockProviderGateway()
        val completions = mutableListOf<RouteSpec>()
        val controller = MockProviderSessionController(
            gateway,
            onStateChanged = {},
            onRouteCompleted = { completions += it },
        )
        val base = MockLocationConfig(start.latitude, start.longitude)
        val player = RoutePlaybackPlanner.player(publishedWithRoute())!!

        controller.start(base, route = player)

        // Departure fix = the static base point, published by start().
        assertEquals(listOf("remove", "replace", "publish:$base"), gateway.calls)
        val initial = controller.state as MockProviderState.Running
        assertEquals(base, initial.config)
        assertFalse(initial.routeCompleted)
        assertEquals(player.spec.sessionKey(), initial.routeKey)

        // Virtual clock: drive the route one 1 Hz beat at a time until completion.
        var ticks = 0
        while (ticks < 400 &&
            !(controller.state as MockProviderState.Running).routeCompleted
        ) {
            controller.tick()
            ticks += 1
        }

        assertTrue("playback duration plausible: $ticks ticks", ticks in 150..190)
        assertEquals("completion fires exactly once", 1, completions.size)
        assertTrue((controller.state as MockProviderState.Running).routeCompleted)
        // Moving fixes were published: not a re-print of the base point each beat.
        assertTrue(
            gateway.publishedConfigs.drop(1).any { it != base },
        )
    }

    @Test
    fun `route emissions satisfy the spacing bound and arrive exactly`() {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)
        val base = MockLocationConfig(start.latitude, start.longitude)
        val spec = routePayload().toRouteSpec()
        val player = RoutePlaybackPlanner.player(publishedWithRoute())!!

        controller.start(base, route = player)
        val moving = mutableListOf<MockLocationConfig>()
        var ticks = 0
        while (ticks < 400 &&
            !(controller.state as MockProviderState.Running).routeCompleted
        ) {
            controller.tick()
            ticks += 1
            val published = gateway.publishedConfigs.last()
            if (moving.isEmpty() || moving.last() != published) moving += published
        }

        val bound = spec.cruiseSpeedMps * spec.tickSeconds * 1.5
        for (i in 1 until moving.size) {
            val gap = GeoMath.distanceMeters(
                moving[i - 1].latitude, moving[i - 1].longitude,
                moving[i].latitude, moving[i].longitude,
            )
            assertTrue("gap at $i was $gap, bound $bound", gap <= bound)
        }
        val arrival = gateway.publishedConfigs.last()
        assertEquals(end.latitude, arrival.latitude, 1e-9)
        assertEquals(end.longitude, arrival.longitude, 1e-9)
    }

    @Test
    fun `after completion the vehicle stays parked at the final waypoint`() {
        val gateway = RecordingMockProviderGateway()
        val controller = MockProviderSessionController(gateway)
        val base = MockLocationConfig(start.latitude, start.longitude)
        val player = RoutePlaybackPlanner.player(publishedWithRoute())!!

        controller.start(base, route = player)
        repeat(300) { controller.tick() }
        val parked = gateway.publishedConfigs.last()
        assertEquals(end.latitude, parked.latitude, 1e-9)
        assertEquals(end.longitude, parked.longitude, 1e-9)
    }

    @Test
    fun `emission conversion carries the base altitude and clamps coordinates`() {
        val base = MockLocationConfig(50.0, 30.0, accuracyMeters = 4f, altitudeMeters = 120.0)
        val point = TrajectoryPoint(
            elapsedSeconds = 1.0,
            latitude = 50.0001,
            longitude = 30.0001,
            speedMps = 9.0,
            bearingDegrees = 42.0,
            accuracyMeters = 3.0,
        )
        val config = point.toMockLocationConfig(base)
        assertEquals(120.0, config.altitudeMeters!!, 0.0)
        assertEquals(3f, config.accuracyMeters)
        assertEquals(50.0001, config.latitude, 1e-9)
    }
}
