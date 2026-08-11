package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * F003 stage-toggle engine tests (AC-F3-2/3/4/5, INV-F3-1/2/3).
 * # F003 阶段开关引擎测试：跳过路径、配额语义、中途切换、双关拒绝
 */
@RunWith(RobolectricTestRunner::class)
class EngineStageToggleTest {

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

    private class FakeRunner(
        private val nowMs: () -> Long
    ) : CellRebelRunner {
        var calls = 0
            private set

        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            calls++
            return AttemptOutcome.Success(
                webScore = 8.0, videoScore = 7.0,
                runningObservedAt = nowMs(), startedAt = startedAt, endedAt = nowMs()
            )
        }
    }

    private class FakeGps(
        outcomes: List<GpsOutcome> = listOf(GpsOutcome.Active)
    ) : GpsLocationSetter {
        private val queue = outcomes.toMutableList()
        var calls = 0
            private set

        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
            calls++
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    private class VirtualClock {
        var now = 0L
        val delays = mutableListOf<Long>()
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> delays.add(ms); now += ms }
    }

    private suspend fun seedPlan(quota: Int): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = quota
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        return planId to db.locationTaskDao().getTasksForPlan(planId).first().id
    }

    private fun buildEngine(
        planId: Long,
        runner: CellRebelRunner,
        gps: GpsLocationSetter,
        clock: VirtualClock,
        toggles: suspend () -> StageToggles,
        gpsSettleMs: Long = 0L,
        bufferSeconds: Int = 0
    ) = AutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = runner,
        gpsSetter = gps,
        bufferGate = BufferGate(bufferSeconds, clock.nowMs),
        testTimeoutMs = 90_000L,
        gpsSettleMs = gpsSettleMs,
        stageToggles = toggles,
        nowMs = clock.nowMs,
        delayMs = clock.delayMs
    )

    @Test
    fun `location stage off skips gps and settle entirely and marks gps_skipped`() = runTest {
        // # AC-F3-2：不触碰 Fake GPS（无调用、无 settle），CellRebel 生命周期与配额不变
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(planId, runner, gps, clock, toggles = {
            StageToggles(locationStageEnabled = false, testStageEnabled = true)
        }, gpsSettleMs = 5_000L).run()

        assertEquals(0, gps.calls)
        assertEquals(1, runner.calls)
        // # 无 settle 延迟（缓冲为 0，唯一可能的 delay 就是 settle）
        assertTrue(clock.delays.isEmpty())

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("succeeded", attempt.status)
        assertEquals("gps_skipped", attempt.stageNotes) // # INV-F3-1：跳过必记录
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `test stage off runs gps only, terminates ok_gps_only and counts quota`() = runTest {
        // # AC-F3-3：不启动 CellRebel；GPS 验证即终态 ok_gps_only 并计配额
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(planId, runner, gps, clock, toggles = {
            StageToggles(locationStageEnabled = true, testStageEnabled = false)
        }, gpsSettleMs = 5_000L).run()

        assertEquals(1, gps.calls)
        assertEquals(0, runner.calls)
        assertEquals(listOf(5_000L), clock.delays) // # settle 仍在激活确认之后

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("ok_gps_only", attempt.status)
        assertEquals(1, attempt.successOrdinal) // # 计配额（KD-F3-2）
        assertEquals("test_skipped", attempt.stageNotes)
        assertNull(attempt.webBrowsingScore)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        assertEquals("completed", task.status)
    }

    @Test
    fun `test stage off with gps failure yields typed failure and records the skip`() = runTest {
        // # AC-F3-3 + INV-F3-1：位置阶段失败 = 类型化失败不占配额，跳过标记仍记录
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        // # 第一次失败、第二次成功（F001 语义：同任务缓冲后重试）
        val gps = FakeGps(
            listOf(
                GpsOutcome.Failed(FailureReason.FAKE_GPS_NOT_ACTIVE, "no stop button"),
                GpsOutcome.Active
            )
        )
        buildEngine(planId, runner, gps, clock, toggles = {
            StageToggles(locationStageEnabled = true, testStageEnabled = false)
        }).run()

        assertEquals(0, runner.calls)
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("FAKE_GPS_NOT_ACTIVE", attempts[0].failureReason)
        assertEquals("test_skipped", attempts[0].stageNotes)
        assertEquals("ok_gps_only", attempts[1].status)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `both stages off rejects start with no session created`() = runTest {
        // # AC-F3-4：双关 = 配置错误，拒绝启动且不创建会话
        val (planId, _) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(planId, runner, gps, clock, toggles = {
            StageToggles(locationStageEnabled = false, testStageEnabled = false)
        }).run()

        assertEquals(0, gps.calls)
        assertEquals(0, runner.calls)
        assertNull(db.runSessionDao().getLatest())
        assertTrue(db.testAttemptDao().getAttemptsForPlan(planId).isEmpty())
    }

    @Test
    fun `stale attempt and session plus persisted both-OFF resume sweeps first and creates no new session`() = runTest {
        // # F3R1-3 回归：持久化双关 + 崩溃残留时，recovery sweep 必须先于
        // # both-OFF guard 执行（F001 INV-9），guard 拒绝后不新建 session
        val (planId, taskId) = seedPlan(quota = 2)
        // # 崩溃残留：running attempt + running session
        val staleSessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = 500L, planId = planId)
        )
        db.testAttemptDao().insert(
            com.example.cellrebelauto.model.plan.TestAttempt(
                taskId = taskId, runSessionId = staleSessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = 650L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(planId, runner, gps, clock, toggles = {
            StageToggles(locationStageEnabled = false, testStageEnabled = false)
        }).run()

        // # sweep 已执行：残留行被终态化
        val staleAttempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("interrupted", staleAttempt.status)
        assertEquals(650L, staleAttempt.runningObservedAt)
        assertEquals("interrupted", db.runSessionDao().getById(staleSessionId)!!.status)

        // # guard 拒绝：没有新建 session（仍只有残留那一个），没有新 attempt
        assertEquals(staleSessionId, db.runSessionDao().getLatest()!!.id)
        assertEquals(0, runner.calls)
        assertEquals(0, gps.calls)
    }

    @Test
    fun `two ok_gps_only with nonzero buffer makes the second wait the full buffer`() = runTest {
        // # F3R1-2 回归：ok_gps_only 是终态，必须参与全局缓冲投影（F001 INV-5）
        val (planId, _) = seedPlan(quota = 2)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(
            planId, runner, gps, clock,
            toggles = { StageToggles(locationStageEnabled = true, testStageEnabled = false) },
            gpsSettleMs = 0L,
            bufferSeconds = 60
        ).run()

        assertEquals(2, gps.calls)
        // # 第一次 ok_gps_only 结束后，第二次 attempt 恰好等满 60s 缓冲
        assertEquals(listOf(60_000L), clock.delays)
    }

    @Test
    fun `mid plan both-OFF creates no new attempt, bumps no quota, session fails closed`() = runTest {
        // # F3R1-1 回归：运行中双关 = 配置错误——不创建 attempt、不涨配额、
        // # session 以 error 终态收尾（AC-F3-4/KD-F3-3/INV-F3-1）
        val (planId, taskId) = seedPlan(quota = 2)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(planId, runner, gps, clock, toggles = {
            // # 第一个 attempt 完成后操作员把两个开关都关掉
            val firstAttemptDone = runner.calls >= 1
            StageToggles(
                locationStageEnabled = !firstAttemptDone,
                testStageEnabled = !firstAttemptDone
            )
        }).run()

        // # 恰好 1 个 attempt（第一个成功），第二个从未创建
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(1, attempts.size)
        assertEquals("succeeded", attempts[0].status)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)

        // # session fail-closed（error 终态），不留 running
        val session = db.runSessionDao().getLatest()!!
        assertEquals("error", session.status)
        assertTrue(session.endedAt != null)
    }

    @Test
    fun `mid plan toggle change takes effect from the next attempt`() = runTest {
        // # AC-F3-5：运行中改开关，下个 attempt 生效（每次 attempt 重新读取）
        val (planId, taskId) = seedPlan(quota = 2)
        val clock = VirtualClock()
        val runner = FakeRunner(clock.nowMs)
        val gps = FakeGps()
        buildEngine(planId, runner, gps, clock, toggles = {
            // # 第一个 attempt 完成后操作员把位置阶段关掉（provider 会被重复调用，
            // # 用已执行 attempt 数而不是调用次数来表达"中途"）
            StageToggles(
                locationStageEnabled = runner.calls < 1,
                testStageEnabled = true
            )
        }).run()

        assertEquals(2, runner.calls)
        assertEquals(1, gps.calls) // # 只有第一个 attempt 触碰了 Fake GPS
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertNull(attempts[0].stageNotes)
        assertEquals("gps_skipped", attempts[1].stageNotes)
        assertEquals(2, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }
}
