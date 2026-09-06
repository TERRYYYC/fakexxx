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
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #86: negative completion outcomes have an immutable carrier before any release/final projection. */
@RunWith(RobolectricTestRunner::class)
class UnverifiedOutcomeCarrierTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlanRepository
    private var attemptId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = PlanRepository(db)
        val planId = db.planDao().insertPlanWithTasks(LocationPlan(sourceFileName = "n.csv", importedAt = 1, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1), listOf(LocationTask(planId = 0, csvRow = 1, longitude = 1.0, latitude = 1.0, priority = 1, requiredSuccesses = 1)))
        val task = db.locationTaskDao().getTasksForPlan(planId).single()
        val session = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId))
        attemptId = db.testAttemptDao().insert(TestAttempt(taskId = task.id, runSessionId = session, attemptOrdinal = 1, successOrdinal = null, startedAt = 1, runningObservedAt = null, endedAt = null, status = "running", failureReason = null, webBrowsingScore = null, videoStreamingScore = null, latitude = 1.0, longitude = 1.0))
    }
    @After fun tearDown() = db.close()

    @Test fun `negative outcome writes immutable carrier and same input replays`() = runTest {
        repository.recordUnverifiedOutcome(attemptId, "POST_OBSERVATION_UNAVAILABLE", "missing-post:$attemptId")
        repository.recordUnverifiedOutcome(attemptId, "POST_OBSERVATION_UNAVAILABLE", "missing-post:$attemptId")
        val row = db.unverifiedAttemptRecordDao().getByAttempt(attemptId)!!
        assertEquals("POST_OBSERVATION_UNAVAILABLE", row.reason)
        assertEquals("missing-post:$attemptId", row.evidenceDigest)
    }

    @Test fun `negative outcome rejects a conflicting replay instead of rewriting its carrier`() = runTest {
        repository.recordUnverifiedOutcome(attemptId, "POST_OBSERVATION_UNAVAILABLE", "missing-post:$attemptId")

        assertThrows(IllegalStateException::class.java) {
            runTest {
                repository.recordUnverifiedOutcome(attemptId, "COMPLETION_EVIDENCE_UNAVAILABLE", "different")
            }
        }
        val row = db.unverifiedAttemptRecordDao().getByAttempt(attemptId)!!
        assertEquals("POST_OBSERVATION_UNAVAILABLE", row.reason)
        assertEquals("missing-post:$attemptId", row.evidenceDigest)
    }
}
