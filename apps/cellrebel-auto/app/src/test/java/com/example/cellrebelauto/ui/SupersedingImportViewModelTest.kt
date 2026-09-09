package com.example.cellrebelauto.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.SupersessionStopStatus
import com.example.cellrebelauto.cutover.CutoverExclusiveAdmission
import com.example.cellrebelauto.cutover.CutoverExclusiveRelease
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.WorklistRow
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SupersedingImportViewModelTest {
    private lateinit var db: AppDatabase

    private class FakeStopClient : SupersessionStopClient {
        val mutableStatus = MutableStateFlow<SupersessionStopStatus>(SupersessionStopStatus.Idle)
        override val status: StateFlow<SupersessionStopStatus> = mutableStatus
        val requests = mutableListOf<Triple<Long, Long, String>>()
        override fun request(planId: Long, sessionId: Long, requestId: String) {
            requests += Triple(planId, sessionId, requestId)
        }
    }

    // Rebase note (round-3): same tree-level TestMainDispatcher race as
    // PlanProfileConsistencyViewModelTest — cancel every created VM's scope
    // BEFORE resetMain so no Eagerly stateIn resumption escapes into the next
    // class's setMain window (the #112/#111 drain pattern).
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

    @Suppress("UNCHECKED_CAST")
    private fun stageProposal(vm: MainViewModel, proposal: ImportProposal) {
        val field = MainViewModel::class.java.getDeclaredField("_importProposal")
        field.isAccessible = true
        (field.get(vm) as MutableStateFlow<ImportProposal?>).value = proposal
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    @Test
    fun `explicit confirmation binds one stop request and imports only its verified durable proof`() = runTest {
        val oldPlanId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "old.csv",
                importedAt = 100L,
                globalBufferSeconds = 5,
                totalRows = 1,
                totalRequiredSuccesses = 1
            ),
            listOf(LocationTask(planId = 0L, csvRow = 1, longitude = 30.5, latitude = 50.4,
                priority = 1, requiredSuccesses = 1))
        )
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val client = FakeStopClient()
        val vm = MainViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedDb = db,
            supersessionStopClient = client,
            injectedAccessGate = com.example.cellrebelauto.cutover.CutoverAccessGate.open(),
            // #135: the #97 stop-proof path is the LIVE-engine branch — pin the probe.
            runningProbe = { true }
        ).also { createdVms += it }
        stageProposal(
            vm,
            ImportProposal(oldPlanId, "old.csv", "new.csv", 5,
                listOf(WorklistRow(31.5, 51.4, 1, 1, 1)))
        )

        vm.confirmImportReplacement()
        await("confirmation requests stop verification") { client.requests.size == 1 }
        val firstRequest = client.requests.single()
        assertEquals(Triple(oldPlanId, sessionId, firstRequest.third), firstRequest)
        assertTrue(vm.isImportReplacementStopping.value)
        assertNotNull(vm.importProposal.value)

        vm.confirmImportReplacement()
        assertEquals("repeated confirm cannot create a second request", 1, client.requests.size)
        client.mutableStatus.value = SupersessionStopStatus.Blocked("late-other-request", "late")
        assertTrue("a late foreign callback is ignored", vm.isImportReplacementStopping.value)

        // #135: a MATCHING Blocked is no longer a "review and retry" dead end — the same
        // single confirmation falls back to abandon+import (the coordinator has joined
        // the engine job by then), and the durable shape must be the abandon semantics.
        client.mutableStatus.value = SupersessionStopStatus.Blocked(firstRequest.third, "retryable")
        await("the matching Blocked completes via abandon+import") {
            !vm.isImportReplacementStopping.value && vm.importProposal.value == null
        }
        assertEquals("new.csv", db.planDao().getLatestPlan()?.sourceFileName)
        assertTrue(
            "remaining tasks must be cancelled",
            db.locationTaskDao().getTasksForPlan(oldPlanId)
                .all { it.status == "completed" || it.status == "cancelled" }
        )
        assertEquals("stopped", db.runSessionDao().getById(sessionId)!!.status)
        assertEquals(1, db.auditEventDao().forEventType("PLAN_ABANDONED").size)
    }

    @Test
    fun `confirmation rejected by a closed gate retires busy state and remains retryable`() = runTest {
        val oldPlanId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "old.csv",
                importedAt = 100L,
                globalBufferSeconds = 5,
                totalRows = 1,
                totalRequiredSuccesses = 1
            ),
            listOf(LocationTask(planId = 0L, csvRow = 1, longitude = 30.5, latitude = 50.4,
                priority = 1, requiredSuccesses = 1))
        )
        val gate = com.example.cellrebelauto.cutover.CutoverAccessGate.open()
        val lease = (gate.acquireCaptureExclusive("confirm-rejected") { true } as
            CutoverExclusiveAdmission.Granted).lease
        val vm = MainViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedDb = db,
            supersessionStopClient = FakeStopClient(),
            injectedAccessGate = gate
        ).also { createdVms += it }
        stageProposal(
            vm,
            ImportProposal(oldPlanId, "old.csv", "new.csv", 5,
                listOf(WorklistRow(31.5, 51.4, 1, 1, 1)))
        )

        vm.confirmImportReplacement()

        await("closed-gate rejection retires the stopping owner") {
            !vm.isImportReplacementStopping.value
        }
        assertNotNull("the proposal remains available for retry", vm.importProposal.value)
        assertTrue(vm.importNotice.value.orEmpty().contains("unavailable", ignoreCase = true))

        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        vm.confirmImportReplacement()
        await("reopen permits the same proposal to be confirmed") { vm.importProposal.value == null }
        assertEquals("new.csv", db.planDao().getLatestPlan()?.sourceFileName)
    }

    @Test
    fun `verified stop rejected by a closed gate retires its request and preserves proposal`() = runTest {
        val oldPlanId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "old.csv",
                importedAt = 100L,
                globalBufferSeconds = 5,
                totalRows = 1,
                totalRequiredSuccesses = 1
            ),
            listOf(LocationTask(planId = 0L, csvRow = 1, longitude = 30.5, latitude = 50.4,
                priority = 1, requiredSuccesses = 1))
        )
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val gate = com.example.cellrebelauto.cutover.CutoverAccessGate.open()
        val client = FakeStopClient()
        val vm = MainViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            injectedDb = db,
            supersessionStopClient = client,
            injectedAccessGate = gate,
            // #135: this oracle pins the LIVE-engine proof branch — pin the probe.
            runningProbe = { true }
        ).also { createdVms += it }
        stageProposal(
            vm,
            ImportProposal(oldPlanId, "old.csv", "new.csv", 5,
                listOf(WorklistRow(31.5, 51.4, 1, 1, 1)))
        )
        vm.confirmImportReplacement()
        await("confirmation requests stop verification") { client.requests.size == 1 }
        val requestId = client.requests.single().third
        val proof = (PlanRepository(db, gate).verifyAndStopForSupersession(
            requestId, oldPlanId, sessionId, 300L
        ) as PlanRepository.SupersessionStopVerification.Verified).proof
        val lease = (gate.acquireCaptureExclusive("proof-rejected") { true } as
            CutoverExclusiveAdmission.Granted).lease

        client.mutableStatus.value = SupersessionStopStatus.Verified(requestId, proof)

        await("closed-gate proof rejection retires the stopping owner") {
            !vm.isImportReplacementStopping.value
        }
        assertNotNull("the proposal remains available for a fresh confirmation", vm.importProposal.value)
        assertTrue(vm.importNotice.value.orEmpty().contains("unavailable", ignoreCase = true))
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))

        vm.confirmImportReplacement()
        await("a fresh confirmation can request a new proof after reopen") { client.requests.size == 2 }
        val retryRequest = client.requests.last()
        assertNotEquals(requestId, retryRequest.third)
        val retryProof = (PlanRepository(db, gate).verifyAndStopForSupersession(
            retryRequest.third, oldPlanId, sessionId, 400L
        ) as PlanRepository.SupersessionStopVerification.Verified).proof
        client.mutableStatus.value = SupersessionStopStatus.Verified(retryRequest.third, retryProof)
        await("the fresh proof can finish after reopen") { vm.importProposal.value == null }
        assertEquals("new.csv", db.planDao().getLatestPlan()?.sourceFileName)
    }
}
