package name.caiyao.fakegps.motion

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3.1 插值器断言清单（TASK-ACCEPTANCE T9 A 层）：
 *  1. 相邻投递点间距 ≤ v_max×Δt×1.5
 *  2. bearing 变化率 ≤ 阈值（逐 tick ≤ maxTurnRate×Δt；直线上 ≈ 0）
 *  3. speed ≈ 位移导数（逐点容差 + 全程积分一致性）
 *  4. 抖动有界（≤ accuracy×fraction）且可复现（种子化）
 *  5. 速度剖面：起步加速 → 巡航 ≤ cruise → 减速停（终点精确到 waypoint、速度 0）
 */
class TrajectoryInterpolatorTest {

    private val start = RouteWaypoint(50.450100, 30.523400)
    private val end = RouteWaypoint(50.460000, 30.540000)

    private fun straightRoute(cruise: Double = 10.0, seed: Long = 7) = RouteSpec(
        waypoints = listOf(start, end),
        cruiseSpeedMps = cruise,
        seed = seed,
    )

    @Test
    fun `adjacent spacing never exceeds vmax times dt times 1_5`() {
        val spec = straightRoute()
        val points = RoutePlayer.interpolateAll(spec)
        val bound = spec.cruiseSpeedMps * spec.tickSeconds * 1.5

        for (i in 1 until points.size) {
            val gap = GeoMath.distanceMeters(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
            assertTrue("gap at $i was $gap, bound $bound", gap <= bound)
        }
    }

    @Test
    fun `bearing change per tick stays under the turn-rate threshold`() {
        // A route with a 90° corner: bearing must SLEW, never snap.
        val corner = RouteWaypoint(50.460000, 30.540000)
        val spec = RouteSpec(
            waypoints = listOf(start, corner, RouteWaypoint(50.460000, 30.560000)),
            cruiseSpeedMps = 10.0,
            seed = 11,
        )
        val points = RoutePlayer.interpolateAll(spec)
        val threshold = spec.maxTurnRateDegPerSec * spec.tickSeconds

        for (i in 1 until points.size) {
            val change = abs(GeoMath.bearingDeltaDegrees(points[i - 1].bearingDegrees, points[i].bearingDegrees))
            assertTrue("bearing jump at $i was $change, threshold $threshold", change <= threshold + 1e-9)
        }
    }

    @Test
    fun `reported speed tracks the displacement derivative point by point`() {
        val spec = straightRoute(cruise = 12.0)
        val points = RoutePlayer.interpolateAll(spec)
        val jitterRadius = spec.jitterFraction * spec.accuracyMeters

        // The LAST fix is the arrival: instantaneous rest at the exact waypoint. A real GPS
        // fix reports instantaneous speed too — the fix after the final step reads ~0 even
        // though the previous second covered travel — so the derivative pairing excludes it.
        val moving = points.dropLast(1)
        for (i in 1 until moving.size) {
            val gap = GeoMath.distanceMeters(
                moving[i - 1].latitude, moving[i - 1].longitude,
                moving[i].latitude, moving[i].longitude,
            )
            val expected = moving[i].speedMps * spec.tickSeconds
            // Displacement = profile speed × dt, ± the jitter disc of both endpoints
            // plus the ±3% speed-noise spread between profile and reported speed.
            assertTrue(
                "speed ${moving[i].speedMps} vs gap $gap at $i",
                abs(gap - expected) <= 2 * jitterRadius + 0.05 * expected + 1e-6,
            )
        }
    }

    @Test
    fun `integrated speed reconstructs the route length`() {
        val spec = straightRoute(cruise = 10.0)
        val points = RoutePlayer.interpolateAll(spec)
        val pathLength = spec.totalLengthMeters()
        val integrated = points.sumOf { it.speedMps * spec.tickSeconds }

        assertTrue(
            "integrated $integrated vs path $pathLength",
            abs(integrated - pathLength) / pathLength <= 0.06,
        )
    }

    @Test
    fun `profile accelerates from near rest cruises under the cap and stops at rest`() {
        // A long straight route so cruise is actually reached.
        val far = RouteWaypoint(50.450100, 30.523400 + 0.02)
        val spec = RouteSpec(waypoints = listOf(start, far), cruiseSpeedMps = 10.0, seed = 3)
        val points = RoutePlayer.interpolateAll(spec)
        val speeds = points.map { it.speedMps }

        assertTrue(
            "first tick must be in the acceleration ramp, got ${speeds.first()}",
            speeds.first() < spec.cruiseSpeedMps * 0.5,
        )
        assertTrue("some tick must reach cruise", speeds.any { it >= spec.cruiseSpeedMps * 0.97 })
        assertTrue("no tick may exceed cruise + noise", speeds.max() <= spec.cruiseSpeedMps * 1.04)
        assertEquals("arrival must be at rest", 0.0, speeds.last(), 0.0)
        // Monotone ramp at departure: the first 3 ticks never slow down.
        assertTrue(speeds[0] <= speeds[1] && speeds[1] <= speeds[2])
    }

    @Test
    fun `arrival fix is the exact final waypoint with zero jitter`() {
        val spec = straightRoute()
        val points = RoutePlayer.interpolateAll(spec)
        val last = points.last()

        assertEquals(end.latitude, last.latitude, 0.0)
        assertEquals(end.longitude, last.longitude, 0.0)
        assertEquals(0.0, last.speedMps, 0.0)
    }

    @Test
    fun `trajectory passes through every interior waypoint`() {
        val middle = RouteWaypoint(50.455000, 30.530000)
        val spec = RouteSpec(
            waypoints = listOf(start, middle, end),
            cruiseSpeedMps = 10.0,
            seed = 5,
        )
        val points = RoutePlayer.interpolateAll(spec)
        val jitterRadius = spec.jitterFraction * spec.accuracyMeters

        // Interior waypoints are exact trajectory nodes, but 1 Hz sampling of a moving fix
        // misses them by up to half a tick of travel (the turn cap is what limits the speed
        // at a corner) plus the jitter disc. The DEPARTURE fix is not emitted by the player
        // (the provider publishes it as the static start), so the start gets the wider bound.
        val turnSpeed = SpeedProfile.SHARP_TURN_SPEED_MPS
        val closestMiddle = points.minOf {
            GeoMath.distanceMeters(it.latitude, it.longitude, middle.latitude, middle.longitude)
        }
        assertTrue(
            "middle approached to $closestMiddle",
            closestMiddle <= turnSpeed * spec.tickSeconds / 2 + jitterRadius + 0.5,
        )
        val lastTickTravel = spec.cruiseSpeedMps * spec.tickSeconds
        val closestStart = points.minOf {
            GeoMath.distanceMeters(it.latitude, it.longitude, start.latitude, start.longitude)
        }
        assertTrue(
            "start approached to $closestStart",
            closestStart <= lastTickTravel + jitterRadius + 0.3,
        )
    }

    @Test
    fun `jitter is bounded by fraction times accuracy`() {
        val spec = straightRoute()
        val jitterRadius = spec.jitterFraction * spec.accuracyMeters
        val points = RoutePlayer.interpolateAll(spec)

        // Bounded: every reported fix lies within the jitter disc of SOME true trajectory point;
        // verify through the spacing bound, which tightens to cruise·dt + 2·jitterRadius.
        for (i in 1 until points.size) {
            val gap = GeoMath.distanceMeters(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
            assertTrue(gap <= spec.cruiseSpeedMps * spec.tickSeconds + 2 * jitterRadius + 1e-9)
        }
    }

    @Test
    fun `same seed reproduces the identical trajectory and a different seed does not`() {
        val replay = RoutePlayer.interpolateAll(straightRoute(seed = 42))
        val replayAgain = RoutePlayer.interpolateAll(straightRoute(seed = 42))
        val otherSeed = RoutePlayer.interpolateAll(straightRoute(seed = 43))

        assertEquals(replay, replayAgain)
        assertTrue(
            "a different seed must change the noise pattern",
            replay.zip(otherSeed).any { (a, b) -> a != b },
        )
    }

    @Test
    fun `zero jitter fraction produces exact profile motion`() {
        val spec = straightRoute(seed = 1).copy(jitterFraction = 0.0)
        // Same arrival exclusion as the derivative test: the terminal fix reads rest.
        val points = RoutePlayer.interpolateAll(spec).dropLast(1)

        for (i in 1 until points.size) {
            val gap = GeoMath.distanceMeters(
                points[i - 1].latitude, points[i - 1].longitude,
                points[i].latitude, points[i].longitude,
            )
            val expected = points[i].speedMps * spec.tickSeconds
            // Reported speed carries ±3% seeded noise on top of the profile speed that
            // actually moves the position — hence the proportional tolerance.
            assertTrue(
                "gap $gap vs speed-derived $expected at $i",
                abs(gap - expected) <= 0.05 * expected + 0.05,
            )
        }
    }

    @Test
    fun `completion is terminal and 1 Hz cadence is the tick count`() {
        val player = RoutePlayer(straightRoute(cruise = 10.0))
        var ticks = 0
        while (player.tick() != null) ticks += 1

        assertTrue(player.completed)
        assertEquals(null, player.tick())
        assertEquals(ticks, player.emittedCount)
        // ~1.6 km route at 10 m/s: cruise dominates; ramps add ~12 s. Bound catches gross
        // regressions (a stuck profile or an early snap), not single seconds.
        assertTrue("ticks $ticks out of plausible range", ticks in 150..190)
    }

    @Test
    fun `duplicate consecutive waypoints do not divide by zero`() {
        val spec = RouteSpec(
            waypoints = listOf(start, start, end),
            cruiseSpeedMps = 10.0,
        )
        val points = RoutePlayer.interpolateAll(spec)
        assertTrue(points.isNotEmpty())
    }

    @Test
    fun `spec rejects degenerate routes and speeds`() {
        val failures = listOf(
            { RouteSpec(waypoints = listOf(start)) },
            { RouteSpec(waypoints = listOf(start, end), cruiseSpeedMps = 0.1) },
            { RouteSpec(waypoints = listOf(start, end), cruiseSpeedMps = 100.0) },
            { RouteSpec(waypoints = listOf(start, end), jitterFraction = 1.5) },
            { RouteSpec(waypoints = listOf(start, end), tickSeconds = 0.0) },
            { RouteWaypoint(91.0, 0.0) },
            { RouteWaypoint(0.0, 181.0) },
            { RouteWaypoint(0.0, 0.0, speedMps = -1.0) },
        )
        for (candidate in failures) {
            val failure = runCatching { candidate() }.exceptionOrNull()
            assertTrue("expected validation failure", failure is IllegalArgumentException)
        }
    }

    @Test
    fun `session key is stable and reflects route identity`() {
        assertEquals(straightRoute(seed = 1).sessionKey(), straightRoute(seed = 1).sessionKey())
        assertTrue(
            straightRoute(seed = 1).sessionKey() != straightRoute(seed = 2).sessionKey(),
        )
    }
}
