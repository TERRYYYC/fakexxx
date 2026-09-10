package name.caiyao.fakegps.ui.screen.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import name.caiyao.fakegps.motion.RouteSummary

/**
 * P3.1 运动链：a route profile announces itself with this card (waypoint count / total length /
 * play time at the speed profile). The waypoints themselves come from a route CSV or the
 * plan-adjacent synthesis — they are not typed into the field grid.
 *
 * T11d: shared verbatim by the expert editor and the simple editor — the route card is one of the
 * few things the simple mode keeps.
 */
@Composable
internal fun ProfileRouteCard(summary: RouteSummary) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "路线",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "${summary.waypointCount} 个路点 · " +
                    "%.1f km".format(summary.lengthMeters / 1000.0) + " · " +
                    "预计 %d 分钟".format(
                        (summary.estimatedDurationSeconds / 60.0).toInt().coerceAtLeast(1),
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "开启「运动链」模块后，System Mock 沿该路线以 1 Hz 连续投递（速度剖面 + GPS 抖动）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
