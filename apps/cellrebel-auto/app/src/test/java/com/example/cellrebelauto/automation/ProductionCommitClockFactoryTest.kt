package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.repository.PlanRepository
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R43 (Sol R42 P1-2): the production-composition oracle for the monotonic commit clock.
 *
 * The engine is built through [AutomationEngineFactory.productionEngine] — the SAME construction
 * path [AutomationService] uses — with an explicitly injected WALL clock (`nowMs = { WALL }`) and
 * the commit clock left at the production default (`productionCommitClockMs`, monotonic
 * elapsedRealtime). A full §6.4-positive DECIDING recovery mints through it; the minted
 * `committedAt` must bind the MONOTONIC domain, never the wall value.
 *
 * Killing mutations (all verified to FAIL this test):
 *  1. reverting the ledger-commit call to `nowMs()` — committedAt == WALL ⇒ the assertNotEquals fails;
 *  2. changing the FACTORY DEFAULT parameter to a wall clock (the exact mutation that previously
 *     survived: the clock-source property stays monotonic but the default wiring is corrupted) —
 *     the engine now binds wall, committedAt == WALL ⇒ fails. The default is observed because the
 *     test OMITS commitClockMs.
 *
 * # 生产组合根 monotonic commit-clock oracle（Sol R42 P1-2）：经 factory 同路径铸币，committedAt 必须落在 monotonic 域
 */
@RunWith(RobolectricTestRunner::class)
class ProductionCommitClockFactoryTest {

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

    @Test
    fun `production engine factory wires the monotonic commit clock for the ledger mint`() = runTest {
        val WALL_VALUE = 15000L // deliberately distinctive wall-domain value (≠ the shadow monotonic clock)
        // Seed a §6.4-positive DECIDING crash fixture (mirrors CrashMatrixTest.M_CR_06).
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "clk.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = 77L, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = "DECIDING", aplusLeaseId = "lease-77"
            )
        )
        val intentDigest = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
            .requestDigest(39.9, 116.4, 77L, sessionId)
        val seededDigest = "ev-clock-${java.util.UUID.randomUUID()}"
        // Durable carriers (the GREEN recovery re-decide reads ONLY these).
        db.attemptExecutionDao().insert(
            com.example.cellrebelauto.model.execution.CellRebelExecution(
                executionId = "exec-current-77", attemptId = 77L,
                completionEvidenceWire = 1, evidencePayloadDigest = seededDigest,
                startedAt = 1000L, classifiedAt = 1100L,
                startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L, completedAtElapsed = 13000L,
                baselineRunningState = "IDLE", runningMarkerText = "RUNNING", runningDurationMs = 10900L,
                webBrowsingScore = 8.0, videoStreamingScore = 7.0, roundTimestampsElapsed = "2000;13000"
            )
        )
        db.testAttemptDao().markCurrentExecutionId(77L, "exec-current-77")
        fun snapshot(phase: String) = com.example.cellrebelauto.environment.ObservationSnapshot(
            leaseId = "lease-77", acceptedIntentHash = intentDigest,
            coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
            deliveryMode = "SYSTEM_MOCK", isMock = true, scheduleDecision = "ALLOWED_NOW",
            effectiveLat = 39.9, effectiveLng = 116.4,
            environmentRevision = 7L, environmentFingerprint = "fp-1",
            observedAtElapsedRealtimeMs = if (phase == "PRE") 1000L else 14000L,
            observedAtEpochMs = 900L, continuitySinceElapsedRealtimeMs = 500L,
            evidenceRefs = listOf("qwy:store:abc")
        )
        repo.persistObservation(77L, "PRE", snapshot("PRE"))
        repo.persistObservation(77L, "POST", snapshot("POST"))
        repo.persistCompletionReceipt(77L, 1, intentDigest, "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(77L), intentDigest, "RELEASED", 1000L)

        val backend = com.example.cellrebelauto.automation.APlusComposition.run {
            object : com.example.cellrebelauto.automation.aplus.APlusBackend {
                override val executor = executor
                override val recoveryLog = log
                override val observeIntent = com.example.cellrebelauto.recovery.ObserveIntentAcquirer { true }
                override val receiptRevision = com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer { _, _ -> true }
                override val trustedQuota = com.example.cellrebelauto.recovery.TrustedQuotaAcquirer { true }
                override val evidenceSource = object : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
                    override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) = null as com.example.cellrebelauto.environment.ObservationSnapshot?
                    override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) = null as com.example.cellrebelauto.environment.ObservationSnapshot?
                    override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) = null as com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence?
                }
            }
        }
        val (coordinator, evidence) = com.example.cellrebelauto.automation.APlusComposition.engineAplusParams(backend)

        // THE production construction path (exactly what AutomationService calls; the commit clock
        // is left at the factory default — the monotonic seam under test).
        val engine = AutomationEngineFactory.productionEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = object : com.example.cellrebelauto.automation.CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onRunningObserved: suspend (Long) -> Unit
                ): com.example.cellrebelauto.automation.AttemptOutcome =
                    com.example.cellrebelauto.automation.AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 0L)
            },
            gpsSetter = object : com.example.cellrebelauto.automation.GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) =
                    com.example.cellrebelauto.automation.GpsOutcome.Active
            },
            globalBufferSeconds = 0,
            testTimeoutMs = 90_000L,
            gpsSettleMs = 0L,
            stageToggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
            auditDao = db.auditEventDao(),
            aplusCoordinator = coordinator,
            aplusEvidence = evidence,
            bridge = null,
            nowMs = { WALL_VALUE },          // wall domain, distinctive
            delayMs = { }                     // no-op delays
            // R43 (Sol GREEN-review P1-4): commitClockMs is DELIBERATELY OMITTED — the test observes
            // the FACTORY DEFAULT that AutomationService actually relies on. A mutation changing the
            // default parameter (e.g. to a wall clock) is what this oracle must kill.
        )
        engine.run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("the factory-built engine must mint the trusted entry (absence = failure)", entry)
        // THE oracle: committedAt binds the MONOTONIC production clock, never the injected wall value.
        assertNotEquals(
            "committedAt must NOT bind the wall/nowMs domain ($WALL_VALUE) — the production commit clock is monotonic elapsedRealtime (Sol R42 P1-2)",
            WALL_VALUE,
            entry!!.committedAt
        )
        // And it must equal a reading of the same production clock source.
        val monotonicNow = AutomationEngineFactory.productionCommitClockMs()
        assertEquals(
            "committedAt must bind the productionCommitClockMs source value at mint time",
            monotonicNow,
            entry.committedAt
        )
        // Sanity: the two domains are actually distinct in this test (otherwise the oracle is void).
        assertNotEquals("test setup: wall and monotonic values must differ for this oracle to bite", WALL_VALUE, monotonicNow)
    }
}
