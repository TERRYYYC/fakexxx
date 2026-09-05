package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #17 oracle — the A+ contract lane owns location injection, so the legacy Fake GPS
 * stage (gpsSetter driving the THIRD-PARTY Fake GPS app) must be skipped BY CONSTRUCTION
 * whenever the engine runs in A+ mode, REGARDLESS of the locationStage toggle.
 *
 * Device truth (Moto/Android 15, two reproductions): with the location stage ON (default) on
 * the NORMAL (non-recovery) path the engine died silently in the GPS settle stage — the Run
 * page kept "Running" with the local stepper spinning ("已等待 4m47s（上限 45s）"), zero
 * process logs, and History kept a running zombie attempt. With the location stage OFF the
 * engine was stable. The legacy stage's only remaining reachability is a non-A+ engine
 * (unit tests / pre-A+ wiring); production always injects the A+ seams — this oracle pins
 * that invariant to the attempt row, the engine log, and the gpsSetter call count.
 *
 * Assertions per attempt (full happy-path A+ run, same harness shape as
 * [EngineTrustedPathRedTest]'s passing backend):
 *  1. gpsSetter.setLocation is NEVER called in A+ mode — even with locationStageEnabled=true;
 *  2. the attempt STILL enters the A+ flow (apply dispatched → lease persisted → trusted mint);
 *  3. the attempt row is annotated gps_skipped (INV-F3-1: a skip must be recorded) — the A+
 *     lane leaves stageNotes null when the toggle is ON, so History cannot explain why no GPS
 *     stage ran ⇒ RED;
 *  4. the engine logs ONE explicit line naming the A+ skip reason ⇒ RED (no such line today);
 *  5. with locationStageEnabled=false the A+ behavior is UNCHANGED (gps_skipped, no gps call).
 *
 * # #17 引擎级 oracle：A+ 契约 lane 下 legacy Fake GPS 阶段与开关无关地跳过，且跳过必须留痕
 */
@RunWith(RobolectricTestRunner::class)
class EngineAPlusLegacyGpsSkipTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    /** Counting gps setter — the oracle's kill-switch: any call in A+ mode fails the test. */
    private class CountingGps : GpsLocationSetter {
        var calls = 0
            private set
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome {
            calls++
            return GpsOutcome.Active
        }
    }

    private class VirtualClock(initialNow: Long = 1000L) {
        var now = initialNow
        val delays = mutableListOf<Long>()
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> delays.add(ms); now += ms }
    }

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

    private suspend fun seedPlan(taskId: Long, quota: Int): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "issue17.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = quota
            )
        )
        db.planDao().insertTasks(
            listOf(
                LocationTask(
                    id = taskId, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        return planId
    }

    /**
     * §6.4-positive evidence source reading the SAME durable owner rows the engine writes —
     * the intent-hash recompute (INV-23) always binds the real attempt identity/anchor/window.
     */
    private val passingEvidence = object : APlusEvidenceSource {
        private suspend fun hash(attemptId: Long, runSessionId: Long): String {
            val attempt = db.testAttemptDao().getAttemptById(attemptId)!!
            val task = db.locationTaskDao().getTaskById(attempt.taskId)!!
            val plan = db.planDao().getPlanById(task.planId)!!
            return APlusOperationIdentity.requestDigest(
                APlusOperationIdentity.intent(
                    runSessionId, attemptId, plan.id, attempt.aplusAnchorScheduleId!!,
                    attempt.startedAt, attempt.startedAt + 90_000L
                )
            )
        }

        private suspend fun snap(attemptId: Long, runSessionId: Long, observedAtElapsed: Long) =
            ObservationSnapshot(
                leaseId = "lease-$attemptId", acceptedIntentHash = hash(attemptId, runSessionId),
                coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK", isMock = true, scheduleDecision = "ALLOWED_NOW",
                effectiveLat = 39.9, effectiveLng = 116.4,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = observedAtElapsed, observedAtEpochMs = 900L,
                continuitySinceElapsedRealtimeMs = 500L, evidenceRefs = listOf("qwy:s:1")
            )

        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
            snap(attemptId, runSessionId, 1000L)

        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
            snap(attemptId, runSessionId, 14000L)

        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) =
            APlusCompletionEvidence(
                execution = com.example.cellrebelauto.model.execution.CellRebelExecution(
                    executionId = "exec-issue17-$attemptId", attemptId = attemptId,
                    completionEvidenceWire = 1, evidencePayloadDigest = "ev-$attemptId",
                    startedAt = 1000L, classifiedAt = 1100L,
                    startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L, completedAtElapsed = 13000L,
                    baselineRunningState = "IDLE", runningMarkerText = "RUNNING", runningDurationMs = 10900L,
                    webBrowsingScore = 8.0, videoStreamingScore = 7.0, roundTimestampsElapsed = "2000;13000"
                ),
                completionEvidenceWire = 1,
                applyReceiptIntentHash = hash(attemptId, runSessionId),
                applyReceiptLease = "lease-$attemptId"
            )
    }

    /** The service-used composition point (Sol round-11 P1-1): one place, production-shaped. */
    private fun passingBackend(executor: RecordingExternalApplyExecutor): APlusBackend =
        object : APlusBackend {
            override val executor: ExternalApplyExecutor = executor
            override val recoveryLog: DurableRecoveryLog = FakeDurableRecoveryLog()
            override val observeIntent: ObserveIntentAcquirer = ObserveIntentAcquirer { false }
            override val receiptRevision: ReceiptRevisionAcquirer = ReceiptRevisionAcquirer { _, _ -> false }
            override val trustedQuota: TrustedQuotaAcquirer = TrustedQuotaAcquirer { false }
            override val evidenceSource: APlusEvidenceSource = passingEvidence
        }

    private fun buildEngine(
        planId: Long,
        gps: CountingGps,
        clock: VirtualClock,
        toggles: suspend () -> StageToggles,
        backend: APlusBackend
    ): AutomationEngine {
        val (coordinator, evidence) = APlusComposition.engineAplusParams(backend)
        return AutomationEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onRunningObserved: suspend (Long) -> Unit,
                    onStageHeartbeat: suspend (phase: String, elapsedMs: Long, budgetMs: Long) -> Unit
                ): AttemptOutcome {
                    onRunningObserved(clock.nowMs())
                    return AttemptOutcome.Success(
                        webScore = 8.0, videoScore = 7.0,
                        runningObservedAt = clock.nowMs(), startedAt = startedAt, endedAt = clock.nowMs()
                    )
                }
            },
            gpsSetter = gps,
            bufferGate = BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L,
            // # the device's settle budget — A+ mode must never wait it
            gpsSettleMs = 45_000L,
            stageToggles = toggles,
            nowMs = clock.nowMs,
            delayMs = clock.delayMs,
            recoveryCoordinator = coordinator,
            completionEvidenceSource = evidence
        )
    }

    @Test
    fun `A+ lane with location stage ON never calls the legacy gpsSetter, still runs the A+ flow, and records the skip`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val executor = RecordingExternalApplyExecutor()
        val gps = CountingGps()
        val clock = VirtualClock()
        val engine = buildEngine(
            planId, gps, clock,
            toggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
            backend = passingBackend(executor)
        )
        engine.run()

        // (1) The kill-switch: the legacy Fake GPS setter is NEVER touched in A+ mode.
        assertEquals("A+ contract lane must never call gpsSetter.setLocation (issue #17)", 0, gps.calls)
        // (2) The attempt really entered the A+ flow (apply dispatched → lease persisted → trusted mint).
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals(
            "the A+ flow ran (apply dispatched exactly once)",
            1, executor.invocationCount(APlusOperationIdentity.applyIdempotencyKey(attempt.id))
        )
        assertEquals(
            "the provider lease was persisted for the attempt",
            "lease-${attempt.id}", db.testAttemptDao().getAplusLeaseId(attempt.id)
        )
        assertNotNull("a trusted completion was minted through the A+ lane", db.trustedQuotaDao().getByAttempt(attempt.id))
        // (3) INV-F3-1 for the A+ lane: the skip MUST be recorded on the attempt row — RED today (null).
        assertEquals(
            "A+ lane skips the legacy Fake GPS stage regardless of the toggle — the attempt must carry gps_skipped (issue #17)",
            "gps_skipped",
            attempt.stageNotes
        )
        // (4) ONE explicit engine log line naming the A+ skip reason — RED today (no such line).
        assertTrue(
            "the engine must log the A+ skip reason; logs were: ${engine.logs.value}",
            engine.logs.value.any { it.contains("A+ contract lane") && it.contains("legacy Fake GPS stage skipped") }
        )
        // The settle wait (the device's silent-death stage) never ran: with buffer 0 the only
        // possible delay in this harness would be the legacy settle — none may appear.
        assertTrue("no legacy GPS settle wait in A+ mode (delays: ${clock.delays})", clock.delays.isEmpty())
    }

    @Test
    fun `A+ lane with location stage OFF keeps gps_skipped and the no-gps behavior unchanged`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val gps = CountingGps()
        val clock = VirtualClock()
        val engine = buildEngine(
            planId, gps, clock,
            toggles = { StageToggles(locationStageEnabled = false, testStageEnabled = true) },
            backend = passingBackend(RecordingExternalApplyExecutor())
        )
        engine.run()

        assertEquals("no legacy gps call with the toggle OFF (unchanged)", 0, gps.calls)
        assertEquals(
            "gps_skipped stays the marking when the toggle is OFF (unchanged semantics)",
            "gps_skipped",
            db.testAttemptDao().getAttemptsForTask(taskId).single().stageNotes
        )
        assertTrue(
            "the A+ skip line is logged regardless of the toggle; logs were: ${engine.logs.value}",
            engine.logs.value.any { it.contains("A+ contract lane") && it.contains("legacy Fake GPS stage skipped") }
        )
    }
}
