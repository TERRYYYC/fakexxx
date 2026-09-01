package io.github.terryyyc.fakexxx.integration.pr63issue66

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.toTypedResult

internal data class JourneyRpcCounters(
    var discover: Int = 0,
    var preflight: Int = 0,
    var apply: Int = 0,
    var observe: Int = 0,
    var release: Int = 0,
    var advance: Int = 0,
)

/** Local Binder transport only; every behavior decision delegates to the real QWY handler. */
internal class LocalQwyBinder(
    private val qwy: ProviderHarness,
    private val callingUid: Int,
    private val calls: JourneyRpcCounters = JourneyRpcCounters(),
) : IEnvironmentControlV1.Stub() {
    override fun discover(): EnvironmentControlResultV1 {
        calls.discover++
        return toTypedResult { EnvironmentControlResultV1.discover(qwy.handler.discover(callingUid)) }
    }

    override fun preflight(request: PreflightRequestV1): EnvironmentControlResultV1 {
        calls.preflight++
        return toTypedResult {
            EnvironmentControlResultV1.preflight(qwy.handler.preflight(callingUid, request))
        }
    }

    override fun apply(request: ApplyRequestV1): EnvironmentControlResultV1 {
        calls.apply++
        return toTypedResult { EnvironmentControlResultV1.apply(qwy.handler.apply(callingUid, request)) }
    }

    override fun observe(request: ObserveRequestV1): EnvironmentControlResultV1 {
        calls.observe++
        return toTypedResult { EnvironmentControlResultV1.observe(qwy.handler.observe(callingUid, request)) }
    }

    override fun release(request: ReleaseRequestV1): EnvironmentControlResultV1 {
        calls.release++
        return toTypedResult { EnvironmentControlResultV1.release(qwy.handler.release(callingUid, request)) }
    }

    override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): EnvironmentControlResultV1 {
        calls.advance++
        return toTypedResult {
            EnvironmentControlResultV1.completeAndAdvance(
                qwy.handler.completeAndAdvance(callingUid, request),
            )
        }
    }
}

internal class RoutingContext(
    base: Context,
    private val bindersByApplicationId: Map<String, IBinder>,
) : ContextWrapper(base) {
    val bindAttempts = mutableListOf<String>()
    val acceptedBinds = mutableListOf<String>()
    var unbindCount = 0
        private set

    override fun getApplicationContext(): Context = this

    override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
        val component = requireNotNull(service.component)
        bindAttempts += component.packageName
        if (component.className != ContractV1.SERVICE_CLASS_NAME) return false
        val binder = bindersByApplicationId[component.packageName] ?: return false
        acceptedBinds += component.packageName
        connection.onServiceConnected(component, binder)
        return true
    }

    override fun unbindService(connection: ServiceConnection) {
        unbindCount++
    }
}
