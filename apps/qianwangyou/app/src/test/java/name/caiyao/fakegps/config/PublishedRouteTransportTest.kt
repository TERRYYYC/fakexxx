package name.caiyao.fakegps.config

import name.caiyao.fakegps.motion.RoutePayload
import name.caiyao.fakegps.motion.RouteWaypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** P3.1: the `route` key's transport contract on the readback side. */
class PublishedRouteTransportTest {

    private fun payloadWith(routeJson: String?) = buildString {
        append("""{"schemaVersion":5,"mode":"always_on","fields":{"latitude":"1.0"},""")
        if (routeJson != null) {
            append(""""route":""")
            append(routeJson)
            append(",")
        }
        append(""""modules":{"location":true}}""")
    }

    @Test
    fun `payload without route parses with null route`() {
        val parsed = PublishedConfig.parse(payloadWith(null))!!
        assertNull(parsed.route)
    }

    @Test
    fun `valid route object round-trips through the payload`() {
        val route = RoutePayload.fromWaypoints(
            source = RoutePayload.SOURCE_PLAN,
            waypoints = listOf(
                RouteWaypoint(50.4501, 30.5234),
                RouteWaypoint(50.4600, 30.5400, speedMps = 8.0),
            ),
            cruiseSpeedMps = 10.0,
            seed = 5,
        )
        val json = RoutePayload.encode(route)
        val parsed = PublishedConfig.parse(payloadWith(json))!!
        assertEquals(route, parsed.route)
        assertEquals(2, parsed.route!!.toRouteSpec().waypoints.size)
    }

    @Test
    fun `malformed route object fails the whole payload`() {
        assertNull(PublishedConfig.parse(payloadWith("{broken")))
        assertNull(PublishedConfig.parse(payloadWith("""{"source":"plan"}""")))
        assertNull(
            PublishedConfig.parse(
                payloadWith(
                    """{"source":"evil","waypoints":[{"lat":95.0,"lng":30.0},{"lat":50.0,"lng":30.0}]}""",
                ),
            ),
        )
        assertNull(
            PublishedConfig.parse(
                payloadWith(
                    """{"source":"plan","waypoints":[{"lat":50.0,"lng":30.0}]}""",
                ),
            ),
        )
        assertNull(
            PublishedConfig.parse(
                payloadWith(
                    """["not","an","object"]""",
                ),
            ),
        )
    }

    @Test
    fun `route with a single waypoint is structurally invalid`() {
        assertNull(
            PublishedConfig.parse(
                payloadWith(
                    """{"source":"plan","waypoints":[{"lat":50.0,"lng":30.0},{"lat":50.0,"lng":30.0}]}""",
                ),
            ),
        )
    }
}
