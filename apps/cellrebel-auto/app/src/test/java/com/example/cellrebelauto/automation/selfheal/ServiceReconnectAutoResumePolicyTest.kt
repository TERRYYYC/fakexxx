package com.example.cellrebelauto.automation.selfheal

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.data.SelfHealConfig
import com.example.cellrebelauto.data.SelfHealSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * P1.3 #2 — service reconnect auto-resume decision oracle (pure) + persisted-toggle contract.
 *
 * Default OFF (conservative); when enabled, a pending recycle marker resumes the recycled plan
 * through the EXISTING start/resume entry; the 30-minute rolling window admits at most 3
 * auto-resumes — over budget the engine stays at its typed terminal with a human-readable reason.
 *
 * # 服务重连自动恢复 oracle：默认关；marker 存在才动作；30 分钟 ≤3 次，超限持停机+原因
 */
@RunWith(RobolectricTestRunner::class)
class ServiceReconnectAutoResumePolicyTest {

    // ---- policy (pure) ----

    @Test
    fun `disabled policy never resumes even with a fresh marker`() {
        val policy = ServiceReconnectAutoResumePolicy(nowMs = { 1_000_000L })
        val decision = policy.decide(
            enabled = false,
            marker = ServiceReconnectAutoResumePolicy.RecycleMarker(planId = 7L, recycledAtMs = 999_000L),
            autoResumeTimestampsMs = emptyList()
        )
        assertEquals(ServiceReconnectAutoResumePolicy.Decision.Hold("AUTO_RESUME_DISABLED"), decision)
    }

    @Test
    fun `no marker means nothing to do (ordinary first connect)`() {
        val policy = ServiceReconnectAutoResumePolicy(nowMs = { 1_000_000L })
        assertEquals(
            ServiceReconnectAutoResumePolicy.Decision.NothingToDo,
            policy.decide(enabled = true, marker = null, autoResumeTimestampsMs = emptyList())
        )
        // And disabled + no marker is likewise a no-action, not a Hold with a reason to audit.
        assertEquals(
            ServiceReconnectAutoResumePolicy.Decision.NothingToDo,
            policy.decide(enabled = false, marker = null, autoResumeTimestampsMs = emptyList())
        )
    }

    @Test
    fun `enabled with fresh marker and budget available resumes the recycled plan`() {
        val policy = ServiceReconnectAutoResumePolicy(nowMs = { 1_000_000L })
        val decision = policy.decide(
            enabled = true,
            marker = ServiceReconnectAutoResumePolicy.RecycleMarker(42L, 999_000L),
            autoResumeTimestampsMs = listOf(800_000L, 900_000L)
        )
        assertEquals(ServiceReconnectAutoResumePolicy.Decision.Resume(42L), decision)
    }

    @Test
    fun `a fourth resume inside the 30 minute window is held with the budget reason`() {
        val now = 1_800_000L
        val policy = ServiceReconnectAutoResumePolicy(nowMs = { now })
        val decision = policy.decide(
            enabled = true,
            marker = ServiceReconnectAutoResumePolicy.RecycleMarker(42L, now - 1_000),
            autoResumeTimestampsMs = listOf(
                now - 29 * 60_000L, now - 20 * 60_000L, now - 1 * 60_000L
            )
        )
        assertTrue(
            "decision=$decision",
            decision is ServiceReconnectAutoResumePolicy.Decision.Hold &&
                decision.reason.startsWith("AUTO_RESUME_BUDGET_EXHAUSTED")
        )
    }

    @Test
    fun `auto-resumes older than the rolling window fall out of the budget`() {
        val now = 1_800_000L
        val policy = ServiceReconnectAutoResumePolicy(nowMs = { now }, windowMs = 30 * 60_000L)
        assertEquals(3, policy.autoResumesInWindow(listOf(now, now - 1, now - 30 * 60_000L)).size)
        assertEquals(2, policy.autoResumesInWindow(listOf(now, now - 1, now - 30 * 60_000L - 1)).size)
    }

    // ---- recycle marker store (SharedPreferences) ----

    @Test
    fun `recycle marker round-trips and is consumed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServiceRecycleMarkerStore(context, nowMs = { 5_000L })
        assertNull(store.pendingRecycle())
        store.saveRecycle(planId = 77L, recycledAtMs = 4_000L)
        assertEquals(
            ServiceReconnectAutoResumePolicy.RecycleMarker(77L, 4_000L),
            store.pendingRecycle()
        )
        store.clearPendingRecycle()
        assertNull(store.pendingRecycle())
    }

    @Test
    fun `auto-resume ledger accumulates timestamps`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ServiceRecycleMarkerStore(context, nowMs = { 5_000L })
        store.recordAutoResume(1L)
        store.recordAutoResume(2L)
        store.recordAutoResume(3L)
        assertEquals(listOf(1L, 2L, 3L), store.autoResumeTimestamps())
    }

    // ---- persisted toggles (DataStore) — defaults ON/ON/OFF per P1.3 ----

    private fun TestScope.newSettings(): SelfHealSettings {
        val file = File(
            System.getProperty("java.io.tmpdir"),
            "self-heal-test-${UUID.randomUUID()}.preferences_pb"
        )
        return SelfHealSettings(
            PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file })
        )
    }

    @Test
    fun `self-heal toggles default to watchdog on guard on autoresume off`() = runTest {
        val settings = newSettings()
        assertEquals(
            SelfHealConfig(
                attemptWatchdogEnabled = true,
                coordinateGuardEnabled = true,
                serviceReconnectAutoResumeEnabled = false
            ),
            settings.config.first()
        )
    }

    @Test
    fun `self-heal toggles persist independently`() = runTest {
        val settings = newSettings()
        settings.setServiceReconnectAutoResumeEnabled(true)
        settings.setAttemptWatchdogEnabled(false)
        val config = settings.config.first()
        assertTrue(config.serviceReconnectAutoResumeEnabled)
        assertFalse(config.attemptWatchdogEnabled)
        assertTrue("coordinate guard default must survive unrelated writes", config.coordinateGuardEnabled)
    }

    // ---- coordinator: the decision's side effects (audit + budget ledger + marker + resume) ----

    private inner class CoordinatorFixture(enabled: Boolean, now: Long) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val markerStore = ServiceRecycleMarkerStore(context, nowMs = { now })
        val audits = mutableListOf<Triple<String, String?, String>>()
        val logs = mutableListOf<String>()
        val resumed = mutableListOf<Long>()
        // Real-IO DataStore (NOT the test scheduler): the fixture constructor must await the
        // enabled-write without deadlocking the runTest thread.
        private val file = File(
            System.getProperty("java.io.tmpdir"),
            "self-heal-coordinator-${UUID.randomUUID()}.preferences_pb"
        )
        private val settings = SelfHealSettings(
            PreferenceDataStoreFactory.create(
                scope = CoroutineScope(Job() + kotlinx.coroutines.Dispatchers.IO),
                produceFile = { file }
            )
        )

        init {
            kotlinx.coroutines.runBlocking {
                settings.setServiceReconnectAutoResumeEnabled(enabled)
            }
        }

        val coordinator = ServiceReconnectAutoResumeCoordinator(
            settings = settings,
            markerStore = markerStore,
            policy = ServiceReconnectAutoResumePolicy(nowMs = { now }),
            audit = { eventType, correlationRef, detail, _ -> audits += Triple(eventType, correlationRef, detail) },
            log = { logs += it },
            resume = { resumed += it },
            nowMs = { now }
        )
    }

    @Test
    fun `resume consumes the marker, books the budget, audits, logs and starts the plan`() = runTest {
        val fixture = CoordinatorFixture(enabled = true, now = 1_000_000L)
        fixture.markerStore.saveRecycle(planId = 42L, recycledAtMs = 999_000L)

        fixture.coordinator.afterServiceConnected()

        assertEquals(listOf(42L), fixture.resumed)
        assertNull("the marker must be consumed by the action", fixture.markerStore.pendingRecycle())
        assertEquals(listOf(1_000_000L), fixture.markerStore.autoResumeTimestamps())
        val (eventType, correlationRef, detail) = fixture.audits.single()
        assertEquals("SERVICE_AUTO_RESUME", eventType)
        assertEquals("plan:42", correlationRef)
        assertTrue(detail.contains("SERVICE_RECYCLED"))
        assertTrue(fixture.logs.single().contains("auto-resuming plan #42"))
    }

    @Test
    fun `hold keeps the marker, audits the reason and never starts`() = runTest {
        val fixture = CoordinatorFixture(enabled = false, now = 1_000_000L)
        fixture.markerStore.saveRecycle(planId = 42L, recycledAtMs = 999_000L)

        fixture.coordinator.afterServiceConnected()

        assertTrue(fixture.resumed.isEmpty())
        assertEquals(
            "the marker stays pending for a later enabled reconnect or manual Resume",
            ServiceReconnectAutoResumePolicy.RecycleMarker(42L, 999_000L),
            fixture.markerStore.pendingRecycle()
        )
        assertEquals(0, fixture.markerStore.autoResumeTimestamps().size)
        val (eventType, correlationRef, detail) = fixture.audits.single()
        assertEquals("SERVICE_AUTO_RESUME_HELD", eventType)
        assertEquals("plan:42", correlationRef)
        assertEquals("AUTO_RESUME_DISABLED", detail)
    }

    @Test
    fun `budget exhausted holds with the typed reason and does not restart`() = runTest {
        val fixture = CoordinatorFixture(enabled = true, now = 1_800_000L)
        fixture.markerStore.saveRecycle(planId = 42L, recycledAtMs = 1_799_000L)
        repeat(3) { fixture.markerStore.recordAutoResume(1_800_000L - it) }

        fixture.coordinator.afterServiceConnected()

        assertTrue("over budget the engine stays stopped", fixture.resumed.isEmpty())
        val (eventType, _, detail) = fixture.audits.single()
        assertEquals("SERVICE_AUTO_RESUME_HELD", eventType)
        assertTrue(detail.startsWith("AUTO_RESUME_BUDGET_EXHAUSTED"))
    }

    @Test
    fun `no marker means no action and no audit rows`() = runTest {
        val fixture = CoordinatorFixture(enabled = true, now = 1_000_000L)

        fixture.coordinator.afterServiceConnected()

        assertTrue(fixture.resumed.isEmpty())
        assertTrue(fixture.audits.isEmpty())
        assertTrue(fixture.logs.isEmpty())
    }
}
