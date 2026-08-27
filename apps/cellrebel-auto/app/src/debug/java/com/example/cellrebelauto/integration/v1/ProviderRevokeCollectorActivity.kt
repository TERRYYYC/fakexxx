package com.example.cellrebelauto.integration.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import java.io.File
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * P10DBG-COLLECTOR-V1 — debug-only provider-revoke command surface (Auto side,
 * G2 §3 P10 / §5C).
 *
 * WHY THIS EXISTS
 * ---------------
 * `ProviderTrustStore.revoke` was reachable ONLY through ProviderApprovalScreen
 * (MainViewModel.revokeProvider ← MainActivity onRevoke). No adb-fireable
 * command exists, and — decisive for §5C — no way to fire the revoke at a
 * SPECIFIED moment of an in-flight attempt. §5C "run 进行中撤销" asserts the
 * in-flight attempt enters NORMAL release/recovery and is NOT misrouted into
 * qwy's revoked-caller self-cleanup; that is only provable with an exact
 * window.
 *
 * COMMANDS (adb `am start`, singleTop re-fire)
 *
 *   state  — durable readback: pairing rows (Room), running attempts + their
 *            aplusState, trusted-quota total. The before/after probe.
 *   revoke — plain at-rest revoke (§5C 新 run 前): ProviderTrustStore.revoke
 *            over the SAME AppDatabase singleton the UI writes — not a
 *            parallel store that drifts. Readback follows.
 *   arm    — exact-window trigger. Gates (evaluated against durable Room
 *            rows only):
 *              run_active | attempt_state:<STATE> | trusted_count:<N>
 *            actions:
 *              revoke_provider — fire the revoke the moment the gate opens
 *              self_kill       — unclean Auto process death at that moment
 *                                (§5B Auto checkpoint crash window; trusted
 *                                ledger must not double-count after restart)
 *   disarm — cancel a pending arm.
 *
 * Everything appends to filesDir/debug-collector/arm.log (ARMED/FIRED/TIMEOUT/
 * OUTCOME). src/debug ONLY — production carries none of this.
 */
class ProviderRevokeCollectorActivity : Activity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 11f
            setPadding(24, 48, 24, 24)
            text = "P10 provider-revoke collector — working…"
        }
        setContentView(ScrollView(this).apply { addView(view) })
        processIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        view.text = "P10 provider-revoke collector — working…"
        val cmd = intent?.getStringExtra(EXTRA_CMD)?.trim()
        thread(name = "ec-p10-auto-collector") {
            val report = runCatching { dispatch(cmd, intent) }
                .getOrElse { "FAILED: ${it::class.java.name}: ${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun dispatch(cmd: String?, intent: Intent?): String = buildString {
        appendLine("P10 provider-revoke collector — $MARKER")
        appendLine("-".repeat(52))
        when (cmd) {
            "state" -> state()
            "revoke" -> revoke(intent)
            "arm" -> arm(intent)
            "disarm" -> disarm()
            else -> {
                appendLine("REFUSED: --es cmd must be one of state | revoke | arm | disarm")
                appendLine()
                usage()
            }
        }
    }

    // ---- durable readback -------------------------------------------------

    /**
     * Snapshot of Auto's durable run state. Room reads only — the run's truth
     * is in these tables, not in the engine's memory or logcat.
     */
    private fun snapshot(): AutoRunSnapshot = runBlocking {
        val db = AppDatabase.getInstance(applicationContext)
        val attempts = db.testAttemptDao().getAllAttempts()
        val running = attempts.filter { it.status == "starting" || it.status == "running" }
        AutoRunSnapshot(
            runningAttemptCount = running.size,
            runningAplusStates = running.mapNotNull { it.aplusState },
            trustedCountTotal = db.trustedQuotaDao().countAll(),
        )
    }

    private fun StringBuilder.state() {
        val snap = snapshot()
        val db = runBlocking { AppDatabase.getInstance(applicationContext).providerPairingDao().all() }
        appendLine("[state] durable readback (Room):")
        appendLine("running attempts: ${snap.runningAttemptCount} " +
            "aplusStates=${snap.runningAplusStates.distinct().ifEmpty { listOf("—") }}")
        appendLine("trusted quota entries (total): ${snap.trustedCountTotal}")
        appendLine("provider pairing rows: ${db.size}")
        db.forEach {
            appendLine("  ${it.applicationId} signer=${it.currentSignerDigest.take(12)}… " +
                "revokedAt=${it.revokedAt ?: "—"}")
        }
        appendLine()
        appendLine("readback path: Room queries over the app database — durable rows, not memory.")
    }

    // ---- plain revoke -----------------------------------------------------

    private fun StringBuilder.revoke(intent: Intent?) {
        val appId = intent?.getStringExtra(EXTRA_APP_ID)?.trim()
        val signer = intent?.getStringExtra(EXTRA_SIGNER)?.trim()
        if (appId.isNullOrEmpty() || signer.isNullOrEmpty()) {
            appendLine("REFUSED: revoke needs BOTH --es $EXTRA_APP_ID and --es $EXTRA_SIGNER")
            appendLine("Half a principal is not a weaker revoke, it is a different one (§6.5).")
            return
        }
        appendLine("[revoke] ProviderTrustStore.revoke($appId, ${signer.take(12)}…) " +
            "via the app's AppDatabase singleton — the same store the UI writes.")
        // R2 (gpt55 P1-2): the verdict binds to the store's boolean return
        // (true iff the EXACT principal's active row flipped) and the
        // exact-principal activeFor query — broad byApplicationId rows are
        // context, never proof.
        val (revoked, activeAfter, contextRows) = runBlocking {
            val db = AppDatabase.getInstance(applicationContext)
            val dao = db.providerPairingDao()
            val flipped = ProviderTrustStore(dao).revoke(appId, signer, System.currentTimeMillis())
            Triple(
                flipped,
                dao.activeFor(appId, signer) != null,
                dao.byApplicationId(appId).size,
            )
        }
        val verdict = RevokeReadback.verdict(revoked, activeAfter)
        appendLine("store returned: $revoked; exact principal active after: $activeAfter; " +
            "rows for app (context only): $contextRows")
        appendLine(RevokeReadback.render(verdict))
        armLog().appendText(
            AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
                "OUTCOME", "revoke_provider", "at-rest", appId,
                System.currentTimeMillis(), "verdict=$verdict",
            )) + "\n",
        )
    }

    // ---- exact-window arm ---------------------------------------------------

    private fun StringBuilder.arm(intent: Intent?) {
        val action = AutoArmAction.parse(intent?.getStringExtra(EXTRA_ACTION) ?: "")
        val gateToken = intent?.getStringExtra(EXTRA_GATE)?.trim() ?: ""
        val gate = AutoGate.parse(gateToken)
        val appIdRaw = intent?.getStringExtra(EXTRA_APP_ID)?.trim()?.takeIf { it.isNotEmpty() }
        val signer = intent?.getStringExtra(EXTRA_SIGNER)?.trim()?.takeIf { it.isNotEmpty() }
        // R2 (gpt55 P1-1): adb --ei stores Integer and getLongExtra silently
        // defaults — coerce every numeric extra (Int/Long/String accepted).
        val pollMs = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_POLL_MS)) ?: AutoArmSpec.DEFAULT_POLL_MS
        val timeoutMs = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_TIMEOUT_MS)) ?: AutoArmSpec.DEFAULT_TIMEOUT_MS
        val appId = appIdRaw

        val problem = AutoArmSpec.validate(action, gate, gateToken, appId, signer, pollMs, timeoutMs)
        if (problem != null) {
            appendLine("REFUSED: $problem")
            appendLine()
            usage()
            return
        }

        val spec = AutoArmSpec(
            action = action!!,
            gate = gate!!,
            gateToken = gateToken,
            providerApplicationId = appId,
            providerSignerDigest = signer,
            pollMs = pollMs,
            timeoutMs = timeoutMs,
        )
        cancelled = false
        appendLine("[arm] ${spec.action.token} on gate '$gateToken', " +
            "poll ${spec.pollMs}ms, timeout ${spec.timeoutMs}ms")
        armLog().appendText(AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
            "ARMED", spec.action.token, spec.gateToken, appId,
            System.currentTimeMillis(), null,
        )) + "\n")

        val deadline = System.currentTimeMillis() + spec.timeoutMs
        var openSnapshot: AutoRunSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) {
                armLog().appendText(AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
                    "DISARMED", spec.action.token, spec.gateToken, appId,
                    System.currentTimeMillis(), null,
                )) + "\n")
                appendLine("DISARMED before firing.")
                return
            }
            val snap = snapshot()
            if (spec.isSatisfiedBy(snap)) {
                openSnapshot = snap
                break
            }
            Thread.sleep(spec.pollMs)
        }

        if (openSnapshot == null) {
            armLog().appendText(AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
                "TIMEOUT", spec.action.token, spec.gateToken, appId,
                System.currentTimeMillis(), "gate never opened within $timeoutMs ms",
            )) + "\n")
            appendLine("TIMEOUT: gate '$gateToken' never opened within ${spec.timeoutMs}ms — NOT FIRED.")
            return
        }

        appendLine("FIRED at gate: running=${openSnapshot.runningAttemptCount} " +
            "aplus=${openSnapshot.runningAplusStates.distinct()} trusted=${openSnapshot.trustedCountTotal}")
        armLog().appendText(AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
            "FIRED", spec.action.token, spec.gateToken, appId,
            System.currentTimeMillis(),
            "running=${openSnapshot.runningAttemptCount} trusted=${openSnapshot.trustedCountTotal}",
        )) + "\n")

        when (spec.action) {
            AutoArmAction.REVOKE_PROVIDER -> {
                appendLine("[revoke_provider] firing revoke NOW (in-flight moment).")
                // R2 (gpt55 P1-2): same principal-bound proof as the at-rest
                // revoke — store boolean + exact-principal activeFor.
                val (revoked, activeAfter, contextRows) = runBlocking {
                    val db = AppDatabase.getInstance(applicationContext)
                    val dao = db.providerPairingDao()
                    val flipped = ProviderTrustStore(dao)
                        .revoke(spec.providerApplicationId!!, spec.providerSignerDigest!!,
                            System.currentTimeMillis())
                    Triple(
                        flipped,
                        dao.activeFor(spec.providerApplicationId, spec.providerSignerDigest) != null,
                        dao.byApplicationId(spec.providerApplicationId).size,
                    )
                }
                val verdict = RevokeReadback.verdict(revoked, activeAfter)
                appendLine("store returned: $revoked; exact principal active after: $activeAfter; " +
                    "rows for app (context only): $contextRows")
                appendLine(RevokeReadback.render(verdict))
                armLog().appendText(AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
                    "OUTCOME", spec.action.token, spec.gateToken, spec.providerApplicationId,
                    System.currentTimeMillis(), "verdict=$verdict",
                )) + "\n")
                appendLine()
                appendLine("§5C assertion to observe next: the in-flight attempt must enter NORMAL")
                appendLine("release/recovery (see state), NOT qwy's revoked-caller self-cleanup.")
            }
            AutoArmAction.SELF_KILL -> {
                appendLine("[self_kill] unclean Auto process death NOW — no cleanup callbacks run.")
                appendLine("After restart: §5B asserts the trusted ledger does NOT re-count.")
                runOnUiThread { view.text = toString() }
                Thread.sleep(150) // let the FIRED arm-log bytes land
                Process.killProcess(Process.myPid())
            }
        }
    }

    private fun StringBuilder.disarm() {
        cancelled = true
        armLog().appendText(AutoArmRecordCodec.encode(AutoArmRecordCodec.ArmLine(
            "DISARMED", "-", "-", null, System.currentTimeMillis(), "disarm command",
        )) + "\n")
        appendLine("DISARM requested — a pending arm worker stops at its next poll tick.")
    }

    // ---- helpers ------------------------------------------------------------

    private fun armLog(): File {
        val dir = File(applicationContext.filesDir, "debug-collector").apply { mkdirs() }
        return File(dir, "arm.log")
    }

    private fun StringBuilder.usage() {
        appendLine("state:  --es cmd state")
        appendLine("revoke: --es cmd revoke --es app_id <provider> --es signer <sha256>")
        appendLine("arm:    --es cmd arm --es action revoke_provider|self_kill " +
            "--es gate run_active|attempt_state:<STATE>|trusted_count:<N> " +
            "[--es app_id X --es signer Y] [--ei poll_ms 200] [--ei timeout_ms 600000]")
        appendLine("disarm: --es cmd disarm")
    }

    private companion object {
        const val TAG = "ECP10AutoCollector"
        const val MARKER = "P10DBG-COLLECTOR-V1"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ACTION = "action"
        const val EXTRA_GATE = "gate"
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_SIGNER = "signer"
        const val EXTRA_POLL_MS = "poll_ms"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"

        @Volatile
        var cancelled = false
    }
}
