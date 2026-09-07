package com.example.cellrebelauto.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `named recovery transition atomically records owner reason and exact event`() = runTest {
        repository.transitionToRecoveryRequired(
            attemptId,
            AttemptEvent.ADVANCE_NOT_PROVEN,
            "ADVANCE_NOT_PROVEN:PROVIDER_ERROR_16",
            1_000L
        )

        val owner = db.testAttemptDao().getAttemptById(attemptId)!!
        assertEquals("RECOVERY_REQUIRED", owner.aplusState)
        assertEquals("ADVANCE_NOT_PROVEN:PROVIDER_ERROR_16", owner.failureReason)

        val events = db.auditEventDao().forAttempt(attemptId)
        assertEquals("the owner mutation must not be audit-free", 1, events.size)
        assertEquals("ADVANCE_NOT_PROVEN", events.single().eventType)
        assertEquals(
            "ADVANCE_PENDING->RECOVERY_REQUIRED[ADVANCE_NOT_PROVEN:PROVIDER_ERROR_16]",
            events.single().payloadDigest
        )
    }

    @Test
    fun `named recovery transition rejects an event not owned by the durable phase`() = runTest {
        val thrown = runCatching {
            repository.transitionToRecoveryRequired(
                attemptId,
                AttemptEvent.START_FAILED_BEFORE_RUNNING,
                "MISSING_START_INTERACTION_EVIDENCE",
                1_001L
            )
        }.exceptionOrNull()

        assertTrue(thrown is IllegalStateException)
        val owner = db.testAttemptDao().getAttemptById(attemptId)!!
        assertEquals("ADVANCE_PENDING", owner.aplusState)
        assertEquals(null, owner.failureReason)
        assertTrue(db.auditEventDao().forAttempt(attemptId).isEmpty())
    }

    @Test
    fun `audit insert failure rolls back the named owner transition`() = runTest {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER fail_named_recovery_audit
            BEFORE INSERT ON auto_audit_events
            WHEN NEW.eventType = 'ADVANCE_NOT_PROVEN'
            BEGIN
              SELECT RAISE(ABORT, 'forced named audit failure');
            END
            """.trimIndent()
        )

        val thrown = runCatching {
            repository.transitionToRecoveryRequired(
                attemptId,
                AttemptEvent.ADVANCE_NOT_PROVEN,
                "ADVANCE_NOT_PROVEN:PROVIDER_TRANSPORT_FAILURE",
                1_002L
            )
        }.exceptionOrNull()
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_named_recovery_audit")

        assertTrue(thrown != null)
        val owner = db.testAttemptDao().getAttemptById(attemptId)!!
        assertEquals("ADVANCE_PENDING", owner.aplusState)
        assertEquals(null, owner.failureReason)
        assertTrue(db.auditEventDao().forAttempt(attemptId).isEmpty())
    }
}
