package com.example.cellrebelauto.integration.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.db.AppDatabase
import java.security.MessageDigest
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * P10DBG-COLLECTOR-V1 — debug-only A+ §5A seed/run command surface (Auto side).
 *
 * WHY THIS EXISTS
 * ---------------
 * The G2 §5A 10-address block had NO adb-reachable way to (a) seed Auto's plan
 * and (b) start a run:
 *
 *   - a plan is created only by [com.example.cellrebelauto.ui.MainViewModel.importCsv],
 *     driven by the system file picker — not adb-fireable;
 *   - a run is started only by [AutomationService.startAutomation], and the
 *     service is `exported=false` behind BIND_ACCESSIBILITY_SERVICE, so
 *     `adb shell am start`/`startservice` cannot reach it.
 *
 * That is the shared gap① the A/B/C blocks all inherit: no product run can be
 * started from a device shell. This surface closes it with two commands.
 *
 *   seed_plan — decode the frozen fixture payload (pinned to the REGISTERED
 *               digest), build the plan + 10 tasks consuming ONLY
 *               {order, journeyCaseId, requiredSuccesses} — KB-8: Auto does
 *               not import coordinates; the legacy non-null LocationTask
 *               coordinate columns carry an out-of-domain placeholder — insert
 *               atomically, and print the fixtureIndex ↔ taskId attribution map.
 *   start_run — call the SAME companion the UI calls
 *               (AutomationService.startAutomation) for a planId that must be
 *               the seeded FX-G2-10A plan/topology. The verdict binds to a
 *               request-owned durable generation: a NEW RunSession (id > the
 *               pre-command max) whose planId equals the request — never the
 *               global isRunning flag, which another request can flip
 *               (R6 P1-2). RUN_NOT_STARTED / RUN_START_CONFLICT are typed
 *               failures; a stale same-plan attempt can never satisfy it.
 *
 * src/debug ONLY — production carries none of this.
 */
class APlusSeedActivity : Activity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = TextView(this).apply {
            textSize = 11f
            setPadding(24, 48, 24, 24)
            text = "A+ §5A seed/run — working…"
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
        view.text = "A+ §5A seed/run — working…"
        val cmd = intent?.getStringExtra(EXTRA_CMD)?.trim()
        thread(name = "ec-aplus-seed") {
            val report = runCatching { dispatch(cmd, intent) }
                .getOrElse { "FAILED: ${it::class.java.name}: ${it.message}" }
            Log.i(TAG, report)
            runOnUiThread { view.text = report }
        }
    }

    private fun dispatch(cmd: String?, intent: Intent?): String = buildString {
        appendLine("A+ §5A seed/run — $MARKER")
        appendLine("-".repeat(52))
        when (cmd) {
            "seed_plan" -> seedPlan(intent)
            "start_run" -> startRun(intent)
            else -> {
                appendLine("REFUSED: --es cmd must be one of seed_plan | start_run")
                appendLine()
                usage()
            }
        }
    }

    private fun StringBuilder.seedPlan(intent: Intent?) {
        val payloadB64 = intent?.getStringExtra(EXTRA_FIXTURE_PAYLOAD_B64)
        val declaredDigest = intent?.getStringExtra(EXTRA_FIXTURE_DIGEST)
        if (payloadB64.isNullOrEmpty() || declaredDigest.isNullOrEmpty()) {
            appendLine("REFUSED: seed_plan needs --es $EXTRA_FIXTURE_PAYLOAD_B64 and --es $EXTRA_FIXTURE_DIGEST")
            return
        }
        val decoded = Base64.decode(payloadB64, Base64.DEFAULT)
        val computed = sha256Hex(decoded)
        // PR #62 P1-1: pin to the REGISTERED digest, not a caller-supplied one.
        // The recomputed digest must equal the frozen registration (any byte
        // edit fails) AND the declared digest must equal it too (the caller may
        // not register its own). Structure is still validated by parsePayload;
        // the pin covers the per-item vector the structure bind cannot.
        try {
            APlus10APlanSeed.requireRegisteredDigest(computed, declaredDigest)
        } catch (e: IllegalArgumentException) {
            appendLine("REFUSED: ${e.message}")
            return
        }
        val globalBuffer = ExtraCoerce.longOf(intent.extras?.get(EXTRA_GLOBAL_BUFFER_SECONDS))?.toInt()
            ?: APlus10APlanSeed.DEFAULT_GLOBAL_BUFFER_SECONDS

        val items = APlus10APlanSeed.parsePayload(String(decoded, Charsets.UTF_8))
        val plan = APlus10APlanSeed.toPlan(items, globalBuffer)
        val tasks = APlus10APlanSeed.toTasks(items)

        val (planId, taskIds) = runBlocking {
            val db = AppDatabase.getInstance(applicationContext)
            val pid = db.planDao().insertPlanWithTasks(plan, tasks)
            // getTasksForPlan is ordered `priority ASC, csvRow ASC` — the same
            // fixture order the seed built, so taskIds[i] pairs with items[i].
            val tids = db.locationTaskDao().getTasksForPlan(pid).map { it.id }
            pid to tids
        }
        // seedReport throws if the inserted task count diverges — no green over a partial seed.
        // PR #62 P1-1: emit only the independently verified REGISTERED digest.
        append(APlus10APlanSeed.seedReport(items, planId, taskIds, APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST))
        appendLine()
        appendLine("NEXT: start the run with --es cmd start_run --el plan_id $planId (accessibility service must be enabled)")
    }

    private fun StringBuilder.startRun(intent: Intent?) {
        val planId = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_PLAN_ID))
        if (planId == null) {
            appendLine("REFUSED: start_run needs --el plan_id <id> (from a prior seed_plan)")
            return
        }
        // PR #62 R3 P2: bind to the SEEDED FX-G2-10A plan/topology, not any
        // planId. A run started against a foreign plan (a leftover CSV import,
        // a wrong id) would execute the wrong journeys and mis-attribute.
        val topologyMismatch = runBlocking {
            val db = AppDatabase.getInstance(applicationContext)
            val plan = db.planDao().getPlanById(planId)
                ?: return@runBlocking "plan $planId not found — seed it first with cmd=seed_plan"
            val tasks = db.locationTaskDao().getTasksForPlan(planId)
            APlus10APlanSeed.verifyPlanTopology(plan, tasks)
        }
        if (topologyMismatch != null) {
            appendLine("REFUSED: $topologyMismatch")
            return
        }
        // R6 P1-2 — request-owned durable-start generation. startAutomation is
        // a Unit fire-and-forget; the global isRunning flag can be set by a
        // DIFFERENT request while this one is silently ignored ("Already
        // running"), and a stale nonterminal same-plan attempt satisfies any
        // attempt-scanning predicate without this request starting anything.
        // The ONLY durable transition this request can own is a NEW RunSession
        // row (id > pre-command max) whose planId equals the requested plan —
        // captured before the call, verdict typed by APlus10APlanSeed.startRunVerdict.
        appendLine("[start_run] AutomationService.startAutomation(plan=$planId) — the product's own run entry.")
        if (AutomationService.isRunning.value) {
            appendLine("REFUSED: a run is already active (isRunning=true) — one run at a time; stop it first.")
            return
        }
        val preMaxSessionId = runBlocking {
            AppDatabase.getInstance(applicationContext).runSessionDao().getLatest()?.id ?: 0L
        }
        AutomationService.startAutomation(planId)
        val deadline = System.currentTimeMillis() + START_CONFIRM_TIMEOUT_MS
        var latest: com.example.cellrebelauto.model.RunSession? = null
        while (System.currentTimeMillis() < deadline) {
            latest = runBlocking { AppDatabase.getInstance(applicationContext).runSessionDao().getLatest() }
            if (latest != null && latest.id > preMaxSessionId) break
            Thread.sleep(START_CONFIRM_POLL_MS)
        }
        when (val v = APlus10APlanSeed.startRunVerdict(
            preMaxSessionId = preMaxSessionId,
            latestSessionId = latest?.id,
            latestSessionPlanId = latest?.planId,
            requestedPlanId = planId,
        )) {
            is APlus10APlanSeed.StartRunVerdict.Started -> {
                appendLine("RUN_STARTED sessionId=${v.sessionId} planId=${v.planId} " +
                    "(request-owned durable generation: NEW RunSession > pre-max $preMaxSessionId, plan-bound)")
                appendLine("Attribute everything to sessionId=${v.sessionId}: cmd=state lines carry " +
                    "runSessionId + task/session plan legs; rows bound to another session are NOT this run.")
            }
            is APlus10APlanSeed.StartRunVerdict.WrongPlanSession -> {
                appendLine("RUN_START_CONFLICT: new session ${v.sessionId} belongs to plan " +
                    "${v.sessionPlanId ?: "<null>"}, not requested ${v.requestedPlanId} — a concurrent/" +
                    "foreign start won the engine slot. This request did NOT start its run; do not proceed.")
            }
            APlus10APlanSeed.StartRunVerdict.NoNewSession -> {
                appendLine("RUN_NOT_STARTED: no NEW RunSession within ${START_CONFIRM_TIMEOUT_MS}ms " +
                    "(pre-max $preMaxSessionId unchanged). Causes: accessibility service not connected " +
                    "(silent no-op), request ignored as duplicate, or engine failed before session " +
                    "creation. A pre-existing same-plan attempt does NOT satisfy this request.")
            }
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun StringBuilder.usage() {
        // Numeric extras are canonically --el (Long) per the runbook's frozen
        // extra-type discipline; ExtraCoerce tolerates --ei/--es but the
        // documented spelling must not teach the silent-default typo.
        appendLine("seed_plan: --es cmd seed_plan --es $EXTRA_FIXTURE_PAYLOAD_B64 <base64> --es $EXTRA_FIXTURE_DIGEST <sha256> [--el global_buffer_seconds 60]")
        appendLine("start_run: --es cmd start_run --el plan_id <id>")
    }

    private companion object {
        const val TAG = "ECAPlusSeed"
        const val MARKER = "P10DBG-COLLECTOR-V1"
        const val EXTRA_CMD = "cmd"
        const val EXTRA_FIXTURE_PAYLOAD_B64 = "fixture_payload_base64"
        const val EXTRA_FIXTURE_DIGEST = "fixture_digest"
        const val EXTRA_GLOBAL_BUFFER_SECONDS = "global_buffer_seconds"
        const val EXTRA_PLAN_ID = "plan_id"
        const val START_CONFIRM_TIMEOUT_MS = 10_000L
        const val START_CONFIRM_POLL_MS = 200L
    }
}
