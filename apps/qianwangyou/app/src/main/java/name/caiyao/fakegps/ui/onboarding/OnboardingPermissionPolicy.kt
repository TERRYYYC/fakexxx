package name.caiyao.fakegps.ui.onboarding

import android.Manifest

/**
 * P0.1-1 首启权限流判定（pure JVM）：app 历史上从不主动申请运行时权限，装机全靠
 * `adb pm grant`。向导在首次组合时检测 ACCESS_FINE/COARSE_LOCATION 与 POST_NOTIFICATIONS
 * 的缺失，投影成标准运行时权限申请数组交给 launcher；用户拒绝后不再自动弹（同一进程内），
 * 由设置页的权限卡提供显式重试入口。
 *
 * 判定全部收敛在这里，Compose 侧（[FirstLaunchPermissionGate] / 设置页权限卡）只做粘合，
 * 保证「缺权限→申请投影」「拒绝→可重试」两条行为在 JVM lane 可证。
 */
object OnboardingPermissionPolicy {
    private const val NOTIFICATION_PERMISSION_SDK = 33

    fun toRequest(
        sdkInt: Int,
        fineLocationGranted: Boolean,
        coarseLocationGranted: Boolean,
        notificationsGranted: Boolean,
    ): List<String> = buildList {
        // 定位权限成对申请：系统把 fine 视为 coarse 的超集，缺任一个都按对申请，
        // 避免「只批了 coarse 但向导以为已齐」的半授权态。
        if (!fineLocationGranted || !coarseLocationGranted) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (sdkInt >= NOTIFICATION_PERMISSION_SDK && !notificationsGranted) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 首启自动弹窗只发生一次/每进程：任何结果（授予或拒绝）之后都不再自动弹，
     * 防止用户拒绝后被系统限流的请求反复打扰。
     */
    fun shouldAutoPrompt(missing: List<String>, promptedThisLaunch: Boolean): Boolean =
        missing.isNotEmpty() && !promptedThisLaunch

    /** 拒绝后设置页的可重试态：缺权限集合非空即允许重试。 */
    fun canRetryFromSettings(missing: List<String>): Boolean = missing.isNotEmpty()
}
