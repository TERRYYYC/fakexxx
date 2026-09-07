package name.caiyao.fakegps.motion

import kotlin.math.abs
import kotlin.math.sin

/**
 * P3.2 运动传感器合成模型 — the PURE half of the Sensors hook group.
 *
 * The anti-detection core of the motion chain: 步频 × 步幅 ≈ GPS 速度 (v = f × stride). The
 * step counter, the step detector and the accelerometer synthesis MUST be driven by the SAME
 * trajectory the location interpolator emits — two independent "plausible" sources that
 * disagree with each other are WORSE than one honest static value, because a pedometer × GPS
 * cross-check is a standard anti-cheat correlation.
 *
 * This class holds only the decision math (steps-per-tick, acceleration samples). The Android
 * half (SensorManager.registerListener / onSensorChanged delivery inside the target process)
 * is the hook group `Sensors` owned by the `motion` module in [name.caiyao.fakegps.config.SpoofModules].
 * Registering that group is deliberately NOT done in this thread: P3.2 ships the model + tests,
 * the hook registration lands with device-only verification (root + Vector machine).
 */
class MotionSensorModel(
    private val spec: RouteSpec,
    private val strideMeters: Double = DEFAULT_STRIDE_METERS,
) {
    init {
        require(strideMeters.isFinite() && strideMeters in 0.4..1.2) {
            "stride must be within 0.4..1.2 m (human walking range)"
        }
    }

    /** Walking cadence (steps/s) implied by the trajectory speed at [speedMps]. */
    fun cadenceHz(speedMps: Double): Double {
        if (speedMps <= 0.0) return 0.0
        return speedMps / strideMeters
    }

    /** Steps accumulated over the whole route — the TYPE_STEP_COUNTER total. */
    fun totalSteps(): Int {
        val player = RoutePlayer(spec)
        var steps = 0
        while (!player.completed) {
            val point = player.tick() ?: break
            steps += stepsForTick(point.speedMps)
        }
        return steps
    }

    /** Steps emitted during one 1 Hz tick at [speedMps] (fractional steps accumulate per call). */
    fun stepsForTick(speedMps: Double): Int {
        val exact = cadenceHz(speedMps) * spec.tickSeconds
        val floor = exact.toInt()
        carryFraction += exact - floor
        if (carryFraction >= 1.0) {
            carryFraction -= 1.0
            return floor + 1
        }
        return floor
    }

    private var carryFraction = 0.0

    /**
     * Synthetic TYPE_ACCELEROMETER magnitude (m/s²) sampled at [sampleRateHz] over the 1 Hz
     * fix [point]: gravity + a sinusoid at the walking cadence. A pedometer algorithm fed this
     * signal must find peaks at [cadenceHz] — that is the v = f × stride coherence.
     */
    fun accelerationSample(
        point: TrajectoryPoint,
        phaseSeconds: Double,
        sampleRateHz: Double = DEFAULT_SAMPLE_RATE_HZ,
    ): Double {
        val cadence = cadenceHz(point.speedMps)
        val gravity = 9.81
        if (cadence <= 0.0) return gravity
        val amplitude = ACCEL_AMPLITUDE_MPS2
        return gravity + amplitude * sin(2.0 * Math.PI * cadence * phaseSeconds)
    }

    /**
     * Cross-consistency assertion used by tests AND (later) by the Auto-side observer: over a
     * window, steps × stride must reconstruct the travelled distance within [toleranceFraction].
     */
    fun stepsDistanceConsistency(
        points: List<TrajectoryPoint>,
        toleranceFraction: Double = 0.15,
    ): Boolean {
        var steps = 0.0
        for (point in points) {
            steps += cadenceHz(point.speedMps) * spec.tickSeconds
        }
        val stepsDistance = steps * strideMeters
        val gpsDistance = points.size * spec.tickSeconds *
            points.map { it.speedMps }.average()
        if (gpsDistance <= 0.0) return true
        return abs(stepsDistance - gpsDistance) / gpsDistance <= toleranceFraction
    }

    companion object {
        const val DEFAULT_STRIDE_METERS = 0.72
        const val DEFAULT_SAMPLE_RATE_HZ = 50.0

        /** Peak of the gait sinusoid around gravity (1.5 m/s² ≈ a brisk walk). */
        const val ACCEL_AMPLITUDE_MPS2 = 1.5
    }
}
