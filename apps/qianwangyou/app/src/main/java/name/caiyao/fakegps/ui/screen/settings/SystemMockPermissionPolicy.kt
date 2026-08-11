package name.caiyao.fakegps.ui.screen.settings

enum class SystemMockPermission {
    FineLocation,
    CoarseLocation,
    Notifications,
}

/** Pure permission policy shared by the Compose launcher and JVM tests. */
object SystemMockPermissionPolicy {
    private const val NOTIFICATION_PERMISSION_SDK = 33

    fun missing(
        sdkInt: Int,
        fineLocationGranted: Boolean,
        coarseLocationGranted: Boolean,
        notificationsGranted: Boolean,
    ): Set<SystemMockPermission> = buildSet {
        if (!fineLocationGranted && !coarseLocationGranted) {
            add(SystemMockPermission.FineLocation)
            add(SystemMockPermission.CoarseLocation)
        }
        if (sdkInt >= NOTIFICATION_PERMISSION_SDK && !notificationsGranted) {
            add(SystemMockPermission.Notifications)
        }
    }
}
