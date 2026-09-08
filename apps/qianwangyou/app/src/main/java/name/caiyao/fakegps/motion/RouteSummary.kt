package name.caiyao.fakegps.motion

/**
 * Read-only route facts for the profile editor's "路线" card (UI 最小面): waypoint count,
 * polyline length and the speed-profile play time at 1 Hz. Pure projection of the profile's
 * `route_waypoints_json`; null when the column is absent/unparseable (= single-point profile).
 */
data class RouteSummary(
    val waypointCount: Int,
    val lengthMeters: Double,
    val estimatedDurationSeconds: Double,
) {
    companion object {
        fun of(routeWaypointsJson: String?): RouteSummary? {
            val waypoints = RoutePayload.parseWaypoints(routeWaypointsJson) ?: return null
            val spec = runCatching {
                RouteSpec(waypoints = waypoints)
            }.getOrNull() ?: return null
            if (!spec.isPlayable()) return null
            val duration = RoutePlayer.interpolateAll(spec).lastOrNull()?.elapsedSeconds ?: 0.0
            return RouteSummary(
                waypointCount = waypoints.size,
                lengthMeters = spec.totalLengthMeters(),
                estimatedDurationSeconds = duration,
            )
        }
    }
}
