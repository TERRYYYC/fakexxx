package name.caiyao.fakegps.motion

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The transport shape of a published route (`route` key in the v5 payload) and of the
 * `route_waypoints_json` profile column.
 *
 * kotlinx.serialization (NOT org.json) because org.json is a stub in JVM unit tests and this
 * codec is a contract both the writer (ConfigPrefsSync), the reader (PublishedConfig) and the
 * delivery scheduler compile against.
 */
@Serializable
data class RoutePayload(
    /**
     * "profile" = independent route CSV attached to the profile (来源 a);
     * "plan" = synthesized from adjacent plan rows (来源 b, default).
     */
    val source: String,
    val waypoints: List<Waypoint>,
    val cruiseSpeedMps: Double = RouteSpec.DEFAULT_CRUISE_SPEED_MPS,
    val seed: Long = RouteSpec.DEFAULT_SEED,
) {
    @Serializable
    data class Waypoint(
        val lat: Double,
        val lng: Double,
        val speedMps: Double? = null,
    )

    fun toRouteSpec(): RouteSpec = RouteSpec(
        waypoints = waypoints.map { RouteWaypoint(it.lat, it.lng, it.speedMps) },
        cruiseSpeedMps = cruiseSpeedMps,
        seed = seed,
    )

    companion object {
        const val SOURCE_PROFILE = "profile"
        const val SOURCE_PLAN = "plan"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

        private val waypointListSerializer =
            kotlinx.serialization.builtins.ListSerializer(Waypoint.serializer())

        fun fromWaypoints(
            source: String,
            waypoints: List<RouteWaypoint>,
            cruiseSpeedMps: Double = RouteSpec.DEFAULT_CRUISE_SPEED_MPS,
            seed: Long = RouteSpec.DEFAULT_SEED,
        ): RoutePayload = RoutePayload(
            source = source,
            waypoints = waypoints.map { Waypoint(it.latitude, it.longitude, it.speedMps) },
            cruiseSpeedMps = cruiseSpeedMps,
            seed = seed,
        )

        fun parse(text: String?): RoutePayload? {
            if (text.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
        }

        fun encode(payload: RoutePayload): String = json.encodeToString(serializer(), payload)

        /** Encode/decode for the `route_waypoints_json` profile column (waypoint list only). */
        fun encodeWaypoints(waypoints: List<RouteWaypoint>): String =
            json.encodeToString(
                waypointListSerializer,
                waypoints.map { Waypoint(it.latitude, it.longitude, it.speedMps) },
            )

        fun parseWaypoints(text: String?): List<RouteWaypoint>? {
            if (text.isNullOrBlank()) return null
            val decoded = runCatching {
                json.decodeFromString(waypointListSerializer, text)
            }.getOrNull() ?: return null
            if (decoded.size < RouteSpec.MIN_WAYPOINTS) return null
            return decoded.map { RouteWaypoint(it.lat, it.lng, it.speedMps) }
        }
    }
}
