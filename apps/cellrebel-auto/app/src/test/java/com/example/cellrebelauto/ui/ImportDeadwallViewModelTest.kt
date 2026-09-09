package com.example.cellrebelauto.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.WorklistRow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * #135 RED oracles: the unfinished-plan import protection must not be a dead wall.
 *
 * ① `isUnfinished` excludes fully cancelled plans (an abandoned plan never blocks import);
 * ② a CSV import passes STRAIGHT THROUGH when the current plan is fully abandoned;
 * ④ confirming the replacement of an unfinished plan abandons the remaining tasks
 *    (cancelled), terminalizes the session, appends PLAN_ABANDONED, and imports —
 *    old plan superseded — in ONE confirmation, without depending on the a11y
 *    stop-proof round trip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ImportDeadwallViewModelTest {

    private lateinit var db: AppDatabase

    private class NoResponseStopClient : SupersessionStopClient {
        val mutableStatus = kotlinx.coroutines.flow.MutableStateFlow(
            com.example.cellrebelauto.automation.SupersessionStopStatus.Idle
        )
        override val status: kotlinx.coroutines.flow.StateFlow<
            com.example.cellrebelauto.automation.SupersessionStopStatus> = mutableStatus
        val requests = mutableListOf<Triple<Long, Long, String>>()
        override fun request(planId: Long, sessionId: Long, requestId: String) {
            requests += Triple(planId, sessionId, requestId)
        }
    }

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

    private fun newVm(stopClient: SupersessionStopClient = NoResponseStopClient()): MainViewModel =
        MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            supersessionStopClient = stopClient,
            injectedAccessGate = CutoverAccessGate.open(),
        ).also { createdVms += it }

    @Suppress("UNCHECKED_CAST")
    private fun stageProposal(vm: MainViewModel, proposal: ImportProposal) {
        val field = MainViewModel::class.java.getDeclaredField("_importProposal")
        field.isAccessible = true
        (field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<ImportProposal?>).value = proposal
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    private fun task(status: String, csvRow: Int = 1) = LocationTask(
        planId = 0L, csvRow = csvRow, longitude = 30.5, latitude = 50.4,
        priority = 1, requiredSuccesses = 1, status = status
    )

    private fun plan(fileName: String = "old.csv") = LocationPlan(
        sourceFileName = fileName, importedAt = 100L, globalBufferSeconds = 5,
        totalRows = 2, totalRequiredSuccesses = 2
    )

    // ---- ① isUnfinished excludes cancelled tasks (#135 acceptance RED) ----

    @Test
    fun `isUnfinished is false when every non-completed task is cancelled`() {
        val state = PlanUiState(
            plan = plan(),
            tasks = listOf(task("cancelled"), task("cancelled", csvRow = 2))
        )
        assertFalse(
            "an all-cancelled (abandoned) plan must not count as unfinished",
            state.isUnfinished
        )
    }

    @Test
    fun `isUnfinished is false for a mix of cancelled and completed tasks`() {
        val state = PlanUiState(
            plan = plan(),
            tasks = listOf(task("completed"), task("cancelled", csvRow = 2))
        )
        assertFalse(
            "completed + cancelled with nothing else must not count as unfinished",
            state.isUnfinished
        )
    }

    @Test
    fun `isUnfinished stays true for genuinely unfinished plans`() {
        val state = PlanUiState(
            plan = plan(),
            tasks = listOf(task("completed"), task("active", csvRow = 2), task("pending", csvRow = 3))
        )
        assertTrue("active/pending tasks must keep the plan unfinished", state.isUnfinished)
    }

    // ---- ② import passes straight through an abandoned plan (#135 acceptance RED) ----

    @Test
    fun `csv import bypasses the replacement proposal when the plan is fully abandoned`() = runTest {
        val oldPlanId = db.planDao().insertPlanWithTasks(
            plan(),
            listOf(task("cancelled"), task("completed", csvRow = 2))
        )
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app.contentResolver).registerInputStream(
            Uri.parse("content://test/worklist.csv"),
            buildString {
                appendLine("longitude,latitude,priority,required_successes")
                appendLine("31.5,51.4,1,1")
            }.byteInputStream()
        )
        val vm = newVm()
        await("plan config projection reached Ready") {
            vm.planConfig.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
        }
        await("plan UI projection reached Ready") {
            vm.planUiState.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
        }

        vm.importCsv(Uri.parse("content://test/worklist.csv"))

        await("import succeeded straight through the abandoned plan") {
            vm.importNotice.value?.startsWith("Imported") == true
        }
        assertNull("no replacement confirmation may be demanded for an abandoned plan", vm.importProposal.value)
        val latest = db.planDao().getLatestPlan()
        assertEquals("worklist.csv", latest?.sourceFileName)
        assertTrue("the new plan must be a fresh row", latest?.id != oldPlanId)
    }

    // ---- ④ one confirmation = stop + abandon + import (#135 core scenario) ----

    @Test
    fun `confirming replacement of an unfinished plan abandons remaining tasks and imports`() = runTest {
        val oldPlanId = db.planDao().insertPlanWithTasks(
            plan(),
            listOf(task("completed"), task("active", csvRow = 2))
        )
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val stopClient = NoResponseStopClient()
        val vm = newVm(stopClient)
        stageProposal(
            vm,
            ImportProposal(oldPlanId, "old.csv", "new.csv", 5,
                listOf(WorklistRow(31.5, 51.4, 1, 1, 1)))
        )

        vm.confirmImportReplacement()

        await("one confirmation completes stop+abandon+import without the stop round trip") {
            vm.importProposal.value == null
        }
        assertTrue(
            "the engine-stop proof must NOT be required while no engine is running",
            stopClient.requests.isEmpty()
        )
        val successor = db.planDao().getLatestPlan()
        assertEquals("new.csv", successor?.sourceFileName)
        val oldPlan = db.planDao().getPlanById(oldPlanId)!!
        assertNotNull("the old plan must be archived as superseded", oldPlan.supersededAt)
        assertEquals(successor?.id, oldPlan.supersededByPlanId)
        val oldTasks = db.locationTaskDao().getTasksForPlan(oldPlanId)
        assertTrue(
            "remaining tasks must be cancelled, got ${oldTasks.map { it.status }}",
            oldTasks.all { it.status == "completed" || it.status == "cancelled" }
        )
        val session = db.runSessionDao().getById(sessionId)!!
        assertEquals("the abandoned plan's session must be terminal", "stopped", session.status)
        assertNotNull(session.endedAt)
        assertEquals(
            "exactly one PLAN_ABANDONED audit row must be appended",
            1,
            db.auditEventDao().forEventType("PLAN_ABANDONED").size
        )
    }
}
