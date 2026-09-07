package com.example.cellrebelauto.remote

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Binder
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.automation.AutomationStartStatus
import com.example.cellrebelauto.automation.AutomationService.Companion as SvcCompanion
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowLog

/**
 * T3 (P1.2) remote-control receiver oracle.
 *
 * Security model: the manifest protects the exported receiver with the
 * signature-level `<applicationId>.permission.REMOTE_CONTROL`, so on-device
 * the SYSTEM drops foreign senders before delivery (root/uid 0 exempt — the
 * `adb shell su -c am broadcast` ops path). The in-receiver gate is the
 * second belt: unpermitted dispatch paths must be IGNORED (zero engine entry
 * calls, zero audit rows) with a WARN naming the caller.
 *
 * Routing discipline: an authorized action must reach exactly the SAME entry
 * the UI buttons use — never a parallel path:
 *   START_PLAN / RESUME -> AutomationService.startAutomation(latestPlanId)
 *   STOP                -> AutomationService.stopAutomation()
 *   RESET_PLAN          -> PlanRepository.resetPlanAsFreshGeneration()
 *   STATUS              -> ordered-broadcast result with the engine summary
 *
 * Observability note (JVM): the accessibility service cannot run under
 * Robolectric, so `startAutomation` observably fails with the typed
 * SERVICE_NOT_CONNECTED rejection — reaching that rejection PROVES the real
 * companion entry was invoked. STOP is observed by attaching a real service
 * via the same reflection seam as AutomationServiceRecycleStateTest and
 * cancelling an injected live automation job.
 *
 * # T3 远程控制接收器 oracle：无权限→零调用+WARN；有权限→逐动作路由到
 * # UI 按钮背后的同一入口；无效迁移安全 no-op；逐动作审计；STATUS 回显
 */
@RunWith(RobolectricTestRunner::class)
@Config(packageName = "com.example.cellrebelauto")
class RemoteControlReceiverTest {

    private lateinit var context: Application
    private lateinit var db: AppDatabase
    private lateinit var receiver: RemoteControlReceiver

    private val pkg: String get() = context.packageName

    // A foreign-app identity that holds NO signature grant unless the test grants it.
    private val strangerUid = 10042
    private val callerPid = 100

    @Before
    fun setUp() {
        ShadowLog.setupLogging()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        RemoteControlReceiver.dbProvider = { db }
        receiver = RemoteControlReceiver()
        resetServiceCompanion()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        db.close()
        // Restore the production singleton resolver — the seam is static.
        RemoteControlReceiver.dbProvider = { ctx -> AppDatabase.getInstance(ctx) }
    }

    // ---- helpers -------------------------------------------------------------

    private fun resetServiceCompanion() {
        companionFlow("_isRunning").value = false
        companionFlow("_currentState").value = AutomationState.IDLE
        companionFlow("_startStatus").value = AutomationStartStatus.IDLE
        companionFlow("_isServiceConnected").value = false
        companionFlow("_currentTask").value = null
        companionFlow("_cooldown").value = null
        companionFlow("_lastFailure").value = null
        companionFlow("_logs").value = emptyList<String>()
        val instanceField = AutomationService::class.java.declaredFields
            .firstOrNull { it.name == "instance" }
        instanceField?.isAccessible = true
        instanceField?.set(null, null)
    }

    /** Reflection seam onto the companion's private StateFlows (same as RecycleStateTest). */
    @Suppress("UNCHECKED_CAST")
    private fun companionFlow(name: String): kotlinx.coroutines.flow.MutableStateFlow<Any?> {
        val outer = AutomationService::class.java
        val staticField = outer.declaredFields.firstOrNull { it.name == name }
        if (staticField != null) {
            staticField.isAccessible = true
            return staticField.get(null) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
        }
        val companionClass = outer.declaredClasses.first { it.simpleName == "Companion" }
        val holder = outer.declaredFields.first { it.type == companionClass }
        holder.isAccessible = true
        val companionInstance = holder.get(null)
        val field = companionClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(companionInstance) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
    }

    /** Attaches a real AutomationService via the RecycleStateTest reflection seam. */
    private fun newConnectedService(): AutomationService {
        val service = AutomationService()
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
        val connect = AutomationService::class.java.getDeclaredMethod("onServiceConnected")
        connect.isAccessible = true
        connect.invoke(service)
        return service
    }

    private fun grantStranger() {
        shadowOf(context).grantPermissions(callerPid, strangerUid, RemoteControlContract.permissionName(pkg))
    }

    private fun asStranger() {
        ShadowBinder.setCallingPid(callerPid)
        ShadowBinder.setCallingUid(strangerUid)
    }

    private fun asRoot() {
        ShadowBinder.setCallingPid(callerPid)
        ShadowBinder.setCallingUid(0) // root adb — the su -c am broadcast path
    }

    private fun remoteIntent(action: String): Intent = Intent(action).setPackage(pkg)

    private suspend fun handleSync(action: String) {
        receiver.handle(context, remoteIntent(action))
    }

    private suspend fun seedPlan(taskStatuses: List<String>, fileName: String = "worklist.csv"): Long {
        return db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = fileName,
                importedAt = 1_000L,
                globalBufferSeconds = 30,
                totalRows = taskStatuses.size,
                totalRequiredSuccesses = taskStatuses.size
            ),
            taskStatuses.mapIndexed { i, status ->
                LocationTask(
                    planId = 0,
                    csvRow = i + 1,
                    longitude = 30.0 + i,
                    latitude = 50.0 + i,
                    priority = 0,
                    requiredSuccesses = 1,
                    completedSuccesses = if (status == "completed") 1 else 0,
                    status = status
                )
            }
        )
    }

    private suspend fun auditEvents() = db.auditEventDao().all()

    private fun denyLogs() = ShadowLog.getLogsForTag(TAG).filter { it.msg.contains("DENIED") }

    private fun allRemoteLogs() = ShadowLog.getLogsForTag(TAG)

    // ---- security: unauthorized callers are ignored --------------------------

    @Test
    fun `caller without the permission - every action is ignored with zero engine entries and a WARN naming the caller`() =
        runBlocking {
            asStranger() // holds NO grant

            val actions = listOf(
                RemoteControlContract.actionStartPlan(pkg),
                RemoteControlContract.actionStop(pkg),
                RemoteControlContract.actionResume(pkg),
                RemoteControlContract.actionResetPlan(pkg),
                RemoteControlContract.actionStatus(pkg)
            )
            actions.forEach { action -> receiver.handle(context, remoteIntent(action)) }

            // Zero engine entry calls: the start entry was never reached (no SERVICE_NOT_CONNECTED
            // rejection appeared) and no run projection moved.
            assertEquals(AutomationStartStatus.IDLE, SvcCompanion.startStatus.value)
            assertFalse(SvcCompanion.isRunning.value)
            assertEquals(AutomationState.IDLE, SvcCompanion.currentState.value)
            // Zero audit rows — an unauthorized caller cannot write into the audit stream.
            assertTrue("denied broadcasts must not write audit", auditEvents().isEmpty())
            // Each denied action logs a WARN carrying the caller identity.
            val warns = denyLogs()
            assertEquals("one WARN per denied action", actions.size, warns.size)
            warns.forEach { assertTrue("WARN must name the caller: ${it.msg}", it.msg.contains("caller")) }
        }

    @Test
    fun `granted stranger - START_PLAN routes to the real AutomationService start entry`() = runBlocking {
        asStranger()
        grantStranger()
        val planId = seedPlan(listOf("pending", "pending"))

        handleSync(RemoteControlContract.actionStartPlan(pkg))

        // The engine entry is the Start/Resume button's own entry; under Robolectric the
        // accessibility host cannot exist, so the entry itself answers with the typed rejection.
        assertEquals(
            "START_PLAN must reach AutomationService.startAutomation (the UI button entry)",
            AutomationStartStatus.Rejected("SERVICE_NOT_CONNECTED"),
            SvcCompanion.startStatus.value
        )
        val remote = auditEvents().last { it.eventType == "REMOTE_CONTROL_START_PLAN" }
        assertTrue(remote.payloadDigest.contains("result=DISPATCHED"))
        assertTrue(remote.correlationRef.orEmpty().contains("caller"))
        assertNull("plan-level event has no attempt", remote.attemptId)
        // The seeded plan is untouched.
        assertEquals(planId, db.planDao().getLatestPlan()?.id)
    }

    @Test
    fun `root uid without any grant - START_PLAN routes (the adb su -c am broadcast ops path)`() = runBlocking {
        asRoot()
        seedPlan(listOf("pending"))

        handleSync(RemoteControlContract.actionStartPlan(pkg))

        assertEquals(
            AutomationStartStatus.Rejected("SERVICE_NOT_CONNECTED"),
            SvcCompanion.startStatus.value
        )
    }

    @Test
    fun `self dispatch uid - STATUS is processed, never DENIED (mi14 dispatch-path regression)`() = runBlocking {
        // Device evidence 2026-09-07, mi14 e53cfd3d (HyperOS/16): manifest receivers are
        // dispatched with Binder.getCallingUid() == the app's OWN uid — the sender's
        // identity never propagates. The defense-in-depth check must exempt self or
        // every `su -c am broadcast` ops call is rejected with caller=<own package>.
        ShadowBinder.setCallingPid(callerPid)
        ShadowBinder.setCallingUid(android.os.Process.myUid())

        handleSync(RemoteControlContract.actionStatus(pkg))

        assertTrue(
            "self-uid dispatch must not log DENIED: " +
                denyLogs().joinToString { it.msg },
            denyLogs().isEmpty()
        )
    }

    @Test
    fun `granted stranger - RESUME routes through the same start entry (Resume == Start, INV-9)`() = runBlocking {
        asStranger()
        grantStranger()
        seedPlan(listOf("pending", "active"))

        handleSync(RemoteControlContract.actionResume(pkg))

        assertEquals(
            "RESUME must use the same AutomationService.startAutomation entry",
            AutomationStartStatus.Rejected("SERVICE_NOT_CONNECTED"),
            SvcCompanion.startStatus.value
        )
        val remote = auditEvents().last { it.eventType == "REMOTE_CONTROL_RESUME" }
        assertTrue(remote.payloadDigest.contains("result=DISPATCHED"))
    }

    @Test
    fun `granted caller - STOP cancels the live automation job via AutomationService stopRunning`() = runBlocking {
        val service = newConnectedService()
        // A live "run" the operator would want to stop remotely.
        val liveJob = launch { awaitCancellation() }
        val jobField = AutomationService::class.java.getDeclaredField("automationJob")
        jobField.isAccessible = true
        jobField.set(service, liveJob)
        asStranger()
        grantStranger()

        try {
            handleSync(RemoteControlContract.actionStop(pkg))
            withTimeout(5_000) {
                while (!liveJob.isCancelled) delay(10)
            }
            assertTrue("STOP must cancel the engine's automation job", liveJob.isCancelled)
            val remote = auditEvents().last { it.eventType == "REMOTE_CONTROL_STOP" }
            assertTrue(remote.payloadDigest.contains("result=DISPATCHED"))
        } finally {
            liveJob.cancel()
            withContext(NonCancellable) { liveJob.join() }
        }
    }

    @Test
    fun `granted caller - RESET_PLAN regenerates the plan via resetPlanAsFreshGeneration`() = runBlocking {
        asStranger()
        grantStranger()
        val oldId = seedPlan(listOf("completed", "completed"))

        handleSync(RemoteControlContract.actionResetPlan(pkg))

        val newId = db.planDao().getLatestPlan()?.id
        assertNotEquals("reset must insert a NEW plan generation", oldId, newId)
        // The repository entry wrote its own typed PLAN_RESET audit row.
        assertTrue(
            auditEvents().any {
                it.eventType == "PLAN_RESET" && it.correlationRef == "plan:$oldId->$newId"
            }
        )
        val remote = auditEvents().last { it.eventType == "REMOTE_CONTROL_RESET_PLAN" }
        assertTrue(remote.payloadDigest.contains("result=RESET"))
    }

    // ---- invalid transitions: safe no-op + log + audit -----------------------

    @Test
    fun `START_PLAN with no plan is a safe no-op - engine entry not called, audited as REFUSED_NO_PLAN`() =
        runBlocking {
            asRoot()

            handleSync(RemoteControlContract.actionStartPlan(pkg))

            assertEquals(
                "no plan -> the start entry must not be reached",
                AutomationStartStatus.IDLE,
                SvcCompanion.startStatus.value
            )
            val remote = auditEvents().single()
            assertEquals("REMOTE_CONTROL_START_PLAN", remote.eventType)
            assertTrue(remote.payloadDigest.contains("result=REFUSED_NO_PLAN"))
            assertTrue(
                "refusal must be logged",
                allRemoteLogs().any { it.msg.contains("REFUSED_NO_PLAN") }
            )
        }

    @Test
    fun `RESUME with no plan is a safe no-op - engine entry not called, audited as REFUSED_NO_PLAN`() =
        runBlocking {
            asRoot()

            handleSync(RemoteControlContract.actionResume(pkg))

            assertEquals(AutomationStartStatus.IDLE, SvcCompanion.startStatus.value)
            val remote = auditEvents().single()
            assertEquals("REMOTE_CONTROL_RESUME", remote.eventType)
            assertTrue(remote.payloadDigest.contains("result=REFUSED_NO_PLAN"))
        }

    @Test
    fun `RESET_PLAN on an unfinished plan is refused by the repository guard - no new generation, audited`() =
        runBlocking {
            asRoot()
            val oldId = seedPlan(listOf("pending", "pending"))

            handleSync(RemoteControlContract.actionResetPlan(pkg))

            assertEquals(
                "guard refusal must not create a generation",
                oldId,
                db.planDao().getLatestPlan()?.id
            )
            assertFalse(auditEvents().any { it.eventType == "PLAN_RESET" })
            val remote = auditEvents().last { it.eventType == "REMOTE_CONTROL_RESET_PLAN" }
            assertTrue(remote.payloadDigest.contains("result=REFUSED"))
            assertTrue(remote.payloadDigest.contains("unfinished"))
        }

    // ---- STATUS: ordered-broadcast result echo -------------------------------

    /*
     * STATUS: the PAYLOAD assembly (resultCode encoding + human summary +
     * machine-readable extras) is asserted via buildStatusResult — the same
     * function the receiver applies to the ordered-broadcast result with
     * setResult* on device (`am broadcast` prints result=<code> data="<line>").
     * The receiver-level ROUTING (handle -> audit) is asserted like every other
     * action; the ordered-broadcast transport itself is device (B-layer) truth.
     */

    private suspend fun statusPayload(): RemoteControlReceiver.StatusResult =
        receiver.buildStatusResult(db, com.example.cellrebelauto.repository.PlanRepository(db))

    @Test
    fun `STATUS encodes the engine state in resultCode and carries a human summary`() = runBlocking {
        asRoot()
        seedPlan(listOf("pending", "pending"))
        companionFlow("_isServiceConnected").value = true
        companionFlow("_isRunning").value = true
        companionFlow("_currentState").value = AutomationState.WAITING_INTERVAL

        val status = statusPayload()

        assertEquals("running -> RUNNING code", RemoteControlContract.RESULT_RUNNING, status.code)
        assertEquals("WAITING_INTERVAL", status.extras.getString("state"))
        assertEquals("true", status.extras.getString("running"))
        assertEquals("worklist.csv", status.extras.getString("plan"))
        assertEquals("0/2", status.extras.getString("progress"))
        assertTrue("human summary line", status.dataLine.contains("Running"))

        // Routing-level parity: handle(STATUS) writes the audit row like every action.
        handleSync(RemoteControlContract.actionStatus(pkg))
        val remote = auditEvents().last { it.eventType == "REMOTE_CONTROL_STATUS" }
        assertTrue(remote.payloadDigest.contains("result=STATUS"))
    }

    @Test
    fun `STATUS with the service down reports SERVICE_DOWN so the operator knows what to fix first`() =
        runBlocking {
            asRoot()

            val status = statusPayload()

            assertEquals(RemoteControlContract.RESULT_SERVICE_DOWN, status.code)
            assertEquals("false", status.extras.getString("service"))
            assertTrue(status.dataLine.isNotBlank())
        }

    @Test
    fun `STATUS over a held terminal state (PAUSED) reports HELD`() = runBlocking {
        asRoot()
        seedPlan(listOf("active"))
        companionFlow("_isServiceConnected").value = true
        companionFlow("_currentState").value = AutomationState.PAUSED

        val status = statusPayload()

        assertEquals(RemoteControlContract.RESULT_HELD, status.code)
        assertTrue(status.dataLine.contains("PAUSED"))
        assertTrue(status.extras.getString("summary").orEmpty().contains("PAUSED"))
    }

    // ---- contract hygiene -----------------------------------------------------

    @Test
    fun `unknown action is ignored without audit even for an authorized caller`() = runBlocking {
        asRoot()

        receiver.handle(context, Intent("com.example.cellrebelauto.remote.control.NOPE"))

        assertTrue(auditEvents().isEmpty())
        assertTrue(allRemoteLogs().any { it.msg.contains("ignored") })
    }

    @Test
    fun `action and permission names are applicationId-scoped`() {
        // Literal appIds: these are the exact strings the merged manifest's
        // ${applicationId} placeholders must produce (checked against
        // build/intermediates/merged_manifests at build acceptance).
        val appId = "com.example.cellrebelauto"
        assertEquals("$appId.remote.control.START_PLAN", RemoteControlContract.actionStartPlan(appId))
        assertEquals("$appId.remote.control.STOP", RemoteControlContract.actionStop(appId))
        assertEquals("$appId.remote.control.RESUME", RemoteControlContract.actionResume(appId))
        assertEquals("$appId.remote.control.RESET_PLAN", RemoteControlContract.actionResetPlan(appId))
        assertEquals("$appId.remote.control.STATUS", RemoteControlContract.actionStatus(appId))
        assertEquals("$appId.permission.REMOTE_CONTROL", RemoteControlContract.permissionName(appId))
    }

    companion object {
        private const val TAG = "RemoteControl"
    }
}
