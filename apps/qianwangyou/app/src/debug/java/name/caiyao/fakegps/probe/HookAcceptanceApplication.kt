package name.caiyao.fakegps.probe

import android.app.Application
import android.util.Log

class HookAcceptanceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!HookAcceptanceRecovery.hasPending(this)) return

        val durableFingerprint = HookAcceptanceRecovery.pendingFingerprint(this)
        if (HookAcceptanceRecovery.recoverIfPending(this)) {
            Log.w(
                HookAcceptanceRecovery.TAG,
                "recovered_pending fp=${durableFingerprint ?: "missing"}",
            )
        } else {
            Log.e(HookAcceptanceRecovery.TAG, "recovery_failed")
        }
    }
}
