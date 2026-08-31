package name.caiyao.fakegps.oracle

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder

/** Explicit, no-filter registrar. Binding is public; authority registration is UID-1000 only. */
class OracleBridgeService : Service() {
    private val registrar = object : IContinuityOracleRegistrar.Stub() {
        override fun registerOracle(oracle: IAuthoritativeContinuityOracle?) {
            val callingUid = Binder.getCallingUid()
            if (!OracleBridgePolicy.acceptsRegistrarCaller(callingUid)) {
                throw SecurityException("continuity oracle registration requires Android system UID")
            }
            val nonNullOracle = oracle
                ?: throw IllegalArgumentException("continuity oracle binder is required")
            if (!OracleClientRegistry.process.register(
                    callingUid,
                    OracleClientRegistry.binderRegistration(nonNullOracle),
                )
            ) {
                throw IllegalStateException("continuity oracle binder is already dead")
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = registrar

    override fun onDestroy() {
        OracleClientRegistry.process.clear()
        super.onDestroy()
    }
}
