package com.example.cellrebelauto.ui.dashboard.v2

/**
 * T7v2 §A1-v2 #3 — the embedded plan map's pure math (no Android, no map SDK).
 *
 * Two layers:
 *  1. [PlanMapProjector] — equal-ratio lat/lng → canvas projection. Longitude
 *     is compressed by cos(mid-lat) so shapes keep their real-world aspect
 *     (经纬比不变形); the content is fitted inside the view with padding and
 *     centered. Degenerate inputs (single point / zero span) are explicit.
 *  2. [MapTransform] — the gesture transform: pinch zooms around the centroid
 *     with scale clamped to 0.5x..20x; pan and zoom are clamped so the content
 *     can never leave the view (content smaller than the view stays centered).
 *
 * The Compose Canvas composable only applies these — all decisions live here
 * and are pinned by PlanMapGeometryTest.
 */
object MapTransform {

    const val MIN_SCALE = 0.5f
    const val MAX_SCALE = 20f

    /** screen = scale * world + offset. */
    data class Transform(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    fun toScreen(t: Transform, wx: Float, wy: Float): Pair<Float, Float> =
        (t.scale * wx + t.offsetX) to (t.scale * wy + t.offsetY)

    fun toWorld(t: Transform, sx: Float, sy: Float): Pair<Float, Float> =
        ((sx - t.offsetX) / t.scale) to ((sy - t.offsetY) / t.scale)

    /**
     * Zoom by [zoom] keeping the world point under the centroid stationary:
     * the screen point of that world coordinate before the pinch must equal
     * the screen point after — this is what makes the map zoom AROUND the
     * fingers instead of around the origin. Scale is clamped to
     * [MIN_SCALE]..[MAX_SCALE] BEFORE the offset is solved, so the anchor
     * stays exact for the achieved scale.
     */
    fun pinch(t: Transform, centroidX: Float, centroidY: Float, zoom: Float): Transform {
        val newScale = clampScale(t.scale * zoom)
        val ratio = if (t.scale == 0f) 1f else newScale / t.scale
        return Transform(
            scale = newScale,
            offsetX = centroidX - (centroidX - t.offsetX) * ratio,
            offsetY = centroidY - (centroidY - t.offsetY) * ratio,
        )
    }

    /**
     * Translate by (dx, dy), clamped so the content can never leave the view:
     * with content larger than the view the offset stays within
     * [viewW - contentW*scale, 0]; with content smaller than the view the
     * content is kept centered (no empty-side flinging).
     */
    fun pan(
        t: Transform, dx: Float, dy: Float,
        contentW: Float, contentH: Float, viewW: Float, viewH: Float,
    ): Transform = Transform(
        scale = t.scale,
        offsetX = clampAxis(t.offsetX + dx, contentW * t.scale, viewW),
        offsetY = clampAxis(t.offsetY + dy, contentH * t.scale, viewH),
    )

    private fun clampAxis(offset: Float, contentExtent: Float, viewExtent: Float): Float =
        if (contentExtent <= viewExtent) {
            (viewExtent - contentExtent) / 2f
        } else {
            offset.coerceIn(viewExtent - contentExtent, 0f)
        }
}

object PlanMapProjector {

    data class GeoPoint(val lat: Double, val lng: Double)

    data class GeoBounds(
        val minLat: Double, val maxLat: Double,
        val minLng: Double, val maxLng: Double,
    )

    fun boundsOf(points: List<GeoPoint>): GeoBounds? {
        if (points.isEmpty()) return null
        return GeoBounds(
            minLat = points.minOf { it.lat },
            maxLat = points.maxOf { it.lat },
            minLng = points.minOf { it.lng },
            maxLng = points.maxOf { it.lng },
        )
    }

    /** Canvas fit for the bounds; null only when bounds is null. */
    data class Fit(val scale: Double, val tx: Double, val ty: Double, val midLatCos: Double)

    /** cos(mid-lat): the longitude compression that keeps shapes true. */
    private fun midLatCos(bounds: GeoBounds): Double =
        kotlin.math.cos(Math.toRadians((bounds.minLat + bounds.maxLat) / 2.0)).coerceIn(0.1, 1.0)

    /**
     * Equal-ratio fit: world units are degrees with lng compressed by
     * cos(mid-lat); the whole content is scaled to fit inside the padded view
     * (never stretched) and centered on the smaller axis.
     */
    fun fit(bounds: GeoBounds, viewW: Double, viewH: Double, paddingPx: Double): Fit? {
        val cos = midLatCos(bounds)
        // World extents in "aspect-true" units (lat degrees vertically).
        val worldW = (bounds.maxLng - bounds.minLng) * cos
        val worldH = (bounds.maxLat - bounds.minLat)
        val availW = (viewW - 2 * paddingPx).coerceAtLeast(1.0)
        val availH = (viewH - 2 * paddingPx).coerceAtLeast(1.0)
        val scale: Double
        val tx: Double
        val ty: Double
        if (worldW <= EPS && worldH <= EPS) {
            // Degenerate (single point): fixed scale, dead center.
            scale = 1.0
            tx = viewW / 2.0
            ty = viewH / 2.0
        } else {
            scale = minOf(availW / worldW.coerceAtLeast(EPS), availH / worldH.coerceAtLeast(EPS))
            tx = paddingPx + (availW - worldW * scale) / 2.0
            ty = paddingPx + (availH - worldH * scale) / 2.0
        }
        return Fit(scale = scale, tx = tx, ty = ty, midLatCos = cos)
    }

    private const val EPS = 1e-12

    /** North up, east right: y grows downward from the max-lat edge. */
    fun project(lat: Double, lng: Double, bounds: GeoBounds, fit: Fit): Pair<Double, Double> {
        val wx = (lng - bounds.minLng) * fit.midLatCos
        val wy = bounds.maxLat - lat
        return (fit.tx + wx * fit.scale) to (fit.ty + wy * fit.scale)
    }
}

/** The three plan-point states (spec v2 colors: done=green, active=blue, pending=grey hollow). */
enum class MapPointState { DONE, ACTIVE, PENDING }

object PlanMapPoints {

    /** Execution-order row input; the ViewModel maps LocationTask onto this. */
    data class Row(
        val id: Long,
        val csvRow: Int,
        val latitude: Double,
        val longitude: Double,
        val status: String,
        val requiredSuccesses: Int,
    )

    /**
     * One probe-measured fix (探针实测位置) for a plan row, straight from the
     * durable observation records (evidence-only display projection).
     * # 实测修复：只读观察投影，绝不入信任/入账路径
     */
    data class MeasuredFix(val latitude: Double, val longitude: Double)

    data class MapPoint(
        val taskId: Long,
        val csvRow: Int,
        val latitude: Double,
        val longitude: Double,
        val state: MapPointState,
        // ---- 实测层（探针观察投影；null = 无观察数据，只画计划位） ----
        val measuredLat: Double? = null,
        val measuredLng: Double? = null,
        /** 实测 vs 计划是否在 CoordinateGuard 容差内；仅实测坐标非空时有意义。 */
        val measuredMatched: Boolean = false,
    )

    /**
     * The measured-vs-plan match verdict — the EXACT CoordinateGuard assertion
     * (per-axis |Δ| ≤ TOLERANCE_DEG) reused verbatim so the map's 匹配/不匹配
     * means precisely what the quota-mint guard enforces. No second threshold.
     * # 匹配判定 = 逐字复用 CoordinateGuard.violation，零新阈值
     */
    fun measuredMatched(
        expectedLat: Double,
        expectedLng: Double,
        fix: MeasuredFix,
    ): Boolean = com.example.cellrebelauto.automation.selfheal.CoordinateGuard.violation(
        expectedLat = expectedLat,
        expectedLng = expectedLng,
        observed = listOf(Triple("MEASURED", fix.latitude, fix.longitude)),
    ) == null

    /**
     * DISPLAY-ONLY great-circle distance in meters (haversine, mean Earth
     * radius) — consumed by [maxDeviationMeters] for the legend's
     * "最大偏差" badge. KB-8 stays intact: Auto still runs NO haversine in
     * the trust path (distance-to-intent remains provider-exclusive); this
     * projection is never a trust input.
     * # 偏差距离（米，haversine）：仅展示层图例；KB-8 信任路径仍然零本地测距
     */
    fun haversineMeters(
        lat1: Double, lng1: Double,
        lat2: Double, lng2: Double,
    ): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLng / 2) * Math.sin(dLng / 2)
        return 2 * r * Math.asin(Math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * 最大偏差（米）：所有带实测数据的点的"实测-计划" haversine 距离取最大；
     * 无任何实测数据时 null。图例/角标"最大偏差 X m"的唯一数据源
     * （[haversineMeters] 的消费点）。# 展示层最大偏差统计：仅图例，不入信任路径
     */
    fun maxDeviationMeters(points: List<MapPoint>): Double? = points.mapNotNull { p ->
        val mLat = p.measuredLat ?: return@mapNotNull null
        val mLng = p.measuredLng ?: return@mapNotNull null
        haversineMeters(p.latitude, p.longitude, mLat, mLng)
    }.maxOrNull()

    fun resolvePointState(status: String, trusted: Int, required: Int, isCurrent: Boolean): MapPointState =
        when {
            // Status flipped by the engine, or the trusted quota already met —
            // either is real, verifiable completion.
            status == "completed" -> MapPointState.DONE
            required > 0 && trusted >= required -> MapPointState.DONE
            isCurrent -> MapPointState.ACTIVE
            else -> MapPointState.PENDING
        }

    /**
     * Projects plan rows to map points in execution order (csvRow ASC).
     * (0,0) rows are parse artifacts and are skipped so they cannot drag the
     * bounds to the Gulf of Guinea.
     *
     * [measured] (optional, default empty) overlays the probe-measured fixes
     * keyed by taskId; a fix arrives later than the plan rows without any
     * ordering guarantee (DB async flow), so a missing entry simply leaves the
     * point plan-only. # 实测数据异步晚到：缺条目 = 只画计划位
     */
    fun project(
        tasks: List<Row>,
        trustedCounts: Map<Long, Int>,
        currentCsvRow: Int?,
        measured: Map<Long, MeasuredFix> = emptyMap(),
    ): List<MapPoint> = tasks
        .sortedBy { it.csvRow }
        .filterNot { it.latitude == 0.0 && it.longitude == 0.0 }
        .map { row ->
            val fix = measured[row.id]
            MapPoint(
                taskId = row.id,
                csvRow = row.csvRow,
                latitude = row.latitude,
                longitude = row.longitude,
                state = resolvePointState(
                    status = row.status,
                    trusted = trustedCounts[row.id] ?: 0,
                    required = row.requiredSuccesses,
                    isCurrent = currentCsvRow != null && row.csvRow == currentCsvRow,
                ),
                measuredLat = fix?.latitude,
                measuredLng = fix?.longitude,
                measuredMatched = fix != null && measuredMatched(row.latitude, row.longitude, fix),
            )
        }
}

/**
 * One row of the CANVAS card's world-coordinate layer: the plan point's world
 * position plus (when a measured fix exists) its measured fix's world position
 * — both re-based onto the SAME content origin.
 * # 一行 = 计划世界坐标 +（可选）实测世界坐标，同原点
 */
data class PlanMeasuredWorld(
    val planX: Float,
    val planY: Float,
    val measuredX: Float? = null,
    val measuredY: Float? = null,
)

/**
 * The CANVAS card's shared projection step ([RunDashboardScreen] PlanMapCard).
 * Plan points AND their measured fixes go through the same
 * [PlanMapProjector.fit] and are re-based onto the SAME content origin — the
 * top-left of the projected PLAN bounding box (min plan pixel), exactly the
 * translation the plan layer has always applied. Raw [PlanMapProjector.project]
 * output is a viewport pixel coordinate (includes fit.tx/ty padding +
 * centering); feeding one layer raw viewport pixels while the other subtracts
 * the origin shifts the overlay by exactly that origin — the #185 F1 bug this
 * pure function pins shut: a zero-deviation fix MUST land exactly on its plan
 * point's world coordinates (pinned by PlanMapGeometryTest).
 * # 实测层与计划层同一投影、同一内容原点：零偏差实测==计划世界坐标（F1 回归钉）
 */
object PlanMapWorld {

    fun project(
        points: List<PlanMapPoints.MapPoint>,
        bounds: PlanMapProjector.GeoBounds,
        fit: PlanMapProjector.Fit,
    ): List<PlanMeasuredWorld> {
        if (points.isEmpty()) return emptyList()
        val planPixels = points.map {
            val (x, y) = PlanMapProjector.project(it.latitude, it.longitude, bounds, fit)
            x.toFloat() to y.toFloat()
        }
        // 内容原点 = 计划像素包围盒左上角——两个图层共用；实测层缺这次平移即整体错位。
        val originX = planPixels.minOf { it.first }
        val originY = planPixels.minOf { it.second }
        return planPixels.mapIndexed { index, (px, py) ->
            val mLat = points[index].measuredLat
            val mLng = points[index].measuredLng
            if (mLat == null || mLng == null) {
                PlanMeasuredWorld(planX = px - originX, planY = py - originY)
            } else {
                val (mx, my) = PlanMapProjector.project(mLat, mLng, bounds, fit)
                PlanMeasuredWorld(
                    planX = px - originX,
                    planY = py - originY,
                    measuredX = mx.toFloat() - originX,
                    measuredY = my.toFloat() - originY,
                )
            }
        }
    }
}
