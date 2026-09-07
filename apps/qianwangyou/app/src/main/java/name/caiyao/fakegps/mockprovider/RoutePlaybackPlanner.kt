package name.caiyao.fakegps.mockprovider

import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.motion.RoutePlayer
import name.caiyao.fakegps.motion.RouteSpec
import name.caiyao.fakegps.motion.TrajectoryPoint

/**
 * P3.1 delivery-side resolution: which route (if any) does the System Mock lane play?
 *
 * The route decision was already made at PUBLISH time ([name.caiyao.fakegps.motion.RoutePublishPolicy]
 * through ConfigPrefsSync); the scheduler simply plays what was published — the payload stays the
 * single contract between the app and its delivery lanes. A payload without a playable route
 * resolves to [None] and the session behaves exactly like the historical static-point session.
 */
sealed interface RoutePlaybackResolution {
    data class Ready(val spec: RouteSpec) : RoutePlaybackResolution
    data object None : RoutePlaybackResolution
}

object RoutePlaybackPlanner {

    fun resolve(published: PublishedConfig?): RoutePlaybackResolution =
        when (val spec = published?.route?.let { payload ->
            runCatching { payload.toRouteSpec() }.getOrNull()
        }) {
            null -> RoutePlaybackResolution.None
            else -> RoutePlaybackResolution.Ready(spec)
        }

    /** A fresh player for the published route, or null for a static session. */
    fun player(published: PublishedConfig?): RoutePlayer? =
        when (val resolution = resolve(published)) {
            is RoutePlaybackResolution.Ready -> RoutePlayer(resolution.spec)
            RoutePlaybackResolution.None -> null
        }
}

/**
 * Trajectory fix → system test provider fix. The base session config contributes the altitude
 * (a route carries no vertical profile yet; nulling it would let the REAL device altitude leak
 * through, which is a worse inconsistency than a constant). Coordinates are clamped into the
 * provider's legal domain so one jittered fix can never fail the whole session.
 */
fun TrajectoryPoint.toMockLocationConfig(base: MockLocationConfig): MockLocationConfig =
    MockLocationConfig(
        latitude = latitude.coerceIn(-90.0, 90.0),
        longitude = longitude.coerceIn(-180.0, 180.0),
        accuracyMeters = accuracyMeters.toFloat().coerceAtLeast(0.1f),
        altitudeMeters = base.altitudeMeters,
    )
