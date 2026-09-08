package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tile map (T-tilemap, 2026-09-08) — the dual-card selection oracle.
 *
 * The run dashboard's map card is STATE-DRIVEN: the real OSM tile map
 * ([TileMapCardPolicy.PlanMapCard.TILES]) is shown only when ALL of
 *  - the user's persisted switch (`map_tiles_enabled`, default true),
 *  - the connectivity gate (CONNECTIVITY 无网 → 回退),
 *  - and the first-tile-callback health (首次瓦片回调失败 → 回退)
 * hold. ANY miss degrades to the existing abstract canvas map — which stays
 * in the codebase as the permanent offline fallback, exactly the prototype
 * README's "断网时地图退纯色底+标记" behavior.
 *
 * Killing mutations: an OR-ed gate (any single signal forcing tiles), an
 * inverted failure check, or a silently ignored user switch all fail the
 * exhaustive matrix below.
 *
 * # 选卡决策 oracle：enabled ∧ network ∧ ¬tileFail → 瓦片图，否则回退抽象地图
 */
class TileMapCardPolicyTest {

    @Test
    fun `all healthy shows the real tile map`() {
        assertEquals(
            TileMapCardPolicy.PlanMapCard.TILES,
            TileMapCardPolicy.decide(tilesEnabled = true, networkOnline = true, tileLoadFailed = false),
        )
    }

    @Test
    fun `user switch off degrades to the canvas map`() {
        assertEquals(
            TileMapCardPolicy.PlanMapCard.CANVAS,
            TileMapCardPolicy.decide(tilesEnabled = false, networkOnline = true, tileLoadFailed = false),
        )
    }

    @Test
    fun `no network (airplane mode) degrades to the canvas map`() {
        assertEquals(
            TileMapCardPolicy.PlanMapCard.CANVAS,
            TileMapCardPolicy.decide(tilesEnabled = true, networkOnline = false, tileLoadFailed = false),
        )
    }

    @Test
    fun `first tile load failure degrades to the canvas map`() {
        assertEquals(
            TileMapCardPolicy.PlanMapCard.CANVAS,
            TileMapCardPolicy.decide(tilesEnabled = true, networkOnline = true, tileLoadFailed = true),
        )
    }

    @Test
    fun `exhaustive matrix — tiles iff enabled and online and not failed`() {
        for (enabled in listOf(true, false)) {
            for (online in listOf(true, false)) {
                for (failed in listOf(true, false)) {
                    val expected = enabled && online && !failed
                    assertEquals(
                        "enabled=$enabled online=$online failed=$failed",
                        if (expected) TileMapCardPolicy.PlanMapCard.TILES else TileMapCardPolicy.PlanMapCard.CANVAS,
                        TileMapCardPolicy.decide(enabled, online, failed),
                    )
                }
            }
        }
    }

    @Test
    fun `decision is a pure total function of its three inputs`() {
        // Determinism: repeated evaluation never flips (no hidden time/network reads).
        val first = TileMapCardPolicy.decide(true, true, false)
        repeat(16) {
            assertEquals(first, TileMapCardPolicy.decide(true, true, false))
        }
        assertTrue(TileMapCardPolicy.PlanMapCard.values().size == 2)
    }
}
