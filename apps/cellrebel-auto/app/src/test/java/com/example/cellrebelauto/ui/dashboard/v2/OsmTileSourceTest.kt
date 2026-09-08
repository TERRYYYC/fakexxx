package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tile map (T-tilemap, 2026-09-08) — the OSM tiles-contract constants.
 *
 *  - The attribution string is LEGALLY REQUIRED by the ODbL: any display of
 *    OpenStreetMap raster tiles must credit "© OpenStreetMap contributors"
 *    (prototype v3 renders `© OpenStreetMap 贡献者`; the production constant
 *    keeps the canonical English credit, always visible bottom-right).
 *  - The tile source URL is the single SELECTION POINT (TILE_SOURCE 配置点):
 *    default = the OSM official standard raster. Per the prototype README's
 *    production note, tile.openstreetmap.org is allowed only for LOW-VOLUME
 *    internal testing under the OSMF tile usage policy — swapping to a
 *    self-hosted/commercial source must touch this constant (and the
 *    user-agent) and nothing else.
 *  - The user-agent must carry the real application id: the OSMF policy
 *    requires a valid, identifying HTTP User-Agent (osmdroid's generic
 *    default is throttled/blocked for exactly this reason).
 *
 * # 归属/瓦片源/UA 契约：ODbL 归属常在，TILE_SOURCE 单点可换，UA 带 app id
 */
class OsmTileSourceTest {

    @Test
    fun `attribution is the canonical ODbL credit`() {
        assertEquals("© OpenStreetMap contributors", OsmTileSource.ATTRIBUTION)
    }

    @Test
    fun `attribution names OpenStreetMap with the copyright mark`() {
        assertTrue(OsmTileSource.ATTRIBUTION.contains("©"))
        assertTrue(OsmTileSource.ATTRIBUTION.contains("OpenStreetMap"))
    }

    @Test
    fun `tile source defaults to the OSM official standard raster`() {
        assertEquals("https://tile.openstreetmap.org/", OsmTileSource.TILE_SOURCE_URL)
    }

    @Test
    fun `user agent carries the application id, not osmdroid's generic default`() {
        val ua = OsmTileSource.userAgent("com.example.cellrebelauto.glmbench")
        assertTrue(ua.startsWith("com.example.cellrebelauto.glmbench"))
        assertTrue(!ua.equals("osmdroid", ignoreCase = true))
    }

    @Test
    fun `tile zoom bounds follow the prototype floors and the OSM serving ceiling`() {
        assertEquals(3, OsmTileSource.MIN_ZOOM) // v3 MAPZ.min
        assertEquals(19, OsmTileSource.MAX_ZOOM) // tile.openstreetmap.org serves up to z19
    }
}
