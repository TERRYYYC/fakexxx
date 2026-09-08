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

    data class MapPoint(
        val taskId: Long,
        val csvRow: Int,
        val latitude: Double,
        val longitude: Double,
        val state: MapPointState,
    )

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
     */
    fun project(
        tasks: List<Row>,
        trustedCounts: Map<Long, Int>,
        currentCsvRow: Int?,
    ): List<MapPoint> = tasks
        .sortedBy { it.csvRow }
        .filterNot { it.latitude == 0.0 && it.longitude == 0.0 }
        .map { row ->
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
            )
        }
}
