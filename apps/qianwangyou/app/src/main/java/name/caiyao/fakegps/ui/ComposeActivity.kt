package name.caiyao.fakegps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import name.caiyao.fakegps.probe.DebugHookProbeController
import name.caiyao.fakegps.mockprovider.MockProviderRuntime
import name.caiyao.fakegps.ui.navigation.AppNavGraph
import name.caiyao.fakegps.ui.theme.FakeGpsTheme

class ComposeActivity : ComponentActivity() {
    private val debugProbeController = DebugHookProbeController()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Publish the effective spoof config into world-readable prefs so the Xposed hook
        // (running inside target apps, e.g. Google Maps) can read it via XSharedPreferences.
        // This is the REAL launcher entry point; the legacy SplashActivity is never opened.
        name.caiyao.fakegps.config.ConfigPrefsSync.sync(applicationContext)
        MockProviderRuntime.reconcileOnAppLaunch(applicationContext)

        // Read-back probe, DEBUG builds only: logs what this (self-hooked) process observes through
        // the public Android APIs, so scripts/test-hook.sh can assert the whole chain with no
        // manual taps. A release build neither self-hooks nor probes.
        //
        // Deliberately delayed: the hook loads its config on a timer ~3s after process start (the
        // very first load runs before Application exists). Probing in onCreate raced that and read
        // pre-spoof values, which looked exactly like a broken hook.
        debugProbeController.schedule(applicationContext)

        enableEdgeToEdge()
        setContent {
            FakeGpsTheme {
                // P0.1-1 首启权限流：缺 ACCESS_FINE/COARSE_LOCATION、POST_NOTIFICATIONS 时
                // 主动弹标准运行时权限申请（代替 adb pm grant）；拒绝后由设置页权限卡重试。
                name.caiyao.fakegps.ui.onboarding.FirstLaunchPermissionGate {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
    }

    override fun onDestroy() {
        debugProbeController.close()
        super.onDestroy()
    }
}
