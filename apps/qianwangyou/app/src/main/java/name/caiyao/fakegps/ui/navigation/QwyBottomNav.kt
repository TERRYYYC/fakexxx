package name.caiyao.fakegps.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavOptions
import androidx.navigation.navOptions

/**
 * #139 底栏模型（fakexxx-map，v3 原型 map 侧定稿）：底栏 = 状态中心 / 档案 / 设置
 * 三个顶层 tab。地图是状态中心的子页（从状态中心进，保留全屏），编辑器从档案页进、
 * 验证从设置页进——都是子页，不占底栏。抽屉导航已整体删除：与新底栏重复的入口
 * 只保留底栏一套（issue #139「二选一」）。
 *
 * 返回栈矩阵（[BackStackMatrixTest] 在 Robolectric 上逐条断言，语义与 Auto 侧一致）：
 *
 * | 当前页                            | 系统返回                                  |
 * |----------------------------------|-------------------------------------------|
 * | 状态中心（startDestination）       | 退出 app（选型：不弹确认直接退，与 v3 一致）|
 * | 档案 / 设置（顶层 tab）            | → 状态中心                                 |
 * | 地图（子页，父=状态中心）          | → 状态中心                                 |
 * | 编辑器（子页，父=档案）            | → 档案                                     |
 * | 验证（子页，父=进入它的 tab）      | → 父级                                     |
 * | 深链 fakexxx-map://pending        | 落在 设置 tab（栈=[状态中心,设置]，不另起栈）；返回 → 状态中心 |
 *
 * tab 互切 = [selectTabOptions]：launchSingleTop + popUpTo(状态中心){saveState} +
 * restoreState。栈恒为 [状态中心, 当前tab]：切换不堆叠、旧 tab 状态保留、无鬼栈。
 */
object QwyBottomNav {

    data class Tab(
        val screen: Screen,
        val label: String,
        val icon: ImageVector,
        val testTag: String,
    )

    /** 底栏三项，按显示顺序（v3 原型 M1 状态中心 / M2 档案 / M4 设置）。 */
    val tabs: List<Tab> = listOf(
        Tab(Screen.StatusCenter, "状态中心", Icons.Default.Home, "qwy_tab_status_center"),
        Tab(Screen.Collection, "档案", Icons.Default.Bookmarks, "qwy_tab_collection"),
        Tab(Screen.Settings, "设置", Icons.Default.Settings, "qwy_tab_settings"),
    )

    /** 返回栈的根与 tab 语义的锚点：唯一“返回=退出”的页面。 */
    val startDestination: Screen = Screen.StatusCenter

    fun tabFor(screen: Screen?): Tab? = tabs.firstOrNull { it.screen == screen }

    /** 当前目的地对应的 tab；子页（地图/编辑器/验证）返回 null = 底栏隐藏。 */
    fun tabForDestination(destination: NavDestination?): Tab? {
        if (destination == null) return null
        return when {
            destination.hasRoute(Screen.StatusCenter::class) -> tabFor(Screen.StatusCenter)
            destination.hasRoute(Screen.Collection::class) -> tabFor(Screen.Collection)
            destination.hasRoute(Screen.Settings::class) -> tabFor(Screen.Settings)
            else -> null
        }
    }

    /**
     * tab 互切的导航选项：单顶 + popUpTo(状态中心，不含) 保存状态 + 恢复状态。
     * 深链落地也走这一组选项（见 NavGraph 的 T11c 注释）——“落到对应 tab 不另起栈”。
     */
    fun selectTabOptions(): NavOptions = navOptions {
        popUpTo(Screen.StatusCenter) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
