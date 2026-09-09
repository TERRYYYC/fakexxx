package name.caiyao.fakegps.ui.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.testing.TestNavHostController
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #139 返回栈矩阵——在 Robolectric 上驱动生产同一份路由/边/选项（[qwyDestinations]）
 * 逐条断言。矩阵定义见 [QwyBottomNav] 的 KDoc；语义与 Auto 侧 AutoBottomNavTest 一致：
 *
 * - 状态中心（start）返回 = 退出（popBackStack()==false）
 * - 档案/设置（顶层 tab）返回 = 状态中心；tab 互切不堆叠（previous 恒为状态中心）
 * - 地图（父=状态中心）/编辑器（父=档案）/验证（父=进入它的页）返回 = 回父级
 * - 编辑器「保存并验证」替换编辑器：验证返回 = 档案（不是旧编辑器）
 * - 深链 fakexxx-map://pending 落在 设置 tab（栈=[状态中心,设置]，不另起栈），返回 =
 *   状态中心；重建重放（launchSingleTop）不叠第二个设置
 *
 * 屏幕内容以 [QwyNavScreens] 桩替换（矩阵只关心栈，不关心 UI）；底栏点击、屏幕按钮
 * 回调与 LaunchedEffect 深链在测试里表现为它们调用的同一段生产导航代码
 * （navigate(tab, selectTabOptions()) / popBackStack() / navigate(...)）。
 */
@RunWith(RobolectricTestRunner::class)
class BackStackMatrixTest {

    private lateinit var nav: TestNavHostController

    private val stubScreens = QwyNavScreens(
        statusCenter = { _, _, _ -> },
        map = { _, _ -> },
        collection = { _ -> },
        editor = { _, _, _, _, _ -> },
        settings = { _, _ -> },
        verify = { _ -> },
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        nav = TestNavHostController(context)
        // TestNavHostController + ComposeNavigator：官方底栏测试的同款组合
        // （TestNavHostController 本身不带 compose 的 navigator）。
        nav.navigatorProvider.addNavigator(ComposeNavigator())
        val builder = NavGraphBuilder(
            nav.navigatorProvider,
            QwyBottomNav.startDestination::class,
            null,
            emptyMap(),
        )
        builder.qwyDestinations(nav, startOnPendingPairing = false, screens = stubScreens)
        nav.setGraph(builder.build(), null)
    }

    private fun assertCurrent(expected: Screen) {
        val destination = nav.currentDestination
        assertNotNull("back stack is empty", destination)
        val matches = when {
            destination!!.hasRoute(Screen.StatusCenter::class) -> expected == Screen.StatusCenter
            destination.hasRoute(Screen.Map::class) -> expected == Screen.Map
            destination.hasRoute(Screen.Collection::class) -> expected == Screen.Collection
            destination.hasRoute(Screen.Settings::class) -> expected == Screen.Settings
            destination.hasRoute(Screen.Editor::class) -> expected == Screen.Editor()
            destination.hasRoute(Screen.Verify::class) -> expected == Screen.Verify
            else -> false
        }
        assertTrue("expected $expected but was ${destination!!.route}", matches)
    }

    /** 当前页的上一栈项必须（或不得）是某页——tab 单顶/不另起栈的关键断言。 */
    private fun assertPreviousIs(expected: Screen) {
        val previous = nav.previousBackStackEntry?.destination
        assertNotNull("no previous entry", previous)
        val matches = when {
            previous!!.hasRoute(Screen.StatusCenter::class) -> expected == Screen.StatusCenter
            previous.hasRoute(Screen.Collection::class) -> expected == Screen.Collection
            else -> false
        }
        assertTrue("expected previous=$expected but was ${previous.route}", matches)
    }

    /** 生产代码里底栏的 onClick / 深链 LaunchedEffect 就是这一行。 */
    private fun tapTab(screen: Screen) {
        nav.navigate(screen, QwyBottomNav.selectTabOptions())
    }

    // ---- 矩阵：状态中心是返回终点 ----

    @Test
    fun startIsStatusCenter_andBackOnItExits() {
        assertCurrent(Screen.StatusCenter)
        assertFalse("back on the start tab must exit", nav.popBackStack())
    }

    // ---- 矩阵：顶层 tab 互切不堆叠，返回回状态中心 ----

    @Test
    fun tabSwitchingNeverStacks_andBackFromAnyTabReturnsToStatusCenter() {
        tapTab(Screen.Collection)
        assertCurrent(Screen.Collection)
        assertPreviousIs(Screen.StatusCenter)
        tapTab(Screen.Settings)
        assertCurrent(Screen.Settings)
        // 关键（无鬼栈）：切了两次 tab，Settings 的上一项仍是状态中心，不是档案
        assertPreviousIs(Screen.StatusCenter)
        tapTab(Screen.StatusCenter)
        assertCurrent(Screen.StatusCenter)
        tapTab(Screen.Collection)
        assertCurrent(Screen.Collection)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
        assertFalse(nav.popBackStack())
    }

    @Test
    fun tappingCurrentTabIsANoOp() {
        tapTab(Screen.Settings)
        assertCurrent(Screen.Settings)
        tapTab(Screen.Settings)
        assertCurrent(Screen.Settings)
        // 若叠加了第二个 Settings，第一次返回会再落 Settings 而不是状态中心
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
        assertFalse(nav.popBackStack())
    }

    // ---- 矩阵：子页返回 = 回父级 ----

    @Test
    fun mapIsAChildOfStatusCenter_backReturnsThere_thenExit() {
        nav.navigate(Screen.Map)
        assertCurrent(Screen.Map)
        // 子页不显示底栏：当前目的地不是任何 tab
        assertNull(QwyBottomNav.tabForDestination(nav.currentDestination))
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
        assertNotNull(QwyBottomNav.tabForDestination(nav.currentDestination))
        assertFalse(nav.popBackStack())
    }

    @Test
    fun editorBackReturnsToCollection() {
        tapTab(Screen.Collection)
        nav.navigate(Screen.Editor(profileId = 1L, lat = 50.45, lon = 30.52))
        assertCurrent(Screen.Editor())
        nav.popBackStack()
        assertCurrent(Screen.Collection)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
    }

    @Test
    fun editorVerifyReplacesEditor_andBackFromVerifyReturnsToCollection() {
        tapTab(Screen.Collection)
        nav.navigate(Screen.Editor(profileId = 1L, lat = 50.45, lon = 30.52))
        assertCurrent(Screen.Editor())
        // 生产编辑器的 onVerify：pop + navigate(Verify)（qwyDestinations 内的既有语义）
        nav.popBackStack()
        nav.navigate(Screen.Verify)
        assertCurrent(Screen.Verify)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.Collection)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
    }

    @Test
    fun verifyFromSettingsReturnsToSettings() {
        tapTab(Screen.Settings)
        nav.navigate(Screen.Verify)
        assertCurrent(Screen.Verify)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.Settings)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
        assertFalse(nav.popBackStack())
    }

    // ---- 矩阵：深链落 tab 不另起栈（T11c 语义在新底栏下保持） ----

    @Test
    fun pendingDeepLinkLandsOnTheSettingsTab_withStatusCenterBeneath() {
        // 生产路径：QwyNavGraph 的 LaunchedEffect(startOnPendingPairing=true) 执行同一行
        nav.navigate(Screen.Settings, QwyBottomNav.selectTabOptions())
        assertCurrent(Screen.Settings)
        assertPreviousIs(Screen.StatusCenter)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
        assertFalse(nav.popBackStack())
    }

    @Test
    fun pendingDeepLinkReplayDoesNotStackASecondSettings() {
        nav.navigate(Screen.Settings, QwyBottomNav.selectTabOptions())
        assertCurrent(Screen.Settings)
        // activity 重建会重放 deep link 导航：singleTop 必须吸收
        nav.navigate(Screen.Settings, QwyBottomNav.selectTabOptions())
        assertCurrent(Screen.Settings)
        assertTrue(nav.popBackStack())
        assertCurrent(Screen.StatusCenter)
        assertFalse(nav.popBackStack())
    }

    @Test
    fun onlyTabDestinationsShowTheBottomBar() {
        assertNotNull(QwyBottomNav.tabForDestination(nav.currentDestination))
        nav.navigate(Screen.Map)
        assertNull("地图是子页：无底栏", QwyBottomNav.tabForDestination(nav.currentDestination))
        nav.popBackStack()
        tapTab(Screen.Collection)
        assertNotNull("档案是顶层 tab：有底栏", QwyBottomNav.tabForDestination(nav.currentDestination))
    }
}
