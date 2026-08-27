package name.caiyao.fakegps.integration.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * P10DBG-COLLECTOR-V1 — debug-only fault & revoke collector for G2 §5B/§5C
 * (gate-audit P10: "先落一个 debug-only、能按 exact window 触发并直接回读
 * durable state 的 collector").
 *
 * THREE COMMANDS, ALL adb-DRIVEN (`am start`, singleTop so re-launch re-fires)
 *
 *   dump  — durable-state readback ONLY. Never boots the provider runtime:
 *           booting runs §8.4 recovery and would destroy the very evidence
 *           being read (see QwyDurableSnapshot). This is the executor's
 *           before/after probe for every injection.
 *
 *   arm   — the exact-window trigger. A gate (lease_active / lease_acquiring /
 *           lease_releasing, optionally scoped to one caller) is polled against
 *           COMMITTED durable lease state; when it opens, the action fires:
 *             self_kill     — unclean process death AT the gated moment
 *                             (§5B.2 进程重启 / M-LS-07 window; the kill is
 *                             Process.killProcess — no user-space cleanup runs,
 *                             which is the point)
 *             revoke_caller — onCallerRevoked(appId, signer) at the gated
 *                             moment (§5C qwy run 中撤销: active lease →
 *                             REVOKED by the REAL transition, then readback)
 *
 *   cleanup_revoked — runRevokedLeaseCleanup(): §6.3.3 qwy-internal
 *                             self-cleanup (REVOKED → RELEASING → RELEASED /
 *                             RELEASE_INCOMPLETE). This transition had ZERO
 *                             call sites; without it §5C cannot be observed
 *                             to convergence.
 *
 *   disarm — cancels a pending arm (best-effort; a killed process cancels
 *            itself by dying).
 *
 * EVERYTHING WRITES AN ARM RECORD — filesDir/debug-collector/arm.log, append
 * only (ARMED/FIRED/TIMEOUT/DISARMED/OUTCOME lines via ArmRecordCodec). The
 * product stores stay the state truth; the arm log is the "what did the
 * collector do" trail an evidence pack can bind.
 *
 * src/debug ONLY — hard boundary of the dispatch: production carries none of
 * this (P10CollectorSurfaceGuardTest + check-debug-only-collector.sh pin it).
 */
class FaultCollectorActivity : Activity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 11f
            setPadding(24, 48, 24, 24)
            text = "P10 fault/revoke collector — working…"
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
        view.text = "P10 fault/revoke collector — working…"
        val cmd = intent?.getStringExtra(EXTRA_CMD)?.trim()
        thread(name = "ec-p10-collector") {
            val report = runCatching { dispatch(cmd, intent) }
                .getOrElse { "FAILED: ${it::class.java.name}: ${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun dispatch(cmd: String?, intent: Intent?): String = buildString {
        appendLine("P10 fault/revoke collector — $MARKER")
        appendLine("-".repeat(52))
        when (cmd) {
            "dump" -> dump(intent)
            "arm" -> arm(intent)
            "disarm" -> disarm()
            "cleanup_revoked" -> cleanupRevoked()
            else -> {
                appendLine("REFUSED: --es cmd must be one of dump | arm | disarm | cleanup_revoked")
                appendLine()
                usage()
            }
        }
    }

    // ---- dump ----------------------------------------------------------

    private fun StringBuilder.dump(intent: Intent?) {
        val appId = intent?.getStringExtra(EXTRA_APP_ID)
        val signer = intent?.getStringExtra(EXTRA_SIGNER)
        appendLine("[dump] durable state, verbatim as committed (runtime NOT booted):")
        appendLine()
        val snapshot = QwyDurableSnapshot.capture(
            QwyDurableSnapshot.durableDir(this@FaultCollectorActivity),
            appId, signer,
        )
        append(QwyDurableSnapshot.render(snapshot))
        appendLine()
        appendLine("readback path: fresh FileDurableKv load — on-disk bytes, not memory.")
    }

    // ---- arm -----------------------------------------------------------

    private fun StringBuilder.arm(intent: Intent?) {
        val action = ArmAction.parse(intent?.getStringExtra(EXTRA_ACTION) ?: "")
        val gateToken = intent?.getStringExtra(EXTRA_GATE)?.trim()
        val gate = FaultGate.parse(gateToken ?: "")
        val caller = intent?.getStringExtra(EXTRA_CALLER)?.trim()?.takeIf { it.isNotEmpty() }
        val signer = intent?.getStringExtra(EXTRA_SIGNER)?.trim()?.takeIf { it.isNotEmpty() }
        // R2 (gpt55 P1-1): adb --ei stores Integer and getLongExtra silently
        // defaults — every numeric extra is coerced (Int/Long/String accepted).
        val pollMs = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_POLL_MS)) ?: ArmSpec.DEFAULT_POLL_MS
        val timeoutMs = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_TIMEOUT_MS)) ?: ArmSpec.DEFAULT_TIMEOUT_MS
        // R2 (gpt55 P1-3): scope binds the FULL principal when both halves are
        // given, so the gate can only open on THAT principal's in-flight lease.
        val scope = CallerScope(caller, signer)
        // A previous disarm must not poison this arm: the flag is per-process
        // and process-local, so re-arm resets it (killed processes reset by dying).
        cancelled = false

        val problem = ArmSpec.validate(action, gate, scope, pollMs, timeoutMs)
        if (problem != null) {
            appendLine("REFUSED: $problem")
            appendLine()
            usage()
            return
        }

        val spec = ArmSpec(
            action = action!!,
            gate = gate!!,
            scope = scope,
            pollMs = pollMs,
            timeoutMs = timeoutMs,
        )

        val armLog = armLogFile()
        appendLine(armLine(spec, "ARMED", detail = "polling every ${spec.pollMs}ms, timeout ${spec.timeoutMs}ms"))
        appendLine("[arm] ${spec.action.token} on gate ${spec.gate.token}" +
            (spec.scope.applicationId?.let { " scoped to caller $it" } ?: ""))
        armLog.appendText(ArmRecordCodec.encode(ArmRecordCodec.ArmLine(
            kind = "ARMED", action = spec.action.token, gate = spec.gate.token,
            caller = spec.scope.applicationId, atMs = System.currentTimeMillis(), detail = null,
        )) + "\n")

        val deadline = System.currentTimeMillis() + spec.timeoutMs
        var fireSnapshot: QwyLeaseSnapshot? = null
        while (System.currentTimeMillis() < deadline) {
            if (cancelled) {
                armLog.appendText(ArmRecordCodec.encode(ArmRecordCodec.ArmLine(
                    kind = "DISARMED", action = spec.action.token, gate = spec.gate.token,
                    caller = spec.scope.applicationId, atMs = System.currentTimeMillis(), detail = null,
                )) + "\n")
                appendLine("DISARMED before firing.")
                return
            }
            val snapshot = QwyDurableSnapshot.capture(
                QwyDurableSnapshot.durableDir(this@FaultCollectorActivity)
            ).lease
            if (spec.isSatisfiedBy(snapshot)) {
                fireSnapshot = snapshot
                break
            }
            Thread.sleep(spec.pollMs)
        }

        if (fireSnapshot == null) {
            armLog.appendText(ArmRecordCodec.encode(ArmRecordCodec.ArmLine(
                kind = "TIMEOUT", action = spec.action.token, gate = spec.gate.token,
                caller = spec.scope.applicationId, atMs = System.currentTimeMillis(),
                detail = "gate never opened within ${spec.timeoutMs}ms",
            )) + "\n")
            appendLine("TIMEOUT: gate ${spec.gate.token} never opened within ${spec.timeoutMs}ms — NOT FIRED.")
            return
        }

        appendLine(armLine(spec, "FIRED",
            detail = "lease=${fireSnapshot.currentLeaseId} state=${fireSnapshot.leaseState}"))
        armLog.appendText(ArmRecordCodec.encode(ArmRecordCodec.ArmLine(
            kind = "FIRED", action = spec.action.token, gate = spec.gate.token,
            caller = spec.scope.applicationId, atMs = System.currentTimeMillis(),
            detail = "lease=${fireSnapshot.currentLeaseId} state=${fireSnapshot.leaseState}",
        )) + "\n")

        when (spec.action) {
            ArmAction.SELF_KILL -> {
                appendLine("[self_kill] unclean process death NOW (no cleanup callbacks run — M-LS-07 window).")
                appendLine("After restart, read back with cmd=dump: expect §8.4 recovery semantics.")
                // Flush the report first: the UI thread may or may not paint it
                // before death — the durable arm log already carries FIRED.
                runOnUiThread { view.text = toString() }
                Thread.sleep(150) // let the FIRED arm-log bytes reach the fs
                Process.killProcess(Process.myPid())
            }
            ArmAction.REVOKE_CALLER -> {
                // R2 (gpt55 P1-2 companion): the proof is the exact principal's
                // BEFORE→AFTER transition, never broad post-conditions. Capture
                // before firing so a typo'd/never-paired principal reports
                // "NOTHING_ACTIVE", not a lying green.
                val before = QwyDurableSnapshot.capture(
                    QwyDurableSnapshot.durableDir(this@FaultCollectorActivity),
                    spec.scope.applicationId, spec.scope.signerDigest,
                )
                // The REAL transition, through the runtime singleton — the same
                // single-writer the contract path uses (pairing revoke + lease
                // REVOKED + audit row, M-PA-09/M-LS-04).
                ProviderRuntime.handler(applicationContext)
                    .onCallerRevoked(spec.scope.applicationId!!, spec.scope.signerDigest!!)
                val post = QwyDurableSnapshot.capture(
                    QwyDurableSnapshot.durableDir(this@FaultCollectorActivity),
                    spec.scope.applicationId, spec.scope.signerDigest,
                )
                appendLine("[revoke_caller] onCallerRevoked fired. Durable readback:")
                appendLine()
                append(QwyDurableSnapshot.render(post))
                val verdict = QwyRevokeProof.verdict(
                    beforeActive = before.pairingStillActive,
                    afterActive = post.pairingStillActive,
                    revokeAudited = post.revokeAudited,
                )
                appendLine()
                appendLine(QwyRevokeProof.render(verdict))
                val outcome = "verdict=$verdict stillActive=${post.pairingStillActive} revokedAudited=${post.revokeAudited}"
                appendLine()
                appendLine("OUTCOME: $outcome")
                armLog.appendText(ArmRecordCodec.encode(ArmRecordCodec.ArmLine(
                    kind = "OUTCOME", action = spec.action.token, gate = spec.gate.token,
                    caller = spec.scope.applicationId, atMs = System.currentTimeMillis(),
                    detail = outcome,
                )) + "\n")
            }
        }
    }

    // ---- disarm / cleanup ----------------------------------------------

    private fun StringBuilder.disarm() {
        cancelled = true
        armLogFile().appendText(ArmRecordCodec.encode(ArmRecordCodec.ArmLine(
            kind = "DISARMED", action = "-", gate = "-", caller = null,
            atMs = System.currentTimeMillis(), detail = "disarm command",
        )) + "\n")
        appendLine("DISARM requested — a pending arm worker will stop at its next poll tick.")
        appendLine("(If the armed process was killed, the poller died with it; this is belt-and-braces.)")
    }

    private fun StringBuilder.cleanupRevoked() {
        appendLine("[cleanup_revoked] runRevokedLeaseCleanup via runtime singleton…")
        ProviderRuntime.handler(applicationContext).runRevokedLeaseCleanup()
        val post = QwyDurableSnapshot.capture(QwyDurableSnapshot.durableDir(this@FaultCollectorActivity))
        appendLine("durable readback:")
        appendLine()
        append(QwyDurableSnapshot.render(post))
    }

    // ---- helpers ---------------------------------------------------------

    private fun armLogFile(): File {
        val dir = File(applicationContext.filesDir, "debug-collector").apply { mkdirs() }
        return File(dir, "arm.log")
    }

    private fun armLine(spec: ArmSpec, kind: String, detail: String? = null): String =
        "[$kind] ${spec.action.token} gate=${spec.gate.token}" +
            (detail?.let { " $it" } ?: "")

    private fun StringBuilder.usage() {
        appendLine("dump:            --es cmd dump [--es app_id X --es signer Y]")
        appendLine("arm self-kill:   --es cmd arm --es action self_kill --es gate lease_active|lease_acquiring|lease_releasing [--es caller pkg] [--ei poll_ms 200] [--ei timeout_ms 600000]")
        appendLine("arm revoke:      --es cmd arm --es action revoke_caller --es caller pkg --es signer sha256 --es gate lease_active")
        appendLine("cleanup revoked: --es cmd cleanup_revoked")
        appendLine("disarm:          --es cmd disarm")
    }

    private companion object {
        const val TAG = "ECP10Collector"
        const val MARKER = "P10DBG-COLLECTOR-V1"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ACTION = "action"
        const val EXTRA_GATE = "gate"
        const val EXTRA_CALLER = "caller"
        const val EXTRA_APP_ID = "app_id"
        const val EXTRA_SIGNER = "signer"
        const val EXTRA_POLL_MS = "poll_ms"
        const val EXTRA_TIMEOUT_MS = "timeout_ms"

        /** Disarm flag; volatile because the arm worker lives on another thread. */
        @Volatile
        var cancelled = false
    }
}
