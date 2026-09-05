package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #17 addendum — the LEGACY cancellation path must still finalize its durable state.
 *
 * Root cause (code-level, cancellation-swallow): run()'s `catch (CancellationException)` legacy
 * branch calls SUSPEND repository writes (`markAttemptInterruptedIfNonTerminal`,
 * `finishSession`) on an ALREADY-CANCELLED coroutine. The first suspension point inside those
 * writes rethrows CancellationException before the DB write lands, so:
 *   - the in-flight attempt row keeps its non-terminal status (the device's History "running"
 *     zombie attempt),
 *   - the run session stays "running",
 *   - `updateState(IDLE)` / `finishSession` are skipped (no terminal state published).
 * This is exactly the shape the OEM service-recycle (#15) triggers from the outside; the A+
 * branch already guards its pause with `withContext(NonCancellable)` — the legacy branch must
 * do the same for its durable finalization.
 *
 * # #17 附带：legacy 取消路径的持久收尾不得被取消吞噬（僵尸 attempt/session 根因）
 */
@RunWith(RobolectricTestRunner::class)
class EngineLegacyCancelFinalizeTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedPlan(taskId: Long): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "issue17-cancel.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1
            )
        )
        db.planDao().insertTasks(
            listOf(
                LocationTask(
                    id = taskId, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 1
                )
            )
        )
        return planId
    }

    @Test
    fun `cancelling the legacy engine mid-GPS-settle finalizes the attempt, session and projections`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId)
        var now = 1000L
        val settleGate = CompletableDeferred<Unit>() // parks the settle wait until the test cancels
        val settleEntered = CompletableDeferred<Unit>() // signals: the engine IS parked in the settle

        val engine = AutomationEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onStartInteraction: suspend () -> Unit,
                    onRunningObserved: suspend (Long) -> Unit,
                ): AttemptOutcome = AttemptOutcome.Success(
                    webScore = 8.0, videoScore = 7.0, runningObservedAt = now, startedAt = startedAt, endedAt = now
                )
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome = GpsOutcome.Active
            },
            bufferGate = BufferGate(0) { now },
            testTimeoutMs = 90_000L,
            gpsSettleMs = 45_000L, // the device's silent-death stage
            stageToggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
            nowMs = { now },
            delayMs = { ms ->
                if (ms == 45_000L) {
                    settleEntered.complete(Unit)
                    settleGate.await() // park INSIDE the settle wait
                } else {
                    now += ms
                }
            }
        )

        val job = launch { engine.run() }
        settleEntered.await() // the engine is parked inside the GPS settle wait (attempt created, settle projected)

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("precondition: the attempt is still non-terminal inside the settle", "starting", attempt.status)
        val sessionId = db.runSessionDao().getLatest()!!.id

        // The service dies / the user stops: the engine coroutine is CANCELLED mid-settle.
        // The cancellation handler must land its durable finalization BEFORE the coroutine dies.
        job.cancelAndJoin()

        assertEquals(
            "the cancelled attempt must be marked interrupted (not left a running zombie — issue #17)",
            "interrupted",
            db.testAttemptDao().getAttemptsForTask(taskId).single().status
        )
        assertEquals(
            "the run session must be finalized stopped (not left running)",
            "stopped",
            db.runSessionDao().getById(sessionId)!!.status
        )
    }
}
