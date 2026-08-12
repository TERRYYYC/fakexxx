package name.caiyao.fakegps.integration.v1

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1
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
 * STATUS — the provider is bindable but NOT yet callable. Do not read the
 * declared manifest entry or the real Stub below as "it works":
 *
 *  - bind succeeds: onBind returns a live binder;
 *  - the FIRST operation on it does not reach handler logic. It calls
 *    [ProviderRuntime.handler], whose composition ends in
 *    onOwnerProcessStart(), which wires the §6.4 relevant-change listener
 *    through [QwyEnvironmentController.setRelevantChangeListener] — still a
 *    TODO(). So discover/apply/observe/release/completeAndAdvance all throw
 *    NotImplementedError before any contract rule runs.
 *
 * An earlier revision of this comment claimed success paths were reachable
 * today. That was false on the actual call chain and is the exact overclaim
 * this file's own guard exists to prevent — recorded here rather than quietly
 * corrected. The provider becomes callable when the adapter lands, which is
 * blocked on schedule ownership, not on this class.
 *
 * The §6.3.3 typed-failure mapping is separately NOT implemented and cannot be
 * until #3 lands a delta — see [typed] for the verified reason.
 *
 * All behavior lives in [EnvironmentControlHandler] so unit lanes never need
 * an Android runtime.
 */
class EnvironmentControlService : Service() {

    private val binder: IEnvironmentControlV1.Stub = object : IEnvironmentControlV1.Stub() {

        override fun discover(): CapabilitySnapshotV1 =
            typed { handler().discover(callingUid()) }

        override fun preflight(request: PreflightRequestV1): PreflightReportV1 =
            typed { handler().preflight(callingUid(), request) }

        override fun apply(request: ApplyRequestV1): ApplyReceiptV1 =
            typed { handler().apply(callingUid(), request) }

        override fun observe(request: ObserveRequestV1): EnvironmentObservationV1 =
            typed { handler().observe(callingUid(), request) }

        override fun release(request: ReleaseRequestV1): ReleaseReceiptV1 =
            typed { handler().release(callingUid(), request) }

        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 =
            typed { handler().completeAndAdvance(callingUid(), request) }
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
     * §6.3.3 error mapping — BLOCKED ON A CONTRACT DELTA (#3 / PR #11).
     *
     * §1506 and the contract README both specify that expected business failures
     * travel as `ServiceSpecificException(ContractErrorCodeV1.wire)`. That class
     * is not implementable by an ordinary app: `android.os.ServiceSpecificException`
     * is absent from the public SDK stub (verified against android-35 and
     * android-36.1 android.jar, javap, and api-versions.xml — framework sources
     * such as android/se/omapi/SEService.java do throw it, so it exists at
     * runtime but is @hide to app compilation).
     *
     * It is also the ONLY exception in Parcel's writeException/readException set
     * that can carry a caller-defined int. So under the public SDK there is no
     * exception-shaped path for a wire code at all: carrying it would require a
     * contract change (an error wire field on the receipts, or a result wrapper),
     * which is #3's call and not something this seam may invent.
     *
     * Until that lands, a typed failure propagates instead of being laundered
     * into a nearby public exception. That is deliberate and fail-closed: Auto
     * reads it as a TRANSPORT failure and enters recovery, which is honest
     * ("something went wrong, no environment claim"), whereas reusing
     * IllegalStateException or an approximate code would let Auto make a trust
     * decision on a fabricated business outcome. §1506's own rule for an
     * unrecognized code is fail-closed, never guess-compatible.
     *
     * Note this mapping is not what currently blocks calls — see the class
     * comment: the adapter TODO() fails earlier, before any typed failure could
     * be produced. Both have to land; neither substitutes for the other.
     */
    private inline fun <T> typed(block: () -> T): T = block()
}
