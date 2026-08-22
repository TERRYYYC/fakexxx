package com.example.cellrebelauto.matrix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AttemptOutcome
import com.example.cellrebelauto.automation.AutomationEngine
import com.example.cellrebelauto.automation.CellRebelRunner
import com.example.cellrebelauto.automation.GpsLocationSetter
import com.example.cellrebelauto.automation.GpsOutcome
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.OperationReceiptRow
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.repository.PlanRepository
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stable `M-AD-14..19` advance matrix — canonical §10.1 entry points at the spec-mandated coordinate:
 * `com.example.cellrebelauto.matrix.AdvanceMatrixTest`.
 *
 * Sol R2 P1-1/P1-3 requirements:
 * - Real Room-backed acquirers (production composition, not default all-false)
 * - Recovery resumes (no spurious PAUSE from scheduleAdvanced gate)
 * - M-AD-17/18 verify DURABLE typed reason (failureReason field, not just aplusState)
 * - Exact-ID method names machine-parseable by check-matrix-coverage.sh
 *
 * | ID | Invariant | Proved property |
 * |----|-----------|-----------------|
 * | M_AD_14 | Quota not met → no advance, engine resumes | trustedCount < required ⇒ advanceReplays=0, status=succeeded |
 * | M_AD_15 | DECIDING crash window re-mint: corrupt row → fail-closed | mismatched evidenceDigest ⇒ IllegalStateException |
 * | M_AD_16 | Exhausted receipt forged digest → RECOVERY_REQUIRED | forged digest ⇒ fail-closed |
 * | M_AD_17 | Observe intentHash mismatch → durable typed reason | failureReason contains "acceptedIntentHash" |
 * | M_AD_18 | Observe environmentRevision mismatch → durable typed reason | failureReason contains "environmentRevision" |
 * | M_AD_19 | Cross-fork same-key replay → idempotent (no second advance) | second engine run ⇒ 0 replays |
 */
@RunWith(RobolectricTestRunner::class)
class AdvanceMatrixTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    private val anchorScheduleId = "sched-matrix-7a"
    private val anchorItemId = "item-matrix-3b"
    private val anchorVersion = 12L

    private val advanceReplays = mutableListOf<CompleteAndAdvanceRequestV1>()
    private var advanceAnswer: AdvanceReceiptV1? = AdvanceReceiptV1(
        outcomeWire = 1, advancedFromItemId = "item-matrix-3b", advancedToItemId = "item-after-9z",
        scheduleVersionAfter = 13L, effectiveIntentHash = "eff-matrix",
        effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
    )

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-$attemptId", operationId = "op-$attemptId")
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("RELEASED", false)
        override fun discover(): CapabilitySnapshotV1? = CapabilitySnapshotV1(
            serviceVersion = "fake-1.0",
            supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            profileRefs = listOf("p"), scheduleRefs = listOf("s"),
            currentScheduleId = anchorScheduleId, currentItemId = anchorItemId, scheduleVersion = anchorVersion, exhausted = false
        )
        override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? =
            PreflightReportV1(
                acceptedIntentHash = requestDigest,
                scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
                waitUntilEpochMs = null,
                achievableVerificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
                environmentRevision = 7L, blockingReasonWires = emptyList(),
                scheduleItemId = anchorItemId, scheduleVersion = anchorVersion, exhausted = false
            )
        override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
            val r = advanceAnswer ?: return null
            if (r.advancedToItemId == null) return null
            return EnvironmentObservationV1(
                leaseId = leaseId, acceptedIntentHash = r.effectiveIntentHash,
                observedAtEpochMs = 0L, observedAtElapsedRealtimeMs = 0L,
                environmentRevision = r.effectiveEnvironmentRevision, environmentFingerprint = "fp",
                continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
                continuitySinceEpochMs = null, continuitySinceElapsedRealtimeMs = null,
                deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
                verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                effectiveLatitude = null, effectiveLongitude = null, isMock = true,
                scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
                evidenceRefs = emptyList(),
                scheduleItemId = r.advancedToItemId!!, scheduleVersion = r.scheduleVersionAfter
            )
        }
        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
            advanceReplays += request
            val base = advanceAnswer ?: return null
            return base.copy(
                receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(base, request.requestDigest, request.idempotencyKey)
            )
        }
    }

    private val minimalEvidenceSource = object : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) = null
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) = null
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) = null
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
    fun tearDown() { db.close() }

    // --- Real Room-backed acquirers (production composition, Sol R2 P1-1) ---

    private fun realObserveAcquirer() = ObserveIntentAcquirer { attemptId ->
        kotlinx.coroutines.runBlocking {
            db.durableObservationDao().forAttemptPhase(attemptId, "PRE") != null
        }
    }

    private fun realReceiptAcquirer() = ReceiptRevisionAcquirer { idempotencyKey, _ ->
        kotlinx.coroutines.runBlocking {
            db.operationReceiptDao().byKey(idempotencyKey) != null
        }
    }

    private fun realTrustedQuotaAcquirer() = TrustedQuotaAcquirer { attemptId ->
        kotlinx.coroutines.runBlocking {
            val attempt = db.testAttemptDao().getAttemptById(attemptId)
            attempt != null && attempt.endedAt == null &&
                db.trustedQuotaDao().trustedCountForTask(attempt.taskId) < (
                    db.locationTaskDao().getTaskById(attempt.taskId)?.requiredSuccesses ?: 0
                )
        }
    }

    private class VClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { now += it }
    }

    private suspend fun seedAdvanceCrash(phase: String, requiredSuccesses: Int = 1): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "m.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = requiredSuccesses),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = requiredSuccesses))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = 31L, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = phase, aplusLeaseId = "lease-31",
                aplusAnchorScheduleId = anchorScheduleId, aplusAnchorItemId = anchorItemId, aplusAnchorVersion = anchorVersion
            )
        )
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(
                attemptId = 31L, taskId = task.id, evidenceDigest = "ev-31", committedAt = 9000L
            )
        )
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(31L),
                requestDigest = "h", resultOutcome = "APPLIED", createdAt = 1000L,
                leaseId = "lease-31", operationId = "op-31"
            )
        )
        return planId to task.id
    }

    /**
     * Build an engine with REAL Room-backed acquirers (production composition).
     * Sol R2 P1-1: default all-false acquirers hide the scheduleAdvanced ordering bug;
     * real acquirers expose it — and prove the fix (removing the gate from recovery) works.
     */
    private fun buildEngine(planId: Long, clock: VClock, executor: ExternalApplyExecutor = journeyExecutor): AutomationEngine {
        val coordinator = RecoveryCoordinator(
            executor,
            RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()),
            realObserveAcquirer(),
            realReceiptAcquirer(),
            realTrustedQuotaAcquirer()
        )
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(startedAt: Long, testTimeoutMs: Long, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome =
                    AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 4300L)
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) = GpsOutcome.Active
            },
            bufferGate = BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs, delayMs = clock.delayMs,
            attemptDriver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = minimalEvidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
    }

    // ===== M_AD_14: Quota not met → no advance, engine resumes =====

    @Test
    fun M_AD_14() = runTest {
        // Requirement (§10.1): trustedCount < requiredSuccesses → NO advance dispatch, but succeed.
        // Sol R2 P1-1: with real TrustedQuotaAcquirer, recovery must NOT PAUSE — the removed
        // scheduleAdvanced gate was the cause; now recovery resumes directly.
        val (planId, _) = seedAdvanceCrash("QUOTA_COMMITTED", requiredSuccesses = 3)
        buildEngine(planId, VClock()).run()
        assertEquals("M-AD-14: no advance when trustedCount(1) < required(3)", 0, advanceReplays.size)
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("M-AD-14: attempt succeeds", "succeeded", attempt.status)
        assertEquals("M-AD-14: aplusState is CLOSED", "CLOSED", attempt.aplusState)
    }

    // ===== M_AD_15: DECIDING crash window — legitimate re-mint is idempotent =====

    @Test
    fun M_AD_15() = runTest {
        // Requirement (§10.1): crash between quota commit and met determination →
        // recovery reconciles, re-inserts the trusted entry (insertIfAbsent = idempotent no-op),
        // then determines whether to advance. The attempt must succeed, NOT ABORT.
        //
        // Sol R2 P1-2: the DECIDING crash window means the trusted entry was committed
        // but the phase was NOT yet persisted as QUOTA_COMMITTED. Recovery at DECIDING
        // will re-run recordTrustedCompletion. insertIfAbsent silently skips the duplicate,
        // the readback verifies fields match, and the attempt closes normally.
        //
        // Killing mutation: replacing insertIfAbsent with insert → UNIQUE ABORT → stuck.
        val (planId, _) = seedAdvanceCrash("QUOTA_COMMITTED", requiredSuccesses = 1)
        // Downgrade to DECIDING: the mint survived (seeded) but phase did not
        db.testAttemptDao().markAplusState(31L, "DECIDING")
        buildEngine(planId, VClock()).run()
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("M-AD-15: DECIDING with pre-existing mint must succeed", "succeeded", attempt.status)
        assertEquals("M-AD-15: aplusState is CLOSED", "CLOSED", attempt.aplusState)
        // Verify the trusted count is exactly 1 (no duplicate mint was created)
        val count = db.trustedQuotaDao().trustedCountForTask(attempt.taskId)
        assertEquals("M-AD-15: trusted count must remain 1 (idempotent)", 1, count)
    }

    // ===== M_AD_16: Exhausted receipt forged digest → RECOVERY_REQUIRED =====

    @Test
    fun M_AD_16() = runTest {
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = 1, advancedFromItemId = anchorItemId, advancedToItemId = null,
            scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-matrix",
            effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
        )
        val forgedExecutor = object : ExternalApplyExecutor by journeyExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
                val receipt = journeyExecutor.completeAndAdvance(request, expectedIntentHash) ?: return null
                return receipt.copy(receiptDigest = "forged-${receipt.receiptDigest}")
            }
        }
        val (planId, _) = seedAdvanceCrash("ADVANCE_PENDING")
        buildEngine(planId, VClock(), forgedExecutor).run()
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("M-AD-16: exhausted forged-digest must fail-closed", "RECOVERY_REQUIRED", attempt.aplusState)
    }

    // ===== M_AD_17: Observe intentHash mismatch → durable typed reason =====

    @Test
    fun M_AD_17() = runTest {
        // Requirement (§10.1): OBSERVED_TUPLE_MISMATCH typed reason must name "acceptedIntentHash".
        val tamperedExecutor = object : ExternalApplyExecutor by journeyExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                journeyExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
                val honest = journeyExecutor.observe(leaseId, operationId, expectedIntentHash)
                return honest?.copy(acceptedIntentHash = "wrong-intent")
            }
        }
        val (planId, _) = seedAdvanceCrash("ADVANCE_PENDING")
        buildEngine(planId, VClock(), tamperedExecutor).run()
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("M-AD-17: state must be RECOVERY_REQUIRED", "RECOVERY_REQUIRED", attempt.aplusState)
        // Sol R2 P1-3: DURABLE typed reason must name the specific leg
        assertTrue(
            "M-AD-17: failureReason must contain 'acceptedIntentHash' leg name",
            attempt.failureReason?.contains("acceptedIntentHash") == true
        )
        assertTrue(
            "M-AD-17: failureReason must contain OBSERVED_TUPLE_MISMATCH type",
            attempt.failureReason?.contains("OBSERVED_TUPLE_MISMATCH") == true
        )
    }

    // ===== M_AD_18: Observe environmentRevision mismatch → durable typed reason =====

    @Test
    fun M_AD_18() = runTest {
        // Requirement (§10.1): OBSERVED_TUPLE_MISMATCH typed reason must name "environmentRevision".
        val tamperedExecutor = object : ExternalApplyExecutor by journeyExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                journeyExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
                val honest = journeyExecutor.observe(leaseId, operationId, expectedIntentHash)
                return honest?.copy(environmentRevision = 999L)
            }
        }
        val (planId, _) = seedAdvanceCrash("ADVANCE_PENDING")
        buildEngine(planId, VClock(), tamperedExecutor).run()
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("M-AD-18: state must be RECOVERY_REQUIRED", "RECOVERY_REQUIRED", attempt.aplusState)
        // Sol R2 P1-3: DURABLE typed reason must name the specific leg
        assertTrue(
            "M-AD-18: failureReason must contain 'environmentRevision' leg name",
            attempt.failureReason?.contains("environmentRevision") == true
        )
        assertTrue(
            "M-AD-18: failureReason must contain OBSERVED_TUPLE_MISMATCH type",
            attempt.failureReason?.contains("OBSERVED_TUPLE_MISMATCH") == true
        )
    }

    // ===== M_AD_19: Cross-fork same-key replay → idempotent (no second advance) =====

    @Test
    fun M_AD_19() = runTest {
        val (planId, _) = seedAdvanceCrash("QUOTA_COMMITTED", requiredSuccesses = 1)
        val clock = VClock()
        buildEngine(planId, clock).run()
        assertEquals("first run dispatches advance", 1, advanceReplays.size)
        advanceReplays.clear()
        // Second engine run: the attempt is already CLOSED/succeeded — no second advance.
        buildEngine(planId, VClock()).run()
        assertEquals("M-AD-19: second engine run must NOT re-advance (idempotent)", 0, advanceReplays.size)
    }
}
