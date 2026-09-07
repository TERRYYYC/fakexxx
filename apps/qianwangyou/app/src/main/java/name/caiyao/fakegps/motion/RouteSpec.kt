package name.caiyao.fakegps.motion

/**
 * Ordered stop of a route (P3.1 档案模型 "路线" 分支).
 *
 * [speedMps] is the OPTIONAL per-waypoint cruise override from an independent route CSV
 * (`waypoint,lat,lng[,speed_mps]`); null = use the route's cruise speed.
 */
data class RouteWaypoint(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double? = null,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "waypoint latitude must be finite and within [-90, 90]"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "waypoint longitude must be finite and within [-180, 180]"
        }
        require(speedMps == null || (speedMps.isFinite() && speedMps > 0.0)) {
            "waypoint speed must be finite and positive when present"
        }
    }
}

/** One emitted 1 Hz fix of the route playback engine. */
data class TrajectoryPoint(
    val elapsedSeconds: Double,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Double,
    val bearingDegrees: Double,
    val accuracyMeters: Double,
)

/**
 * Route playback parameters (the whole motion-chain contract in one immutable value).
 *
 * Speed profile: 起步加速 → 巡航 → 减速停 (trapezoid on a distance grid, with sharp turns
 * slowing the profile down). GPS jitter: seeded, bounded by [jitterFraction]×[accuracyMeters],
 * reproducible across publishes so playback restarts do not change the noise pattern for the
 * same route identity.
 */
data class RouteSpec(
    val waypoints: List<RouteWaypoint>,
    val cruiseSpeedMps: Double = DEFAULT_CRUISE_SPEED_MPS,
    val accelMps2: Double = DEFAULT_ACCEL_MPS2,
    val decelMps2: Double = DEFAULT_DECEL_MPS2,
    val accuracyMeters: Double = DEFAULT_ACCURACY_METERS,
    /** Jitter disc radius as a fraction of [accuracyMeters] (0 disables jitter). */
    val jitterFraction: Double = DEFAULT_JITTER_FRACTION,
    val seed: Long = DEFAULT_SEED,
    /** Emission cadence of the delivery scheduler; the system-mock lane ticks at 1 Hz. */
    val tickSeconds: Double = TICK_SECONDS,
    /** Per-tick bearing slew limit (deg/s) — GPS courses never snap. */
    val maxTurnRateDegPerSec: Double = DEFAULT_MAX_TURN_RATE_DEG_PER_SEC,
) {
    init {
        require(waypoints.size >= MIN_WAYPOINTS) {
            "a route needs at least $MIN_WAYPOINTS distinct waypoints"
        }
        require(cruiseSpeedMps.isFinite() && cruiseSpeedMps in MIN_SPEED_MPS..MAX_SPEED_MPS) {
            "cruise speed must be within $MIN_SPEED_MPS..$MAX_SPEED_MPS m/s"
        }
        require(accelMps2.isFinite() && accelMps2 > 0.0) { "acceleration must be positive" }
        require(decelMps2.isFinite() && decelMps2 > 0.0) { "deceleration must be positive" }
        require(accuracyMeters.isFinite() && accuracyMeters > 0.0) {
            "accuracy must be finite and positive"
        }
        require(jitterFraction.isFinite() && jitterFraction in 0.0..1.0) {
            "jitter fraction must be within [0, 1]"
        }
        require(tickSeconds.isFinite() && tickSeconds > 0.0) { "tick seconds must be positive" }
        require(maxTurnRateDegPerSec.isFinite() && maxTurnRateDegPerSec > 0.0) {
            "turn rate must be positive"
        }
    }

    /** Path length in meters along the waypoint polyline (waypoints deduplicated). */
    fun totalLengthMeters(): Double = segments().sumOf { it.lengthMeters }

    internal data class Segment(
        val from: RouteWaypoint,
        val to: RouteWaypoint,
        val lengthMeters: Double,
        val bearingDegrees: Double,
    )

    /** Consecutive-waypoint segments, zero-length hops (duplicate points) removed. */
    internal fun segments(): List<Segment> {
        val result = mutableListOf<Segment>()
        for (i in 0 until waypoints.size - 1) {
            val from = waypoints[i]
            val to = waypoints[i + 1]
            val length = GeoMath.distanceMeters(
                from.latitude, from.longitude, to.latitude, to.longitude,
            )
            if (length <= 0.0) continue
            result += Segment(
                from, to, length,
                GeoMath.bearingDegrees(from.latitude, from.longitude, to.latitude, to.longitude),
            )
        }
        return result
    }

    /**
     * Stable identity of THIS playback definition. The delivery orchestrator compares it across
     * refreshes: same key = keep playing (the moving current point must NOT be compared, or every
     * 1 Hz tick would look like a config change and rebuild the test provider).
     */
    fun sessionKey(): String = buildString {
        append("v1;")
        append(cruiseSpeedMps).append(';')
        append(accelMps2).append(';').append(decelMps2).append(';')
        append(accuracyMeters).append(';').append(jitterFraction).append(';')
        append(seed).append(';').append(tickSeconds)
        for (w in waypoints) {
            append(';').append(w.latitude).append(',').append(w.longitude)
            append(',').append(w.speedMps)
        }
    }

    companion object {
        const val MIN_WAYPOINTS = 2
        const val MIN_SPEED_MPS = 0.5
        const val MAX_SPEED_MPS = 70.0
        const val TICK_SECONDS = 1.0
        const val DEFAULT_CRUISE_SPEED_MPS = 10.0
        const val DEFAULT_ACCEL_MPS2 = 1.5
        const val DEFAULT_DECEL_MPS2 = 2.0
        const val DEFAULT_ACCURACY_METERS = 3.0
        const val DEFAULT_JITTER_FRACTION = 0.3
        const val DEFAULT_SEED = 0L
        const val DEFAULT_MAX_TURN_RATE_DEG_PER_SEC = 30.0
    }
}
