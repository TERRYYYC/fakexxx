package name.caiyao.fakegps.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.toRoute
import name.caiyao.fakegps.ui.screen.collection.CollectionScreen
import name.caiyao.fakegps.ui.screen.editor.ProfileEditorHost
import name.caiyao.fakegps.ui.screen.map.MapScreen
import name.caiyao.fakegps.ui.screen.settings.SettingsScreen
import name.caiyao.fakegps.ui.screen.statuscenter.StatusCenterScreen
import name.caiyao.fakegps.ui.screen.verify.VerifyScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startOnPendingPairing: Boolean = false,
) {
    QwyNavGraph(navController = navController, startOnPendingPairing = startOnPendingPairing)
}

/**
 * Per-destination content seam. Defaults render the REAL screens; the #139
 * back-stack matrix test overrides them with stubs so the Robolectric test can
 * drive the PRODUCTION routes/edges/navOptions/deep-link path without booting
 * Room/osmdroid. Navigation structure never lives here — only content.
 */
internal class QwyNavScreens(
    val statusCenter: @Composable (
        onOpenMap: () -> Unit,
        onOpenCollection: () -> Unit,
        onOpenSettings: () -> Unit,
    ) -> Unit = { onOpenMap, onOpenCollection, onOpenSettings ->
        StatusCenterScreen(
            onOpenMap = onOpenMap,
            onOpenCollection = onOpenCollection,
            onOpenSettings = onOpenSettings,
        )
    },
    val map: @Composable (
        onBackToStatusCenter: () -> Unit,
        onAddProfile: (lat: Double, lon: Double) -> Unit,
    ) -> Unit = { onBackToStatusCenter, onAddProfile ->
        MapScreen(
            onOpenStatusCenter = onBackToStatusCenter,
            onAddProfile = onAddProfile,
        )
    },
    val collection: @Composable (
        onEditProfile: (id: Long, lat: Double, lon: Double) -> Unit,
    ) -> Unit = { onEditProfile ->
        CollectionScreen(onEditProfile = onEditProfile)
    },
    val editor: @Composable (
        profileId: Long,
        lat: Double,
        lon: Double,
        onBack: () -> Unit,
        onVerify: () -> Unit,
    ) -> Unit = { profileId, lat, lon, onBack, onVerify ->
        // T11d：模式分发在编辑器内容层（ProfileEditorHost）——简单模式默认，专家模式原样；
        // Screen.Editor 路由与 #139 返回栈矩阵保持不变。
        ProfileEditorHost(
            profileId = profileId,
            lat = lat,
            lon = lon,
            onBack = onBack,
            onVerify = onVerify,
        )
    },
    val settings: @Composable (
        onOpenVerify: () -> Unit,
        highlightPendingPairing: Boolean,
    ) -> Unit = { onOpenVerify, highlightPendingPairing ->
        SettingsScreen(
            onOpenVerify = onOpenVerify,
            highlightPendingPairing = highlightPendingPairing,
        )
    },
    val verify: @Composable (onBack: () -> Unit) -> Unit = { onBack ->
        VerifyScreen(onBack = onBack)
    },
)

/**
 * #139 导航重构（v3 原型 map 侧）：底栏 = 状态中心/档案/设置（矩阵见 [QwyBottomNav]）。
 * 地图/编辑器/验证是子页——底栏隐藏、返回回父级。抽屉已删除（与新底栏二选一，保留底栏）。
 *
 * T11c: `fakexxx-map://pending` lands on the Settings TAB via the same tab options
 * ([QwyBottomNav.selectTabOptions]) — the status center STAYS the startDestination
 * (T11b) and the stack is exactly [StatusCenter, Settings]: the pairing highlight
 * still anchors onto the 待批准的 Auto area, back returns to the status center, and
 * launchSingleTop keeps an activity recreation (rotation / process restore) from
 * stacking a second Settings entry.
 */
@Composable
internal fun QwyNavGraph(
    navController: NavHostController,
    startOnPendingPairing: Boolean,
    screens: QwyNavScreens = QwyNavScreens(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentTab = QwyBottomNav.tabForDestination(backStackEntry?.destination)

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            NavHost(
                navController = navController,
                startDestination = Screen.StatusCenter,
            ) {
                qwyDestinations(navController, startOnPendingPairing, screens)
            }
        }

        // #139 底栏：只在顶层 tab 页显示；子页（地图/编辑器/验证）全屏。
        if (currentTab != null) {
            NavigationBar {
                QwyBottomNav.tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == currentTab,
                        onClick = { navController.navigate(tab.screen, QwyBottomNav.selectTabOptions()) },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        modifier = Modifier.testTag(tab.testTag),
                    )
                }
            }
        }
    }

    // T11c deep-link landing (see the class KDoc): tab options, never a raw push —
    // “落到对应 tab 不另起栈”。
    LaunchedEffect(startOnPendingPairing) {
        if (startOnPendingPairing) {
            navController.navigate(Screen.Settings, QwyBottomNav.selectTabOptions())
        }
    }
}

/**
 * The production route/edge wiring, extracted so [BackStackMatrixTest] can build
 * THE SAME graph on a TestNavHostController (Robolectric, no activity) and assert
 * the #139 back-stack matrix against real routes and real options.
 */
internal fun NavGraphBuilder.qwyDestinations(
    navController: NavHostController,
    startOnPendingPairing: Boolean,
    screens: QwyNavScreens,
) {
    composable<Screen.StatusCenter> { entry ->
        val navigation = entry.rememberNavigationActionGuard()
        // (onOpenMap, onOpenCollection, onOpenSettings)
        screens.statusCenter(
            {
                navigation.submit(entry.lifecycle.currentState) {
                    navController.navigate(Screen.Map)
                }
            },
            {
                navigation.submit(entry.lifecycle.currentState) {
                    navController.navigate(Screen.Collection, QwyBottomNav.selectTabOptions())
                }
            },
            {
                navigation.submit(entry.lifecycle.currentState) {
                    navController.navigate(Screen.Settings, QwyBottomNav.selectTabOptions())
                }
            },
        )
    }
    composable<Screen.Map> { entry ->
        val navigation = entry.rememberNavigationActionGuard()
        // (onBackToStatusCenter, onAddProfile)
        screens.map(
            {
                navigation.submit(entry.lifecycle.currentState) {
                    navController.popBackStack(Screen.StatusCenter, inclusive = false)
                }
            },
            { lat, lon ->
                navigation.submit(entry.lifecycle.currentState) {
                    navController.navigate(Screen.Editor(lat = lat, lon = lon))
                }
            },
        )
    }
    composable<Screen.Collection> { entry ->
        val navigation = entry.rememberNavigationActionGuard()
        // (onEditProfile)
        screens.collection(
            { id, lat, lon ->
                navigation.submit(entry.lifecycle.currentState) {
                    navController.navigate(
                        Screen.Editor(profileId = id, lat = lat, lon = lon),
                    )
                }
            },
        )
    }
    composable<Screen.Editor> { backStackEntry ->
        val navigation = backStackEntry.rememberNavigationActionGuard()
        val route = backStackEntry.toRoute<Screen.Editor>()
        // (profileId, lat, lon, onBack, onVerify)
        screens.editor(
            route.profileId,
            route.lat,
            route.lon,
            {
                navigation.submit(backStackEntry.lifecycle.currentState) {
                    navController.popBackStack()
                }
            },
            {
                navigation.submit(backStackEntry.lifecycle.currentState) {
                    // Replace the editor in the back stack: after verifying, "back" should
                    // return to the profile list, not to a stale already-saved editor.
                    navController.popBackStack()
                    navController.navigate(Screen.Verify)
                }
            },
        )
    }
    composable<Screen.Settings> { entry ->
        val navigation = entry.rememberNavigationActionGuard()
        // (onOpenVerify, highlightPendingPairing)
        screens.settings(
            {
                navigation.submit(entry.lifecycle.currentState) {
                    navController.navigate(Screen.Verify)
                }
            },
            startOnPendingPairing,
        )
    }
    composable<Screen.Verify> { entry ->
        val navigation = entry.rememberNavigationActionGuard()
        // (onBack)
        screens.verify(
            {
                navigation.submit(entry.lifecycle.currentState) {
                    navController.popBackStack()
                }
            },
        )
    }
}

@Composable
internal fun NavBackStackEntry.rememberNavigationActionGuard(): NavigationActionGuard {
    val entry = this
    val guard = remember(entry) { NavigationActionGuard() }
    DisposableEffect(entry, guard) {
        val observer = androidx.lifecycle.LifecycleEventObserver { source, _ ->
            guard.onStateChanged(source.lifecycle.currentState)
        }
        entry.lifecycle.addObserver(observer)
        onDispose {
            entry.lifecycle.removeObserver(observer)
            guard.dispose()
        }
    }
    return guard
}
