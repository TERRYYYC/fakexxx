package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-MG-02 (Issue #5 Task 4, area 6) — PRODUCTION-ENTRYPOINT RED (Sol round-3 Finding 3).
 *
 * Sol's round-3 re-review proved the round-3 MmG02 tests were greenable: they called DAO methods +
 * `selectNextTrustedTask` DIRECTLY, but production wiring stays on legacy — `AutomationEngine:171`
 * selects via `PlanScheduler.selectNext` (counter path), the recovery sweep normalizes on the counter,
 * and `finalizeAttemptSuccess` increments the legacy `completedSuccesses`. So a bad impl that implements
 * the trusted DAO SQL + the isolated selector greens those unit tests while the engine/repo keep acting on
 * the counter and the M-MG-02 bug remains (a counter-complete / trusted-incomplete task is skipped or
 * pre-completed, its address silently abandoned).
 *
 * These tests defeat that bad impl by driving the REAL production entrypoints against a real in-memory
 * Room DB:
 *   • [AutomationEngine.run] — the recovery sweep + selection loop the operator actually runs;
 *   • [PlanRepository.finalizeAttemptSuccess] — the atomic success transaction (NOT the raw DAO).
 *
 * The single-task selection test is the discriminator: a counter-complete (3/3) / trusted-incomplete (0)
 * task must be RE-ATTEMPTED by the engine. Under the skeleton the sweep completes it on the counter
 * (RED); under Sol's bad impl (trusted DAO + isolated selector, engine selection still legacy) the sweep
 * leaves it active but legacy `PlanScheduler.selectNext` still skips it (RED); only the full GREEN — rewire
 * the engine to the trusted projection AND make finalize consult the trusted ledger — passes.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.2 (Finding 3).
 *
 * # M-MG-02 生产入口 RED：驱动 AutomationEngine.run() 与 finalizeAttemptSuccess（非裸 DAO）
 */
@RunWith(RobolectricTestRunner::class)
class MmG02EngineSelectionRedTest {

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

    // ---- Minimal fakes (mirror EngineRecoveryTest; kept local to avoid touching the green suite) ----

    private class FakeCellRebelRunner(
        templates: List<AttemptOutcome>,
        private val nowMs: () -> Long
    ) : CellRebelRunner {
        private val queue = templates.toMutableList()
        var calls = 0
            private set

        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onStartInteraction: suspend () -> Unit,
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            calls++
            val template = if (queue.size > 1) queue.removeAt(0) else queue.first()
            if (template is AttemptOutcome.Success) onStartInteraction()
            return when (template) {
                is AttemptOutcome.Success -> template.copy(startedAt = startedAt, endedAt = nowMs())
                is AttemptOutcome.Failure -> template.copy(startedAt = startedAt, endedAt = nowMs())
            }
        }
    }

    private class FakeGpsSetter(outcomes: List<GpsOutcome>) : GpsLocationSetter {
        private val queue = outcomes.toMutableList()
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome =
            if (queue.size > 1) queue.removeAt(0) else queue.first()
    }

    private class VirtualClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> now += ms }
    }

    private val successTemplate = AttemptOutcome.Success(
        webScore = 8.0, videoScore = 7.0, runningObservedAt = 0L, startedAt = 0L, endedAt = 0L
    )

    private fun buildEngine(
        planId: Long,
        runner: FakeCellRebelRunner,
        gps: FakeGpsSetter,
        clock: VirtualClock
    ) = AutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = runner,
        gpsSetter = gps,
        bufferGate = BufferGate(0, clock.nowMs),
        testTimeoutMs = 90_000L,
        gpsSettleMs = 0L,
        nowMs = clock.nowMs,
        delayMs = clock.delayMs
    )

    /**
     * Seeds a single-task plan; the task's legacy counter is force-set to `completed`/`required` and the
     * trusted ledger projection carries `trusted` rows. status is forced to "active" (not yet "completed").
     *
     * The counter/status are written via a direct SQL UPDATE AFTER insert so the seeded state is exact
     * regardless of [insertPlanWithTasks]'s defaults — the RED's "right reason" must not depend on insert
     * semantics. FK is off in Room, but a real plan row is inserted for self-consistency.
     */
    private suspend fun seedTask(completed: Int, required: Int, trusted: Int): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "mmg02.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = required
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = required
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        db.openHelper.writableDatabase.execSQL(
            "UPDATE location_tasks SET completedSuccesses = $completed, requiredSuccesses = $required, " +
                "status = 'active' WHERE id = $taskId"
        )
        repeat(trusted) { i ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO trusted_quota_entries (attemptId, taskId, evidenceDigest, committedAt) " +
                    "VALUES (${taskId * 1000 + i}, $taskId, 'dig-$taskId-$i', ${1000L + i})"
            )
        }
        return planId to taskId
    }

    // ---- SELECTION through AutomationEngine.run() (the discriminator Sol requires) ----

    @Test
    fun `engine re-attempts a counter-complete trusted-incomplete task instead of skipping it`() = runTest {
        // M-MG-02: the legacy counter says 3/3 (done) but the trusted ledger has ZERO entries — this task
        // is NOT actually complete and MUST be re-attempted. Driving the REAL AutomationEngine.run():
        //   • skeleton → the recovery sweep (counter-based normalize) completes A on the counter ⇒ plan
        //     done ⇒ 0 attempts ⇒ RED.
        //   • Sol's bad impl (trusted DAO SQL + isolated trusted selector, engine selection still legacy)
        //     → sweep leaves A active, but PlanScheduler.selectNext still skips A on the counter ⇒ 0
        //     attempts ⇒ STILL RED.
        //   • full GREEN (engine rewired to the trusted projection) → A re-attempted ⇒ passes.
        val (planId, taskId) = seedTask(completed = 3, required = 3, trusted = 0)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        val attemptsForA = db.testAttemptDao().getAttemptsForTask(taskId)
        assertTrue(
            "M-MG-02: engine must RE-ATTEMPT the counter-complete/trusted-incomplete task (trusted ledger " +
                "empty), not skip or pre-complete it on the legacy counter; got ${attemptsForA.size} " +
                "attempt(s), runner.calls=${runner.calls}",
            attemptsForA.isNotEmpty()
        )
    }

    // ---- COMPLETION through PlanRepository.finalizeAttemptSuccess (the production transaction) ----

    @Test
    fun `finalizeAttemptSuccess completes a trusted-complete task despite a zero legacy counter`() = runTest {
        // Inverse polarity through the PRODUCTION success transaction (not the raw DAO — Sol's explicit
        // requirement). The trusted ledger reached quota (3) but the legacy counter is 0: M-MG-02 says this
        // task IS complete. Skeleton finalize increments the counter to 1 and checks 1 >= 3 ⇒ NOT completed
        // ⇒ RED. GREEN must make finalize consult the trusted ledger.
        val (planId, taskId) = seedTask(completed = 0, required = 3, trusted = 3)
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId))
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = 650L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )
        repo.finalizeAttemptSuccess(
            attemptId = attemptId, taskId = taskId, expectedCompletedSuccesses = 0,
            runningObservedAt = 650L, endedAt = 700L, webScore = 8.0, videoScore = 7.0
        )
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(
            "M-MG-02: finalizeAttemptSuccess must complete a trusted-complete task despite a zero counter",
            "completed",
            task.status
        )
    }

    // ---- R6-F3（§11.7）: completion-direction NEGATIVE through the production finalize entry ----

    @Test
    fun `R6-F3 finalizeAttemptSuccess must NOT complete a counter-full trusted-empty task on the legacy counter`() = runTest {
        // §11.7 F3: Sol 的 combined attack 在同一个包里同时保留「trusted-only DAO 投影」+「finalize 仍自增
        // legacy counter 并保留 counter 达成即 completed 的回退」——正反两个方向的既有 RED 都被 green，
        // 因为没有 RED 断言「counter-full/trusted-empty 经生产 finalize 不得完成」。
        // 本 RED 补这个负向：counter 满(1/1)、trusted 空(0)，经生产事务 finalizeAttemptSuccess 后，
        // 任务必须保持未完成（completion 不得走 legacy counter）。
        //   • 现状 finalize：自增 1→2 + completeTaskIfQuotaReached(counter 2>=1) ⇒ completed ⇒ RED。
        //   • GREEN（finalize consult trusted）：trusted 0<1 ⇒ 不完成 ⇒ active ⇒ 通过。
        //   • dual-path 攻击（保留 counter 回退）：counter 2>=1 ⇒ completed ⇒ 期望 active ⇒ 失败 ⇒ 杀攻击。
        val (planId, taskId) = seedTask(completed = 1, required = 1, trusted = 0)
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId))
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = 650L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )
        repo.finalizeAttemptSuccess(
            attemptId = attemptId, taskId = taskId, expectedCompletedSuccesses = 1,
            runningObservedAt = 650L, endedAt = 700L, webScore = 8.0, videoScore = 7.0
        )
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertTrue(
            "R6-F3: counter-full(1/1)/trusted-empty(0) 任务经生产 finalize 不得被 legacy counter 完成；" +
                "completion 必须只走 trusted 账本。实际 status=${task.status}（现状/dual-path 攻击 ⇒ completed）",
            task.status != "completed"
        )
    }
}
