package name.caiyao.fakegps.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 独立路线 CSV（来源 a）解析契约：waypoint,lat,lng[,speed_mps]。 */
class RouteCsvParserTest {

    @Test
    fun `parses bare rows with optional speed`() {
        val parsed = RouteCsvParser.parse(
            """
            1,50.4501,30.5234
            2,50.4600,30.5400,8.5
            3,50.4700,30.5500
            """.trimIndent(),
        )
        assertEquals(3, parsed.waypoints.size)
        assertEquals(50.4501, parsed.waypoints[0].latitude, 0.0)
        assertEquals(null, parsed.waypoints[0].speedMps)
        assertEquals(8.5, parsed.waypoints[1].speedMps!!, 0.0)
    }

    @Test
    fun `header line is detected and skipped`() {
        val parsed = RouteCsvParser.parse(
            """
            waypoint,lat,lng,speed_mps
            1,50.4501,30.5234
            2,50.4600,30.5400,7
            """.trimIndent(),
        )
        assertEquals(2, parsed.waypoints.size)
    }

    @Test
    fun `comments and blank lines are ignored`() {
        val parsed = RouteCsvParser.parse(
            """
            # 跑步路线
            1,50.4501,30.5234

            2,50.4600,30.5400
            """.trimIndent(),
        )
        assertEquals(2, parsed.waypoints.size)
    }

    @Test
    fun `bad rows abort the whole import with line numbers`() {
        try {
            RouteCsvParser.parse(
                """
                1,50.4501,30.5234
                2,not-a-number,30.5400
                3,50.4700
                4,50.4700,30.5500,zero
                """.trimIndent(),
            )
            fail("expected RouteCsvException")
        } catch (expected: RouteCsvParser.RouteCsvException) {
            val message = expected.message!!
            assertTrue(message.contains("3 处"))
            assertTrue(message.contains("第 2 行"))
            assertTrue(message.contains("第 3 行"))
            assertTrue(message.contains("第 4 行"))
        }
    }

    @Test
    fun `fewer than two waypoints is rejected`() {
        try {
            RouteCsvParser.parse("1,50.4501,30.5234")
            fail("expected RouteCsvException")
        } catch (expected: RouteCsvParser.RouteCsvException) {
            assertTrue(expected.message!!.contains("至少需要 2 个路点"))
        }
    }

    @Test
    fun `out of range coordinates are rejected`() {
        try {
            RouteCsvParser.parse(
                """
                1,95.0,30.5234
                2,50.4600,30.5400
                """.trimIndent(),
            )
            fail("expected RouteCsvException")
        } catch (expected: RouteCsvParser.RouteCsvException) {
            assertTrue(expected.message!!.contains("第 1 行"))
        }
    }
}

/** 计划相邻行合成（来源 b，默认）契约。 */
class RouteSynthesisTest {

    @Test
    fun `plan rows become ordered waypoints`() {
        val synthesized = RouteSynthesis.fromPlanRows(
            listOf(
                RouteSynthesis.PlanPoint(1, 50.4501, 30.5234),
                RouteSynthesis.PlanPoint(2, 50.4600, 30.5400, speedMps = 6.0),
                RouteSynthesis.PlanPoint(3, 50.4700, 30.5500),
            ),
        )
        assertEquals(listOf(1L, 2L, 3L), synthesized.sourceProfileIds)
        assertEquals(3, synthesized.waypoints.size)
        assertEquals(6.0, synthesized.waypoints[1].speedMps!!, 0.0)
    }

    @Test
    fun `rows without coordinates are skipped`() {
        val synthesized = RouteSynthesis.fromPlanRows(
            listOf(
                RouteSynthesis.PlanPoint(1, null, 30.5234),
                RouteSynthesis.PlanPoint(2, 50.4600, 30.5400),
                RouteSynthesis.PlanPoint(3, 50.4700, null),
            ),
        )
        assertEquals(listOf(2L), synthesized.sourceProfileIds)
    }

    @Test
    fun `synthesized result needs at least two usable rows to be playable`() {
        val one = RouteSynthesis.fromPlanRows(
            listOf(RouteSynthesis.PlanPoint(1, 50.4501, 30.5234)),
        )
        try {
            RouteSpec(waypoints = one.waypoints)
            fail("single-row plan must not form a playable route")
        } catch (expected: IllegalArgumentException) {
            // expected: RouteSpec requires MIN_WAYPOINTS
        }
    }
}

/** 发布 payload / 档案列 JSON 编解码契约。 */
class RoutePayloadTest {

    @Test
    fun `route payload round-trips`() {
        val payload = RoutePayload.fromWaypoints(
            source = RoutePayload.SOURCE_PLAN,
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4600, 30.5400, speedMps = 7.5),
            ),
            cruiseSpeedMps = 9.0,
            seed = 1234,
        )
        val encoded = RoutePayload.encode(payload)
        val decoded = RoutePayload.parse(encoded)!!
        assertEquals(payload, decoded)
        val spec = decoded.toRouteSpec()
        assertEquals(9.0, spec.cruiseSpeedMps, 0.0)
        assertEquals(1234L, spec.seed)
    }

    @Test
    fun `waypoint column json round-trips and rejects degenerate lists`() {
        val waypoints = listOf(
            RouteWaypoint(50.4501, 30.5234),
            RouteWaypoint(50.4600, 30.5400),
        )
        val text = RoutePayload.encodeWaypoints(waypoints)
        assertEquals(waypoints, RoutePayload.parseWaypoints(text))

        assertEquals(null, RoutePayload.parseWaypoints(null))
        assertEquals(null, RoutePayload.parseWaypoints("[]"))
        assertEquals(null, RoutePayload.parseWaypoints("not json"))
        assertEquals(
            null,
            RoutePayload.parseWaypoints("""[{"lat":1.0,"lng":2.0}]"""),
        )
    }

    @Test
    fun `malformed route payload parses to null instead of throwing`() {
        assertEquals(null, RoutePayload.parse("{broken"))
        assertEquals(null, RoutePayload.parse(""))
        assertEquals(null, RoutePayload.parse("null"))
    }
}
