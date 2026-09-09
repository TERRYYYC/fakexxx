package com.example.cellrebelauto.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #135 RED oracles for the Plan-page "abandon current plan" escape hatch (issue
 * option 1): the operator-confirmed abandon cancels the remaining tasks, closes
 * the active session, terminalizes non-terminal attempt rows through the EXISTING
 * interrupted path, and appends one PLAN_ABANDONED audit row. After abandon the
 * Plan page must project a finished (importable) state — `isUnfinished` false,
 * `isAbandoned` true — and a refusal must never append audit rows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlanAbandonViewModelTest {

    private lateinit var db: AppDatabase

    private val createdVms = mutableListOf<MainViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        createdVms.forEach { it.viewModelScope.cancel() }
        createdVms.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun newVm(): MainViewModel = MainViewModel(
        ApplicationProvider.getApplicationContext(),
        injectedDb = db,
        injectedAccessGate = CutoverAccessGate.open(),
    ).also { createdVms += it }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    // MainViewModel keeps its own readyValueOrNull private; unwrap here instead.
    private fun readyPlanState(vm: MainViewModel): PlanUiState? =
        (vm.planUiState.value as? com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>)
            ?.value as? PlanUiState

    private fun task(status: String, csvRow: Int = 1) = LocationTask(
        planId = 0L, csvRow = csvRow, longitude = 30.5, latitude = 50.4,
        priority = 1, requiredSuccesses = 1, status = status
    )

    @Test
    fun `abandonPlan cancels remaining tasks, terminalizes session and attempt, appends PLAN_ABANDONED`() =
        runTest {
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
                RunSession(startedAt = 200L, status = "paused", planId = planId)
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
            val vm = newVm()
            await("plan UI projection reached Ready") {
                vm.planUiState.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
            }
            assertTrue(
                "the seeded plan must start unfinished",
                readyPlanState(vm)?.isUnfinished == true
            )

            vm.abandonPlan()

            await("abandon posts its completion notice") {
                vm.importNotice.value?.startsWith("Plan abandoned") == true
            }
            val tasks = db.locationTaskDao().getTasksForPlan(planId)
            assertTrue(
                "remaining tasks must be cancelled, got ${tasks.map { it.status }}",
                tasks.all { it.status == "completed" || it.status == "cancelled" }
            )
            assertTrue(tasks.any { it.status == "cancelled" })
            val session = db.runSessionDao().getById(sessionId)!!
            assertEquals("the session must be terminal after abandon", "stopped", session.status)
            assertNotNull(session.endedAt)
            val attempt = db.testAttemptDao().getAttemptById(attemptId)!!
            assertEquals(
                "a running attempt must ride the EXISTING interrupted terminalization",
                "interrupted", attempt.status
            )
            assertEquals("INTERRUPTED", attempt.failureReason)
            assertNotNull(attempt.endedAt)
            val audits = db.auditEventDao().forEventType("PLAN_ABANDONED")
            assertEquals("exactly one PLAN_ABANDONED audit row", 1, audits.size)
            assertTrue(
                "the audit row must bind the abandoned plan",
                audits.single().correlationRef.orEmpty().contains("plan:$planId")
            )
            await("the Plan projection must leave the unfinished state") {
                val state = readyPlanState(vm)
                state?.isUnfinished == false && state?.isAbandoned == true
            }
        }

    @Test
    fun `abandonPlan is refused on a complete plan and appends no audit`() = runTest {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "done.csv", importedAt = 100L, globalBufferSeconds = 5,
                totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(task("completed"))
        )
        val vm = newVm()
        await("plan UI projection reached Ready") {
            vm.planUiState.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
        }

        vm.abandonPlan()

        await("the refusal is posted") {
            vm.importNotice.value.orEmpty().contains("refused", ignoreCase = true)
        }
        assertTrue(
            "no audit row may be appended on refusal",
            db.auditEventDao().forEventType("PLAN_ABANDONED").isEmpty()
        )
        assertEquals(
            "task rows must be untouched on refusal",
            listOf("completed"),
            db.locationTaskDao().getTasksForPlan(planId).map { it.status }
        )
        assertFalse(readyPlanState(vm)?.isAbandoned ?: false)
    }

    /**
     * #135 review RED: after abandon the Run console's Resume suggestion is still
     * engine-state-driven (PAUSED projection), so startOrResumePlan is the last line
     * of defense — it must refuse an abandoned plan honestly instead of starting the
     * engine on a terminal plan (a bound provider could even re-drive cancelled rows).
     */
    @Test
    fun `startOrResumePlan refuses an abandoned plan with an honest notice`() = runTest {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "abandoned.csv", importedAt = 100L, globalBufferSeconds = 5,
                totalRows = 2, totalRequiredSuccesses = 2
            ),
            listOf(task("completed"), task("cancelled", csvRow = 2))
        )
        val vm = newVm()
        await("plan UI projection reached Ready") {
            vm.planUiState.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
        }
        await("the seeded plan must project as abandoned") {
            readyPlanState(vm)?.isAbandoned == true
        }

        vm.startOrResumePlan()

        await("the abandoned plan must be refused with the abandon wording") {
            vm.importNotice.value?.contains("abandoned", ignoreCase = true) == true
        }
        assertEquals(
            "the abandoned plan row must be untouched",
            planId,
            db.planDao().getLatestPlan()?.id
        )
    }
}
