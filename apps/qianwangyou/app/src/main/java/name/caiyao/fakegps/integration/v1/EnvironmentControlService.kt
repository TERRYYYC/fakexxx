package name.caiyao.fakegps.integration.v1

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1

/**
 * Binder entry point: name.caiyao.fakegps.integration.v1.EnvironmentControlService
 * (§6.1 — class name is frozen; the ComponentName package half is the runtime
 * applicationId, production or .bench, each pairing independently).
 *
 * Glue only:
 *  - captures the incoming UID for authorization, then runs QWY-owned work
 *    under QWY's identity, restoring the incoming identity on every exit
 *  - Binder death / RemoteException stay transport failures (recovery path),
 *    never ContractErrorCodeV1 values
 *  - exported across apps, no network surface
 *
 * §6.3.3 typed-failure mapping is NOW LIVE (KB-7=A, v1.59): every call returns
 * the app-public [EnvironmentControlResultV1] carrier. Expected business
 * failures arrive as resultKindWire=ERROR + errorCodeWire (the frozen wire
 * code), NOT as hidden framework exceptions — `android.os.ServiceSpecificException`
 * is absent from the public SDK, so the typed carrier is the only app-public
 * path a wire code can travel. Transport failures (binder death) remain
 * outside this carrier, as the carrier's own contract states.
 *
 * All behavior lives in [EnvironmentControlHandler] so unit lanes never need
 * an Android runtime.
 */
class EnvironmentControlService : Service() {

    private val binder: IEnvironmentControlV1.Stub = object : IEnvironmentControlV1.Stub() {

        override fun discover(): EnvironmentControlResultV1 =
            typedResult { uid -> EnvironmentControlResultV1.discover(handler().discover(uid)) }

        override fun preflight(request: PreflightRequestV1): EnvironmentControlResultV1 =
            typedResult { uid -> EnvironmentControlResultV1.preflight(handler().preflight(uid, request)) }

        override fun apply(request: ApplyRequestV1): EnvironmentControlResultV1 =
            typedResult { uid -> EnvironmentControlResultV1.apply(handler().apply(uid, request)) }

        override fun observe(request: ObserveRequestV1): EnvironmentControlResultV1 =
            typedResult { uid -> EnvironmentControlResultV1.observe(handler().observe(uid, request)) }

        override fun release(request: ReleaseRequestV1): EnvironmentControlResultV1 =
            typedResult { uid -> EnvironmentControlResultV1.release(handler().release(uid, request)) }

        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): EnvironmentControlResultV1 =
            typedResult { uid -> EnvironmentControlResultV1.completeAndAdvance(handler().completeAndAdvance(uid, request)) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * §8.4 clean-shutdown evidence. An orderly teardown leaves a marker the next
     * start consumes; a kill, a low-memory reap or power loss leaves nothing, so
     * the next start correctly reports unclean and ACTIVE leases go to
     * RELEASE_INCOMPLETE rather than being assumed released.
     *
     * This call site was missing, which made cleanlinessProvable a constant
     * false — the clean branch of §8.4 was unreachable while looking implemented.
     */
    override fun onDestroy() {
        ProviderRuntime.recordCleanShutdown()
        super.onDestroy()
    }

    private fun handler(): EnvironmentControlHandler = ProviderRuntime.handler(this)

    /**
     * §6.3.3 typed-failure mapping (KB-7=A, v1.59): a [ContractException] from
     * the handler is an EXPECTED business failure and travels inside the
     * [EnvironmentControlResultV1] carrier as ERROR + the frozen wire code.
     *
     * The mapping itself lives in [toTypedResult] (top-level, JVM-testable —
     * this service is Binder glue and cannot be instantiated in a unit lane).
     */
    private inline fun typedResult(crossinline block: (Int) -> EnvironmentControlResultV1): EnvironmentControlResultV1 =
        withProviderBinderIdentity { callerUid -> toTypedResult { block(callerUid) } }
}

/**
 * Keep the kernel-supplied principal separate from the identity used for local
 * QWY work (including deferred handler initialization and owner recovery).
 * Handler authorization/lease checks MUST use the captured UID, not re-read it
 * after clearing. A caller never supplies this value in request data.
 *
 * Synchronous, same-thread scope only: never move the token across an async
 * boundary. Restore even when initialization, authorization or execution throws.
 */
internal inline fun <T> withProviderBinderIdentity(block: (callerUid: Int) -> T): T {
    val callerUid = Binder.getCallingUid()
    val token = Binder.clearCallingIdentity()
    return try {
        block(callerUid)
    } finally {
        Binder.restoreCallingIdentity(token)
    }
}

/**
 * The KB-7=A exception→carrier mapping, as a pure function so the unit lane
 * can pin it (the service class is Android-bound; the mapping is the part
 * that can drift silently).
 *
 * ContractException (expected business failure) → ERROR + the frozen wire
 * code, crossing Binder as DATA. Anything else (store I/O failure, adapter
 * crash) propagates as a transport failure — it is not a business answer and
 * must not be laundered into one.
 */
fun toTypedResult(block: () -> EnvironmentControlResultV1): EnvironmentControlResultV1 =
    try {
        block()
    } catch (e: ContractException) {
        EnvironmentControlResultV1.failure(
            errorCodeWire = e.code.wire,
            diagnosticMessage = e.message,
        )
    }
