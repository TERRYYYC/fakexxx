package name.caiyao.fakegps.motion

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * WGS84 geodesics for the route playback engine (P3.1).
 *
 * Pure Kotlin — no Android types — so the trajectory assertions (spacing, bearing rate,
 * speed-vs-displacement) are plain JVM tests. Accuracy is deliberately modest: waypoints are
 * human-scale (meters to kilometers apart), where the spherical haversine model is far inside
 * GPS noise.
 */
object GeoMath {

    const val EARTH_RADIUS_METERS = 6_371_008.8

    private const val DEG_TO_RAD = Math.PI / 180.0
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /** Great-circle distance in meters (haversine). */
    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dPhi = (lat2 - lat1) * DEG_TO_RAD
        val dLambda = (lng2 - lng1) * DEG_TO_RAD
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, normalized to [0, 360). */
    fun bearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val phi1 = lat1 * DEG_TO_RAD
        val phi2 = lat2 * DEG_TO_RAD
        val dLambda = (lng2 - lng1) * DEG_TO_RAD
        val y = sin(dLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return normalizeBearing(atan2(y, x) * RAD_TO_DEG)
    }

    /** Point at [distanceMeters] from (lat, lng) along [bearingDegrees]. Returns [lat, lng]. */
    fun destination(
        lat: Double,
        lng: Double,
        bearingDegrees: Double,
        distanceMeters: Double,
    ): DoubleArray {
        val delta = distanceMeters / EARTH_RADIUS_METERS
        val phi1 = lat * DEG_TO_RAD
        val lambda1 = lng * DEG_TO_RAD
        val theta = bearingDegrees * DEG_TO_RAD
        // Standard "destination point given distance and bearing" formulation:
        //   φ2 = asin(sin φ1 ⋅ cos δ + cos φ1 ⋅ sin δ ⋅ cos θ)
        //   λ2 = λ1 + atan2(sin θ ⋅ sin δ ⋅ cos φ1, cos δ − sin φ1 ⋅ sin φ2)
        val latRad = asin(sin(phi1) * cos(delta) + cos(phi1) * sin(delta) * cos(theta))
        val lngRad = lambda1 + atan2(
            sin(theta) * sin(delta) * cos(phi1),
            cos(delta) - sin(phi1) * sin(latRad),
        )
        return doubleArrayOf(latRad * RAD_TO_DEG, normalizeLng(lngRad * RAD_TO_DEG))
    }

    fun normalizeBearing(degrees: Double): Double {
        val normalized = degrees % 360.0
        return if (normalized < 0) normalized + 360.0 else normalized
    }

    /** Shortest signed rotation from [from] to [to], in (-180, 180]. */
    fun bearingDeltaDegrees(from: Double, to: Double): Double {
        val delta = normalizeBearing(to - from)
        return if (delta > 180.0) delta - 360.0 else delta
    }

    private fun normalizeLng(degrees: Double): Double = when {
        degrees > 180.0 -> degrees - 360.0
        degrees < -180.0 -> degrees + 360.0
        else -> degrees
    }
}
