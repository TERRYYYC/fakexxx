package com.example.cellrebelauto.automation

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #80: success is a durable session receipt, never a best-effort coroutine launch. */
@RunWith(RobolectricTestRunner::class)
class RunStartCoordinatorTest {
    private lateinit var db: AppDatabase
    private lateinit var coordinator: RunStartCoordinator
    private var planId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val repository = PlanRepository(db)
        coordinator = RunStartCoordinator(repository)
        planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "start.csv", importedAt = 100L, globalBufferSeconds = 5,
                totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 30.0, latitude = 50.0, priority = 1, requiredSuccesses = 1))
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `accepted start receipt names a durable starting session`() = runTest {
        val result = coordinator.admit(planId, startedAt = 200L)

        val accepted = result as RunStartReceipt.Accepted
        val session = db.runSessionDao().getById(accepted.sessionId)
        assertEquals("accepted must be emitted only after Room insert", "starting", session!!.status)
        assertEquals(planId, session.planId)
    }

    @Test
    fun `a second admission reuses the durable owner instead of creating a second session`() = runTest {
        val first = coordinator.admit(planId, startedAt = 200L) as RunStartReceipt.Accepted
        val second = coordinator.admit(planId, startedAt = 201L)

        val resumed = second as RunStartReceipt.Resumed
        assertEquals(first.sessionId, resumed.sessionId)
        val count = db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM run_sessions WHERE planId = ?", arrayOf(planId.toString())
        ).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        assertEquals("duplicate admission must not mint another run session", 1, count)
    }

    @Test
    fun `missing plan is rejected without creating a session`() = runTest {
        val result = coordinator.admit(planId = 999L, startedAt = 200L)

        assertTrue(result is RunStartReceipt.Rejected)
        val count = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM run_sessions").use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(0, count)
    }
}
