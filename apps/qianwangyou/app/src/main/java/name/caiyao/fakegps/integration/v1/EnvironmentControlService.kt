package name.caiyao.fakegps.integration.v1

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Binder entry point: name.caiyao.fakegps.integration.v1.EnvironmentControlService
 * (§6.1 — class name is frozen; the ComponentName package half is the runtime
 * applicationId, production or .bench, each pairing independently).
 *
 * Glue only:
 *  - resolves Binder.getCallingUid() per call and passes it to the handler
 *  - maps ContractException → ServiceSpecificException(code.wire) (§6.3.3)
 *  - Binder death / RemoteException stay transport failures (recovery path),
 *    never ContractErrorCodeV1 values
 *  - exported across apps, no network surface
 *
 * All behavior lives in [EnvironmentControlHandler] so unit lanes never need
 * an Android runtime.
 */
class EnvironmentControlService : Service() {

    override fun onBind(intent: Intent?): IBinder =
        TODO("Task 3 GREEN: IEnvironmentControlV1.Stub delegating to EnvironmentControlHandler")
}
