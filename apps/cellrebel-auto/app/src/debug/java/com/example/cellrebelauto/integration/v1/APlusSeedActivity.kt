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
 *   seed_plan — decode the frozen fixture payload (its SHA-256 must equal the
 *               executor-recorded digest), build the plan + 10 tasks WITH the
 *               trust-target coordinates, insert atomically, and print the
 *               fixtureIndex ↔ taskId attribution map.
 *   start_run — call the SAME companion the UI calls
 *               (AutomationService.startAutomation) for a given planId. Requires
 *               the accessibility service to be enabled (operator precondition);
 *               reports honestly when it is not connected.
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
        // Byte-exactness: the decoded payload must hash to the executor's frozen
        // fixture digest — otherwise what reached the device is not the fixture.
        if (!computed.equals(declaredDigest, ignoreCase = true)) {
            appendLine("REFUSED: payload digest $computed != declared $declaredDigest")
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
        append(APlus10APlanSeed.seedReport(items, planId, taskIds, declaredDigest))
        appendLine()
        appendLine("NEXT: start the run with --es cmd start_run --el plan_id $planId (accessibility service must be enabled)")
    }

    private fun StringBuilder.startRun(intent: Intent?) {
        val planId = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_PLAN_ID))
        if (planId == null) {
            appendLine("REFUSED: start_run needs --el plan_id <id> (from a prior seed_plan)")
            return
        }
        val exists = runBlocking {
            AppDatabase.getInstance(applicationContext).planDao().getPlanById(planId) != null
        }
        if (!exists) {
            appendLine("REFUSED: plan $planId not found — seed it first with cmd=seed_plan")
            return
        }
        // The SAME entry the product UI uses. startAutomation is a silent no-op
        // when the accessibility service is not connected (it only logs), so a
        // bare "start requested" would be a false green (PR #62 review P2).
        // Poll the service's own isRunning StateFlow with a bounded timeout and
        // report a DISTINCT verdict for each outcome.
        appendLine("[start_run] AutomationService.startAutomation(plan=$planId) — the product's own run entry.")
        if (AutomationService.isRunning.value) {
            appendLine("REFUSED: a run is already active (isRunning=true) — one run at a time; stop it first.")
            return
        }
        AutomationService.startAutomation(planId)
        val deadline = System.currentTimeMillis() + START_CONFIRM_TIMEOUT_MS
        var confirmed = false
        while (System.currentTimeMillis() < deadline) {
            if (AutomationService.isRunning.value) { confirmed = true; break }
            Thread.sleep(START_CONFIRM_POLL_MS)
        }
        if (confirmed) {
            appendLine("START_CONFIRMED isRunning=true within ${START_CONFIRM_TIMEOUT_MS}ms.")
            appendLine("Observe attempts via ProviderRevokeCollector cmd=state; durable rows are the truth, not this line.")
        } else {
            appendLine("START_NOT_CONFIRMED: isRunning stayed false for ${START_CONFIRM_TIMEOUT_MS}ms after the call.")
            appendLine("Most likely the CellRebel Auto accessibility service is not enabled/connected")
            appendLine("(startAutomation is a silent no-op without it — operator precondition). Do NOT treat this as started.")
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
