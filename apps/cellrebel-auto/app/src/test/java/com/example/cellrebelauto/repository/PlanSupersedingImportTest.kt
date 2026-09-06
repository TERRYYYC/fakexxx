package com.example.cellrebelauto.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.WorklistRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #97: a confirmed replacement archives history; it never resets or deletes the old plan. */
@RunWith(RobolectricTestRunner::class)
class PlanSupersedingImportTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlanRepository
    private var oldPlanId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = PlanRepository(db)
        oldPlanId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "old.csv", importedAt = 100L, globalBufferSeconds = 5,
                totalRows = 1, totalRequiredSuccesses = 2
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 30.5, latitude = 50.4,
                    priority = 1, requiredSuccesses = 2
                )
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `confirmed replacement archives old plan and makes new plan current without deleting history`() = runTest {
        val oldTaskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val stoppedSession = db.runSessionDao().insert(
            RunSession(startedAt = 200L, endedAt = 300L, status = "stopped", planId = oldPlanId)
        )

        val result = repository.confirmSupersedingImport(
            expectedOldPlanId = oldPlanId,
            sourceFileName = "new.csv",
            globalBufferSeconds = 5,
            rows = listOf(WorklistRow(31.5, 51.4, 1, 1, 1)),
            importedAt = 400L,
            supersededAt = 400L
        )

        val imported = result as PlanRepository.SupersedingImportResult.Imported
        val oldPlan = db.planDao().getPlanById(oldPlanId)!!
        assertEquals("old plan is retained as an auditable record", 400L, oldPlan.supersededAt)
        assertEquals(imported.planId, oldPlan.supersededByPlanId)
        assertEquals("old task survives archival", oldTaskId, db.locationTaskDao().getTasksForPlan(oldPlanId).single().id)
        assertEquals("old session survives archival", stoppedSession, db.runSessionDao().getById(stoppedSession)!!.id)
        assertEquals("new.csv", db.planDao().getLatestPlan()!!.sourceFileName)
        assertNull("newly imported plan is active", db.planDao().getPlanById(imported.planId)!!.supersededAt)
    }

    @Test
    fun `active old session refuses replacement and leaves old plan current`() = runTest {
        val activeSession = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )

        val result = repository.confirmSupersedingImport(
            expectedOldPlanId = oldPlanId,
            sourceFileName = "new.csv",
            globalBufferSeconds = 5,
            rows = listOf(WorklistRow(31.5, 51.4, 1, 1, 1)),
            importedAt = 400L,
            supersededAt = 400L
        )

        val rejected = result as PlanRepository.SupersedingImportResult.ActiveSession
        assertEquals(activeSession, rejected.sessionId)
        assertNull(db.planDao().getPlanById(oldPlanId)!!.supersededAt)
        assertEquals("old.csv", db.planDao().getLatestPlan()!!.sourceFileName)
        assertTrue(db.locationTaskDao().getTasksForPlan(oldPlanId).isNotEmpty())
        assertNotNull(db.runSessionDao().getById(activeSession))
    }
}
