package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R44 (DSF review P1-2): oracles for the ENGINE's discover / preflight / completeAndAdvance
 * CONSUMERS — the three journey methods that had implementations but zero production callers.
 *
 * Each consumer is driven through a REAL AutomationEngine run with a fake journey executor at the
 * coordinator seam; killing mutations (consumer removed) each fail their test:
 *  - discover consumer removed → the incompatible-provider run proceeds instead of pausing
 *  - preflight consumer removed → a DENIED schedule is applied to anyway
 *  - completeAndAdvance consumer removed → a trusted mint completes without the provider advance
 *
 * # 引擎旅程消费者 oracle：discover/preflight/completeAndAdvance 三个 mutation 各自反红
 */
@RunWith(RobolectricTestRunner::class)
class EngineJourneyConsumerOracleTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: com.example.cellrebelauto.repository.PlanRepository

    // Programmable journey surface + call recording.
    private var discoverResult: CapabilitySnapshotV1? = CapabilitySnapshotV1(
        serviceVersion = "fake-1.0",
        supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
        supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
        continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
        environmentRevision = 7L,
        profileRefs = listOf("auto-profile"), scheduleRefs = listOf("auto-schedule"),
        currentScheduleId = "plan-1", currentItemId = "task-1", scheduleVersion = 1L, exhausted = false
    )
    private var preflightDecision: Int = ScheduleDecisionV1.ALLOWED_NOW.wire
    private var advanceResult: AdvanceReceiptV1? = AdvanceReceiptV1(
        outcomeWire = 1, advancedFromItemId = "task-1", advancedToItemId = null,
        scheduleVersionAfter = 2L, effectiveIntentHash = "h",
        effectiveEnvironmentRevision = 7L, receiptDigest = "rd"
    )
    private val discoverCalls = mutableListOf<Unit>()
    private val preflightCalls = mutableListOf<EnvironmentIntentV1>()
    private val advanceCalls = mutableListOf<CompleteAndAdvanceRequestV1>()

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-$attemptId")
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("RELEASED", false)
        override fun discover(): CapabilitySnapshotV1? {
            discoverCalls += Unit
            return discoverResult
        }
        override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? {
            preflightCalls += intent
            return PreflightReportV1(
                acceptedIntentHash = requestDigest,
                scheduleDecisionWire = preflightDecision,
                waitUntilEpochMs = null,
                achievableVerificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                environmentRevision = 7L, blockingReasonWires = emptyList(),
                scheduleItemId = "task-1", scheduleVersion = 1L, exhausted = false
            )
        }
        override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? = null
        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
            advanceCalls += request
            return advanceResult
        }
    }

    // The §6.4-positive evidence source the trusted path needs. The intent hash is RECOMPUTED
    // from the same durable owner identity the engine uses (attempt → task → plan from the DB),
    // so the INV-23 three-way hash actually agrees.
    private val evidenceSource = object : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
        suspend fun hash(attemptId: Long, runSessionId: Long): String {
            val attempt = db.testAttemptDao().getAttemptById(attemptId)!!
            val task = db.locationTaskDao().getTaskById(attempt.taskId)!!
            val plan = db.planDao().getPlanById(task.planId)!!
            return com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.requestDigest(
                com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.intent(
                    runSessionId, attemptId, plan.id, task.id, attempt.startedAt, attempt.startedAt + 90_000L
                )
            )
        }
        suspend fun snap(attemptId: Long, runSessionId: Long, observedAt: Long) =
            com.example.cellrebelauto.environment.ObservationSnapshot(
                leaseId = "lease-$attemptId", acceptedIntentHash = hash(attemptId, runSessionId),
                coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK", isMock = true, scheduleDecision = "ALLOWED_NOW",
                effectiveLat = 39.9, effectiveLng = 116.4,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = observedAt, observedAtEpochMs = 900L,
                continuitySinceElapsedRealtimeMs = 500L, evidenceRefs = listOf("qwy:s:1")
            )
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
            snap(attemptId, runSessionId, 1000L)
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
            snap(attemptId, runSessionId, 14000L)
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) =
            com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence(
                execution = com.example.cellrebelauto.model.execution.CellRebelExecution(
                    executionId = "exec-$attemptId", attemptId = attemptId, completionEvidenceWire = 1,
                    evidencePayloadDigest = "ev-$attemptId", startedAt = 0L, classifiedAt = 0L,
                    startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L, completedAtElapsed = 13000L,
                    baselineRunningState = "IDLE", runningMarkerText = "RUNNING", runningDurationMs = 10900L,
                    webBrowsingScore = 8.0, videoStreamingScore = 7.0, roundTimestampsElapsed = "2000;13000"
                ),
                completionEvidenceWire = 1,
                applyReceiptIntentHash = hash(attemptId, runSessionId),
                applyReceiptLease = "lease-$attemptId"
            )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = com.example.cellrebelauto.repository.PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class VClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { now += it }
    }

    private suspend fun seedPlan(): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "j.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        return planId to task.id
    }

    private fun buildEngine(planId: Long, clock: VClock, driver: com.example.cellrebelauto.automation.aplus.APlusAttemptDriver?): AutomationEngine {
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            journeyExecutor, FakeDurableRecoveryLog()
        )
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(startedAt: Long, testTimeoutMs: Long, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome {
                    onRunningObserved(4242L)
                    return AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 4300L)
                }
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) = GpsOutcome.Active
            },
            bufferGate = com.example.cellrebelauto.automation.plan.BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs, delayMs = clock.delayMs,
            attemptDriver = driver,
            recoveryCoordinator = coordinator,
            completionEvidenceSource = evidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
    }

    @Test
    fun `the engine CONSUMES discover at run start - an incompatible provider pauses before any attempt`() = runTest {
        val (planId, taskId) = seedPlan()
        discoverResult = null // the incompatible/unavailable provider
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertTrue("the discover gate was consulted", discoverCalls.isNotEmpty())
        assertEquals(
            "an unavailable provider pauses BEFORE any attempt is created (killing mutation: consumer removed ⇒ attempts exist)",
            0, db.testAttemptDao().getAttemptsForTask(taskId).size
        )
        assertEquals("no session activity beyond the pause", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `the engine CONSUMES preflight - a DENIED schedule fail-closes before apply`() = runTest {
        val (planId, taskId) = seedPlan()
        preflightDecision = ScheduleDecisionV1.DENIED.wire
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertTrue("preflight was consulted for the intent", preflightCalls.isNotEmpty())
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertTrue(
            "a DENIED preflight fail-closes the attempt (UNTRUSTED, no lease) before any external apply (killing mutation: consumer removed ⇒ lease applied)",
            attempts.all { db.testAttemptDao().getAplusLeaseId(it.id) == null }
        )
    }

    @Test
    fun `the engine CONSUMES completeAndAdvance after a trusted mint - with the CAS preconditions`() = runTest {
        val (planId, taskId) = seedPlan()
        val driver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao())
        val clock = VClock()
        buildEngine(planId, clock, driver).run()

        // The trusted mint happened (the §6.4-positive source + trusted-only SQL).
        assertEquals("one trusted mint", 1, db.trustedQuotaDao().countAll())
        // Killing mutation: completeAndAdvance consumer removed ⇒ advanceCalls empty ⇒ FAIL.
        assertEquals("the provider's schedule advance was invoked exactly once", 1, advanceCalls.size)
        val req = advanceCalls[0]
        assertEquals("CAS: expectedCurrentItemId binds the completed task", "task-$taskId", req.expectedCurrentItemId)
        assertEquals("the proof binds the ledger projection", 1, req.completionProof.trustedSuccessCount)
        assertEquals("the proof binds the quota", 1, req.completionProof.quotaRequired)
        assertTrue("the proof's ledger ref is attempt-addressed", req.completionProof.ledgerRef.startsWith("ledger-"))
        // A failed advance fail-closes AFTER the local mint (the ledger is Auto's own authority —
        // §6.7.2: the provider RECORDS completion, never recomputes it): the mint may commit, but
        // the run PAUSES instead of continuing to the next task.
        advanceResult = null
        val (planId2, taskId2) = seedPlan()
        val clock2 = VClock()
        buildEngine(planId2, clock2, null).run()
        val advanceAttempts = advanceCalls.size
        assertEquals(
            "both runs drove their completeAndAdvance (the second one failed and paused)",
            2, advanceAttempts
        )
        // The second plan's own task never got a SECOND attempt after the failed advance — the
        // pause happened; killing the consumer (advanceCalls empty) fails the FIRST assertion above.
        val secondPlanAttempts = db.testAttemptDao().getAttemptsForTask(taskId2)
        assertTrue(
            "the paused plan created at most one attempt (no silent retry loop past a failed advance)",
            secondPlanAttempts.size <= 1
        )
    }
}
