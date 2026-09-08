package name.caiyao.fakegps.motion

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P3.2 纯模型：步频 × 步幅 ≈ GPS 速度（v = f × stride），同一插值器驱动。 */
class MotionSensorModelTest {

    @Test
    fun `cadence times stride equals gps speed`() {
        val spec = RouteSpec(
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4560, 30.5300),
            ),
            cruiseSpeedMps = 1.5, // brisk walking
        )
        val model = MotionSensorModel(spec)
        assertEquals(1.5, model.cadenceHz(1.5) * MotionSensorModel.DEFAULT_STRIDE_METERS, 1e-9)
    }

    @Test
    fun `step counter total reconstructs the walked distance`() {
        val spec = RouteSpec(
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4560, 30.5300),
            ),
            cruiseSpeedMps = 1.4,
        )
        val model = MotionSensorModel(spec)
        val pathLength = spec.totalLengthMeters()
        val steps = model.totalSteps()
        val stepsDistance = steps * MotionSensorModel.DEFAULT_STRIDE_METERS

        assertTrue(
            "steps $steps → $stepsDistance m vs path $pathLength m",
            abs(stepsDistance - pathLength) / pathLength <= 0.15,
        )
    }

    @Test
    fun `acceleration sinusoid oscillates at the walking cadence`() {
        val spec = RouteSpec(
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4520, 30.5260),
            ),
            cruiseSpeedMps = 1.44, // 2 Hz cadence at 0.72 m stride
        )
        val model = MotionSensorModel(spec)
        val cadence = model.cadenceHz(1.44)
        assertEquals(2.0, cadence, 1e-9)

        val point = TrajectoryPoint(
            elapsedSeconds = 0.0,
            latitude = 50.4501,
            longitude = 30.5234,
            speedMps = 1.44,
            bearingDegrees = 45.0,
            accuracyMeters = 3.0,
        )
        // One second at 2 Hz: exactly two gait peaks (maxima of the sinusoid).
        val samples = (0..50).map {
            model.accelerationSample(point, it / MotionSensorModel.DEFAULT_SAMPLE_RATE_HZ)
        }
        val peaks = (1 until samples.size - 1).count {
            samples[it] > samples[it - 1] && samples[it] > samples[it + 1]
        }
        assertEquals(2, peaks)
        // Gravity + bounded gait amplitude at the sinusoid's peak.
        val extreme = model.accelerationSample(point, 0.125)
        assertEquals(
            9.81 + MotionSensorModel.ACCEL_AMPLITUDE_MPS2,
            extreme,
            1e-6,
        )
    }

    @Test
    fun `stationary fix has no gait signal`() {
        val spec = RouteSpec(
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4600, 30.5400),
            ),
        )
        val model = MotionSensorModel(spec)
        assertEquals(0.0, model.cadenceHz(0.0), 0.0)
        val rest = TrajectoryPoint(0.0, 0.0, 0.0, 0.0, 0.0, 3.0)
        assertEquals(9.81, model.accelerationSample(rest, 0.37), 1e-9)
    }

    @Test
    fun `steps and gps distance stay consistent across a whole replay window`() {
        val spec = RouteSpec(
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4560, 30.5300),
                RouteWaypoint(50.4600, 30.5400),
            ),
            cruiseSpeedMps = 1.5,
        )
        val model = MotionSensorModel(spec)
        val points = RoutePlayer.interpolateAll(spec)
        assertTrue(model.stepsDistanceConsistency(points))
    }
}

/** 几何工具契约。 */
class GeoMathTest {

    @Test
    fun `distance is symmetric and matches known reference`() {
        // ~111.32 km along a meridian for 1° of latitude.
        val d = GeoMath.distanceMeters(50.0, 30.0, 51.0, 30.0)
        assertEquals(111_195.0, d, 500.0)
        assertEquals(
            GeoMath.distanceMeters(50.0, 30.0, 51.0, 31.0),
            GeoMath.distanceMeters(51.0, 31.0, 50.0, 30.0),
            1e-6,
        )
    }

    @Test
    fun `destination round-trips distance and bearing`() {
        val lat = 50.4501
        val lng = 30.5234
        val bearing = 123.0
        val distance = 500.0
        val arrived = GeoMath.destination(lat, lng, bearing, distance)
        assertEquals(
            distance,
            GeoMath.distanceMeters(lat, lng, arrived[0], arrived[1]),
            1e-6,
        )
        assertEquals(
            bearing,
            GeoMath.bearingDegrees(lat, lng, arrived[0], arrived[1]),
            1e-6,
        )
    }

    @Test
    fun `bearing delta takes the shortest path across the 0-360 wrap`() {
        assertEquals(10.0, GeoMath.bearingDeltaDegrees(355.0, 5.0), 1e-9)
        assertEquals(-10.0, GeoMath.bearingDeltaDegrees(5.0, 355.0), 1e-9)
        assertEquals(180.0, GeoMath.bearingDeltaDegrees(0.0, 180.0), 1e-9)
    }
}
