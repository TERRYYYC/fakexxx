package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.advance.AdvanceCoordinator
import com.example.cellrebelauto.automation.advance.AdvanceStateStore
import com.example.cellrebelauto.automation.advance.PendingProviderGateway
import com.example.cellrebelauto.automation.advance.RoomAdvanceStateStore
import com.example.cellrebelauto.automation.advance.RoomQuotaReader
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Engine plan-loop + recovery tests (INV-2/3/4/5/9).
 * Robolectric in-memory Room + fake typed-outcome handlers + virtual clock.
 * # 引擎计划循环与恢复测试：内存 Room + 假处理器 + 虚拟时钟
 */
@RunWith(RobolectricTestRunner::class)
class EngineRecoveryTest {

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

    // ---- Fakes ----

    /** # 脚本化假 CellRebel 执行器：逐条消费结果模板并用虚拟时钟重新盖时间戳 */
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
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            calls++
            val template = if (queue.size > 1) queue.removeAt(0) else queue.first()
            return when (template) {
                is AttemptOutcome.Success -> template.copy(startedAt = startedAt, endedAt = nowMs())
                is AttemptOutcome.Failure -> template.copy(startedAt = startedAt, endedAt = nowMs())
            }
        }
    }

    /** # 脚本化假 GPS 设置器 */
    private class FakeGpsSetter(outcomes: List<GpsOutcome>) : GpsLocationSetter {
        private val queue = outcomes.toMutableList()
        var calls = 0
            private set

        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
            calls++
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    /** # 虚拟时钟：记录所有 delay 调用，时间只在 delay 时前进 */
    private class VirtualClock {
        var now = 0L
        val delays = mutableListOf<Long>()
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> delays.add(ms); now += ms }
    }

    private val successTemplate = AttemptOutcome.Success(
        webScore = 8.0, videoScore = 7.0, runningObservedAt = 0L, startedAt = 0L, endedAt = 0L
    )

    private fun failureTemplate(reason: FailureReason) = AttemptOutcome.Failure(
        reason = reason, detail = null, startedAt = 0L, endedAt = 0L
    )

    // ---- Seed helpers ----

    private suspend fun seedPlan(quota: Int, bufferSeconds: Int = 60): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = bufferSeconds, totalRows = 1, totalRequiredSuccesses = quota
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        return planId to taskId
    }

    private fun buildEngine(
        planId: Long,
        runner: CellRebelRunner,
        gps: GpsLocationSetter,
        clock: VirtualClock,
        bufferSeconds: Int = 60,
        gpsSettleMs: Long = 0L,
        advanceCoordinator: AdvanceCoordinator? = null,
        advanceStateStore: AdvanceStateStore? = null,
    ) = AutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = runner,
        gpsSetter = gps,
        bufferGate = BufferGate(bufferSeconds, clock.nowMs),
        testTimeoutMs = 90_000L,
        gpsSettleMs = gpsSettleMs,
        nowMs = clock.nowMs,
        delayMs = clock.delayMs,
        advanceCoordinator = advanceCoordinator,
        advanceStateStore = advanceStateStore,
    )

    @Test
    fun `crash window mid attempt is swept to interrupted and task count preserved`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        // # 模拟崩溃窗口：上一次进程留下 running 会话 + running 尝试
        val staleSessionId = db.runSessionDao().insert(RunSession(startedAt = 500L))
        db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = staleSessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = 650L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )

        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # INV-9：残留 running 尝试被清扫为 interrupted，失败原因 INTERRUPTED
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("interrupted", attempts[0].status)
        assertEquals("INTERRUPTED", attempts[0].failureReason)
        assertEquals("succeeded", attempts[1].status)
        // # C2：sweep 不清空已持久化的 runningObservedAt 审计证据
        assertEquals(650L, attempts[0].runningObservedAt)

        // # 计数只来自新尝试；旧残留没有计入
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        assertEquals("completed", task.status)

        // # 残留 running 会话被标记 interrupted；新会话 completed
        assertEquals("interrupted", db.runSessionDao().getById(staleSessionId)!!.status)
    }

    @Test
    fun `success path fills quota exactly once and repeated finalize is idempotent`() = runTest {
        val (planId, taskId) = seedPlan(quota = 2)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate, successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # 恰好 2 次成功，successOrdinal 为 1、2
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals(listOf(1, 2), attempts.map { it.successOrdinal })
        assertTrue(attempts.all { it.status == "succeeded" })
        assertTrue(attempts.all { it.runningObservedAt != null })

        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(2, task.completedSuccesses)
        assertEquals("completed", task.status)
        assertEquals(2, runner.calls)

        // # INV-3：用过期的期望值重复 finalize → 不再增加（幂等）
        val finalized = repo.finalizeAttemptSuccess(
            attemptId = attempts[1].id,
            taskId = taskId,
            expectedCompletedSuccesses = 1, // # 过期值（当前已是 2）
            runningObservedAt = 0L, endedAt = 0L, webScore = 9.0, videoScore = 9.0
        )
        assertFalse(finalized)
        assertEquals(2, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
        // # 尝试行也未被重复覆写
        assertEquals(2, db.testAttemptDao().getAttemptsForTask(taskId)[1].successOrdinal)
    }

    @Test
    fun `failure persists typed reason without quota and same task is retried`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(
            listOf(failureTemplate(FailureReason.CELLREBEL_TIMEOUT), successTemplate),
            clock.nowMs
        )
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # INV-4：失败行带类型化原因，不计入配额
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("CELLREBEL_TIMEOUT", attempts[0].failureReason)
        assertEquals(null, attempts[0].successOrdinal)
        assertEquals("succeeded", attempts[1].status)
        assertEquals(1, attempts[1].successOrdinal)

        // # INV-2：同一任务被重试（两次尝试挂在同一 taskId 上），最终完成
        assertTrue(attempts.all { it.taskId == taskId })
        assertEquals(2, runner.calls)
        assertEquals(2, gps.calls)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        assertEquals("completed", task.status)
    }

    @Test
    fun `gps activation failure yields typed failed attempt and consumes no quota`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        // # 第一次 GPS 激活失败，第二次成功
        val gps = FakeGpsSetter(
            listOf(
                GpsOutcome.Failed(FailureReason.FAKE_GPS_NOT_ACTIVE, "no stop button"),
                GpsOutcome.Active
            )
        )
        buildEngine(planId, runner, gps, clock).run()

        // # INV-10：GPS 失败 → 类型化失败尝试，不消耗配额，也不运行 CellRebel
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("failed", attempts[0].status)
        assertEquals("FAKE_GPS_NOT_ACTIVE", attempts[0].failureReason)
        assertEquals(1, runner.calls)
        assertEquals("completed", db.locationTaskDao().getTaskById(taskId)!!.status)
    }

    @Test
    fun `buffer gate delays next attempt by remaining buffer after success`() = runTest {
        val (planId, _) = seedPlan(quota = 2, bufferSeconds = 60)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate, successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        // # settle=0，隔离出唯一的缓冲延迟
        buildEngine(planId, runner, gps, clock, bufferSeconds = 60, gpsSettleMs = 0).run()

        // # INV-5：第一次成功结束后，第二次尝试恰好等了完整缓冲 60s
        assertEquals(listOf(60_000L), clock.delays)
    }

    @Test
    fun `gps settle happens after activation is confirmed and before cellrebel launch`() = runTest {
        // # F3 回归：批准的旅程是 Setting GPS → GPS settling → Testing；
        // # settle 在 setLocation 之前等于等旧坐标，新坐标激活后必须等稳定再测
        val (planId, _) = seedPlan(quota = 1, bufferSeconds = 0)
        val clock = VirtualClock()
        val events = mutableListOf<String>()
        val gps = object : GpsLocationSetter {
            override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
                events.add("gps-set")
                return GpsOutcome.Active
            }
        }
        val runner = object : CellRebelRunner {
            override suspend fun runTest(
                startedAt: Long,
                testTimeoutMs: Long,
                onRunningObserved: suspend (Long) -> Unit
            ): AttemptOutcome {
                events.add("run-test")
                return successTemplate.copy(startedAt = startedAt, endedAt = clock.nowMs())
            }
        }
        AutomationEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = runner,
            gpsSetter = gps,
            bufferGate = BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L,
            gpsSettleMs = 5_000L,
            nowMs = clock.nowMs,
            delayMs = { ms -> events.add("settle-$ms"); clock.delayMs(ms) }
        ).run()

        assertEquals(listOf("gps-set", "settle-5000", "run-test"), events)
    }

    @Test
    fun `running transition is persisted the moment it is observed`() = runTest {
        // # C2 回归：观察到 RUNNING 的瞬间 attempt 行必须立即变为 running + 时间戳，
        // # 而不是等到终局才一次性写（spec O3 状态表 starting -> running）
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        var stateAtObservation: Pair<String, Long?>? = null
        val runner = object : CellRebelRunner {
            override suspend fun runTest(
                startedAt: Long,
                testTimeoutMs: Long,
                onRunningObserved: suspend (Long) -> Unit
            ): AttemptOutcome {
                onRunningObserved(4242L)
                // # 回调返回后立刻读库：engine 必须已同步持久化 running 迁移
                val row = db.testAttemptDao().getAttemptsForTask(taskId).single()
                stateAtObservation = row.status to row.runningObservedAt
                return successTemplate.copy(startedAt = startedAt, endedAt = clock.nowMs())
            }
        }
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        assertEquals("running" to 4242L, stateAtObservation)
        // # 终局收尾后 runningObservedAt 保留
        val final = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("succeeded", final.status)
        assertEquals(4242L, final.runningObservedAt)
    }

    @Test
    fun `running row swept to interrupted keeps runningObservedAt`() = runTest {
        // # C2 崩溃窗口：running 迁移已持久化后进程死亡（此处以异常模拟），
        // # recovery 终态化为 interrupted 时 runningObservedAt 审计证据必须保留
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = object : CellRebelRunner {
            override suspend fun runTest(
                startedAt: Long,
                testTimeoutMs: Long,
                onRunningObserved: suspend (Long) -> Unit
            ): AttemptOutcome {
                onRunningObserved(5555L)
                throw RuntimeException("process died mid-run")
            }
        }
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        val row = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("interrupted", row.status)
        assertEquals("INTERRUPTED", row.failureReason)
        assertEquals(5555L, row.runningObservedAt)
    }

    @Test
    fun `unexpected handler exception terminalizes in flight attempt instead of orphaning it`() = runTest {
        // # F7 回归：依赖抛异常时，session 标 error 且在飞 attempt 必须终态化（不留 starting 孤儿）
        val (planId, taskId) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = object : GpsLocationSetter {
            override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
                throw RuntimeException("bridge exploded")
            }
        }
        buildEngine(planId, runner, gps, clock).run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(1, attempts.size)
        // # 不允许仍是 starting：必须有定义的终态 + typed 原因
        assertEquals("interrupted", attempts[0].status)
        assertEquals("INTERRUPTED", attempts[0].failureReason)
        assertTrue(attempts[0].endedAt != null)

        val session = db.runSessionDao().getLatest()!!
        assertEquals("error", session.status)
    }

    @Test
    fun `finalize that reaches quota marks task completed inside the same transaction`() = runTest {
        // # F5 回归：quota 达成 → completed 必须与收尾同事务，不留 active+满配额 的崩溃窗口
        val (_, taskId) = seedPlan(quota = 1)
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = null))
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = 650L,
                endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )

        val finalized = repo.finalizeAttemptSuccess(
            attemptId = attemptId, taskId = taskId, expectedCompletedSuccesses = 0,
            runningObservedAt = 650L, endedAt = 700L, webScore = 8.0, videoScore = 7.0
        )

        assertTrue(finalized)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertEquals(1, task.completedSuccesses)
        // # 关键断言：不需要第二次写，任务在同一事务内已 completed
        assertEquals("completed", task.status)
    }

    @Test
    fun `recovery normalizes quota full active task so a completed plan is truly completable`() = runTest {
        // # F5 精确崩溃窗口：上次进程在 quota 自增后、markTaskCompleted 前死亡
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = 60, totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 1,
                    completedSuccesses = 1, status = "active" // # 满配额但仍 active
                )
            )
        )
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock).run()

        // # 恢复归一化：任务被识别为已完成，计划真正完成，会话 completed，无新尝试
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        assertEquals("completed", task.status)
        assertEquals(0, runner.calls)
        val session = db.runSessionDao().getLatest()!!
        assertEquals("completed", session.status)
    }

    // ════════════════════════════════════════════════════════════════════════
    // Issue #19: advance protocol blocks session terminal state
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun `provider unavailable prevents session from reaching completed`() = runTest {
        // # Real AutomationEngine + Room + RoomAdvanceStateStore:
        // # Prove unresolved advance records physically prevent session "completed".
        val (planId, _) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))

        // # Production advance wiring: PendingProviderGateway always throws.
        val quotaReader = RoomQuotaReader(repo)
        val providerGateway = PendingProviderGateway()
        val coordinator = AdvanceCoordinator(providerGateway, quotaReader)
        val advanceStore = RoomAdvanceStateStore(db.advanceRecordDao())

        // # Engine run: success → quota met → advance fails (provider unavailable)
        buildEngine(planId, runner, gps, clock,
            advanceCoordinator = coordinator, advanceStateStore = advanceStore).run()

        // # KEY: session is "advance_pending", NOT "completed"
        val session = db.runSessionDao().getLatest()!!
        assertEquals("session must NOT be terminal",
            "advance_pending", session.status)

        // # Advance records are unresolved in Room
        val unresolved = advanceStore.listAllUnresolved()
        assertTrue("unresolved advance records must exist in Room",
            unresolved.isNotEmpty())

        // # Task IS completed (quota met, measurements done) — it's the
        // # session that's blocked, not the task
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        assertEquals("completed", task.status)
        assertEquals(1, task.completedSuccesses)
    }

    @Test
    fun `advance resolved externally then recovery sweep upgrades session`() = runTest {
        // # Real AutomationEngine + Room restart: prove that after advance records
        // # are resolved (by real provider on a future run), the recovery sweep
        // # upgrades advance_pending → completed.
        val (planId, _) = seedPlan(quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))

        val quotaReader = RoomQuotaReader(repo)
        val providerGateway = PendingProviderGateway()
        val coordinator = AdvanceCoordinator(providerGateway, quotaReader)
        val advanceStore = RoomAdvanceStateStore(db.advanceRecordDao())

        // # First run: session → advance_pending (provider unavailable)
        buildEngine(planId, runner, gps, clock,
            advanceCoordinator = coordinator, advanceStateStore = advanceStore).run()
        val session1 = db.runSessionDao().getLatest()!!
        assertEquals("advance_pending", session1.status)

        // # Simulate external resolution: real provider resolved all advance records
        // # (e.g., real AIDL ProviderGateway handled them between runs).
        val unresolved = advanceStore.listAllUnresolved()
        for (record in unresolved) {
            advanceStore.resolve(record.id, "advanced", "digest-ok", null)
        }
        assertEquals("all records resolved", 0, advanceStore.listAllUnresolved().size)

        // # Second engine run: recovery sweep sees no unresolved records
        // # → upgrades advance_pending sessions → session becomes completed
        val clock2 = VirtualClock().apply { now = 100_000L }
        val runner2 = FakeCellRebelRunner(listOf(successTemplate), clock2.nowMs)
        val gps2 = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner2, gps2, clock2,
            advanceCoordinator = coordinator, advanceStateStore = advanceStore).run()

        // # No new attempts (plan already complete)
        assertEquals("no new attempt on second run", 0, runner2.calls)

        // # First session upgraded: advance_pending → completed
        val session1After = db.runSessionDao().getById(session1.id)!!
        assertEquals("first session upgraded to completed",
            "completed", session1After.status)
    }
}
