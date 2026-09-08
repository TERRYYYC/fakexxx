package name.caiyao.fakegps.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 发布策略契约：motion 门控 + 两种路线来源（独立 CSV 挂档案 > 计划相邻行合成）。 */
class RoutePublishPolicyTest {

    private fun planRows(vararg coords: Pair<Double, Double>) = coords.mapIndexed { index, it ->
        RoutePublishPolicy.PlanRow(
            id = (index + 1).toLong(),
            latitude = it.first,
            longitude = it.second,
        )
    }

    @Test
    fun `motion off produces no route - byte-identical legacy payload`() {
        assertNull(
            RoutePublishPolicy.build(
                motionEnabled = false,
                activeProfileId = 1L,
                planRows = planRows(50.0 to 30.0, 50.1 to 30.1),
            ),
        )
    }

    @Test
    fun `explicit profile route wins over plan synthesis`() {
        val explicit = RoutePayload.encodeWaypoints(
            listOf(
                RouteWaypoint(51.0, 31.0),
                RouteWaypoint(51.1, 31.1),
            ),
        )
        val rows = listOf(
            RoutePublishPolicy.PlanRow(1, 50.0, 30.0, routeWaypointsJson = explicit),
            RoutePublishPolicy.PlanRow(2, 50.1, 30.1),
        )
        val payload = RoutePublishPolicy.build(true, activeProfileId = 1L, planRows = rows)!!

        assertEquals(RoutePayload.SOURCE_PROFILE, payload.source)
        assertEquals(51.0, payload.waypoints[0].lat, 0.0)
        assertEquals(2, payload.waypoints.size)
    }

    @Test
    fun `plan adjacent rows are the default source`() {
        val payload = RoutePublishPolicy.build(
            motionEnabled = true,
            activeProfileId = 2L,
            planRows = planRows(50.0 to 30.0, 50.1 to 30.1, 50.2 to 30.2),
        )!!

        assertEquals(RoutePayload.SOURCE_PLAN, payload.source)
        assertEquals(3, payload.waypoints.size)
        assertEquals(30.2, payload.waypoints[2].lng, 0.0)
        assertEquals(50.2, payload.waypoints[2].lat, 0.0)
    }

    @Test
    fun `fewer than two usable plan rows produce no route`() {
        assertNull(
            RoutePublishPolicy.build(
                motionEnabled = true,
                activeProfileId = 1L,
                planRows = listOf(RoutePublishPolicy.PlanRow(1, 50.0, 30.0)),
            ),
        )
        assertNull(
            RoutePublishPolicy.build(
                motionEnabled = true,
                activeProfileId = 1L,
                planRows = listOf(
                    RoutePublishPolicy.PlanRow(1, null, 30.0),
                    RoutePublishPolicy.PlanRow(2, 50.1, null),
                ),
            ),
        )
    }

    @Test
    fun `seed is stable across rebuilds of the same route identity`() {
        val rows = planRows(50.0 to 30.0, 50.1 to 30.1)
        val first = RoutePublishPolicy.build(true, 2L, rows)!!
        val second = RoutePublishPolicy.build(true, 2L, rows)!!
        assertEquals(first.seed, second.seed)
        val other = RoutePublishPolicy.build(true, 3L, rows)!!
        assertTrue(first.seed != other.seed)
    }

    @Test
    fun `profile speed seeds the cruise speed`() {
        val rows = listOf(
            RoutePublishPolicy.PlanRow(1, 50.0, 30.0, speedMps = 6.0),
            RoutePublishPolicy.PlanRow(2, 50.1, 30.1),
        )
        val payload = RoutePublishPolicy.build(true, 1L, rows)!!
        assertEquals(6.0, payload.cruiseSpeedMps, 0.0)
    }

    @Test
    fun `malformed explicit route json falls through to plan synthesis`() {
        val rows = listOf(
            RoutePublishPolicy.PlanRow(1, 50.0, 30.0, routeWaypointsJson = "{broken"),
            RoutePublishPolicy.PlanRow(2, 50.1, 30.1),
        )
        val payload = RoutePublishPolicy.build(true, 1L, rows)!!
        assertEquals(RoutePayload.SOURCE_PLAN, payload.source)
    }
}
