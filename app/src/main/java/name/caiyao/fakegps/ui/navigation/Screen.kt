package name.caiyao.fakegps.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Screen {
    @Serializable data object Map : Screen
    @Serializable data object Collection : Screen
    @Serializable data object Settings : Screen
    @Serializable data class Editor(val profileId: Long = -1L, val lat: Double = 0.0, val lon: Double = 0.0) : Screen
    @Serializable data object Verify : Screen
}
