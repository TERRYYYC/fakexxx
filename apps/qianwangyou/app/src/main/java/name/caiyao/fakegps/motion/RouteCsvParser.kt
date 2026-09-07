package name.caiyao.fakegps.motion

/**
 * Parser for the independent route CSV (来源 a):
 *
 * ```
 * waypoint,lat,lng[,speed_mps]
 * 1,50.450100,30.523400
 * 2,50.460000,30.530000,8.5
 * ```
 *
 * The optional header line is detected by content (a non-numeric first cell), so exports both
 * with and without headers round-trip. Lines starting with `#` are comments; blank lines are
 * skipped. Errors carry the 1-based line number — an operator feeding a broken route to a live
 * device must be able to find the bad row from the message alone.
 *
 * Pure Kotlin, strict fail-closed: anything unparseable aborts the whole import (no partial
 * routes), matching the profile-import discipline.
 */
object RouteCsvParser {

    data class Parsed(val waypoints: List<RouteWaypoint>)

    class RouteCsvException(message: String) : IllegalArgumentException(message)

    fun parse(text: String): Parsed {
        val waypoints = mutableListOf<RouteWaypoint>()
        val errors = mutableListOf<String>()
        var sawHeader = false

        text.lineSequence().forEachIndexed { index, rawLine ->
            val lineNumber = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            val cells = line.split(',').map { it.trim() }
            if (waypoints.isEmpty() && !sawHeader && isHeader(cells)) {
                sawHeader = true
                return@forEachIndexed
            }
            if (cells.size < 3 || cells.size > 4) {
                errors += "第 $lineNumber 行：需要 3~4 列（waypoint,lat,lng[,speed_mps]），实际 ${cells.size} 列"
                return@forEachIndexed
            }
            val lat = cells[1].toDoubleOrNull()
            val lng = cells[2].toDoubleOrNull()
            if (lat == null || lng == null) {
                errors += "第 $lineNumber 行：纬度/经度必须是数字（got '${cells[1]}','${cells[2]}'）"
                return@forEachIndexed
            }
            var speed: Double? = null
            if (cells.size == 4) {
                val parsedSpeed = cells[3].toDoubleOrNull()
                when {
                    parsedSpeed == null ->
                        errors += "第 $lineNumber 行：speed_mps 必须是数字（got '${cells[3]}'）"
                    parsedSpeed <= 0.0 ->
                        errors += "第 $lineNumber 行：speed_mps 必须为正数（got ${cells[3]}）"
                    else -> speed = parsedSpeed
                }
            }
            runCatching { RouteWaypoint(lat, lng, speed) }
                .onFailure { errors += "第 $lineNumber 行：${it.message}" }
                .onSuccess { waypoints += it }
        }

        if (errors.isNotEmpty()) {
            throw RouteCsvException(
                "路线 CSV 解析失败（${errors.size} 处）：\n" + errors.takeFirst(10).joinToString("\n"),
            )
        }
        if (waypoints.size < RouteSpec.MIN_WAYPOINTS) {
            throw RouteCsvException(
                "路线 CSV 至少需要 ${RouteSpec.MIN_WAYPOINTS} 个路点，实际 ${waypoints.size} 个",
            )
        }
        return Parsed(waypoints)
    }

    private fun isHeader(cells: List<String>): Boolean =
        cells.getOrNull(1)?.toDoubleOrNull() == null

    private fun <T> List<T>.takeFirst(n: Int): List<T> = if (size <= n) this else take(n)
}
