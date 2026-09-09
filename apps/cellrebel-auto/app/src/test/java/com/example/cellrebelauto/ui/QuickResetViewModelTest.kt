package com.example.cellrebelauto.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.selfheal.ServiceRecycleMarkerStore
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverDataState
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.integration.v1.EnvironmentMaintenanceClient
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #140 dual-app quick reset at the Auto seam. The four-weird-states acceptance
 * at the caller side: an UNFINISHED-PLAN stall (active task + running session +
 * running attempt) plus RECOVERY residue (a pending service-recycle marker that
 * would ghost-resume the abandoned plan) — one `quickResetAll()` must return
 * every leg to the clean state: tasks cancelled, session terminal, attempt
 * interrupted, marker cleared, provider schedule reset (the injected fake
 * channel models the paired QWY having executed the productized schedule_reset),
 * ONE typed QUICK_RESET audit row, and an honest 「双 app 已重置 ✓」 summary.
 * Channel failures keep the local reset and say so — never a lying green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuickResetViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var app: Application

    private val createdVms = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        createdVms.forEach { it.viewModelScope.cancel() }
        createdVms.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newVm(
        channel: QuickResetChannel? = null,
    ): MainViewModel = MainViewModel(
        app,
        injectedDb = db,
        injectedAccessGate = CutoverAccessGate.open(),
        quickResetChannel = channel,
    ).also { createdVms += it }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    private fun readyPlanState(vm: MainViewModel): PlanUiState? =
        (vm.planUiState.value as? CutoverDataState.Ready<*>)
            ?.value as? PlanUiState

    private fun task(status: String, csvRow: Int = 1) = LocationTask(
        planId = 0L, csvRow = csvRow, longitude = 30.5, latitude = 50.4,
        priority = 1, requiredSuccesses = 1, status = status
    )

    private data class StuckSeed(val planId: Long, val sessionId: Long, val attemptId: Long)

    /**
     * Seeds the 未完成计划卡死 + 恢复态残留 state: an unfinished plan with an
     * active task, a RUNNING session, a RUNNING attempt, and a pending
     * service-recycle marker — without the marker clear, the next service
     * reconnect would ghost-resume the very plan being abandoned.
     */
    private suspend fun seedStuckPlanAndResidue(): StuckSeed {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "stuck.csv", importedAt = 100L, globalBufferSeconds = 5,
                totalRows = 2, totalRequiredSuccesses = 2
            ),
            listOf(task("completed"), task("active", csvRow = 2))
        )
        val activeTask = db.locationTaskDao().getTasksForPlan(planId)
            .single { it.status == "active" }
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "running", planId = planId)
        )
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = activeTask.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 250L, runningObservedAt = 260L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 50.4, longitude = 30.5
            )
        )
        ServiceRecycleMarkerStore(app).saveRecycle(planId)
        assertNotNull(ServiceRecycleMarkerStore(app).pendingRecycle())
        return StuckSeed(planId, sessionId, attemptId)
    }

    @Test
    fun `quickResetAll abandons the stuck plan clears residue resets provider and reports success`() =
        runTest {
            val seed = seedStuckPlanAndResidue()
            val vm = newVm(
                channel = {
                    EnvironmentMaintenanceClient.ResetResult.ResetDone(
                        scheduleVersionAfter = 9L,
                        republishedProfileRef = "profile-3",
                        providerPackage = "name.caiyao.fakegps.glmbench",
                    )
                },
            )
            await("plan UI projection reached Ready") {
                vm.planUiState.value is CutoverDataState.Ready<*>
            }
            assertTrue("the seeded plan must start unfinished", readyPlanState(vm)?.isUnfinished == true)

            vm.quickResetAll()

            await("the success summary is posted") {
                vm.importNotice.value?.contains("双 app 已重置 ✓") == true
            }
            val tasks = db.locationTaskDao().getTasksForPlan(seed.planId)
            assertTrue(
                "remaining tasks must be cancelled, got ${tasks.map { it.status }}",
                tasks.all { it.status == "completed" || it.status == "cancelled" },
            )
            assertTrue(tasks.any { it.status == "cancelled" })
            val session = db.runSessionDao().getById(seed.sessionId)!!
            assertEquals("the seeded session must be terminal", "stopped", session.status)
            assertNull(
                "no active running session may survive the reset",
                db.runSessionDao().findActiveRunningSession(seed.planId),
            )
            val attempt = db.testAttemptDao().getAttemptById(seed.attemptId)!!
            assertEquals(
                "the running attempt must ride the EXISTING interrupted terminalization",
                "interrupted", attempt.status,
            )
            assertNull("the recycle marker must be gone", ServiceRecycleMarkerStore(app).pendingRecycle())
            assertTrue(
                "plan must no longer block imports",
                readyPlanState(vm)?.isUnfinished == false,
            )
            val audits = db.auditEventDao().forEventType("QUICK_RESET")
            assertEquals("exactly one QUICK_RESET audit row", 1, audits.size)
            val digest = audits.single().payloadDigest
            assertTrue(
                "audit must record the provider leg: $digest",
                digest.contains("provider=RESET_DONE:V=9:ref=profile-3"),
            )
            assertTrue(
                "audit must record the cleared recycle marker: $digest",
                digest.contains("recycleMarker=CLEARED"),
            )
        }

    @Test
    fun `provider channel failure keeps the local reset and reports honestly`() = runTest {
        seedStuckPlanAndResidue()
        val vm = newVm(
            channel = {
                EnvironmentMaintenanceClient.ResetResult.NotBindable(listOf("name.caiyao.fakegps.glmbench"))
            },
        )
        await("plan UI projection reached Ready") {
            vm.planUiState.value is CutoverDataState.Ready<*>
        }

        vm.quickResetAll()

        await("the honest partial summary is posted") {
            vm.importNotice.value?.contains("快速重置未完全完成") == true &&
                vm.importNotice.value.orEmpty().contains("QWY 不可达")
        }
        val tasks = db.locationTaskDao().getTasksForPlan(
            db.planDao().getLatestPlan()!!.id
        )
        assertTrue(
            "the LOCAL half must still complete",
            tasks.all { it.status == "completed" || it.status == "cancelled" },
        )
        assertNull(ServiceRecycleMarkerStore(app).pendingRecycle())
        val digest = db.auditEventDao().forEventType("QUICK_RESET").single().payloadDigest
        assertTrue("audit must carry the provider failure", digest.contains("provider=NOT_BINDABLE"))
        assertFalse(
            "no lying success marker",
            vm.importNotice.value.orEmpty().contains("双 app 已重置 ✓"),
        )
    }

    @Test
    fun `channel crash keeps the local reset and reports the channel anomaly`() = runTest {
        seedStuckPlanAndResidue()
        val vm = newVm(channel = { throw IllegalStateException("binder exploded") })
        await("plan UI projection reached Ready") {
            vm.planUiState.value is CutoverDataState.Ready<*>
        }

        vm.quickResetAll()

        await("the channel-crash summary is posted") {
            vm.importNotice.value?.contains("QWY 重置通道异常") == true
        }
        val providerLeg = db.auditEventDao().forEventType("QUICK_RESET").single().payloadDigest
            .split(";").first { it.startsWith("provider=") }
        assertEquals("provider=CHANNEL_CRASHED", providerLeg)
    }

    @Test
    fun `no plan is still a clean reset when the provider leg succeeds`() = runTest {
        val vm = newVm(
            channel = {
                EnvironmentMaintenanceClient.ResetResult.ResetDone(
                    scheduleVersionAfter = 4L,
                    republishedProfileRef = "profile-1",
                    providerPackage = "pkg",
                )
            },
        )
        await("plan UI projection reached Ready") {
            vm.planUiState.value is CutoverDataState.Ready<*>
        }

        vm.quickResetAll()

        await("the success summary is posted even with no plan") {
            vm.importNotice.value?.contains("双 app 已重置 ✓") == true &&
                vm.importNotice.value.orEmpty().contains("当前无计划")
        }
    }
}
