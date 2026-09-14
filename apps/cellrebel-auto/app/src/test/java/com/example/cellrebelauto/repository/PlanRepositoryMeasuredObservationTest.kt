package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 运行台地图卡实测层（evidence-only display）的查询语义 oracle：
 * 每 task 取**最近一次 succeeded attempt**（succeeded | ok_gps_only，与
 * RunProgressProjection 的成功语义一致）内 **id 最大且坐标非空**的那条观察
 * （同 attempt 内后写入的行 id 更大，正常流即 POST；坐标 NULL 的"未捕获"行绝不参与）。
 *
 * 严格性边界：最近一次 succeeded attempt 若没有任何带坐标观察，该 task 不出结果
 * ——绝不回退到更早的 attempt（宁可无标记，不画旧证据）。
 *
 * 跨 run 新鲜度（#185 F4）：只认该 plan 最近一次 run session 启动之后的观察——
 * 重复运行时上一 run 的旧证据不立即上屏；无 plan 关联的旧会话不设门槛。
 *
 * # 实测层查询 oracle：最近 succeeded attempt / 最新带坐标观察 / 严格不回退 / 跨 run 新鲜度
 */
@RunWith(RobolectricTestRunner::class)
class PlanRepositoryMeasuredObservationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository
    private var sessionId: Long = 0

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        repo = PlanRepository(db, com.example.cellrebelauto.cutover.CutoverAccessGate.open())
        sessionId = db.runSessionDao().insert(RunSession(startedAt = 1L))
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertPlanWithTasks(vararg taskIds: Long): Long {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "plan.csv", importedAt = 1L,
                globalBufferSeconds = 0, totalRows = taskIds.size,
                totalRequiredSuccesses = taskIds.size,
            ),
            taskIds.map { id ->
                LocationTask(
                    planId = 0, csvRow = id.toInt(),
                    longitude = 30.0 + id, latitude = 50.0 + id,
                    priority = 1, requiredSuccesses = 1,
                )
            },
        )
        return planId
    }

    private suspend fun insertAttempt(
        taskId: Long,
        status: String,
        ordinal: Int = 1,
        session: Long = sessionId,
    ): Long =
        db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = session,
                attemptOrdinal = ordinal, successOrdinal = if (status == "succeeded" || status == "ok_gps_only") ordinal else null,
                startedAt = ordinal * 10L, runningObservedAt = null, endedAt = ordinal * 100L,
                status = status, failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 0.0, longitude = 0.0,
            )
        )

    private suspend fun insertObservation(
        attemptId: Long,
        phase: String,
        lat: Double?,
        lng: Double?,
        observedAt: Long = 1L,
        servingCi: Long? = null,
    ): Long =
        db.durableObservationDao().insert(
            DurableObservationRecord(
                attemptId = attemptId, phase = phase,
                leaseId = "lease-$attemptId-$phase", acceptedIntentHash = "hash",
                coverage = "FULL", verificationLevel = "VERIFIED", deliveryMode = "MOCK",
                isMock = true, scheduleDecision = "ALLOWED_NOW",
                effectiveLat = lat, effectiveLng = lng,
                environmentRevision = 1L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = 1L, observedAtEpochMs = observedAt,
                continuitySinceElapsedRealtimeMs = null, continuitySinceEpochMs = null,
                evidenceRefsJson = "[]", evidenceRefs = "",
                servingCi = servingCi,
            )
        )

    private suspend fun measured(planId: Long) = repo.observeMeasuredObservations(planId).first()

    // ---- 主语义：最近 succeeded attempt 的最新带坐标观察 -------------------------

    @Test
    fun latestSucceededAttempt_wins_overEarlierOnes() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a1 = insertAttempt(1L, "succeeded", ordinal = 1)
        insertObservation(a1, "POST", lat = 50.0, lng = 30.0)
        val a2 = insertAttempt(1L, "succeeded", ordinal = 2)
        insertObservation(a2, "POST", lat = 50.0001, lng = 30.0001)

        val row = measured(planId).getValue(1L)
        assertEquals(50.0001, row.measuredLat, 1e-12)
        assertEquals(30.0001, row.measuredLng, 1e-12)
        assertTrue(row.verificationLevel.isNotEmpty())
    }

    @Test
    fun withinOneAttempt_latestObservationWins_postBeatsPre() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a1 = insertAttempt(1L, "succeeded")
        insertObservation(a1, "PRE", lat = 50.0, lng = 30.0)
        insertObservation(a1, "POST", lat = 50.00002, lng = 30.00002)

        val row = measured(planId).getValue(1L)
        assertEquals(50.00002, row.measuredLat, 1e-12)
        assertEquals(30.00002, row.measuredLng, 1e-12)
    }

    // ---- 资格：只有成功的 attempt 才有实测 ---------------------------------------

    @Test
    fun failedAndInterruptedAttempts_neverQualify() = runTest {
        val planId = insertPlanWithTasks(1L)
        val aFailed = insertAttempt(1L, "failed")
        val aInterrupted = insertAttempt(1L, "interrupted", ordinal = 2)
        insertObservation(aFailed, "PRE", lat = 50.0, lng = 30.0)
        insertObservation(aInterrupted, "POST", lat = 51.0, lng = 31.0)

        assertTrue(measured(planId).isEmpty())
    }

    @Test
    fun okGpsOnly_countsAsSucceeded() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a = insertAttempt(1L, "ok_gps_only")
        insertObservation(a, "POST", lat = 50.0, lng = 30.0)

        val row = measured(planId).getValue(1L)
        assertEquals(50.0, row.measuredLat, 1e-12)
    }

    // ---- 严格性：最新 succeeded attempt 无带坐标观察 → 无实测（不回退） ----------

    @Test
    fun latestSucceededWithoutAnyCoordinateObservation_yieldsNothing() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a1 = insertAttempt(1L, "succeeded")
        insertObservation(a1, "POST", lat = 50.0, lng = 30.0)
        // 更晚的成功 attempt：观察存在但坐标未捕获（NULL）——诚实缺席，绝不回退画旧点。
        val a2 = insertAttempt(1L, "succeeded", ordinal = 2)
        insertObservation(a2, "PRE", lat = null, lng = null)

        assertFalse(measured(planId).containsKey(1L))
    }

    @Test
    fun withinOneAttempt_nullCoordinateRow_skipped_notFatal() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a1 = insertAttempt(1L, "succeeded")
        // PRE 未捕获坐标、POST 带坐标：POST（最新带坐标者）仍可用。
        insertObservation(a1, "PRE", lat = null, lng = null)
        insertObservation(a1, "POST", lat = 50.5, lng = 30.5)

        val row = measured(planId).getValue(1L)
        assertEquals(50.5, row.measuredLat, 1e-12)
    }

    // ---- 跨 run 新鲜度（#185 F4）：只认该 plan 最近一次 session 之后的观察 -------

    @Test
    fun observation_withinLatestSession_isShown() = runTest {
        val planId = insertPlanWithTasks(1L)
        // 旧 run 的观察先落库（session 起始 1000，观察 1500——当时新鲜）。
        val s1 = db.runSessionDao().insert(RunSession(startedAt = 1_000L, planId = planId))
        val a1 = insertAttempt(1L, "succeeded", session = s1)
        insertObservation(a1, "POST", 50.0, 30.0, observedAt = 1_500L)
        assertEquals(50.0, measured(planId).getValue(1L).measuredLat, 1e-12)

        // 重跑同一 plan：新 session（起始 2000）内的新鲜观察胜出上屏。
        val s2 = db.runSessionDao().insert(RunSession(startedAt = 2_000L, planId = planId))
        val a2 = insertAttempt(1L, "succeeded", ordinal = 2, session = s2)
        insertObservation(a2, "POST", 50.0001, 30.0001, observedAt = 2_500L)

        val row = measured(planId).getValue(1L)
        assertEquals(50.0001, row.measuredLat, 1e-12)
    }

    @Test
    fun observation_beforeLatestSessionStart_isStaleAndHidden() = runTest {
        val planId = insertPlanWithTasks(1L)
        val s1 = db.runSessionDao().insert(RunSession(startedAt = 1_000L, planId = planId))
        val a1 = insertAttempt(1L, "succeeded", session = s1)
        insertObservation(a1, "POST", 50.0, 30.0, observedAt = 1_500L)
        assertEquals(50.0, measured(planId).getValue(1L).measuredLat, 1e-12)

        // 新 session 启动后（如重复运行同计划），上一 run 的观察立即变陈旧——
        // 不再上屏（宁可无标记，不画旧证据，语义 = "最近一次 run 的实测"）。
        db.runSessionDao().insert(RunSession(startedAt = 2_000L, planId = planId))
        assertTrue(measured(planId).isEmpty())
    }

    @Test
    fun legacySessionWithoutPlanLink_doesNotGate() = runTest {
        // setUp 的 session 无 planId（v3 之前旧数据）：不设新鲜度门槛，历史观察仍上屏。
        val planId = insertPlanWithTasks(1L)
        val a = insertAttempt(1L, "succeeded") // runSessionId = setUp 的无关联 session
        insertObservation(a, "POST", 50.0, 30.0, observedAt = 1L)

        assertEquals(50.0, measured(planId).getValue(1L).measuredLat, 1e-12)
    }

    // ---- 计划隔离与多任务 --------------------------------------------------------

    @Test
    fun plansAreIsolated_andTasksAreIndependent() = runTest {
        val planId = insertPlanWithTasks(1L, 2L)
        val a1 = insertAttempt(1L, "succeeded")
        insertObservation(a1, "POST", lat = 50.0, lng = 30.0)
        // task 2 只有 failed attempt；task 1 有实测。

        val m = measured(planId)
        assertEquals(setOf(1L), m.keys)

        // 另一个计划（重复 task 行）绝不串数据。
        val plan2 = insertPlanWithTasks(1L)
        assertTrue(measured(plan2).isEmpty())
    }

    @Test
    fun projection_carriesVerificationLevel_andEpoch() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a = insertAttempt(1L, "succeeded")
        insertObservation(a, "POST", lat = 50.0, lng = 30.0)
        val row = measured(planId).getValue(1L)
        assertEquals("VERIFIED", row.verificationLevel)
        assertEquals(1L, row.observedAtEpochMs)
        assertEquals(50.0, row.measuredLat, 1e-12)
        assertEquals(30.0, row.measuredLng, 1e-12)
    }

    // ---- #190：投影带出同一条观察采样的 servingCi（CI 验证层实测腿） -------------

    @Test
    fun projection_carriesServingCi_fromSameObservation() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a = insertAttempt(1L, "succeeded")
        // POST 带坐标+小区：正常 hook 路径（observeLive 同时刻采样坐标与 serving cell）。
        insertObservation(a, "POST", lat = 50.0, lng = 30.0, servingCi = 28918569L)

        val row = measured(planId).getValue(1L)
        assertEquals(28918569L, row.servingCi)
    }

    @Test
    fun projection_nullServingCi_isHonestAbsence_notZero() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a = insertAttempt(1L, "succeeded")
        // 带坐标但未捕获小区（v10 前历史行/读数失败）：null 原样透传，绝不编 0。
        insertObservation(a, "POST", lat = 50.0, lng = 30.0, servingCi = null)

        assertNull(measured(planId).getValue(1L).servingCi)
    }

    @Test
    fun projection_servingCi_followsLatestCoordinateObservation() = runTest {
        val planId = insertPlanWithTasks(1L)
        val a1 = insertAttempt(1L, "succeeded", ordinal = 1)
        insertObservation(a1, "POST", lat = 50.0, lng = 30.0, servingCi = 111L)
        // 更晚的成功 attempt：新观察携带新 ci —— 同一条证据行，位置与小区同源。
        val a2 = insertAttempt(1L, "succeeded", ordinal = 2)
        insertObservation(a2, "POST", lat = 50.0001, lng = 30.0001, servingCi = 222L)

        val row = measured(planId).getValue(1L)
        assertEquals(50.0001, row.measuredLat, 1e-12)
        assertEquals(222L, row.servingCi)
    }
}
