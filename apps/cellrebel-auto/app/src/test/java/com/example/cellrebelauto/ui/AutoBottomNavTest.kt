package com.example.cellrebelauto.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #139 底栏语义与返回栈矩阵（fakexxx-auto）。
 *
 * Operator 反馈（2026-09-09）：运行台是首页，底栏却只有 Plan/History/Provider——
 * 回运行台只能靠系统返回，「底栏菜单与跳转逻辑不符合逻辑」。v3 原型定稿：
 * 底栏 = 运行台/计划/Provider 三个顶层 tab，History 降为计划页的子页。
 *
 * Auto 没有导航栈（[MainViewModel] 单一 `currentScreen` 状态，天然单顶不堆栈），
 * 所以矩阵的可测试内核就是 [AutoBottomNav]：底栏结构 + 返回目标函数。
 * [MainActivity] 用 [AutoBottomNav.backTarget] 驱动 BackHandler。
 *
 * 矩阵（与 QWY 的 BackStackMatrixTest 同一套语义）：
 * | 当前页        | 系统返回                     |
 * |--------------|------------------------------|
 * | 运行台        | 退出 app（选型：不弹确认直接退）|
 * | 计划          | → 运行台                      |
 * | Provider      | → 运行台                      |
 * | 历史（子页）   | → 计划（父级）                 |
 * | 深链 providers | 落在 Provider tab；返回 → 运行台 |
 */
class AutoBottomNavTest {

    // ---- 底栏结构：v3 原型的三项，顺序固定 ----

    @Test
    fun bottomBarIsExactlyTheThreeV3TabsInOrder() {
        assertEquals(
            listOf(Screen.RUN, Screen.PLAN, Screen.PROVIDERS),
            AutoBottomNav.tabs.map { it.screen },
        )
        assertEquals(
            listOf("运行台", "计划", "Provider"),
            AutoBottomNav.tabs.map { it.label },
        )
    }

    @Test
    fun historyIsASubPageAndNeverOnTheBottomBar() {
        assertNull("History 从计划页进（v3），不再是底栏项", AutoBottomNav.tabOf(Screen.HISTORY))
        AutoBottomNav.tabs.forEach { tab ->
            assertNotNull(AutoBottomNav.tabOf(tab.screen))
        }
    }

    // ---- 返回栈矩阵 ----

    @Test
    fun backFromRunExitsTheApp() {
        assertNull("首页 tab 是返回的终点：再按返回 = 退出（无确认）", AutoBottomNav.backTarget(Screen.RUN))
    }

    @Test
    fun backFromPlanAndProvidersReturnsToRun() {
        assertEquals(Screen.RUN, AutoBottomNav.backTarget(Screen.PLAN))
        assertEquals(Screen.RUN, AutoBottomNav.backTarget(Screen.PROVIDERS))
    }

    @Test
    fun backFromHistoryReturnsToItsParentPlan() {
        assertEquals(Screen.PLAN, AutoBottomNav.backTarget(Screen.HISTORY))
    }

    // ---- 深链落 tab 不另起栈 ----

    @Test
    fun deepLinkTargetProvidersIsABottomBarTab() {
        // fakexxx-auto://providers 的落地面必须就是底栏上的 tab——
        // 深链不另起一个平行的 Provider 页面（CrossAppLinkingTest 管路由本身）。
        assertNotNull(AutoBottomNav.tabOf(Screen.PROVIDERS))
    }

    // ---- 全程走查：任意页连按返回都可预测 ----

    @Test
    fun everyBackPressOnAJourneyIsPredictableUntilExit() {
        // 落地运行台 → 计划（tab 切换，无栈）→ 历史（计划页进）→ 逐级返回
        var screen: Screen? = Screen.RUN
        screen = Screen.PLAN           // tab 互切：单顶，旧 tab 不留鬼栈
        assertEquals(Screen.RUN, AutoBottomNav.backTarget(screen!!))
        screen = Screen.HISTORY        // 子页
        assertEquals(Screen.PLAN, AutoBottomNav.backTarget(screen!!))
        screen = Screen.PLAN
        assertEquals(Screen.RUN, AutoBottomNav.backTarget(screen!!))
        screen = Screen.PROVIDERS      // 顶层 tab 互切
        assertEquals(Screen.RUN, AutoBottomNav.backTarget(screen!!))
        screen = Screen.RUN
        assertNull(AutoBottomNav.backTarget(screen!!))  // 终点：退出
    }
}
