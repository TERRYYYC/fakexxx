package name.caiyao.fakegps.motion

/**
 * Publish-time decision: WHICH route (if any) goes into the payload's `route` object.
 *
 * Sources, in priority order:
 *  a) the effective profile's own `route_waypoints_json` (independent route CSV import);
 *  b) 计划相邻行合成 — the ordered plan rows (id ASC), the DEFAULT source. With the motion
 *     module on, the plan stops being N static teleports and becomes one continuous polyline.
 *
 * Gate: `motionEnabled == false` → null, and the writer emits NO `route` key at all — that is
 * the "motion off = byte-identical legacy payload" guarantee. A route that cannot reach two
 * usable waypoints is also null: one point cannot be interpolated, so playback falls back to
 * the plain static profile.
 */
object RoutePublishPolicy {

    /** Minimal projection of one plan row — everything the policy needs from the cursor. */
    data class PlanRow(
        val id: Long,
        val latitude: Double?,
        val longitude: Double?,
        val speedMps: Double? = null,
        val routeWaypointsJson: String? = null,
    )

    fun build(
        motionEnabled: Boolean,
        activeProfileId: Long?,
        planRows: List<PlanRow>,
    ): RoutePayload? {
        if (!motionEnabled) return null

        val activeRow = planRows.firstOrNull { it.id == activeProfileId }
        val explicit = RoutePayload.parseWaypoints(activeRow?.routeWaypointsJson)
        if (explicit != null) {
            return RoutePayload.fromWaypoints(
                source = RoutePayload.SOURCE_PROFILE,
                waypoints = explicit,
                cruiseSpeedMps = effectiveCruise(explicit, activeRow),
                seed = seedFor(activeProfileId, explicit.size, RoutePayload.SOURCE_PROFILE),
            )
        }

        val synthesized = RouteSynthesis.fromPlanRows(
            planRows.map { RouteSynthesis.PlanPoint(it.id, it.latitude, it.longitude, it.speedMps) },
        )
        if (synthesized.waypoints.size < RouteSpec.MIN_WAYPOINTS) return null
        return RoutePayload.fromWaypoints(
            source = RoutePayload.SOURCE_PLAN,
            waypoints = synthesized.waypoints,
            cruiseSpeedMps = effectiveCruise(synthesized.waypoints, activeRow),
            seed = seedFor(activeProfileId, synthesized.waypoints.size, RoutePayload.SOURCE_PLAN),
        )
    }

    /** A profile speed field overrides the default cruise; per-waypoint speeds stay in waypoints. */
    private fun effectiveCruise(
        waypoints: List<RouteWaypoint>,
        activeRow: PlanRow?,
    ): Double {
        val waypointOverride = waypoints.mapNotNull { it.speedMps }.average().takeIf {
            waypoints.any { w -> w.speedMps != null }
        }
        val rowSpeed = activeRow?.speedMps
        return when {
            waypointOverride != null && rowSpeed != null -> (waypointOverride + rowSpeed) / 2.0
            waypointOverride != null -> waypointOverride
            rowSpeed != null -> rowSpeed.coerceIn(RouteSpec.MIN_SPEED_MPS, RouteSpec.MAX_SPEED_MPS)
            else -> RouteSpec.DEFAULT_CRUISE_SPEED_MPS
        }.coerceIn(RouteSpec.MIN_SPEED_MPS, RouteSpec.MAX_SPEED_MPS)
    }

    /** Stable across publishes: a republish mid-route must not change the noise pattern. */
    internal fun seedFor(activeProfileId: Long?, waypointCount: Int, source: String): Long {
        val base = (activeProfileId ?: 0L) * 1_000_003L + waypointCount * 97L
        return base + if (source == RoutePayload.SOURCE_PROFILE) 1L else 0L
    }
}
