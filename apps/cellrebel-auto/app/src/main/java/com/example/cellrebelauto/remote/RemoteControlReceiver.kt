package com.example.cellrebelauto.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.automation.AutomationStartStatus
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.audit.AutoAuditEvent
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Wire contract of the remote-control broadcast surface (P1.2 外部触发接口). Action and
 * permission names are scoped by THIS build's applicationId so lane installs
 * (debug / glmbench / release) can never drive each other's engines.
 * # 动作与权限名按本构建 applicationId 命名空间隔离，lane 之间互不可控
 */
object RemoteControlContract {
    private const val SUFFIX_ACTION = ".remote.control."
    const val PERMISSION_SUFFIX = ".permission.REMOTE_CONTROL"

    fun permissionName(packageName: String): String = "$packageName$PERMISSION_SUFFIX"

    fun actionStartPlan(packageName: String): String = "${packageName}${SUFFIX_ACTION}START_PLAN"
    fun actionStop(packageName: String): String = "${packageName}${SUFFIX_ACTION}STOP"
    fun actionResume(packageName: String): String = "${packageName}${SUFFIX_ACTION}RESUME"
    fun actionResetPlan(packageName: String): String = "${packageName}${SUFFIX_ACTION}RESET_PLAN"
    fun actionStatus(packageName: String): String = "${packageName}${SUFFIX_ACTION}STATUS"

    /** STATUS resultCode encoding: `am broadcast` prints result=<code> — one glance, one line. */
    const val RESULT_IDLE = 0          // service connected, engine idle, plan ready
    const val RESULT_RUNNING = 1       // engine mid-run
    const val RESULT_HELD = 2          // engine parked on a terminal needing operator action
    const val RESULT_SERVICE_DOWN = 3  // accessibility service not connected — fix that first
}

/**
 * T3 (P1.2): exported broadcast entry for unattended device ops — one adb line
 * instead of 8 minutes of blocked UI coordinate injection (the HyperOS input-
 * injection blockade). SECURITY, two belts:
 *  1. the manifest protects this receiver with the signature-level
 *     `${applicationId}.permission.REMOTE_CONTROL` — the SYSTEM drops foreign
 *     senders before delivery (uid 0 / root adb is exempt, exactly as in the
 *     framework's component-permission check);
 *  2. [handle] re-checks the caller and IGNORES anything unpermitted with a
 *     WARN naming the caller. Denied attempts write NO audit row (an
 *     unauthorized caller must not be able to spam the audit stream).
 *
 * ROUTING DISCIPLINE: there is NO second engine here. Every accepted action
 * delegates to the SAME entry the Plan/Run page buttons invoke:
 *   START_PLAN / RESUME -> AutomationService.startAutomation(latestPlanId)
 *                          (Start and Resume are one idempotent entry, INV-9;
 *                          BOTH_STAGES_OFF / admission guards live in the
 *                          engine and surface via the startStatus flow)
 *   STOP                -> AutomationService.stopAutomation()
 *   RESET_PLAN          -> PlanRepository.resetPlanAsFreshGeneration()
 *                          (the repository's transactional guard re-checks
 *                          completion / RECOVERY_REQUIRED — a running or
 *                          mid-progress plan is REFUSED, never reset blindly)
 *   STATUS              -> reads the AutomationService companion projections
 *                          (+ latest plan progress) and replies via the
 *                          ordered-broadcast result for `am broadcast` output.
 *
 * Every processed action appends an auto_audit_events row whose eventType is
 * prefixed REMOTE_CONTROL_ (the audit table has no separate source column —
 * the prefix IS the source marker), carrying the caller identity and the
 * routing result (DISPATCHED / REFUSED_NO_PLAN / REFUSED:<reason> /
 * RESET:<id> / STATUS:<code>).
 *
 * # 无人值守运维的 exported broadcast 入口：权限被拒→忽略+Log.w（含 caller）；
 * # 有效动作全部复用 UI 按钮背后的同一入口，禁止旁路新建状态机；逐动作审计
 */
class RemoteControlReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "RemoteControl"

        /** Terminal states that need an operator decision before anything moves again. */
        private val HELD_STATES =
            setOf(AutomationState.PAUSED, AutomationState.ERROR, AutomationState.SERVICE_RECYCLED)

        /**
         * DB seam (R44 MainViewModel.injectedDb precedent): production resolves
         * the Room singleton; Robolectric oracles inject an in-memory instance.
         */
        @Volatile
        internal var dbProvider: (Context) -> AppDatabase = { AppDatabase.getInstance(it) }

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                handle(appContext, intent)
            } catch (t: Throwable) {
                Log.w(
                    TAG,
                    "RemoteControl: handler failure action=${intent.action} " +
                        "${t.javaClass.simpleName}: ${t.message}"
                )
            } finally {
                pending.finish()
            }
        }
    }

    internal suspend fun handle(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (!isCallerAllowed(context, action)) return

        val db = dbProvider(context)
        val repository = PlanRepository(db)

        when (action) {
            RemoteControlContract.actionStartPlan(context.packageName) ->
                handleStartOrResume(context, db, isResume = false)
            RemoteControlContract.actionResume(context.packageName) ->
                handleStartOrResume(context, db, isResume = true)
            RemoteControlContract.actionStop(context.packageName) -> {
                Log.w(TAG, "RemoteControl: STOP -> AutomationService.stopAutomation()")
                AutomationService.stopAutomation()
                appendAudit(context, db, "REMOTE_CONTROL_STOP", "result=DISPATCHED")
            }
            RemoteControlContract.actionResetPlan(context.packageName) ->
                handleReset(context, db, repository)
            RemoteControlContract.actionStatus(context.packageName) ->
                handleStatus(context, db, repository)
            else -> Log.w(TAG, "RemoteControl: unknown action=$action ignored")
        }
    }

    // ---- security ------------------------------------------------------------

    private fun isCallerAllowed(context: Context, action: String): Boolean {
        val callerUid = Binder.getCallingUid()
        // Root (uid 0 — the adb `su -c am broadcast` ops path) and the system's
        // own delivery identity are exempt, mirroring the framework's
        // component-permission check that already guards the manifest attribute.
        //
        // Own uid must ALSO be exempt: manifest receivers are dispatched through the
        // app's own message queue, and on that dispatch path Binder.getCallingUid()
        // returns the LOCAL uid, not the sender's — the sender's identity never
        // propagates here. Device evidence (mi14 e53cfd3d, HyperOS/16): every
        // `su -c am broadcast` was DENIED with caller=<this app's own package>,
        // i.e. the defense-in-depth check rejected the very ops path it exists to
        // allow. Real sender-side enforcement stays with the manifest permission
        // attribute; this check can only ever see system/self, never the sender.
        if (callerUid == Process.ROOT_UID ||
            callerUid == Process.SYSTEM_UID ||
            callerUid == Process.myUid()
        ) return true
        val permission = RemoteControlContract.permissionName(context.packageName)
        val granted = context.checkPermission(permission, Binder.getCallingPid(), callerUid) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.w(
                TAG,
                "RemoteControl: DENIED action=$action caller=${resolveCaller(context, callerUid)} " +
                    "(requires $permission)"
            )
        }
        return granted
    }

    private fun resolveCaller(context: Context, uid: Int): String =
        context.packageManager.getNameForUid(uid) ?: "uid:$uid"

    // ---- actions ---------------------------------------------------------------

    /**
     * START_PLAN and RESUME are one entry (the engine's recovery sweep makes
     * starting idempotent, INV-9) — exactly what Plan/Run's Start/Resume
     * buttons call via MainViewModel.startOrResumePlan.
     * # START/RESUME 同一入口（引擎恢复清扫保证幂等），无计划时安全 no-op
     */
    private suspend fun handleStartOrResume(context: Context, db: AppDatabase, isResume: Boolean) {
        val verb = if (isResume) "RESUME" else "START_PLAN"
        val plan = db.planDao().getLatestPlan()
        if (plan == null) {
            Log.w(TAG, "RemoteControl: $verb REFUSED_NO_PLAN — import a worklist CSV first")
            appendAudit(context, db, "REMOTE_CONTROL_$verb", "result=REFUSED_NO_PLAN")
            return
        }
        Log.w(TAG, "RemoteControl: $verb -> AutomationService.startAutomation(plan=${plan.id})")
        AutomationService.startAutomation(plan.id)
        appendAudit(context, db, "REMOTE_CONTROL_$verb", "result=DISPATCHED;plan=${plan.id}")
    }

    /**
     * RESET_PLAN — the repository entry re-checks its guard INSIDE the
     * transaction (unfinished plan with no RECOVERY_REQUIRED attempt is
     * REFUSED), so a mid-progress plan can never be reset blindly.
     * # RESET_PLAN 复用 resetPlanAsFreshGeneration 的事务内守卫
     */
    private suspend fun handleReset(context: Context, db: AppDatabase, repository: PlanRepository) {
        when (val outcome = repository.resetPlanAsFreshGeneration()) {
            is PlanRepository.PlanResetOutcome.Reset -> {
                Log.w(
                    TAG,
                    "RemoteControl: RESET_PLAN -> new plan=${outcome.newPlanId} (${outcome.rows} rows); " +
                        "provider schedule_reset + re-approval still required"
                )
                appendAudit(
                    context, db, "REMOTE_CONTROL_RESET_PLAN",
                    "result=RESET:newPlanId=${outcome.newPlanId};rows=${outcome.rows}"
                )
            }
            is PlanRepository.PlanResetOutcome.NoPlan -> {
                Log.w(TAG, "RemoteControl: RESET_PLAN REFUSED_NO_PLAN — nothing to reset")
                appendAudit(context, db, "REMOTE_CONTROL_RESET_PLAN", "result=REFUSED_NO_PLAN")
            }
            is PlanRepository.PlanResetOutcome.Refused -> {
                Log.w(TAG, "RemoteControl: RESET_PLAN REFUSED — ${outcome.reason}")
                appendAudit(context, db, "REMOTE_CONTROL_RESET_PLAN", "result=REFUSED;${outcome.reason}")
            }
        }
    }

    /**
     * STATUS — answers the ordered broadcast so `am broadcast` prints the state:
     * resultCode encodes the coarse state, resultData a one-line human summary,
     * resultExtras the machine-readable fields. The payload is assembled by
     * [buildStatusResult] (pure, JVM-oracle-drivable); [handleStatus] only
     * applies it to the ordered-broadcast result.
     * # STATUS：resultCode 编码状态 + 人话摘要回显，adb 一行可查
     */
    internal data class StatusResult(
        val code: Int,
        val dataLine: String,
        val extras: Bundle
    )

    internal suspend fun buildStatusResult(db: AppDatabase, repository: PlanRepository): StatusResult {
        val connected = AutomationService.isServiceConnected.value
        val running = AutomationService.isRunning.value
        val state = AutomationService.currentState.value
        val plan = db.planDao().getLatestPlan()
        var fileName = "-"
        var progress = "-"
        if (plan != null) {
            fileName = plan.sourceFileName
            val trusted = repository.observeTasksWithTrustedCounts(plan.id)
                .first()
                .sumOf { it.trustedSuccesses }
            progress = "$trusted/${plan.totalRequiredSuccesses}"
        }
        val lastRejection = (AutomationService.startStatus.value as? AutomationStartStatus.Rejected)?.reason
        val (code, summary) = when {
            !connected -> RemoteControlContract.RESULT_SERVICE_DOWN to
                "SERVICE OFF — enable accessibility for this app, then RESUME"
            running -> RemoteControlContract.RESULT_RUNNING to
                "Running: ${state.name} (${state.displayName}) progress=$progress plan=$fileName"
            state in HELD_STATES -> RemoteControlContract.RESULT_HELD to
                "Held: ${state.name} (${state.displayName}) progress=$progress plan=$fileName"
            else -> RemoteControlContract.RESULT_IDLE to
                "Idle: plan=$fileName progress=$progress — send START_PLAN"
        }
        val line = if (lastRejection != null) "$summary lastStartRejection=$lastRejection" else summary
        return StatusResult(
            code = code,
            dataLine = line,
            extras = Bundle().apply {
                putString("state", state.name)
                putString("running", running.toString())
                putString("service", connected.toString())
                putString("plan", fileName)
                putString("progress", progress)
                putString("summary", line)
            }
        )
    }

    private suspend fun handleStatus(context: Context, db: AppDatabase, repository: PlanRepository) {
        val result = buildStatusResult(db, repository)
        // setResult* is legal only while an ordered result is pending — goAsync()
        // in onReceive provides exactly that on-device. The guard keeps direct
        // invocation paths (JVM oracles) from crashing on a missing dispatch.
        runCatching {
            setResultExtras(result.extras)
            setResultData(result.dataLine)
            setResultCode(result.code)
        }.onFailure {
            Log.w(TAG, "RemoteControl: STATUS result not applicable to this dispatch: ${it.message}")
        }
        Log.w(TAG, "RemoteControl: STATUS result=${result.code} summary=\"${result.dataLine}\"")
        appendAudit(
            context, db, "REMOTE_CONTROL_STATUS",
            "result=STATUS:code=${result.code};state=${result.extras.getString("state")}"
        )
    }

    // ---- audit ----------------------------------------------------------------

    private suspend fun appendAudit(context: Context, db: AppDatabase, eventType: String, digest: String) {
        db.auditEventDao().insert(
            AutoAuditEvent(
                // Same single-writer monotonic convention as APlusAttemptDriver: length + 1.
                seq = db.auditEventDao().count().toLong() + 1,
                attemptId = null, // plan-level / engine-level events
                correlationRef = "caller=${resolveCaller(context, Binder.getCallingUid())}",
                eventType = eventType,
                payloadDigest = digest,
                recordedAt = System.currentTimeMillis()
            )
        )
    }
}
