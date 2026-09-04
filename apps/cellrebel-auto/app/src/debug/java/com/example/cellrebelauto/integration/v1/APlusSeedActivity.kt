package com.example.cellrebelauto.integration.v1

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.os.SystemClock
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
 *               the seeded FX-G2-10A plan/topology. R7 P1-2: single-flight
 *               (process-wide lock = the request's owner token). R8 P1-2
 *               (Option D): the START_RECEIPT is OBSERVED after the product
 *               call on the product's own published verdict (public `logs`
 *               shape + isRunning), never predicted before it; only an
 *               observed accept reaches the verdict bound to a request-owned
 *               durable generation — exactly ONE new RunSession (MAX(id)
 *               fence, cardinality != 1 rejected) with the requested planId
 *               AND the durable first-attempt milestone. Zero-attempt
 *               paused/terminal sessions are typed RUN_START_DEGENERATE,
 *               never RUN_STARTED; a stale same-plan attempt, a racing loser,
 *               or a rejected request can never borrow a foreign session.
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
        val seedReport = APlus10APlanSeed.seedReport(items, planId, taskIds, APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST)
        // PR #62 merge-gate P1 (codex inline 3898022696, Sol @ faf561d): every seed_plan inserts a
        // NEW plan and the earlier FX-G2-10A plans stay in the DB with identical topology, so
        // start_run must be bound to THIS seed's identity, not to "a plan that matches". Record the
        // latest seed only after seedReport proved the seed complete, and under START_RUN_LOCK so a
        // concurrent start_run reads either the previous record or this one — never a torn write.
        val latest = synchronized(START_RUN_LOCK) {
            APlusSeedBinding.record(
                prefs = APlusSeedBinding.prefs(applicationContext),
                planId = planId,
                token = APlusSeedBinding.newToken(),
                fixtureDigest = APlus10APlanSeed.REGISTERED_FIXTURE_DIGEST,
                seededAtElapsedMs = SystemClock.elapsedRealtime(),
            )
        }
        append(seedReport)
        appendLine(
            "SEED_BOUND generation=${latest.generation} plan=$planId " +
                "${APlusSeedBinding.EXTRA_SEED_TOKEN}=${latest.token} (earlier seeds are no longer startable)",
        )
        appendLine()
        appendLine(
            "NEXT: start the run with --es cmd start_run --el plan_id $planId " +
                "--es ${APlusSeedBinding.EXTRA_SEED_TOKEN} ${latest.token} (accessibility service must be enabled)",
        )
    }

    private fun StringBuilder.startRun(intent: Intent?) {
        val planId = ExtraCoerce.longOf(intent?.extras?.get(EXTRA_PLAN_ID))
        if (planId == null) {
            appendLine("REFUSED: start_run needs --el plan_id <id> (from a prior seed_plan)")
            return
        }
        val seedToken = intent?.getStringExtra(APlusSeedBinding.EXTRA_SEED_TOKEN)
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
        // R7 P1-2 → R8 P1-2 (Option D) — request-owned start with an OBSERVED receipt.
        //
        // (1) SINGLE-FLIGHT: processIntent spawns one thread per intent, so two
        //     start_run callers could race the same pre-max snapshot and the
        //     loser could borrow the winner's session (Sol's R6 counterexample).
        //     All of precheck + pre-max + startAutomation + receipt + poll runs
        //     under one process-wide lock: the loser enters only after the
        //     winner's verdict, and only an ACCEPTED request ever polls.
        // (2) PRECHECKS are fast-path REFUSALS only (instance reflected — the
        //     production field is private and must stay untouched). They save a
        //     pointless call; the verdict never depends on them.
        // (3) RECEIPT is OBSERVED, not predicted (Sol R8 P1-2: "accepted" printed
        //     BEFORE the call was the check half of a check-then-act — the UI
        //     could start the same plan in between and the harness would have
        //     attributed the UI's session). startAutomation is a synchronous
        //     direct call into the product's check-and-set, which publishes its
        //     own verdict: reject → exactly one "Already running…" entry on the
        //     public logs StateFlow; accept → isRunning=true and NO entry before
        //     launch. Snapshot logs before, read logs+isRunning immediately
        //     after, and accept ONLY the two provable shapes; anything else (a
        //     foreign engine's log forwarder overwriting the window, an ERROR
        //     line, a no-op call) is indeterminate and never attributes.
        // (4) GENERATION (unchanged): pre-max via MAX(id); ALL rows id > pre-max
        //     are read and any cardinality other than one is rejected; Started
        //     additionally requires the durable first-attempt milestone.
        //     Closure: accepted_observed proves the product's check-and-set took
        //     THIS call, so isRunning is ours from here — a later UI click is
        //     rejected by the product, an earlier foreign start would have
        //     rejected US. Hence accepted_observed + exactly one new session > M
        //     + plan-bound + first-attempt milestone ⇒ the session is ours.
        synchronized(START_RUN_LOCK) {
            // PR #62 merge-gate P1 (3898022696): identity FIRST — the exact latest seed_plan
            // (plan_id AND seed_token), read under the same lock seed_plan writes it. The topology
            // check above proves structure; this proves it is THIS seed and not a stale one whose
            // tasks may already carry statuses, attempts and trusted-quota rows.
            val bindingMismatch = APlusSeedBinding.verifyLatestSeed(
                APlusSeedBinding.prefs(applicationContext), planId, seedToken,
            )
            if (bindingMismatch != null) {
                appendLine("REFUSED: $bindingMismatch")
                appendLine(
                    "RUN_NOT_STARTED: start_run is bound to the exact latest seed_plan — " +
                        "re-run seed_plan and use the plan_id + seed_token it prints.",
                )
                return
            }
            appendLine("START_BINDING latest_seed plan=$planId ok")
            appendLine("[start_run] AutomationService.startAutomation(plan=$planId) — the product's own run entry.")
            val serviceInstance = AutomationService::class.java
                .getDeclaredField(SERVICE_INSTANCE_FIELD)
                .apply { isAccessible = true }
                .get(null)
            if (serviceInstance == null) {
                appendLine("START_PRECHECK not_connected")
                appendLine("RUN_NOT_STARTED: accessibility service not connected — startAutomation " +
                    "would be a silent no-op. Enable the service, then re-run.")
                return
            }
            if (AutomationService.isRunning.value) {
                appendLine("START_PRECHECK already_running")
                appendLine("RUN_NOT_STARTED: a run is already active (isRunning=true) — one run at a " +
                    "time; stop it first. A pre-existing run NEVER satisfies this request.")
                return
            }
            val db = AppDatabase.getInstance(applicationContext)
            val preMaxSessionId = db.query("SELECT COALESCE(MAX(id), 0) FROM run_sessions", null).use { c ->
                c.moveToFirst(); c.getLong(0)
            }
            val logsBefore = AutomationService.logs.value
            AutomationService.startAutomation(planId)
            val logsAfter = AutomationService.logs.value
            val runningAfter = AutomationService.isRunning.value
            val receipt = APlus10APlanSeed.observeStartReceipt(logsBefore, logsAfter, runningAfter)
            when (receipt) {
                APlus10APlanSeed.StartReceipt.AcceptedObserved -> {
                    appendLine("START_RECEIPT accepted_observed (product check-and-set published no reject; " +
                        "isRunning=true after the call — this request owns the engine slot)")
                }
                APlus10APlanSeed.StartReceipt.RejectedAlreadyRunning -> {
                    appendLine("START_RECEIPT rejected_already_running (product published " +
                        "'${APlus10APlanSeed.PRODUCT_REJECT_SENTINEL}' for THIS call)")
                    appendLine("RUN_NOT_STARTED: a foreign start won the engine slot between precheck and call — " +
                        "any session that appears after pre-max $preMaxSessionId belongs to it, NOT to this request.")
                    return
                }
                is APlus10APlanSeed.StartReceipt.Indeterminate -> {
                    appendLine("START_RECEIPT indeterminate: ${receipt.reason}")
                    appendLine("RUN_NOT_STARTED: the product's verdict for this call is unobservable — refusing to " +
                        "attribute any session after pre-max $preMaxSessionId. Stop any run, then re-run.")
                    return
                }
            }
            val verdict = APlus10APlanSeed.onlyIfAccepted(receipt) { pollStartVerdict(db, planId, preMaxSessionId) }
                ?: return
            when (val v = verdict) {
                is APlus10APlanSeed.StartRunVerdict.Started -> {
                    appendLine("RUN_STARTED sessionId=${v.sessionId} planId=${v.planId} firstAttemptId=${v.firstAttemptId} " +
                        "(request-owned generation: accepted_observed + single NEW RunSession > pre-max $preMaxSessionId, " +
                        "plan-bound, durable first-attempt milestone present)")
                    appendLine("Attribute everything to sessionId=${v.sessionId}: cmd=state lines carry " +
                        "runSessionId + task/session plan legs; rows bound to another session are NOT this run.")
                }
                is APlus10APlanSeed.StartRunVerdict.WrongPlanSession -> {
                    appendLine("RUN_START_CONFLICT: new session ${v.sessionId} belongs to plan " +
                        "${v.sessionPlanId ?: "<null>"}, not requested ${v.requestedPlanId} — a concurrent/" +
                        "foreign start won the engine slot. This request did NOT start its run; do not proceed.")
                }
                is APlus10APlanSeed.StartRunVerdict.AmbiguousNewSessions -> {
                    appendLine("RUN_START_CONFLICT: ${v.sessionIds.size} new sessions ${v.sessionIds} appeared " +
                        "after pre-max $preMaxSessionId — attribution is ambiguous (racing starters). " +
                        "REFUSING to attribute; recover manually before re-running.")
                }
                is APlus10APlanSeed.StartRunVerdict.DegenerateSession -> {
                    appendLine("RUN_START_DEGENERATE sessionId=${v.sessionId} status=${v.status}: the session " +
                        "was created but reached '${v.status}' with ZERO attempts (provider discovery " +
                        "failure / already-complete plan). NOT a usable run — do not attribute results to it.")
                }
                APlus10APlanSeed.StartRunVerdict.AwaitingMilestone -> {
                    appendLine("RUN_NOT_STARTED: session created but no durable first-attempt milestone " +
                        "within ${START_CONFIRM_TIMEOUT_MS}ms — engine start unproven; treat as not started.")
                }
                APlus10APlanSeed.StartRunVerdict.NoNewSession -> {
                    appendLine("RUN_NOT_STARTED: no NEW RunSession within ${START_CONFIRM_TIMEOUT_MS}ms " +
                        "(pre-max $preMaxSessionId unchanged) — request ignored as duplicate or engine " +
                        "failed before session creation. A pre-existing same-plan attempt does NOT " +
                        "satisfy this request.")
                }
            }
        } // synchronized(START_RUN_LOCK)
    }

    /**
     * Request-owned generation poll (R7 P1-2). Reachable ONLY through
     * [APlus10APlanSeed.onlyIfAccepted] after an observed accept — a rejected
     * or indeterminate request never reads the sessions a foreign start made.
     */
    private fun pollStartVerdict(db: AppDatabase, planId: Long, preMaxSessionId: Long): APlus10APlanSeed.StartRunVerdict {
        val deadline = System.currentTimeMillis() + START_CONFIRM_TIMEOUT_MS
        var verdict: APlus10APlanSeed.StartRunVerdict = APlus10APlanSeed.StartRunVerdict.NoNewSession
        while (System.currentTimeMillis() < deadline) {
            val newSessions = db.query(
                "SELECT id, planId, status FROM run_sessions WHERE id > ? ORDER BY id ASC",
                arrayOf(preMaxSessionId),
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(APlus10APlanSeed.NewSessionRow(
                            id = c.getLong(0),
                            planId = if (c.isNull(1)) null else c.getLong(1),
                            status = c.getString(2),
                        ))
                    }
                }
            }
            val firstAttemptId = newSessions.singleOrNull()?.let { s ->
                db.query(
                    "SELECT id FROM test_attempts WHERE runSessionId = ? ORDER BY id ASC LIMIT 1",
                    arrayOf(s.id),
                ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            }
            verdict = APlus10APlanSeed.startRunVerdict(
                newSessions = newSessions,
                requestedPlanId = planId,
                firstAttemptId = firstAttemptId,
            )
            val nonTerminal = verdict is APlus10APlanSeed.StartRunVerdict.AwaitingMilestone ||
                verdict is APlus10APlanSeed.StartRunVerdict.NoNewSession
            if (!nonTerminal) break
            Thread.sleep(START_CONFIRM_POLL_MS)
        }
        return verdict
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun StringBuilder.usage() {
        // Numeric extras are canonically --el (Long) per the runbook's frozen
        // extra-type discipline; ExtraCoerce tolerates --ei/--es but the
        // documented spelling must not teach the silent-default typo.
        appendLine("seed_plan: --es cmd seed_plan --es $EXTRA_FIXTURE_PAYLOAD_B64 <base64> --es $EXTRA_FIXTURE_DIGEST <sha256> [--el global_buffer_seconds 60]")
        appendLine("start_run: --es cmd start_run --el plan_id <id> --es ${APlusSeedBinding.EXTRA_SEED_TOKEN} <token from the latest seed_plan report>")
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

        /**
         * R7 P1-2 single-flight owner token: every start_run request holds
         * this process-wide monitor across precheck + pre-max + start + observed receipt + poll,
         * so a losing same-plan racer enters only after the winner's verdict
         * and can never borrow the winner's session.
         */
        val START_RUN_LOCK = Any()

        /** AutomationService.instance backing field (private; reflected read-only). */
        const val SERVICE_INSTANCE_FIELD = "instance"
    }
}
