package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T7v2 §A1-v2 #2 — the serving-cell selection oracle.
 *
 * getAllCellInfo() returns a list of visible cells (serving + neighbours).
 * The hero card must show THE serving cell: registered cells win, and among
 * registered candidates NR outranks LTE (the newer RAT is the one whose
 * identity the hook profile targets). Non-registered cells are never shown.
 *
 * # 服务小区选择 oracle：registered 优先、NR > LTE、非 registered 永不入选
 */
class ServingCellSelectorTest {

    private fun cell(
        rat: String,
        registered: Boolean,
        ci: Long? = 100L,
        readAtMs: Long = 1_000L,
    ) = ServingCellReading(
        rat = rat, ci = ci, tac = 1, pci = 2, mcc = "460", mnc = "0",
        rsrpDbm = -95, registered = registered, readAtMs = readAtMs,
    )

    @Test
    fun emptyList_yieldsNull() {
        assertNull(ServingCellSelector.select(emptyList()))
    }

    @Test
    fun registeredCell_beatsNewerUnregistered() {
        val lteServing = cell("LTE", registered = true, ci = 111L)
        val nrNeighbour = cell("NR", registered = false, ci = 222L)
        assertEquals(111L, ServingCellSelector.select(listOf(nrNeighbour, lteServing))?.ci)
    }

    @Test
    fun amongRegistered_nrOutranksLte() {
        val lte = cell("LTE", registered = true, ci = 111L)
        val nr = cell("NR", registered = true, ci = 222L)
        assertEquals(222L, ServingCellSelector.select(listOf(lte, nr))?.ci)
        // order in the list must not matter
        assertEquals(222L, ServingCellSelector.select(listOf(nr, lte))?.ci)
    }

    @Test
    fun onlyNeighbours_fallsBackToNewestReading() {
        val older = cell("LTE", registered = false, ci = 1L, readAtMs = 100L)
        val newer = cell("LTE", registered = false, ci = 2L, readAtMs = 200L)
        val picked = ServingCellSelector.select(listOf(older, newer))
        // A visible-only cell is still a REAL device reading — the hero shows it
        // (badge semantics stay honest) rather than showing nothing.
        assertEquals(2L, picked?.ci)
    }

    @Test
    fun nullCiReading_isStillSelectable_valueRendersAsPlaceholder() {
        val noCi = cell("LTE", registered = true, ci = null)
        assertEquals(noCi, ServingCellSelector.select(listOf(noCi)))
    }
}
