package com.example.cellrebelauto.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * #139 底栏语义与返回栈矩阵（fakexxx-auto，v3 原型 A1/plan/A2 定稿）：
 * 底栏 = 运行台 / 计划 / Provider 三个顶层 tab；History 降为计划页的子页（从计划页进）。
 *
 * Operator 反馈（2026-09-09）：运行台是首页、底栏却只有 Plan/History/Provider——回运行台
 * 只能靠系统返回，这正是「底栏菜单与跳转逻辑不符合逻辑」的核心。
 *
 * Auto 不用导航栈：[MainViewModel] 只持有一个 currentScreen 状态，tab 互切天然单顶，
 * 旧 tab 不留鬼栈。「返回」由 [backTarget] 驱动 MainActivity 的 BackHandler：
 *
 * | 当前页                          | 系统返回                                   |
 * |--------------------------------|--------------------------------------------|
 * | 运行台 RUN（首页）               | 退出 app（选型：不弹确认直接退，与 v3 一致） |
 * | 计划 PLAN / Provider PROVIDERS  | → 运行台                                    |
 * | 历史 HISTORY（子页，父=计划）     | → 计划（父级）                              |
 * | 深链 fakexxx-auto://providers   | 落在 Provider tab（不另起栈）；返回 → 运行台 |
 *
 * 运行台内嵌地图卡的 ⛶ 全屏态是页面内子态：返回先收起全屏（RunDashboardScreen 内
 * BackHandler），再次返回才走本矩阵。
 */
object AutoBottomNav {

    data class Tab(
        val screen: Screen,
        val label: String,
        val icon: ImageVector,
    )

    /** 底栏三项，按显示顺序（v3 原型：运行台 / 计划 / Provider）。 */
    val tabs: List<Tab> = listOf(
        Tab(Screen.RUN, "运行台", Icons.Default.PlayArrow),
        Tab(Screen.PLAN, "计划", Icons.Default.List),
        Tab(Screen.PROVIDERS, "Provider", Icons.Default.Build),
    )

    /** 该页对应的底栏 tab；null = 子页（History），不显示底栏。 */
    fun tabOf(screen: Screen): Tab? = tabs.firstOrNull { it.screen == screen }

    /**
     * 返回矩阵：从 [from] 按系统返回应去的页面；null = 退出 app
     * （首页 tab 没有更多目的地，走系统默认退出）。
     */
    fun backTarget(from: Screen): Screen? = when (from) {
        Screen.RUN -> null
        Screen.PLAN, Screen.PROVIDERS -> Screen.RUN
        Screen.HISTORY -> Screen.PLAN
    }
}
