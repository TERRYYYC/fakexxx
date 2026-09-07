package name.caiyao.fakegps.ui.onboarding

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.1-1 首启权限流 policy oracle（JVM lane）。
 *
 * 实测痛点：app 从不主动申请运行时权限，装机全靠 `adb pm grant`。向导的判定必须可测：
 * 「缺权限 → 申请投影」与「拒绝 → 可重试（且本轮不再自动弹）」。
 *
 * Killing mutation: 反转任一 granted 分支 / 删掉 POST_NOTIFICATIONS 的 SDK 33 门 / 把
 * shouldAutoPrompt 改成无条件 true —— 对应断言立刻变红。
 */
class OnboardingPermissionPolicyTest {

    @Test
    fun `missing permissions project to the standard runtime request array`() {
        // 缺权限→申请投影：全新安装（API 33+）缺全部三项 → launcher 收到完整请求数组。
        val request = OnboardingPermissionPolicy.toRequest(
            sdkInt = 33,
            fineLocationGranted = false,
            coarseLocationGranted = false,
            notificationsGranted = false,
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
            request,
        )
        // 首启（本轮还没弹过）→ 允许自动弹出标准申请。
        assertTrue(OnboardingPermissionPolicy.shouldAutoPrompt(request, promptedThisLaunch = false))
    }

    @Test
    fun `location pair is requested when either location permission is missing`() {
        // 只缺 coarse：fine/coarse 必须成对申请（系统授予 coarse 会连带 fine 的低精度面）。
        val request = OnboardingPermissionPolicy.toRequest(
            sdkInt = 34,
            fineLocationGranted = true,
            coarseLocationGranted = false,
            notificationsGranted = true,
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            request,
        )
    }

    @Test
    fun `fully granted device projects an empty request and never auto-prompts`() {
        val request = OnboardingPermissionPolicy.toRequest(
            sdkInt = 34,
            fineLocationGranted = true,
            coarseLocationGranted = true,
            notificationsGranted = true,
        )
        assertTrue(request.isEmpty())
        assertFalse(OnboardingPermissionPolicy.shouldAutoPrompt(request, promptedThisLaunch = false))
    }

    @Test
    fun `notifications are not requested below api 33`() {
        val request = OnboardingPermissionPolicy.toRequest(
            sdkInt = 30,
            fineLocationGranted = false,
            coarseLocationGranted = false,
            notificationsGranted = false,
        )
        assertEquals(
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            request,
        )
    }

    @Test
    fun `a denied result stops the auto prompt but keeps settings retry available`() {
        // 拒绝→可重试：用户拒绝后（本轮已弹过），不再自动弹（避免死循环骚扰），
        // 但缺权限集合仍非空 → 设置页的重试入口必须可用。
        val stillMissing = OnboardingPermissionPolicy.toRequest(
            sdkInt = 33,
            fineLocationGranted = false,
            coarseLocationGranted = false,
            notificationsGranted = false,
        )
        assertFalse(
            "denial must not re-trigger the launch auto prompt in the same session",
            OnboardingPermissionPolicy.shouldAutoPrompt(stillMissing, promptedThisLaunch = true),
        )
        assertTrue(
            "denial must keep the Settings retry entry available",
            OnboardingPermissionPolicy.canRetryFromSettings(stillMissing),
        )
        // 全部授予后，重试入口消失。
        assertFalse(
            OnboardingPermissionPolicy.canRetryFromSettings(emptyList()),
        )
    }
}
