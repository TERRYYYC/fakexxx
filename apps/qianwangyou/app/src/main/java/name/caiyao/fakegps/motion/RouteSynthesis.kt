package name.caiyao.fakegps.motion

/**
 * 计划相邻行合成（来源 b，默认）：the ordered plan (profile rows, `id ASC`) IS the route.
 *
 * Today a plan is consumed as N independent static points — the target app sees a teleport
 * between consecutive rows. With the motion module ON, the same rows become a continuous
 * polyline: row k drives to row k+1 under the speed profile, so trajectory continuity,
 * speed plausibility and (P3.2) cadence cross-checks all see one coherent motion.
 *
 * Rows without coordinates (cellular-only profiles) are skipped; fewer than two usable rows
 * means there is nothing to interpolate and the caller must fall back to the static point.
 */
object RouteSynthesis {

    /** Minimal projection of one plan row — everything synthesis needs from ProfileEntity. */
    data class PlanPoint(
        val profileId: Long,
        val latitude: Double?,
        val longitude: Double?,
        val speedMps: Double? = null,
    )

    data class Synthesized(val waypoints: List<RouteWaypoint>, val sourceProfileIds: List<Long>)

    fun fromPlanRows(rows: List<PlanPoint>): Synthesized {
        val waypoints = mutableListOf<RouteWaypoint>()
        val ids = mutableListOf<Long>()
        for (row in rows) {
            val lat = row.latitude ?: continue
            val lng = row.longitude ?: continue
            if (!lat.isFinite() || !lng.isFinite()) continue
            waypoints += RouteWaypoint(lat, lng, row.speedMps)
            ids += row.profileId
        }
        return Synthesized(waypoints, ids)
    }
}
