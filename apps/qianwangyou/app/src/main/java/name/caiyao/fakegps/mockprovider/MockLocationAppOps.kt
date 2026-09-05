package name.caiyao.fakegps.mockprovider

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Issue #8: AppOpsManager probe for the android:mock_location app-op.
 *
 * The OS can silently reset the app-op back to deny (observed overnight on a Moto / Android 15
 * as "… from uid N not allowed to perform MOCK_LOCATION"). Instead of waiting for
 * LocationManager.addTestProvider to throw inside MockProviderService, callers ask here FIRST
 * and fail-fast with the typed [MockProviderState.Failed] state.
 *
 * Fail-open policy: when the op cannot be read (no AppOpsManager service, unexpected error),
 * report `true` — the typed SecurityException mapping in [MockProviderSessionController] remains
 * the authoritative net, so a broken probe can never block a legitimate enable.
 */
object MockLocationAppOps {

    fun isMockLocationAllowed(context: Context): Boolean = try {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return true
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION,
                Process.myUid(),
                context.packageName,
            )
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (failure: Throwable) {
        true
    }

    /** The canonical app-op-denied fail-fast state shared by all preflight call sites. */
    fun appOpDeniedState(): MockProviderState.Failed = MockProviderState.Failed(
        message = "模拟位置权限（mock_location AppOps）已被系统重置为拒绝，" +
            "当前千网游不被允许执行 MOCK_LOCATION",
        recovery = MockProviderRecovery.SelectThisAppAndRetryStart,
        providerCleanupRequired = false,
        reason = MockProviderFailureReason.MOCK_LOCATION_APP_OP_DENIED,
    )
}
