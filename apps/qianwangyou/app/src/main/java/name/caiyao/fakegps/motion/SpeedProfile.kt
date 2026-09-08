package name.caiyao.fakegps.motion

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 1-D speed profile along the route path: 起步加速 → 巡航（弯道降速）→ 减速停.
 *
 * Built on a distance grid because the grid formulation makes the two kinematic constraints
 * locally checkable and therefore testable: a forward pass caps acceleration
 * (v[i] ≤ √(v[i-1]² + 2·a·ds)) and a backward pass caps braking
 * (v[i] ≤ √(v[i+1]² + 2·d·ds)). Sharp turns inject a local speed LIMIT before both passes,
 * so the vehicle realistically slows into corners instead of slewing the bearing at cruise.
 */
internal class SpeedProfile private constructor(
    private val gridStepMeters: Double,
    private val speeds: DoubleArray,
    /** True polyline length; the grid covers up to (nodeCount-1)·ds ≥ this. */
    val totalLengthMeters: Double,
) {
    /** Profile speed at path distance [s] (clamped to [0, totalLength]). */
    fun speedAt(s: Double): Double {
        if (s <= 0) return speeds[0]
        if (s >= totalLengthMeters) return speeds.last()
        val fractional = s / gridStepMeters
        val index = fractional.toInt().coerceAtMost(speeds.lastIndex - 1)
        val alpha = fractional - index
        return speeds[index] + (speeds[index + 1] - speeds[index]) * alpha
    }

    companion object {
        /** Grid resolution: ~1 m, bounded so very long routes stay within memory. */
        private const val TARGET_GRID_STEP_METERS = 1.0
        private const val MAX_GRID_NODES = 200_000

        /** Turn-angle thresholds and the local caps they apply. */
        internal const val SHARP_TURN_DEGREES = 60.0
        internal const val MODERATE_TURN_DEGREES = 30.0
        internal const val SHARP_TURN_SPEED_MPS = 3.0
        internal const val MODERATE_TURN_SPEED_FRACTION = 0.6

        fun build(spec: RouteSpec): SpeedProfile {
            val segments = spec.segments()
            require(segments.isNotEmpty()) { "route has no traversable segments" }

            val totalLength = segments.sumOf { it.lengthMeters }
            val gridStep = minOf(
                TARGET_GRID_STEP_METERS,
                totalLength / 4.0,
            ).coerceAtLeast(totalLength / MAX_GRID_NODES)

            val nodeCount = (totalLength / gridStep).toInt() + 2
            val speeds = DoubleArray(nodeCount) { spec.cruiseSpeedMps }
            speeds[0] = 0.0
            speeds[nodeCount - 1] = 0.0

            applyTurnLimits(spec, segments, gridStep, speeds)

            // Backward pass: must be able to brake to the NEXT node's speed.
            for (i in nodeCount - 2 downTo 0) {
                val next = speeds[i + 1]
                speeds[i] = minOf(speeds[i], sqrt(next * next + 2 * spec.decelMps2 * gridStep))
            }
            // Forward pass: must be able to accelerate from the PREVIOUS node's speed.
            for (i in 1 until nodeCount) {
                val previous = speeds[i - 1]
                speeds[i] = minOf(speeds[i], sqrt(previous * previous + 2 * spec.accelMps2 * gridStep))
            }
            // A completed route ALWAYS ends at rest; the passes must not lift the endpoint.
            speeds[nodeCount - 1] = 0.0

            return SpeedProfile(gridStep, speeds, totalLength)
        }

        /**
         * Slow down INTO waypoints where the route changes direction. The turn angle is the
         * absolute change between incoming and outgoing segment bearings at an interior vertex.
         */
        private fun applyTurnLimits(
            spec: RouteSpec,
            segments: List<RouteSpec.Segment>,
            gridStep: Double,
            speeds: DoubleArray,
        ) {
            var cumulative = 0.0
            for (i in 0 until segments.size - 1) {
                cumulative += segments[i].lengthMeters
                val turn = abs(
                    GeoMath.bearingDeltaDegrees(
                        segments[i].bearingDegrees,
                        segments[i + 1].bearingDegrees,
                    ),
                )
                val cap = when {
                    turn >= SHARP_TURN_DEGREES -> minOf(spec.cruiseSpeedMps, SHARP_TURN_SPEED_MPS)
                    turn >= MODERATE_TURN_DEGREES ->
                        minOf(
                            spec.cruiseSpeedMps,
                            spec.cruiseSpeedMps * MODERATE_TURN_SPEED_FRACTION,
                        )
                    else -> continue
                }
                dipLocal(cap, (cumulative / gridStep).toInt(), speeds, gridStep)
            }
        }

        /** Widen one turn cap over a small window so the passes see a smooth dip, not a spike. */
        private fun dipLocal(cap: Double, center: Int, speeds: DoubleArray, gridStep: Double) {
            val window = (cap / gridStep).toInt().coerceAtLeast(1) + 1
            for (j in -window..window) {
                val index = center + j
                if (index in 1..speeds.lastIndex - 1) {
                    speeds[index] = minOf(speeds[index], cap)
                }
            }
        }
    }
}
