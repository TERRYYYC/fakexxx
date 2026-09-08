package name.caiyao.fakegps.motion

import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Streaming 1 Hz route playback (P3.1 插值器).
 *
 * One [tick] = one delivery-scheduler beat = [RouteSpec.tickSeconds] of virtual travel time.
 * The FIRST tick emits the position ~1 s after departure (the departure fix itself is the
 * static effective-profile point the mock provider already published on start). The LAST tick
 * emits the exact final waypoint at rest and flips [completed].
 *
 * Determinism: the GPS jitter and speed noise come from one [Random] seeded by
 * [RouteSpec.seed], so a restarted session with the same spec reproduces byte-identical
 * emissions — a testable anti-flap property AND a debugging superpower on device logs.
 *
 * Pure Kotlin: no Android types, no clocks, no schedulers. Virtual time is the tick count.
 */
class RoutePlayer(spec: RouteSpec) {

    val spec: RouteSpec
    private val profile: SpeedProfile
    private val segments: List<RouteSpec.Segment>
    private val cumulativeStart: DoubleArray
    private val random: Random
    private val jitterRadiusMeters: Double

    private var traveledMeters = 0.0
    private var elapsedSeconds = 0.0
    private var currentBearingDeg: Double
    private var done = false

    /** Number of emissions delivered so far. */
    var emittedCount = 0
        private set

    /** True once the exact final waypoint has been emitted (at rest, no jitter). */
    var completed = false
        private set

    init {
        this.spec = spec
        this.profile = SpeedProfile.build(spec)
        this.segments = spec.segments()
        this.cumulativeStart = DoubleArray(segments.size)
        var acc = 0.0
        for (i in segments.indices) {
            cumulativeStart[i] = acc
            acc += segments[i].lengthMeters
        }
        this.random = Random(spec.seed)
        this.jitterRadiusMeters = spec.jitterFraction * spec.accuracyMeters
        this.currentBearingDeg = segments.first().bearingDegrees
    }

    /**
     * Advance virtual time by [RouteSpec.tickSeconds] and emit the next fix, or null when the
     * route is already complete. The final arrival fix carries speed 0 and the untouched
     * waypoint coordinates — arrival is a fact, not a noisy observation.
     */
    fun tick(): TrajectoryPoint? {
        if (done) return null

        val remaining = profile.totalLengthMeters - traveledMeters
        if (remaining <= ARRIVAL_SNAP_METERS) return arrival()

        // One profile sample drives BOTH the step and the reported speed, so the emitted
        // speed is by construction the derivative of the emitted displacement.
        val sampledSpeed = profile.speedAt(
            min(traveledMeters + LOOKAHEAD_METERS, profile.totalLengthMeters),
        )
        val step = sampledSpeed * spec.tickSeconds
        elapsedSeconds += spec.tickSeconds

        // The step overshoots what is left (or the profile has already decayed to rest) —
        // snap to the exact arrival. Without this the decel-to-rest tail asymptotically
        // approaches the endpoint and never completes (v→0 ⇒ step→0).
        if (step >= remaining) return arrival()

        traveledMeters += step
        val position = positionAt(traveledMeters)
        val segmentBearing = bearingAt(traveledMeters)
        currentBearingDeg = slewBearing(currentBearingDeg, segmentBearing)

        val reportedSpeed = addSpeedNoise(sampledSpeed)
        val jitter = jitterOffset(position[0])

        emittedCount += 1
        return TrajectoryPoint(
            elapsedSeconds = elapsedSeconds,
            latitude = position[0] + jitter[0],
            longitude = position[1] + jitter[1],
            speedMps = reportedSpeed,
            bearingDegrees = currentBearingDeg,
            accuracyMeters = spec.accuracyMeters,
        )
    }

    private fun arrival(): TrajectoryPoint {
        done = true
        completed = true
        val last = segments.last().to
        emittedCount += 1
        return TrajectoryPoint(
            elapsedSeconds = elapsedSeconds,
            latitude = last.latitude,
            longitude = last.longitude,
            speedMps = 0.0,
            bearingDegrees = currentBearingDeg,
            accuracyMeters = spec.accuracyMeters,
        )
    }

    /** Position on the polyline at path distance [s]. */
    private fun positionAt(s: Double): DoubleArray {
        if (s >= profile.totalLengthMeters) {
            val last = segments.last().to
            return doubleArrayOf(last.latitude, last.longitude)
        }
        var index = segments.lastIndex
        for (i in segments.indices) {
            if (s < cumulativeStart[i] + segments[i].lengthMeters) {
                index = i
                break
            }
        }
        val segment = segments[index]
        val into = (s - cumulativeStart[index]).coerceIn(0.0, segment.lengthMeters)
        val remain = segment.lengthMeters - into
        val total = segment.lengthMeters
        // Linear blend in lat/lng is exact enough at human scales (segment ≪ 1° of arc).
        val lat = (segment.from.latitude * remain + segment.to.latitude * into) / total
        val lng = (segment.from.longitude * remain + segment.to.longitude * into) / total
        return doubleArrayOf(lat, lng)
    }

    private fun bearingAt(s: Double): Double {
        for (i in segments.indices) {
            if (s < cumulativeStart[i] + segments[i].lengthMeters || i == segments.lastIndex) {
                return segments[i].bearingDegrees
            }
        }
        return segments.last().bearingDegrees
    }

    /** GPS courses slew; they never snap. Cap the per-tick course change. */
    private fun slewBearing(current: Double, target: Double): Double {
        val delta = GeoMath.bearingDeltaDegrees(current, target)
        val maxStep = spec.maxTurnRateDegPerSec * spec.tickSeconds
        val step = delta.coerceIn(-maxStep, maxStep)
        return GeoMath.normalizeBearing(current + step)
    }

    /** Uniformly distributed offset inside the jitter disc (radius ∝ √u keeps it uniform). */
    private fun jitterOffset(latitude: Double): DoubleArray {
        if (jitterRadiusMeters <= 0.0) return doubleArrayOf(0.0, 0.0)
        val radius = jitterRadiusMeters * sqrt(random.nextDouble())
        val theta = random.nextDouble() * 2.0 * Math.PI
        val metersNorth = radius * cos(theta)
        val metersEast = radius * sin(theta)
        val latDegrees = metersNorth / METERS_PER_DEGREE_LAT
        val lngDegrees = metersEast /
            (METERS_PER_DEGREE_LAT * cos(Math.PI / 180.0 * latitude).coerceAtLeast(0.01))
        return doubleArrayOf(latDegrees, lngDegrees)
    }

    private fun addSpeedNoise(profileSpeed: Double): Double {
        if (profileSpeed <= 0.0) return 0.0
        val noise = SPEED_NOISE_FRACTION * (random.nextDouble() * 2.0 - 1.0)
        return (profileSpeed * (1.0 + noise)).coerceAtLeast(0.0)
    }

    companion object {
        private const val SPEED_NOISE_FRACTION = 0.03
        private const val METERS_PER_DEGREE_LAT = 111_320.0
        private const val LOOKAHEAD_METERS = 0.25

        /**
         * Remaining distance below which the next tick snaps to arrival. Above this the decel
         * profile still produces a full-speed step, so the snap only ever eats sub-meter slack.
         */
        private const val ARRIVAL_SNAP_METERS = 0.5

        /** Drain the player: every emission until (and including) arrival. */
        fun interpolateAll(spec: RouteSpec): List<TrajectoryPoint> {
            val player = RoutePlayer(spec)
            val points = mutableListOf<TrajectoryPoint>()
            generateSequence { player.tick() }.forEach { points += it }
            return points
        }
    }
}
