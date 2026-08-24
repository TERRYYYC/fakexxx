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
import com.example.cellrebelauto.recovery.ContractResponseValidator
import com.example.cellrebelauto.recovery.ContractResponseValidator.ValidatedContractResponse
import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
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
 *
 * EVERY RESPONSE GOES THROUGH [ContractResponseValidator]
 * -------------------------------------------------------
 * Raw AIDL returns an [EnvironmentControlResultV1] carrier that can carry schema
 * mismatches, unexpected result kinds, foreign payloads, and unknown wire codes.
 * The probe must not silently accept any of these — a false green from a probe
 * is worse than a false red, because it sends an operator to a device with a
 * promise the wire didn't actually make. So every call site routes through the
 * unified validator that already exists on main, and fails the probe on any
 * violation rather than ploughing on.
 */
class FullLoopProbeActivity : Activity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 11f
            setPadding(24, 48, 24, 24)
            text = "full §6.7 loop — running…"
        }
        setContentView(ScrollView(this).apply { addView(view) })
        launchProbe()
    }

    /**
     * F-11: singleTop + onNewIntent so repeated `adb shell am start` re-runs the
     * full loop instead of silently bringing the stale result to front. The loop
     * includes a finally-block release, so the previous run's mock state is cleaned
     * up before the new run starts.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchProbe()
    }

    private fun launchProbe() {
        view.text = "full §6.7 loop — running…"
        thread(name = "ec-full-loop") {
            val report = runCatching { runLoop() }
                .getOrElse { "LOOP ABORTED: ${it::class.java.name}: ${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    /**
     * Unwrap a validated response or fail the probe report.
     * Every call site in this loop uses this — no raw payload extraction.
     */
    private fun <T> StringBuilder.requireValid(
        step: String,
        result: ValidatedContractResponse<T>
    ): T? = when (result) {
        is ValidatedContractResponse.Success -> result.payload
        is ValidatedContractResponse.Failure -> {
            appendLine("FAILED: $step → ${result.typedOutcome}")
            null
        }
    }

    private fun runLoop(): String = buildString {
        appendLine("Environment Control v1 — full loop (validated)")
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
            // ---- 1. discover — validated ----------------------------------------
            val snap = requireValid("[1] discover",
                ContractResponseValidator.validateDiscover(svc.discover())
            ) ?: return@buildString
            appendLine()
            appendLine("[1] discover → item=${snap.currentItemId} ver=${snap.scheduleVersion} rev=${snap.environmentRevision}")
            val itemId = snap.currentItemId
                ?: run { appendLine("STOP: provider has no current schedule item"); return@buildString }
            val schedVer = snap.scheduleVersion
                ?: run { appendLine("STOP: provider has no schedule version"); return@buildString }
            val schedId = snap.currentScheduleId
                ?: run { appendLine("STOP: provider has no current schedule id"); return@buildString }

            // ---- 2. preflight — validated ---------------------------------------
            // KB-8: coordinates removed from EnvironmentIntentV1 — the provider
            // resolves them from its own schedule item data; Auto passes only
            // profileRef + scheduleRef as references.
            val intent = EnvironmentIntentV1(
                runId = RUN_ID,
                attemptId = "attempt-${UUID.randomUUID()}",
                profileRef = itemId,
                scheduleRef = schedId,
                requiredVerificationWire = 1,
                notBeforeEpochMs = System.currentTimeMillis() - 1_000,
                deadlineEpochMs = System.currentTimeMillis() + 600_000,
            )
            val intentHash = CanonicalIntentDigestV1.compute(intent)
            val pfKey = "pf-${UUID.randomUUID()}"
            val pre = requireValid("[2] preflight",
                ContractResponseValidator.validatePreflight(
                    svc.preflight(PreflightRequestV1(intent, pfKey, ContractV1.PROTOCOL_VERSION)),
                    intentHash
                )
            ) ?: return@buildString
            appendLine("[2] preflight → decision=${pre.scheduleDecisionWire} blockers=${pre.blockingReasonWires}")
            if (pre.blockingReasonWires.isNotEmpty()) {
                appendLine("    (blocked — continuing anyway to surface the real apply answer)")
            }

            // ---- 3. apply — THE DEVICE ACTUALLY MOVES HERE (validated) ----------
            val applyKey = "ap-${UUID.randomUUID()}"
            val receipt = requireValid("[3] apply",
                ContractResponseValidator.validateApply(
                    svc.apply(ApplyRequestV1(intent, applyKey, ContractV1.PROTOCOL_VERSION)),
                    applyKey, intentHash
                )
            ) ?: return@buildString
            leaseId = receipt.leaseId
            appendLine("[3] apply → lease=${receipt.leaseId.take(8)}… rev=${receipt.environmentRevision} verif=${receipt.verificationLevelWire}")
            appendLine("    intentHash=${receipt.acceptedIntentHash.take(16)}…")

            // ---- 4. observe — independent confirmation (§6.7.5, validated) ------
            val obs = requireValid("[4] observe",
                ContractResponseValidator.validateObserve(
                    svc.observe(ObserveRequestV1(receipt.leaseId, "ob-${UUID.randomUUID()}", intentHash)),
                    receipt.leaseId, intentHash
                )
            ) ?: return@buildString
            appendLine("[4] observe → rev=${obs.environmentRevision} coverage=${obs.continuityCoverageWire} mode=${obs.deliveryModeWire}")
            appendLine("    fingerprint=${obs.environmentFingerprint}")
            appendLine("    hashMatch=${obs.acceptedIntentHash == receipt.acceptedIntentHash}")

            // ---- 5. release BEFORE advance (§6.7.4a, validated) -----------------
            // CRITICAL: leaseId is ONLY cleared when the validator confirms
            // releaseComplete=true. An incomplete release leaves the cleanup guard
            // armed and halts the probe — the device must not be left in mock state,
            // and an unproven cleanup must not be followed by advance.
            val rlKey = "rl-${UUID.randomUUID()}"
            val rkKey = "rk-${UUID.randomUUID()}"
            val rel = requireValid("[5] release",
                ContractResponseValidator.validateRelease(
                    svc.release(ReleaseRequestV1(receipt.leaseId, rlKey, rkKey)),
                    receipt.leaseId
                )
            )
            if (rel == null) {
                // releaseComplete=false lands here via PROVIDER_RELEASE_INCOMPLETE.
                // leaseId is NOT cleared — finally cleanup guard stays armed.
                appendLine("SAFETY: lease NOT cleared — finally cleanup will attempt re-release")
                return@buildString
            }
            leaseId = null // validated: releaseComplete=true, cleanup guard can retire
            appendLine("[5] release → complete=${rel.releaseComplete} residuals=${rel.residualReasonWires} rev=${rel.environmentRevision}")

            // ---- 6. completeAndAdvance — validated ------------------------------
            val proof = CompletionProofV1(
                scheduleItemId = itemId,
                trustedSuccessCount = 3,
                quotaRequired = 3,
                ledgerRef = "auto:ledger:$RUN_ID:$itemId",
                verifiedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )
            val advKey = "adv-${UUID.randomUUID()}"
            val draft = CompleteAndAdvanceRequestV1(
                leaseId = receipt.leaseId,
                idempotencyKey = advKey,
                requestDigest = "",
                expectedScheduleId = schedId,
                expectedScheduleVersion = schedVer,
                expectedCurrentItemId = itemId,
                completionProof = proof,
                callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
            )
            // The contract's own framing helper — a second local copy would drift
            // silently and simply compute a different digest.
            val requestDigest = CanonicalAdvanceDigestV1.compute(draft)
            val advReq = draft.copy(requestDigest = requestDigest)
            val adv = requireValid("[6] advance",
                ContractResponseValidator.validateCompleteAndAdvance(
                    svc.completeAndAdvance(advReq),
                    intentHash, requestDigest, advKey
                )
            ) ?: return@buildString
            appendLine("[6] advance → outcome=${adv.outcomeWire} from=${adv.advancedFromItemId} to=${adv.advancedToItemId}")
            appendLine("    verAfter=${adv.scheduleVersionAfter} receiptDigest=${adv.receiptDigest.take(16)}…")

            // ---- 7. post-advance verification — outcome-dependent ---------------
            // The advance outcome determines WHICH verification carrier to use and
            // WHICH predicate to check. The two paths are frozen in §6.7.5:
            //   ADVANCED  → observe() on the released historical lease, four legs
            //   EXHAUSTED → discover() readback, non-null group + four legs
            // The production consumer splits identically (AutomationEngine.kt:1371-1425).
            val outcome = AdvanceOutcomeV1.fromWire(adv.outcomeWire)
                ?: run { appendLine("FAILED: decoded outcome is null despite validator pass — internal error"); return@buildString }

            appendLine()
            when (outcome) {
                AdvanceOutcomeV1.ADVANCED -> {
                    // §6.7.5 non-terminal: observe() on the released historical lease.
                    // The receipt says the pointer moved — we must independently verify
                    // that the NEW environment matches by observing it through the lease
                    // that just advanced. All four legs must bind the receipt.
                    val postObs = requireValid("[7a] post-advance observe",
                        ContractResponseValidator.validateObserve(
                            svc.observe(ObserveRequestV1(
                                receipt.leaseId, "ob-post-adv-${UUID.randomUUID()}",
                                adv.effectiveIntentHash
                            )),
                            receipt.leaseId, adv.effectiveIntentHash
                        )
                    ) ?: return@buildString
                    appendLine("[7a] post-advance observe → item=${postObs.scheduleItemId} ver=${postObs.scheduleVersion} rev=${postObs.environmentRevision}")

                    // Four-leg predicate (AutomationEngine.kt:1393-1399)
                    val mismatchLeg: String? = when {
                        postObs.scheduleItemId != adv.advancedToItemId ->
                            "scheduleItemId (${postObs.scheduleItemId} vs ${adv.advancedToItemId})"
                        postObs.scheduleVersion != adv.scheduleVersionAfter ->
                            "scheduleVersion (${postObs.scheduleVersion} vs ${adv.scheduleVersionAfter})"
                        postObs.acceptedIntentHash != adv.effectiveIntentHash ->
                            "acceptedIntentHash (${postObs.acceptedIntentHash.take(16)}… vs ${adv.effectiveIntentHash.take(16)}…)"
                        postObs.environmentRevision != adv.effectiveEnvironmentRevision ->
                            "environmentRevision (${postObs.environmentRevision} vs ${adv.effectiveEnvironmentRevision})"
                        else -> null
                    }
                    if (mismatchLeg != null) {
                        appendLine("FAILED: post-advance observe mismatch — leg $mismatchLeg")
                    } else {
                        appendLine("LOOP COMPLETE — ADVANCED: pointer moved $itemId → ${adv.advancedToItemId}, independently observed")
                        appendLine("    four-leg observe: scheduleItemId=${postObs.scheduleItemId} ver=${postObs.scheduleVersion} rev=${postObs.environmentRevision} hash=${postObs.acceptedIntentHash.take(16)}…")
                    }
                }
                AdvanceOutcomeV1.EXHAUSTED -> {
                    // §6.7.5 terminal: fresh discover() readback. The receipt says the
                    // schedule is exhausted — we must verify the provider's live state
                    // confirms it. The v1.55 non-null group precondition must pass before
                    // any leg comparison; then four legs must bind.
                    val readback = requireValid("[7b] exhausted readback",
                        ContractResponseValidator.validateDiscover(svc.discover())
                    ) ?: return@buildString
                    appendLine("[7b] exhausted readback → sched=${readback.currentScheduleId} item=${readback.currentItemId} ver=${readback.scheduleVersion} exhausted=${readback.exhausted}")

                    // Non-null group precondition (AutomationEngine.kt:1410-1415)
                    val readbackMismatchLeg: String? = when {
                        readback.currentScheduleId == null -> "currentScheduleId_null"
                        readback.currentItemId == null -> "currentItemId_null"
                        readback.scheduleVersion == null -> "scheduleVersion_null"
                        readback.exhausted == null -> "exhausted_null"
                        // Four-leg predicate (AutomationEngine.kt:1416-1419)
                        readback.currentScheduleId != schedId ->
                            "currentScheduleId (${readback.currentScheduleId} vs $schedId)"
                        readback.currentItemId != adv.advancedFromItemId ->
                            "currentItemId (${readback.currentItemId} vs ${adv.advancedFromItemId})"
                        readback.scheduleVersion != adv.scheduleVersionAfter ->
                            "scheduleVersion (${readback.scheduleVersion} vs ${adv.scheduleVersionAfter})"
                        readback.exhausted != true ->
                            "exhausted (${readback.exhausted} — expected true)"
                        else -> null
                    }
                    if (readbackMismatchLeg != null) {
                        appendLine("FAILED: exhausted readback mismatch — leg $readbackMismatchLeg")
                    } else {
                        appendLine("LOOP COMPLETE — EXHAUSTED: pointer retained at ${readback.currentItemId}, schedule confirmed exhausted")
                        appendLine("    four-leg readback: schedId=${readback.currentScheduleId} item=${readback.currentItemId} ver=${readback.scheduleVersion} exhausted=${readback.exhausted}")
                    }
                }
            }
        } catch (t: Throwable) {
            appendLine()
            appendLine("FAILED at the step above: ${t::class.java.name}: ${t.message}")
        } finally {
            // The device must not be left mocking a location because a step threw.
            // This is the LAST safety path — it fires when the primary release failed
            // or was never reached. It MUST go through the validator: a raw receipt
            // that reports releaseComplete=true on a wrong-schema/wrong-kind/foreign-
            // payload/different-lease response would tell the operator "cleaned up"
            // while the device stays in mock state.
            leaseId?.let { stuck ->
                val cleanupResult = runCatching {
                    ContractResponseValidator.validateRelease(
                        svc.release(ReleaseRequestV1(stuck, "rl-cleanup", "rk-cleanup-${UUID.randomUUID()}")),
                        stuck
                    )
                }
                appendLine()
                when (val validated = cleanupResult.getOrNull()) {
                    is ValidatedContractResponse.Success ->
                        appendLine("CLEANUP: released stuck lease ${stuck.take(8)}… → VALIDATED releaseComplete=true")
                    is ValidatedContractResponse.Failure ->
                        appendLine("CLEANUP UNSAFE: lease ${stuck.take(8)}… release validation failed → ${validated.typedOutcome} — DEVICE MAY STILL BE IN MOCK STATE")
                    null ->
                        appendLine("CLEANUP UNSAFE: lease ${stuck.take(8)}… release threw ${cleanupResult.exceptionOrNull()?.message} — DEVICE MAY STILL BE IN MOCK STATE")
                }
            }
            runCatching { unbindService(conn) }
        }
    }

    private companion object {
        const val TAG = "ECFullLoop"
        const val RUN_ID = "probe-run-1"
    }
}
