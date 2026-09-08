package com.example.cellrebelauto.automation

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AutomationService.Companion as SvcCompanion
import com.example.cellrebelauto.automation.selfheal.ServiceRecycleMarkerStore
import com.example.cellrebelauto.data.SelfHealSettings
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * P1.3 #2 — the service-side reconnect auto-resume oracle.
 *
 * The measured incident: uiautomator dump kills the accessibility service → SERVICE_RECYCLED typed
 * terminal (correct, #15a) → the system rebuilds the service ~1s later → the run sits idle until a
 * HUMAN presses Resume (the highest-frequency manual intervention in unattended runs). The EXISTING
 * connect callback (onServiceConnected of the rebuilt instance) must, when the persisted toggle is
 * ON and a recycle marker is pending, auto-resume the recycled plan through the same startWithPlan
 * entry a manual Resume uses — with a durable audit row per action and the 30-min/3 budget holding
 * the engine stopped (with the reason) once exhausted.
 *
 * # 服务重连自动恢复 oracle：重连回调发现回收标记 → 自动 Resume；显式关不干预（默认 2026-09-08 起 on）；动作必有审计行
 */
// Rebase note: #103/#108 make the resume entry resolve the app-singleton cutover
// gate — the harness therefore must host the REAL CellRebelAutoApp (journal-derived
// gate: OPEN on a fresh host), not Robolectric's default Application.
@RunWith(RobolectricTestRunner::class)
@Config(application = com.example.cellrebelauto.CellRebelAutoApp::class)
class AutomationServiceReconnectResumeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    // Rebase note (T7 isolation): every connected service leaves the companion's
    // static `instance` pointing at it AND serviceScope coroutines alive — on CI's
    // class ordering that residue let a LATER class's resumeRun reach the REAL
    // startWithPlan (app-singleton DB → PLAN_NOT_FOUND) instead of the typed
    // SERVICE_NOT_CONNECTED rejection. onDestroy() is the production drain:
    // cancels serviceScope, unbinds, nulls instance, clears the connected
    // projection. Also restores the SelfHealSettings delegate default (its
    // preferencesDataStore singleton is process-persistent across classes).
    private val createdServices = mutableListOf<AutomationService>()

    @After
    fun tearDown() {
        createdServices.forEach { service ->
            runCatching { service.onDestroy() }
        }
        createdServices.clear()
        runCatching {
            kotlinx.coroutines.runBlocking {
                SelfHealSettings(context).setServiceReconnectAutoResumeEnabled(false)
            }
        }
        // Settle in-flight app-DB/DataStore continuations so the next class's
        // setMain/resetMain (global TestMainDispatcher RW lock) never collides.
        Thread.sleep(250)
    }

    private fun newConnectedService(): AutomationService {
        val service = AutomationService()
        val attach = android.content.ContextWrapper::class.java
            .getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
        // Rebase note: #103/#108 make startWithPlan resolve the app-singleton
        // cutover gate via Service.getApplication(). A bare attachBaseContext
        // leaves mApplication null (the real system rebuild supplies it), so
        // bind the Robolectric application here — its journal-derived gate is
        // OPEN on a fresh host, which is exactly the production reconnect case.
        val appField = android.app.Service::class.java.getDeclaredField("mApplication")
        appField.isAccessible = true
        appField.set(service, context.applicationContext)
        val connect = AutomationService::class.java.getDeclaredMethod("onServiceConnected")
        connect.isAccessible = true
        connect.invoke(service)
        createdServices += service
        return service
    }

    /** Pumps the Robolectric main looper while DataStore/Room IO completes on real worker threads. */
    private suspend fun awaitWithin(timeoutMs: Long = 8_000, condition: suspend () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return true
            Thread.sleep(25)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return condition()
    }

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

    private fun startStatus(): AutomationStartStatus =
        companionFlow("_startStatus").value as AutomationStartStatus

    private suspend fun resetToggles() {
        SelfHealSettings(context).setServiceReconnectAutoResumeEnabled(false)
    }

    /** The companion flows are JVM-static — reset the projection each test starts from. */
    private fun resetStartStatus() {
        companionFlow("_startStatus").value = AutomationStartStatus.IDLE
    }

    @Test
    fun `a pending recycle marker auto-resumes the recycled plan on reconnect when enabled`() = runTest {
        resetToggles()
        resetStartStatus()
        ServiceRecycleMarkerStore(context).saveRecycle(planId = 424_242L)
        SelfHealSettings(context).setServiceReconnectAutoResumeEnabled(true)

        newConnectedService()

        // The auto-resumed startWithPlan(424_242) runs and rejects with PLAN_NOT_FOUND — the typed
        // proof that the reconnect callback STARTED the recycled plan without any human action.
        val resumed = awaitWithin {
            startStatus() is AutomationStartStatus.Rejected &&
                (startStatus() as AutomationStartStatus.Rejected).reason == "PLAN_NOT_FOUND"
        }
        assertTrue("auto-resume never started the recycled plan (status=${startStatus()})", resumed)

        // The marker is consumed by the action.
        assertNull(ServiceRecycleMarkerStore(context).pendingRecycle())
        // The action is auditable durably (service-level audit row, attemptId-less).
        // Rebase note: #103 threads the gate through getInstance; the harness db is standalone.
        val audit = AppDatabase.getInstance(
            context, com.example.cellrebelauto.cutover.CutoverAccessGate.open(),
        ).auditEventDao()
            .forEventType("SERVICE_AUTO_RESUME")
        assertTrue(audit.isNotEmpty())
        assertEquals("plan:424242", audit.last().correlationRef)
        // And visible in the service log trail.
        @Suppress("UNCHECKED_CAST")
        val logs = companionFlow("_logs").value as List<String>
        assertTrue(logs.any { it.contains("auto-resum") && it.contains("424242") })
    }

    @Test
    fun `with the toggle off (explicit) a pending recycle marker never auto-starts`() = runTest {
        resetToggles()
        resetStartStatus()
        ServiceRecycleMarkerStore(context).saveRecycle(planId = 424_242L)

        newConnectedService()

        val settled = awaitWithin(timeoutMs = 3_000) {
            startStatus() !is AutomationStartStatus.IDLE
        }
        assertTrue(
            "explicit OFF must not intervene (status stayed ${startStatus()})",
            !settled || startStatus() is AutomationStartStatus.IDLE
        )
        // The marker stays pending for a later manual Resume (or a later enabled reconnect).
        assertNotNull(ServiceRecycleMarkerStore(context).pendingRecycle())
    }

}
