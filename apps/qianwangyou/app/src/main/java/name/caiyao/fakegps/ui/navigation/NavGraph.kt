package name.caiyao.fakegps.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import name.caiyao.fakegps.ui.screen.collection.CollectionScreen
import name.caiyao.fakegps.ui.screen.editor.ProfileEditorScreen
import name.caiyao.fakegps.ui.screen.map.MapScreen
import name.caiyao.fakegps.ui.screen.settings.SettingsScreen
import name.caiyao.fakegps.ui.screen.statuscenter.StatusCenterScreen
import name.caiyao.fakegps.ui.screen.verify.VerifyScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    // T11c: fakexxx-map://pending lands straight on the settings page with the
    // pairing area scrolled into view and highlighted. The status center STAYS the
    // startDestination (T11b) — Settings is pushed on top of it (see below) — so the
    // back stack is valid in every entry path: Settings' popBackStack() always has a
    // destination to land on, and Map's popBackStack(StatusCenter) can never hit a
    // status center that is missing from the stack.
    startOnPendingPairing: Boolean = false,
) {
    NavHost(
        navController = navController,
        startDestination = Screen.StatusCenter,
    ) {
        composable<Screen.StatusCenter> { entry ->
            val navigation = entry.rememberNavigationActionGuard()
            StatusCenterScreen(
                onOpenMap = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Map)
                    }
                },
                onOpenCollection = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Collection)
                    }
                },
                onOpenSettings = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Settings)
                    }
                },
                onOpenVerify = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Verify)
                    }
                },
            )
        }
        composable<Screen.Map> { entry ->
            val navigation = entry.rememberNavigationActionGuard()
            MapScreen(
                onOpenStatusCenter = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.popBackStack(Screen.StatusCenter, inclusive = false)
                    }
                },
                onAddProfile = { lat, lon ->
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Editor(lat = lat, lon = lon))
                    }
                },
                onOpenCollection = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Collection)
                    }
                },
                onOpenSettings = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Settings)
                    }
                },
                onOpenVerify = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(Screen.Verify)
                    }
                },
            )
        }
        composable<Screen.Collection> { entry ->
            val navigation = entry.rememberNavigationActionGuard()
            CollectionScreen(
                onEditProfile = { id, lat, lon ->
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.navigate(
                            Screen.Editor(profileId = id, lat = lat, lon = lon),
                        )
                    }
                },
                onBack = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.popBackStack()
                    }
                },
            )
        }
        composable<Screen.Editor> { backStackEntry ->
            val navigation = backStackEntry.rememberNavigationActionGuard()
            val route = backStackEntry.toRoute<Screen.Editor>()
            ProfileEditorScreen(
                profileId = route.profileId,
                lat = route.lat,
                lon = route.lon,
                onBack = {
                    navigation.submit(backStackEntry.lifecycle.currentState) {
                        navController.popBackStack()
                    }
                },
                onVerify = {
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
            SettingsScreen(
                onBack = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.popBackStack()
                    }
                },
                // T11c: deep-link landing highlights the 待批准的 Auto pairing area.
                highlightPendingPairing = startOnPendingPairing,
            )
        }
        composable<Screen.Verify> { entry ->
            val navigation = entry.rememberNavigationActionGuard()
            VerifyScreen(
                onBack = {
                    navigation.submit(entry.lifecycle.currentState) {
                        navController.popBackStack()
                    }
                },
            )
        }
    }

    // T11c deep-link landing: push Settings ON TOP of the status center (never as the
    // startDestination — see the parameter KDoc). launchSingleTop keeps an activity
    // recreation (rotation / process restore) from stacking a second Settings entry
    // when the restored back stack already shows it.
    LaunchedEffect(startOnPendingPairing) {
        if (startOnPendingPairing) {
            navController.navigate(Screen.Settings) { launchSingleTop = true }
        }
    }
}

@Composable
private fun NavBackStackEntry.rememberNavigationActionGuard(): NavigationActionGuard {
    val entry = this
    val guard = remember(entry) { NavigationActionGuard() }
    DisposableEffect(entry, guard) {
        val observer = LifecycleEventObserver { source, _ ->
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
