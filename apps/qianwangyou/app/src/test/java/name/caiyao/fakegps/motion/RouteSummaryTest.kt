package name.caiyao.fakegps.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 编辑器「路线」卡的数据投影契约。 */
class RouteSummaryTest {

    @Test
    fun `route column json yields count length and play time`() {
        val json = RoutePayload.encodeWaypoints(
            listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4600, 30.5400),
            ),
        )
        val summary = RouteSummary.of(json)!!

        assertEquals(2, summary.waypointCount)
        assertEquals(1612.0, summary.lengthMeters, 50.0)
        // ~1.6 km at 10 m/s cruise with ramps: minutes-scale, seconds-exact from the player.
        assertTrue(
            "duration ${summary.estimatedDurationSeconds} in plausible range",
            summary.estimatedDurationSeconds in 150.0..190.0,
        )
    }

    @Test
    fun `null blank and single-point columns produce no summary`() {
        assertNull(RouteSummary.of(null))
        assertNull(RouteSummary.of(""))
        assertNull(RouteSummary.of("not json"))
        assertNull(
            RouteSummary.of(
                RoutePayload.encodeWaypoints(listOf(RouteWaypoint(50.0, 30.0))),
            ),
        )
    }

    @Test
    fun `degenerate route with only duplicate points produces no summary`() {
        val duplicates = RoutePayload.encodeWaypoints(
            listOf(
                RouteWaypoint(50.0, 30.0),
                RouteWaypoint(50.0, 30.0),
            ),
        )
        assertNull(RouteSummary.of(duplicates))
    }
}
