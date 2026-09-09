package name.caiyao.fakegps.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #139 底栏语义（fakexxx-map / QWY）：v3 原型 map 侧 = 状态中心/档案/设置 三个顶层 tab。
 * 地图是状态中心的子页（从状态中心进），编辑器/验证是子页；抽屉已删除——
 * 底栏是唯一的一套导航，不再两套并存。
 *
 * 返回栈矩阵的完整行为断言见 [BackStackMatrixTest]（Robolectric 跑真实 NavGraph）；
 * 这里钉住纯模型：底栏结构 + tab 互切的 NavOptions 语义。
 */
class QwyBottomNavTest {

    // ---- 底栏结构：v3 原型 M1/M2/M4 三项，顺序固定 ----

    @Test
    fun bottomBarIsExactlyTheThreeV3TabsInOrder() {
        assertEquals(
            listOf(Screen.StatusCenter, Screen.Collection, Screen.Settings),
            QwyBottomNav.tabs.map { it.screen },
        )
        assertEquals(
            listOf("状态中心", "档案", "设置"),
            QwyBottomNav.tabs.map { it.label },
        )
    }

    @Test
    fun mapEditorVerifyAreSubPagesAndNeverOnTheBottomBar() {
        assertNull("地图从状态中心进（子页）", QwyBottomNav.tabFor(Screen.Map))
        assertNull("编辑器从档案页进（子页）", QwyBottomNav.tabFor(Screen.Editor()))
        assertNull("验证是子页", QwyBottomNav.tabFor(Screen.Verify))
        QwyBottomNav.tabs.forEach { tab ->
            assertNotNull(QwyBottomNav.tabFor(tab.screen))
        }
    }

    @Test
    fun startDestinationIsStatusCenter() {
        assertEquals(Screen.StatusCenter, QwyBottomNav.startDestination)
    }

    // ---- tab 互切：单顶 + popUpTo(start) 保存/恢复——切换不堆栈、无鬼栈 ----

    @Test
    fun tabSwitchOptionsAreSingleTopWithStateSaveAndRestore() {
        val options = QwyBottomNav.selectTabOptions()
        assertTrue("单顶：切到当前 tab 不入栈", options.shouldLaunchSingleTop())
        assertTrue("离开 tab 时保存其状态", options.shouldPopUpToSaveState())
        assertTrue("回来时恢复 tab 状态", options.shouldRestoreState())
        assertFalse("popUpTo 不含 startDestination：栈恒为 [状态中心, 当前tab]，返回回首页", options.isPopUpToInclusive())
        // popUpTo 的落点（= 状态中心，返回终点）由 BackStackMatrixTest 行为断言覆盖：
        // 任意 tab 一次返回即到状态中心。
    }
}
