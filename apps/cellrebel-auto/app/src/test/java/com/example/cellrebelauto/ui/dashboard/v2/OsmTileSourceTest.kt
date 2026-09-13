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
 *    default = the OSM German mirror standard raster (FOSSGIS). Switched from
 *    tile.openstreetmap.org on 2026-09-13 (decision recorded in issue #182):
 *    the official source serves a full-page 403 to "cellular CGNAT egress +
 *    non-browser UA" — exactly this app's real-device shape. The mirror serves
 *    the same ODbL data and is likewise allowed only for LOW-VOLUME internal
 *    testing (its usage policy restricts commercial/high-traffic use) —
 *    swapping to a self-hosted/commercial source must still touch this
 *    constant (and the user-agent) and nothing else. Caveat: swapping the URL
 *    also REQUIRES bumping [OsmTileSource.TILE_SOURCE_CACHE_NAME] — the
 *    XYTileSource name is part of osmdroid's disk-cache key, and #183's
 *    .org→.de swap without a bump left upgraded devices serving stale 403
 *    placeholder tiles under the same key (issue #182).
 *  - The user-agent must carry the real application id: the tile servers
 *    (OSMF official and the FOSSGIS mirror alike) require a valid, identifying
 *    HTTP User-Agent (osmdroid's generic default is throttled/blocked for
 *    exactly this reason).
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
    fun `tile source defaults to the OSM German mirror standard raster`() {
        assertEquals("https://tile.openstreetmap.de/", OsmTileSource.TILE_SOURCE_URL)
    }

    @Test
    fun `cache name tld suffix tracks the tile source host - bump together on source swap`() {
        // Coupling pin (issue #182): the XYTileSource name is part of osmdroid's
        // disk-cache key, so it MUST change whenever TILE_SOURCE_URL changes —
        // otherwise upgraded devices hit stale tiles under the same key. Simplest
        // enforceable contract: the cache name ends with "-<host TLD>", so a
        // source swap to a different domain TLD (org → de → ...) cannot compile
        // green without the name following.
        val host = java.net.URI(OsmTileSource.TILE_SOURCE_URL).host
        val tld = host.substringAfterLast('.')
        assertTrue(
            "TILE_SOURCE_CACHE_NAME must end with \"-$tld\" to match TILE_SOURCE_URL host " +
                "\"$host\"; bump TILE_SOURCE_CACHE_NAME whenever TILE_SOURCE_URL changes " +
                "(issue #182: .org→.de swap without a bump served stale 403 placeholder tiles)",
            OsmTileSource.TILE_SOURCE_CACHE_NAME.endsWith("-$tld"),
        )
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
        assertEquals(19, OsmTileSource.MAX_ZOOM) // de mirror verified serving z19 (2026-09-13 real-tile probes)
    }
}
