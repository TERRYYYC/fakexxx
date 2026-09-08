package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tile map (T-tilemap, 2026-09-08) — the Web Mercator port oracle.
 *
 * The high-fidelity prototype `ui-hifi/v3/index.html` locates its OSM tiles
 * with three functions: `lngToX` / `latToY` (Web Mercator world pixels) and
 * `fitZoom` (the integer tile zoom level whose world-pixel extents fit inside
 * the padded view, scanning from maxZ down to a floor of 3). This test pins
 * the Kotlin port to the prototype SEMANTICS two ways:
 *  1. literal formula equivalence — the prototype JS is re-implemented here
 *     verbatim as the oracle, and both implementations must agree on real
 *     plan coordinates (Lviv→Kyiv) and edge coordinates;
 *  2. behavior pins — degenerate single-point plans, continent-spanning
 *     plans (the z=3 floor), and the fullscreen "+1" raise of `mapFSReset`.
 *
 * Killing mutations: any drift in the Mercator math or in the fit scan
 * (padding, the z>2 loop bound, the maxZ cap) breaks the oracle equality and
 * the behavior pins.
 *
 * # 瓦片地图 z 计算 oracle：与原型 lngToX/latToY/fitZoom 逐行等价
 */
class PlanTileZoomTest {

    /** The prototype's JS, ported verbatim — oracle ONLY, never imported by main. */
    private object Prototype {
        const val TS = 256.0

        fun lngToX(lng: Double, z: Double): Double = ((lng + 180.0) / 360.0) * TS * Math.pow(2.0, z)

        fun latToY(lat: Double, z: Double): Double {
            val s = Math.sin(lat * Math.PI / 180.0)
            return (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TS * Math.pow(2.0, z)
        }

        fun fitZoom(
            lat: List<Double>, lng: List<Double>,
            W: Double, H: Double, Px: Double, Py: Double, maxZ: Int,
        ): Int {
            val mnLat = lat.min()
            val mxLat = lat.max()
            val mnLng = lng.min()
            val mxLng = lng.max()
            for (z in maxZ downTo 3) {
                val w = lngToX(mxLng, z.toDouble()) - lngToX(mnLng, z.toDouble())
                val h = latToY(mnLat, z.toDouble()) - latToY(mxLat, z.toDouble())
                if (w <= W - 2 * Px && h <= H - 2 * Py) return z
            }
            return 3
        }
    }

    private val lvivToKyiv = listOf(
        WebMercator.GeoPoint(49.8397, 24.0297), // Lviv
        WebMercator.GeoPoint(50.4501, 30.5234), // Kyiv
    )

    // ---- Mercator formulas -------------------------------------------------------

    @Test
    fun `tile size and world size match the prototype constant TS`() {
        assertEquals(256.0, WebMercator.TILE_SIZE, 0.0)
        assertEquals(256.0, WebMercator.worldSize(0), 0.0)
        assertEquals(512.0, WebMercator.worldSize(1), 0.0)
        assertEquals(256.0 * Math.pow(2.0, 16.0), WebMercator.worldSize(16), 0.0)
    }

    @Test
    fun `lngToX agrees with the prototype on plan and edge coordinates`() {
        for (z in 3..17) {
            assertEquals(Prototype.lngToX(0.0, z.toDouble()), WebMercator.lngToX(0.0, z), 1e-9)
            assertEquals(Prototype.lngToX(24.0297, z.toDouble()), WebMercator.lngToX(24.0297, z), 1e-9)
            assertEquals(Prototype.lngToX(30.5234, z.toDouble()), WebMercator.lngToX(30.5234, z), 1e-9)
            // west/east edges of the world
            assertEquals(0.0, WebMercator.lngToX(-180.0, z), 1e-9)
            assertEquals(WebMercator.worldSize(z), WebMercator.lngToX(180.0, z), 1e-9)
        }
    }

    @Test
    fun `latToY agrees with the prototype on plan and edge coordinates`() {
        for (z in 3..17) {
            assertEquals(Prototype.latToY(0.0, z.toDouble()), WebMercator.latToY(0.0, z), 1e-9)
            assertEquals(Prototype.latToY(49.8397, z.toDouble()), WebMercator.latToY(49.8397, z), 1e-9)
            assertEquals(Prototype.latToY(50.4501, z.toDouble()), WebMercator.latToY(50.4501, z), 1e-9)
        }
        // equator is the vertical middle; Mercator lat limit ±85.0511… maps to the edges
        assertEquals(WebMercator.worldSize(5) / 2.0, WebMercator.latToY(0.0, 5), 1e-9)
        assertEquals(0.0, WebMercator.latToY(85.0511287798066, 5), 1e-6)
        assertEquals(WebMercator.worldSize(5), WebMercator.latToY(-85.0511287798066, 5), 1e-6)
    }

    @Test
    fun `latToY is north-up and symmetric like the prototype`() {
        val north = WebMercator.latToY(50.4501, 13)
        val south = WebMercator.latToY(49.8397, 13)
        // y grows DOWNWARD from the north edge (smaller y = further north)
        assertTrue(north < south)
        assertEquals(
            WebMercator.worldSize(13) - WebMercator.latToY(50.4501, 13),
            WebMercator.latToY(-50.4501, 13),
            1e-9,
        )
    }

    @Test
    fun `inverse projection round-trips the prototype coordinates`() {
        for (z in listOf(3, 8, 13, 16)) {
            for (p in lvivToKyiv + WebMercator.GeoPoint(0.0, 0.0) + WebMercator.GeoPoint(-40.0, 100.0)) {
                val x = WebMercator.lngToX(p.longitude, z)
                val y = WebMercator.latToY(p.latitude, z)
                assertEquals(p.longitude, WebMercator.xToLng(x, z), 1e-7)
                assertEquals(p.latitude, WebMercator.yToLat(y, z), 1e-7)
            }
        }
    }

    // ---- fitZoom (prototype semantics) -------------------------------------------

    @Test
    fun `fitZoom equals the prototype scan across the embedded and fullscreen view boxes`() {
        val views = listOf(
            // (W, H) — v3 embedded card and the fullscreen phone viewport
            341.0 to 150.0,
            393.0 to 811.0,
            600.0 to 400.0,
        )
        val pads = listOf(40.0 to 24.0, 60.0 to 80.0, 0.0 to 0.0)
        val plans = listOf(
            lvivToKyiv,
            // multi-point sweep with a third point off the diagonal
            lvivToKyiv + WebMercator.GeoPoint(50.0, 27.0),
            // a dense cluster (small span → high z)
            listOf(
                WebMercator.GeoPoint(50.0, 30.0),
                WebMercator.GeoPoint(50.001, 30.001),
            ),
        )
        for ((w, h) in views) {
            for ((px, py) in pads) {
                for (plan in plans) {
                    val expected = Prototype.fitZoom(
                        plan.map { it.latitude }, plan.map { it.longitude },
                        w, h, px, py, 16,
                    )
                    val actual = WebMercator.fitZoom(
                        plan, viewW = w, viewH = h, padX = px, padY = py, maxZoom = 16,
                    )
                    assertEquals("view=$w x $h pad=$px,$py", expected, actual)
                }
            }
        }
    }

    @Test
    fun `fitZoom single point reaches maxZoom like the prototype`() {
        // w=h=0 fits at every z, so the prototype scan returns maxZ
        assertEquals(16, WebMercator.fitZoom(listOf(WebMercator.GeoPoint(50.0, 30.0)), 341.0, 150.0, 40.0, 24.0, 16))
    }

    @Test
    fun `fitZoom continent span floors at 3 like the prototype`() {
        val world = listOf(
            WebMercator.GeoPoint(60.0, -170.0),
            WebMercator.GeoPoint(-60.0, 170.0),
        )
        assertEquals(3, Prototype.fitZoom(listOf(60.0, -60.0), listOf(-170.0, 170.0), 341.0, 150.0, 40.0, 24.0, 16))
        assertEquals(3, WebMercator.fitZoom(world, 341.0, 150.0, 40.0, 24.0, 16))
    }

    @Test
    fun `fitZoom honors the padding budget — bigger pad never zooms in`() {
        val bounds = WebMercator.fitZoom(lvivToKyiv, 341.0, 150.0, 40.0, 24.0, 16)
        val roomier = WebMercator.fitZoom(lvivToKyiv, 341.0, 150.0, 80.0, 48.0, 16)
        assertTrue(roomier <= bounds)
    }

    @Test
    fun `fullscreen fit raises one level capped at maxZoom (mapFSReset semantics)`() {
        // v3 mapFSReset: z = min(16, fitZoom(rows, MAP_W, MAP_H, 60, 80) + 1)
        val base = WebMercator.fitZoom(lvivToKyiv, 393.0, 811.0, 60.0, 80.0, 16)
        assertEquals(minOf(16, base + 1), WebMercator.fitZoomRaised(lvivToKyiv, 393.0, 811.0, 60.0, 80.0, 16))
        // a degenerate single point at maxZ must NOT raise past the cap
        assertEquals(16, WebMercator.fitZoomRaised(listOf(WebMercator.GeoPoint(50.0, 30.0)), 393.0, 811.0, 60.0, 80.0, 16))
    }
}
