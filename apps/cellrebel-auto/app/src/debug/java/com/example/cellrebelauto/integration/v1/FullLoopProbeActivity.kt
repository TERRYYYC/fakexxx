package com.example.cellrebelauto.integration.v1

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * One full §6.7 loop against a real provider on a real device.
 *
 * ORDER IS THE SPEC'S, NOT CONVENIENCE
 * ------------------------------------
 *   discover → preflight → apply → observe → release → completeAndAdvance → discover
 *
 * release precedes advance because §6.7.4a freezes it that way: an apply lease is
 * bound to the CURRENT item's environment, so advancing while it is held would
 * change the environment under an active lease — exactly the drift wire 9 exists
 * to catch, self-inflicted. The closing discover is not decoration: §6.7.5 says a
 * receipt is the provider's own account, not evidence, so the pointer move is
 * confirmed by observing the provider again rather than by believing the receipt.
 *
 * RELEASE IS IN A FINALLY BLOCK, AND THAT IS THE POINT
 * ---------------------------------------------------
 * apply installs a real mock location on the device this runs on. Every app that
 * reads location sees it until the lease is released. So release is not a step
 * that happens if the loop reaches it — it happens even when an earlier step
 * throws. A probe that leaves a device lying about where it is because an
 * assertion failed halfway is worse than no probe.
 */
class FullLoopProbeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply {
            textSize = 11f
            setPadding(24, 48, 24, 24)
            text = "full §6.7 loop — running…"
        }
        setContentView(ScrollView(this).apply { addView(view) })

        thread(name = "ec-full-loop") {
            val report = runCatching { runLoop() }
                .getOrElse { "LOOP ABORTED: ${it::class.java.name}: ${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun runLoop(): String = buildString {
        appendLine("Environment Control v1 — full loop")
        appendLine("=".repeat(52))

        val binderRef = AtomicReference<IBinder?>(null)
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(n: ComponentName?, s: IBinder?) { binderRef.set(s); latch.countDown() }
            override fun onServiceDisconnected(n: ComponentName?) { binderRef.set(null); latch.countDown() }
            override fun onBindingDied(n: ComponentName?) { binderRef.set(null); latch.countDown() }
            override fun onNullBinding(n: ComponentName?) { binderRef.set(null); latch.countDown() }
        }

        var bound = false
        for (pkg in EnvironmentControlClient.PROVIDER_PACKAGES) {
            val intent = Intent().setComponent(
                ComponentName(pkg, EnvironmentControlClient.PROVIDER_SERVICE_CLASS)
            )
            if (bindService(intent, conn, Context.BIND_AUTO_CREATE)) {
                appendLine("bound to: $pkg"); bound = true; break
            }
        }
        if (!bound) run { appendLine("FAILED: no provider bindable"); return@buildString }
        if (!latch.await(5, TimeUnit.SECONDS)) run { appendLine("FAILED: bind timeout"); return@buildString }
        val svc = IEnvironmentControlV1.Stub.asInterface(
            binderRef.get() ?: run { appendLine("FAILED: null binder"); return@buildString }
        )

        var leaseId: String? = null
        try {
            // ---- 1. discover -------------------------------------------------
            val snap = svc.discover()
            appendLine()
            appendLine("[1] discover → item=${snap.currentItemId} ver=${snap.scheduleVersion} rev=${snap.environmentRevision}")
            val itemId = snap.currentItemId
                ?: run { appendLine("STOP: provider has no current schedule item"); return@buildString }
            val schedVer = snap.scheduleVersion
                ?: run { appendLine("STOP: provider has no schedule version"); return@buildString }

            // ---- 2. preflight ------------------------------------------------
            val intent = EnvironmentIntentV1(
                runId = RUN_ID,
                attemptId = "attempt-${UUID.randomUUID()}",
                profileRef = itemId,
                scheduleRef = snap.currentScheduleId ?: "",
                latitude = 31.2304,
                longitude = 121.4737,
                requiredVerificationWire = 1,
                notBeforeEpochMs = System.currentTimeMillis() - 1_000,
                deadlineEpochMs = System.currentTimeMillis() + 600_000,
            )
            val pre = svc.preflight(PreflightRequestV1(intent, "pf-${UUID.randomUUID()}", ContractV1.PROTOCOL_VERSION))
            appendLine("[2] preflight → decision=${pre.scheduleDecisionWire} blockers=${pre.blockingReasonWires}")
            if (pre.blockingReasonWires.isNotEmpty()) {
                appendLine("    (blocked — continuing anyway to surface the real apply answer)")
            }

            // ---- 3. apply — THE DEVICE ACTUALLY MOVES HERE --------------------
            val receipt = svc.apply(ApplyRequestV1(intent, "ap-${UUID.randomUUID()}", ContractV1.PROTOCOL_VERSION))
            leaseId = receipt.leaseId
            appendLine("[3] apply → lease=${receipt.leaseId.take(8)}… rev=${receipt.environmentRevision} verif=${receipt.verificationLevelWire}")
            appendLine("    intentHash=${receipt.acceptedIntentHash.take(16)}…")

            // ---- 4. observe — independent confirmation (§6.7.5) --------------
            val obs = svc.observe(
                ObserveRequestV1(receipt.leaseId, "ob-${UUID.randomUUID()}", CanonicalIntentDigestV1.compute(intent))
            )
            appendLine("[4] observe → rev=${obs.environmentRevision} coverage=${obs.continuityCoverageWire} mode=${obs.deliveryModeWire}")
            appendLine("    fingerprint=${obs.environmentFingerprint}")
            appendLine("    hashMatch=${obs.acceptedIntentHash == receipt.acceptedIntentHash}")

            // ---- 5. release BEFORE advance (§6.7.4a frozen order) ------------
            val rel = svc.release(ReleaseRequestV1(receipt.leaseId, "rl-${UUID.randomUUID()}", "rk-${UUID.randomUUID()}"))
            leaseId = null // released; the finally-guard no longer needs to fire
            appendLine("[5] release → complete=${rel.releaseComplete} residuals=${rel.residualReasonWires} rev=${rel.environmentRevision}")

            // ---- 6. completeAndAdvance --------------------------------------
            val proof = CompletionProofV1(
                scheduleItemId = itemId,
                trustedSuccessCount = 3,
                quotaRequired = 3,
                ledgerRef = "auto:ledger:$RUN_ID:$itemId",
                verifiedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            val draft = CompleteAndAdvanceRequestV1(
                leaseId = receipt.leaseId,
                idempotencyKey = "adv-${UUID.randomUUID()}",
                requestDigest = "",
                expectedScheduleVersion = schedVer,
                expectedCurrentItemId = itemId,
                completionProof = proof,
                callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
            )
            // The contract's own framing helper — a second local copy would drift
            // silently and simply compute a different digest.
            val advReq = draft.copy(requestDigest = CanonicalAdvanceDigestV1.compute(draft))
            val adv = svc.completeAndAdvance(advReq)
            appendLine("[6] advance → outcome=${adv.outcomeWire} from=${adv.advancedFromItemId} to=${adv.advancedToItemId}")
            appendLine("    verAfter=${adv.scheduleVersionAfter} receiptDigest=${adv.receiptDigest.take(16)}…")

            // ---- 7. re-discover: the receipt is a claim, this is the evidence -
            val after = svc.discover()
            appendLine("[7] discover → item=${after.currentItemId} ver=${after.scheduleVersion} rev=${after.environmentRevision}")
            appendLine()
            appendLine(
                if (after.currentItemId != itemId)
                    "LOOP COMPLETE — pointer moved $itemId → ${after.currentItemId}, independently confirmed"
                else
                    "LOOP COMPLETE — pointer retained at $itemId (expected only when EXHAUSTED)"
            )
        } catch (t: Throwable) {
            appendLine()
            appendLine("FAILED at the step above: ${t::class.java.name}: ${t.message}")
            appendLine("(a null return means the typed wire code could not cross Binder — issue #3)")
        } finally {
            // The device must not be left mocking a location because a step threw.
            leaseId?.let { stuck ->
                val cleaned = runCatching {
                    svc.release(ReleaseRequestV1(stuck, "rl-cleanup", "rk-cleanup-${UUID.randomUUID()}"))
                }
                appendLine()
                appendLine("CLEANUP: released stuck lease ${stuck.take(8)}… → ${cleaned.getOrNull()?.releaseComplete ?: "FAILED"}")
            }
            runCatching { unbindService(conn) }
        }
    }

    private companion object {
        const val TAG = "ECFullLoop"
        const val RUN_ID = "probe-run-1"
    }
}
