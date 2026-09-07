package name.caiyao.fakegps.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * P0.1-1 首启权限流（薄 UI 粘合）：包住导航内容，首次组合时检测缺失的运行时权限并
 * 主动弹标准申请；结果回来后（无论授予/拒绝）本轮进程不再自动弹——拒绝方由设置页的
 * 权限卡（「重新申请权限」）接手。判定逻辑全部在 [OnboardingPermissionPolicy]。
 *
 * TODO(P0.1-6 配对时机): QWY 侧「待批准的 Auto」候选目前要等 Auto 首次 Start（契约调用
 *  被拒后留下候选）才会出现在设置页；向导阶段应主动做一次 discover 探测让候选前置出现。
 *  本 thread 范围不含配对时机改造，留待 P0.1-6。
 */
@Composable
fun FirstLaunchPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var promptedThisLaunch by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // 授予与否都只弹这一次；设置页可重试。
        promptedThisLaunch = true
    }

    fun missingNow(): List<String> = OnboardingPermissionPolicy.toRequest(
        sdkInt = Build.VERSION.SDK_INT,
        fineLocationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
        coarseLocationGranted = context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
        notificationsGranted = context.isGranted(Manifest.permission.POST_NOTIFICATIONS),
    )

    LaunchedEffect(Unit) {
        val missing = missingNow()
        if (OnboardingPermissionPolicy.shouldAutoPrompt(missing, promptedThisLaunch)) {
            promptedThisLaunch = true
            launcher.launch(missing.toTypedArray())
        }
    }

    content()
}

/** 设置页权限卡共用：当前缺失的运行时权限（申请投影）。 */
fun missingRuntimePermissions(context: Context): List<String> = OnboardingPermissionPolicy.toRequest(
    sdkInt = Build.VERSION.SDK_INT,
    fineLocationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION),
    coarseLocationGranted = context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION),
    notificationsGranted = context.isGranted(Manifest.permission.POST_NOTIFICATIONS),
)

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
