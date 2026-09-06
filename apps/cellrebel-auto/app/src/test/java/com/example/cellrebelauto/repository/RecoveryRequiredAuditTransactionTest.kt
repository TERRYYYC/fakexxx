package com.example.cellrebelauto.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #89 / #86 regression: RECOVERY_REQUIRED is not merely a string phase. The attempt owner,
 * its typed reason and a bound audit event are one durable fact; a process must never observe one
 * without the others after the repository call returns.
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryRequiredAuditTransactionTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlanRepository
    private var attemptId = 0L

    @Before
    fun setUp() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PlanRepository(db)
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "audit.csv",
                importedAt = 100L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1
            ),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 30.5, latitude = 50.4, priority = 1, requiredSuccesses = 1))
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).single().id
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 100L, planId = planId, status = "running"))
        attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 101L, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null,
                videoStreamingScore = null, latitude = 50.4, longitude = 30.5,
                aplusState = "ADVANCE_PENDING"
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `recovery-required atomically records owner reason and audit event`() = runTest {
        repository.markRecoveryRequired(attemptId, "ADVANCE_NOT_PROVEN:PROVIDER_ERROR_16")

        val owner = db.testAttemptDao().getAttemptById(attemptId)!!
        assertEquals("RECOVERY_REQUIRED", owner.aplusState)
        assertEquals("ADVANCE_NOT_PROVEN:PROVIDER_ERROR_16", owner.failureReason)

        val events = db.auditEventDao().forAttempt(attemptId)
        assertEquals("the owner mutation must not be audit-free", 1, events.size)
        assertEquals("RECOVERY_REQUIRED", events.single().eventType)
        assertEquals(
            "ADVANCE_PENDING->RECOVERY_REQUIRED[ADVANCE_NOT_PROVEN:PROVIDER_ERROR_16]",
            events.single().payloadDigest
        )
    }
}
