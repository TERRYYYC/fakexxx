package com.example.cellrebelauto.ui.dashboard.v2

import kotlin.math.abs

/**
 * Tile map (T-tilemap, 2026-09-08) — Web Mercator world pixels, ported 1:1
 * from the high-fidelity prototype `ui-hifi/v3/index.html`:
 *
 * ```
 * const lngToX=(lng,z)=>((lng+180)/360)*TS*Math.pow(2,z);
 * const latToY=(lat,z)=>{const s=Math.sin(lat*Math.PI/180);
 *   return (0.5-Math.log((1+s)/(1-s))/(4*Math.PI))*TS*Math.pow(2,z);};
 * ```
 *
 * plus `fitZoom` (the integer tile level whose world-pixel extents fit inside
 * the padded view, scanning maxZ→3 with a floor of 3) and `mapFSReset`'s
 * "+1, capped" fullscreen raise. Equivalence with the prototype JS is pinned
 * by PlanTileZoomTest — do NOT "improve" the math here without updating the
 * oracle.
 *
 * Used by [TilePlanMapCard] for the initial fit viewport; the existing
 * abstract canvas map ([PlanMapProjector]) keeps its own projection and is
 * untouched (offline fallback).
 *
 * # Web Mercator 纯函数：与原型 lngToX/latToY/fitZoom 语义逐行等价
 */
object WebMercator {

    /** Prototype `const TS=256`. */
    const val TILE_SIZE = 256.0

    /** Prototype floor of the fit scan (v3 MAPZ.min). */
    const val MIN_FIT_ZOOM = 3

    data class GeoPoint(val latitude: Double, val longitude: Double)

    /** World pixel extent at integer zoom [z]: TS·2^z. */
    fun worldSize(z: Int): Double = TILE_SIZE * Math.pow(2.0, z.toDouble())

    fun lngToX(lng: Double, z: Int): Double = ((lng + 180.0) / 360.0) * worldSize(z)

    fun latToY(lat: Double, z: Int): Double {
        val s = Math.sin(Math.toRadians(lat))
        return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * worldSize(z)
    }

    /** Exact inverse of [lngToX]. */
    fun xToLng(x: Double, z: Int): Double = x / worldSize(z) * 360.0 - 180.0

    /** Exact inverse of [latToY]: u = (0.5 − y/A)·4π with s = tanh(u/2). */
    fun yToLat(y: Double, z: Int): Double {
        val u = (0.5 - y / worldSize(z)) * 4 * Math.PI
        return Math.toDegrees(Math.asin(Math.tanh(u / 2.0)))
    }

    /**
     * Prototype `fitZoom`: the largest z ≤ [maxZoom] whose Mercator world
     * pixel extents of the bounds fit inside `(viewW - 2·padX, viewH - 2·padY)`,
     * scanning DOWN so detail wins; floors at [MIN_FIT_ZOOM] (continent-spanning
     * plans still render the whole world region). Empty input floors too.
     */
    fun fitZoom(
        points: List<GeoPoint>,
        viewW: Double, viewH: Double,
        padX: Double, padY: Double,
        maxZoom: Int,
    ): Int {
        if (points.isEmpty()) return MIN_FIT_ZOOM
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        for (z in maxZoom downTo MIN_FIT_ZOOM) {
            val w = lngToX(maxLng, z) - lngToX(minLng, z)
            val h = latToY(minLat, z) - latToY(maxLat, z)
            if (w <= viewW - 2 * padX && h <= viewH - 2 * padY) return z
        }
        return MIN_FIT_ZOOM
    }

    /**
     * Prototype `mapFSReset`: the fullscreen entry raises the fit one level,
     * capped at [maxZoom] (`z = Math.min(16, fitZoom(…)+1)` in v3).
     */
    fun fitZoomRaised(
        points: List<GeoPoint>,
        viewW: Double, viewH: Double,
        padX: Double, padY: Double,
        maxZoom: Int,
    ): Int = minOf(maxZoom, fitZoom(points, viewW, viewH, padX, padY, maxZoom) + 1)

    /**
     * Prototype `mapFrame`'s center: the mid world-pixel point of the bounds,
     * converted back to lat/lng (z-invariant; computed at z16 for determinism).
     */
    fun centerOf(points: List<GeoPoint>): GeoPoint? {
        if (points.isEmpty()) return null
        val z = 16
        val minLat = points.minOf { it.latitude }
        val maxLat = points.maxOf { it.latitude }
        val minLng = points.minOf { it.longitude }
        val maxLng = points.maxOf { it.longitude }
        val cx = (lngToX(minLng, z) + lngToX(maxLng, z)) / 2.0
        val cy = (latToY(maxLat, z) + latToY(minLat, z)) / 2.0
        return GeoPoint(latitude = yToLat(cy, z), longitude = xToLng(cx, z))
    }

    /** Degenerate plans (all points identical) must not NaN anywhere. */
    fun isDegenerate(points: List<GeoPoint>): Boolean {
        if (points.size < 2) return true
        val first = points.first()
        return points.all {
            abs(it.latitude - first.latitude) < 1e-12 && abs(it.longitude - first.longitude) < 1e-12
        }
    }
}

/**
 * Tile map (T-tilemap, 2026-09-08) — the STATE-DRIVEN dual-card selection.
 *
 * The run dashboard shows the real OSM tile map ONLY when every gate holds;
 * ANY miss degrades to the existing abstract canvas map (kept, never deleted):
 *
 *  - `tilesEnabled` — the persisted user switch (DataStore `map_tiles_enabled`);
 *  - `networkOnline` — CONNECTIVITY gate (无网即回退，原型同款断级行为);
 *  - `!tileLoadFailed` — the FIRST tile callback failure sticks for the
 *    session (cleared by re-enabling the switch or on network recovery).
 *
 * Pure and total: no time reads, no connectivity calls — the screen feeds the
 * three observed signals, this decides which card composes. Pinned by
 * TileMapCardPolicyTest.
 */
object TileMapCardPolicy {

    enum class PlanMapCard { TILES, CANVAS }

    fun decide(tilesEnabled: Boolean, networkOnline: Boolean, tileLoadFailed: Boolean): PlanMapCard =
        if (tilesEnabled && networkOnline && !tileLoadFailed) {
            PlanMapCard.TILES
        } else {
            PlanMapCard.CANVAS
        }
}
