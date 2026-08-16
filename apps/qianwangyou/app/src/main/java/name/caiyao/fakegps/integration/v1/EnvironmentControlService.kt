package name.caiyao.fakegps.integration.v1

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
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
 *  - resolves Binder.getCallingUid() per call and passes it to the handler
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
            typedResult { EnvironmentControlResultV1.discover(handler().discover(callingUid())) }

        override fun preflight(request: PreflightRequestV1): EnvironmentControlResultV1 =
            typedResult { EnvironmentControlResultV1.preflight(handler().preflight(callingUid(), request)) }

        override fun apply(request: ApplyRequestV1): EnvironmentControlResultV1 =
            typedResult { EnvironmentControlResultV1.apply(handler().apply(callingUid(), request)) }

        override fun observe(request: ObserveRequestV1): EnvironmentControlResultV1 =
            typedResult { EnvironmentControlResultV1.observe(handler().observe(callingUid(), request)) }

        override fun release(request: ReleaseRequestV1): EnvironmentControlResultV1 =
            typedResult { EnvironmentControlResultV1.release(handler().release(callingUid(), request)) }

        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): EnvironmentControlResultV1 =
            typedResult { EnvironmentControlResultV1.completeAndAdvance(handler().completeAndAdvance(callingUid(), request)) }
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
     * INV-02: identity is resolved from the kernel-supplied calling uid on every
     * call. A request never gets to state who it is, and the value is read
     * inside the Binder transaction — reading it later (e.g. from a worker
     * thread) would return the provider's own uid.
     */
    private fun callingUid(): Int = Binder.getCallingUid()

    /**
     * §6.3.3 typed-failure mapping (KB-7=A, v1.59): a [ContractException] from
     * the handler is an EXPECTED business failure and travels inside the
     * [EnvironmentControlResultV1] carrier as ERROR + the frozen wire code.
     *
     * History: this seam previously propagated ContractException raw, because
     * the spec named ServiceSpecificException as the channel and that class is
     * @hide to app compilation — there was no app-public exception-shaped path
     * for a wire code at all. The v1.59 carrier resolves that: the error code
     * now crosses Binder as data, not as an exception. Anything that is NOT a
     * ContractException (store I/O failure, adapter crash) still propagates as
     * a transport failure — it is not a business answer and must not be
     * laundered into one.
     */
    private inline fun typedResult(block: () -> EnvironmentControlResultV1): EnvironmentControlResultV1 =
        try {
            block()
        } catch (e: ContractException) {
            EnvironmentControlResultV1.failure(
                errorCodeWire = e.code.wire,
                diagnosticMessage = e.message,
            )
        }
}
