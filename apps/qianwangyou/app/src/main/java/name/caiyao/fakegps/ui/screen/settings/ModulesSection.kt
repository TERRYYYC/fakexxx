package name.caiyao.fakegps.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import name.caiyao.fakegps.config.SpoofModules

/**
 * UI catalogue for the v5 module switches. Display strings live here and ONLY here; the wire
 * names come from [SpoofModules] (the single constant source shared with the hook), so a module
 * cannot exist in the UI without a payload key and vice versa.
 */
internal object SpoofModuleUiCatalog {

    data class Entry(val module: String, val label: String, val detail: String)

    val entries: List<Entry> = listOf(
        Entry(
            SpoofModules.LOCATION,
            "定位",
            "GPS 坐标、Provider 列表与 GPS 状态",
        ),
        Entry(
            SpoofModules.CELLULAR,
            "蜂窝",
            "基站标识/信号、运营商/SIM、网络制式与服务状态",
        ),
        Entry(
            SpoofModules.WIFI,
            "WiFi",
            "SSID/BSSID/信号等 WiFi 指纹",
        ),
        Entry(
            SpoofModules.NETWORK_IP,
            "IP 与连接",
            "IP/DNS/网关/接口与连接类型",
        ),
        Entry(
            SpoofModules.PHONE_STATE,
            "通话状态",
            "注册到目标 App 的电话状态监听回调",
        ),
        Entry(
            SpoofModules.FUSED,
            "融合定位",
            "Google 融合定位（FusedLocation）回调",
        ),
        Entry(
            SpoofModules.MOTION,
            "运动链",
            "路线播放（连续轨迹 + 速度剖面 + GPS 抖动）；后续含传感器合成。" +
                "默认关 = 静态点位现行为，完全可拆卸",
        ),
    )

    init {
        // Catalogue and wire vocabulary must never drift: a new module without a UI row (or a
        // row without a payload key) is a contract failure at first render, not a silent gap.
        check(entries.map { it.module } == SpoofModules.ALL.toList()) {
            "module UI catalogue out of sync with SpoofModules.ALL"
        }
    }
}

/**
 * "模块" settings section (transport schema v5). Each switch decides whether a whole group of
 * hooks registers in the target app: off = the surface reads its fully native stack.
 *
 * Kept in its own file/composable so the existing SettingsScreen blocks stay untouched; the
 * screen inserts a single call. Registration is per target-process: a toggle publishes
 * immediately but fully applies after the target app restarts — the section says so, because a
 * switch that "did nothing" until restart must not read as a silent failure.
 */
@Composable
fun ModulesSection(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val switches by vm.moduleSwitches.collectAsState()

    Column(modifier = modifier) {
        Text(
            text = "模块",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        Text(
            text = "关闭一个模块后，目标 App 在下次重启该 App 时读到该面的完全原生数据。" +
                "开关立即发布，注册在目标进程启动时生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
        for (entry in SpoofModuleUiCatalog.entries) {
            val enabled = switches[entry.module] ?: true
            ListItem(
                headlineContent = { Text(entry.label) },
                supportingContent = { Text(entry.detail) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = { checked -> vm.setModuleEnabled(entry.module, checked) },
                    )
                },
            )
        }
        HorizontalDivider()
    }
}
