package com.example.cellrebelauto.ui.dashboard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T7 — the run dashboard ViewModel projection oracle.
 *
 * The dashboard's progress numbers must come from the TRUSTED ledger
 * projection ONLY: the legacy `completedSuccesses` column stays frozen at 99
 * in this fixture and the dashboard must still show 2/10. Failure classes and
 * the window throughput come from the same attempt rows; the lamps start
 * unprobed (null) and the pause explanation is always present.
 *
 * Killing mutations:
 *  - any read of the legacy column fails the trusted-only test (99 leaks);
 *  - a window that admits all history fails windowSuccesses == 2;
 *  - a dashboard that shows lamps before the first probe fails the
 *    unprobed-lamps test.
 *
 * # 运行台 ViewModel oracle：可信口径唯一（legacy=99 不得泄漏）、吞吐窗口、
 * # 失败分类、三灯未探测为 null、暂停解释常在
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RunDashboardViewModelTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        // Test hygiene (Wave-1 #111 lesson): cancel every constructed MainViewModel's
        // scope BEFORE resetMain — leaked Main-dispatcher coroutines race the next
        // test class's setMain (the PlanProfileConsistencyViewModelTest flake).
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    // Every MainViewModel this class constructs, so tearDown can drain them all.
    private val createdViewModels = mutableListOf<MainViewModel>()

    private fun vm(): MainViewModel =
        MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            // #103 cutover architecture: the app-singleton gate is recoveryRequired under
            // Robolectric, which would hide the seeded data behind CutoverDataState.
            injectedAccessGate = CutoverAccessGate.open(),
        )
            .also { createdViewModels += it }

    /**
     * stateIn(Lazily) starts on the first SUBSCRIBER (not on .value), so the
     * oracle keeps one eager (Unconfined) collector alive per test — the
     * sharing coroutine then updates the StateFlow while awaitUntil polls.
     */
    private val collectorScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.Job()
    )

    private fun startDashboard(viewModel: MainViewModel) {
        collectorScope.launch { viewModel.dashboardState.collect {} }
    }

    /** Bounded spin: viewModelScope work hops to Room executors outside runTest's scheduler. */
    private fun awaitUntil(deadlineMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!kotlinx.coroutines.runBlocking { condition() } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private suspend fun seedPlanWithProgress(): Long {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "dashboard.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 10,
                totalRows = 2,
                totalRequiredSuccesses = 10,
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 30.0, latitude = 50.0,
                    priority = 1, requiredSuccesses = 5,
                    // The frozen legacy column — a dashboard reading it shows 99/10.
                    completedSuccesses = 99, status = "active",
                ),
                LocationTask(
                    planId = 0, csvRow = 2, longitude = 31.0, latitude = 51.0,
                    priority = 2, requiredSuccesses = 5,
                    completedSuccesses = 0, status = "pending",
                ),
            ),
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 2_000L, planId = planId, configSnapshot = "plan:$planId")
        )
        val now = System.currentTimeMillis()
        val attempts = listOf(
            // Two trusted successes inside the 1 h window.
            Triple("succeeded", now - 10 * 60_000L, 1),
            Triple("succeeded", now - 30 * 60_000L, 2),
            // One success OUTSIDE the window (2 h ago) — must not inflate throughput.
            Triple("succeeded", now - 2 * 3_600_000L, 3),
            // Failures for the classification buckets.
            Triple("failed", now - 15 * 60_000L, 4),
            Triple("failed", now - 20 * 60_000L, 5),
            Triple("failed", now - 25 * 60_000L, 6),
        )
        val attemptIds = attempts.map { (status, endedAt, ordinal) ->
            db.testAttemptDao().insert(
                TestAttempt(
                    taskId = task.id,
                    runSessionId = sessionId,
                    attemptOrdinal = ordinal,
                    successOrdinal = if (status == "succeeded") ordinal else null,
                    startedAt = endedAt - 60_000L,
                    runningObservedAt = null,
                    endedAt = endedAt,
                    status = status,
                    failureReason = when (ordinal) {
                        4, 5 -> "FAKE_GPS_NOT_ACTIVE"
                        6 -> "UNTRUSTED"
                        else -> null
                    },
                    webBrowsingScore = null,
                    videoStreamingScore = null,
                    latitude = task.latitude,
                    longitude = task.longitude,
                )
            )
        }
        // Two trusted ledger mints (the ONLY quota口径) on the first two attempts.
        attemptIds.take(2).forEach { attemptId ->
            db.trustedQuotaDao().insert(
                TrustedQuotaEntry(
                    attemptId = attemptId,
                    taskId = task.id,
                    evidenceDigest = "digest-$attemptId",
                    committedAt = 1L,
                )
            )
        }
        return planId
    }

    @Test
    fun `progress reads the trusted ledger only - the frozen legacy 99 never leaks`() = runTest {
        seedPlanWithProgress()
        val viewModel = vm()
        startDashboard(viewModel)
        awaitUntil {
            val progress = viewModel.dashboardState.value.progress
            progress.trustedTotal == 10
        }
        val progress = viewModel.dashboardState.value.progress
        assertEquals("trusted-only quota", 2, progress.trustedDone)
        assertEquals(10, progress.trustedTotal)
    }

    @Test
    fun `throughput counts only the recent window and failures group by class`() = runTest {
        seedPlanWithProgress()
        val viewModel = vm()
        startDashboard(viewModel)
        awaitUntil {
            viewModel.dashboardState.value.progress.windowSuccesses > 0
        }
        val progress = viewModel.dashboardState.value.progress
        assertEquals("only the two in-window successes", 2, progress.windowSuccesses)
        val classes = progress.failureClasses.toMap()
        assertEquals(2, classes["GPS"])
        assertEquals(1, classes["UNTRUSTED"])
    }

    @Test
    fun `lamps start unprobed and the pause explanation is always present`() = runTest {
        seedPlanWithProgress()
        val viewModel = vm()
        val state = viewModel.dashboardState.value
        assertEquals(null, state.lampAccessibility)
        assertEquals(null, state.lampProvider)
        assertEquals(null, state.lampVector)
        assertNotNull(state.explanation)
        assertTrue(state.explanation.headline.isNotBlank())
        assertEquals("idle engine starts unheld", com.example.cellrebelauto.model.AutomationState.IDLE, state.engineState)
    }

    @Test
    fun `refreshDashboardHealth fills all three lamps from injected probes`() = runTest {
        seedPlanWithProgress()
        val viewModel = MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            injectedAccessGate = CutoverAccessGate.open(),
            providerHealthProbe = { HealthLampsProjection.ProviderHandshake(exhausted = false, profileCount = 3) },
            publishTimestampProbe = { System.currentTimeMillis() - 60_000L },
        ).also { createdViewModels += it }
        viewModel.refreshDeviceReadiness()   // a11y enablement probe (Robolectric: empty list)
        startDashboard(viewModel)
        viewModel.refreshDashboardHealth()
        awaitUntil { viewModel.dashboardState.value.lampProvider != null }
        val state = viewModel.dashboardState.value
        assertNotNull(state.lampProvider)
        assertEquals(LampState.GREEN, state.lampProvider!!.state)
        assertNotNull(state.lampVector)
        assertEquals(LampState.GREEN, state.lampVector!!.state)
        // Robolectric reports no enabled a11y services → the yellow "not enabled" lamp.
        assertEquals(LampState.YELLOW, state.lampAccessibility!!.state)
    }
}
