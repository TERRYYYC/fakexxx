package com.example.cellrebelauto.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #12 plan-reset ViewModel oracle — the Auto half of the dead-lock exit.
 *
 * After a plan completes (or an attempt is terminally stuck RECOVERY_REQUIRED
 * with no recovery path left), the Plan page had NO reset/re-run entry: the
 * only hint was "import a new CSV", which works on the Auto side but leaves
 * the provider schedule EXHAUSTED — the lane dead-locks on the next apply.
 *
 * These pin the reset semantics at the ViewModel boundary:
 *  1. reset re-imports the SAME worklist as a NEW plan generation (new plan
 *     row + copied tasks, all pending, zero attempts/quota) and writes a
 *     typed PLAN_RESET audit row binding old→new plan ids;
 *  2. reset is REFUSED while the plan is unfinished and nothing is stuck
 *     RECOVERY_REQUIRED (the import-equivalent guard, enforced in the
 *     repository transaction — not only in hidden UI);
 *  3. reset is the escape hatch when an attempt IS stuck RECOVERY_REQUIRED,
 *     even though the plan is unfinished;
 *  4. the reset entry's visibility projection (canResetPlan) is exactly
 *     "plan complete OR recovery-required attempt present".
 *
 * Killing mutations: a reset that resurrects the OLD plan rows (flips task
 * statuses back) fails 1's pending/status assertions; a reset reachable
 * mid-run fails 2; a hidden-only guard (UI-only visibility) fails 2 as well
 * (the ViewModel is the seam under test, not the composable).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlanResetViewModelTest {

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
        db.close()
        Dispatchers.resetMain()
    }

    // ---- seeding -------------------------------------------------------------

    private suspend fun seedPlan(
        taskStatuses: List<String>,
        sourceFileName: String = "worklist.csv",
    ): Long {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = sourceFileName,
                importedAt = 1_000L,
                globalBufferSeconds = 30,
                totalRows = taskStatuses.size,
                totalRequiredSuccesses = taskStatuses.size,
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
                    status = status,
                )
            },
        )
        return planId
    }

    private suspend fun seedAttempt(
        planId: Long,
        taskIndex: Int,
        aplusState: String? = null,
        status: String = "succeeded",
    ): Long {
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 2_000L, planId = planId, configSnapshot = "plan:$planId")
        )
        val task = db.locationTaskDao().getTasksForPlan(planId)[taskIndex]
        return db.testAttemptDao().insert(
            TestAttempt(
                taskId = task.id,
                runSessionId = sessionId,
                attemptOrdinal = 1,
                successOrdinal = if (status == "succeeded") 1 else null,
                startedAt = 3_000L,
                runningObservedAt = null,
                endedAt = 4_000L,
                status = status,
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = task.latitude,
                longitude = task.longitude,
                aplusState = aplusState,
            )
        )
    }

    /** Bounded spin: viewModelScope work hops to Room executors outside runTest's scheduler. */
    private fun awaitUntil(deadlineMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!kotlinx.coroutines.runBlocking { condition() } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun vm(): MainViewModel =
        MainViewModel(ApplicationProvider.getApplicationContext(), injectedDb = db)

    // ---- 1: the reset itself ---------------------------------------------------

    @Test
    fun `reset re-imports a completed plan as a new generation and audits PLAN_RESET`() = runTest {
        val oldPlanId = seedPlan(listOf("completed", "completed"))
        seedAttempt(oldPlanId, taskIndex = 0)
        assertEquals(1, db.testAttemptDao().getAttemptsForPlan(oldPlanId).size)

        val viewModel = vm()
        viewModel.resetPlan()

        awaitUntil { db.planDao().getLatestPlan()?.id != oldPlanId }
        val latest = db.planDao().getLatestPlan()
        assertNotNull("a NEW plan row must exist after reset", latest)
        assertNotEquals(oldPlanId, latest!!.id)
        assertEquals("the same worklist is re-imported", "worklist.csv", latest.sourceFileName)
        assertEquals(30, latest.globalBufferSeconds)
        assertEquals(2, latest.totalRows)

        // New generation tasks: copied rows, all pending, zero progress.
        val tasks = db.locationTaskDao().getTasksForPlan(latest.id)
        assertEquals(2, tasks.size)
        assertTrue("copied tasks restart pending", tasks.all { it.status == "pending" })
        assertTrue("copied tasks restart at zero successes", tasks.all { it.completedSuccesses == 0 })
        assertEquals(
            "row identity is preserved (csvRow/coords/priority/quota)",
            listOf(1, 2), tasks.map { it.csvRow },
        )

        // The old plan's attempts are NOT deleted (append-only audit trail) but
        // the new plan's projection starts clean.
        assertEquals("old attempts stay (audit trail)", 1, db.testAttemptDao().getAttemptsForPlan(oldPlanId).size)
        assertEquals("new plan has zero attempts", 0, db.testAttemptDao().getAttemptsForPlan(latest.id).size)

        // Typed audit event binding old→new generation.
        val events = db.auditEventDao().all()
        val resetEvent = events.firstOrNull { it.eventType == "PLAN_RESET" }
        assertNotNull("a PLAN_RESET audit row must be written", resetEvent)
        assertEquals("correlation binds old→new plan", "plan:$oldPlanId->${latest.id}", resetEvent!!.correlationRef)
        assertEquals("plan-level event: no attempt correlation", null, resetEvent.attemptId)

        // And the operator notice says what to do next.
        awaitUntil { viewModel.importNotice.value?.contains("reset", ignoreCase = true) == true }
    }

    // ---- 2: the refusal guard ---------------------------------------------------

    @Test
    fun `reset is refused while the plan is unfinished and nothing is stuck RECOVERY_REQUIRED`() = runTest {
        val planId = seedPlan(listOf("completed", "pending"))

        val viewModel = vm()
        viewModel.resetPlan()
        // Give the refused path a moment to (wrongly) write, then assert nothing did.
        awaitUntil { viewModel.importNotice.value != null }

        assertEquals("no new plan may be created", planId, db.planDao().getLatestPlan()?.id)
        assertEquals("no PLAN_RESET audit row may be written", 0, db.auditEventDao().count())
        assertTrue(
            "the refusal must tell the operator why",
            viewModel.importNotice.value!!.contains("unfinished", ignoreCase = true),
        )
    }

    // ---- 3: the RECOVERY_REQUIRED escape hatch -----------------------------------

    @Test
    fun `reset is the escape hatch when an attempt is stuck RECOVERY_REQUIRED`() = runTest {
        val oldPlanId = seedPlan(listOf("active", "pending"))
        seedAttempt(oldPlanId, taskIndex = 0, aplusState = "RECOVERY_REQUIRED", status = "starting")

        val viewModel = vm()
        viewModel.resetPlan()

        awaitUntil { db.planDao().getLatestPlan()?.id != oldPlanId }
        val latest = db.planDao().getLatestPlan()
        assertNotNull("reset must succeed for a RECOVERY_REQUIRED-dead lane", latest)
        assertNotEquals(oldPlanId, latest!!.id)
        assertTrue(
            "a PLAN_RESET audit row must be written",
            db.auditEventDao().all().any { it.eventType == "PLAN_RESET" },
        )
    }

    // ---- 4: the visibility projection ---------------------------------------------

    @Test
    fun `canResetPlan is complete-or-recovery-required and nothing else`() = runTest {
        // (a) complete → visible.
        var planId = seedPlan(listOf("completed", "completed"))
        var viewModel = vm()
        backgroundScope.launch(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        ) { viewModel.planUiState.collect {} }
        awaitUntil { viewModel.planUiState.value.plan?.id == planId }
        awaitUntil { viewModel.planUiState.value.canResetPlan }
        assertTrue("complete plan: reset entry visible", viewModel.planUiState.value.canResetPlan)

        // (b) unfinished + clean → hidden.
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        planId = seedPlan(listOf("completed", "pending"))
        viewModel = vm()
        backgroundScope.launch(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        ) { viewModel.planUiState.collect {} }
        awaitUntil { viewModel.planUiState.value.plan?.id == planId }
        Thread.sleep(300) // let any (wrong) recovery-required projection settle
        assertEquals("unfinished clean plan: reset entry hidden", false, viewModel.planUiState.value.canResetPlan)

        // (c) unfinished + RECOVERY_REQUIRED → visible.
        seedAttempt(planId, taskIndex = 1, aplusState = "RECOVERY_REQUIRED", status = "starting")
        awaitUntil { viewModel.planUiState.value.canResetPlan }
        assertTrue("RECOVERY_REQUIRED: reset entry visible", viewModel.planUiState.value.canResetPlan)
    }
}
