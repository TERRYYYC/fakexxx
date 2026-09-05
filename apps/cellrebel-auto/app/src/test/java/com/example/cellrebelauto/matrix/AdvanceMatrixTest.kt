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
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.DurableCompletionReceipt
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
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
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
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
import org.json.JSONArray
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
 * | M_AD_15 | DECIDING crash window re-mint: corrupt row → fail-closed | mismatched evidenceDigest ⇒ TRUSTED_LEDGER_CORRUPTION |
 * | M_AD_16 | Exhausted receipt forged digest → RECOVERY_REQUIRED | forged digest ⇒ fail-closed |
 * | M_AD_17 | Observe intentHash mismatch → durable typed reason | failureReason contains "acceptedIntentHash" |
 * | M_AD_18 | Observe environmentRevision mismatch → durable typed reason | failureReason contains "environmentRevision" |
 * | M_AD_19 | Cross-fork same-key replay → idempotent (under-target + target-met) | both branches ⇒ 0 replays on second run |
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

    private suspend fun seedAdvanceCrash(phase: String, requiredSuccesses: Int = 1, attemptId: Long = 31L): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "m.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = requiredSuccesses),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = requiredSuccesses))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = phase, aplusLeaseId = "lease-$attemptId",
                aplusAnchorScheduleId = anchorScheduleId, aplusAnchorItemId = anchorItemId, aplusAnchorVersion = anchorVersion
            )
        )
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(
                attemptId = attemptId, taskId = task.id, evidenceDigest = "ev-$attemptId", committedAt = 9000L
            )
        )
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "h", resultOutcome = "APPLIED", createdAt = 1000L,
                leaseId = "lease-$attemptId", operationId = "op-$attemptId"
            )
        )
        if (phase in setOf("ADVANCE_PENDING", "ADVANCE_OBSERVING", "ADVANCE_STATE_READBACK")) {
            val leaseId = "lease-$attemptId"
            db.releaseReceiptDao().insertIfAbsent(
                ReleaseReceiptRow(
                    idempotencyKey = APlusOperationIdentity.releaseIdempotencyKey(attemptId),
                    leaseId = leaseId,
                    releaseDigest = APlusOperationIdentity.releaseDigest(leaseId),
                    resultOutcome = "RELEASED",
                    createdAt = 8500L
                )
            )
            repo.completeTaskIfQuotaReached(task.id)
        }
        return planId to task.id
    }

    // --- Durable evidence seeding for DECIDING re-decision (Sol R3 P1-2) ---
    // Mirrors CrashMatrixTest's seedMcr06Fixture helpers: seeds the four durable carriers
    // that redecideDecidingAttempt reads from the DB (execution, PRE/POST observations, receipt).

    private companion object {
        const val WIRE_VERIFIED = 1
        const val PRE_OBSERVED_AT_ELAPSED = 1000L
        const val POST_OBSERVED_AT_ELAPSED = 14000L
        const val EXEC_STARTED_AT_ELAPSED = 2000L
        const val EXEC_RUNNING_CONFIRMED_AT_ELAPSED = 2100L
        const val EXEC_COMPLETED_AT_ELAPSED = 13000L  // 13000 - 2100 = 10900 ≥ MIN_RUNNING_EVIDENCE_MS (10000)
        const val CONTINUITY_SINCE_ELAPSED = 500L
    }

    private suspend fun seedDurableExecution(execId: String, attemptId: Long, digest: String) {
        db.attemptExecutionDao().insert(
            CellRebelExecution(
                executionId = execId, attemptId = attemptId,
                completionEvidenceWire = WIRE_VERIFIED, evidencePayloadDigest = digest,
                startedAt = 1000L, classifiedAt = 1100L,
                startedAtElapsed = EXEC_STARTED_AT_ELAPSED,
                runningConfirmedAtElapsed = EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
                completedAtElapsed = EXEC_COMPLETED_AT_ELAPSED,
                baselineRunningState = "IDLE", runningMarkerText = "RUNNING",
                runningDurationMs = EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
                webBrowsingScore = 8.0, videoStreamingScore = 7.0,
                roundTimestampsElapsed = "$EXEC_STARTED_AT_ELAPSED;$EXEC_COMPLETED_AT_ELAPSED"
            )
        )
    }

    private suspend fun seedDurableObservation(attemptId: Long, phase: String, intentHash: String) {
        val observedAt = if (phase == "PRE") PRE_OBSERVED_AT_ELAPSED else POST_OBSERVED_AT_ELAPSED
        db.durableObservationDao().insert(
            DurableObservationRecord(
                attemptId = attemptId, phase = phase,
                leaseId = "lease-$attemptId",
                acceptedIntentHash = intentHash,
                coverage = "FULL",
                verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK",
                isMock = true,
                scheduleDecision = "ALLOWED_NOW",
                effectiveLat = 39.9, effectiveLng = 116.4,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = observedAt,
                observedAtEpochMs = if (phase == "PRE") 900L else 6500L,
                continuitySinceElapsedRealtimeMs = CONTINUITY_SINCE_ELAPSED,
                continuitySinceEpochMs = null,
                evidenceRefsJson = JSONArray(listOf("qwy:store:abc")).toString(),
                evidenceRefs = "qwy:store:abc"
            )
        )
    }

    private suspend fun seedDurableReceipt(attemptId: Long, intentHash: String) {
        db.durableCompletionReceiptDao().insert(
            DurableCompletionReceipt(
                attemptId = attemptId,
                completionEvidenceWire = WIRE_VERIFIED,
                acceptedIntentHash = intentHash,
                leaseId = "lease-$attemptId"
            )
        )
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
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onStartInteraction: suspend () -> Unit,
                    onRunningObserved: suspend (Long) -> Unit
                ): AttemptOutcome {
                    onStartInteraction()
                    return AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 4300L)
                }
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

    // ===== M_AD_15: DECIDING crash window — corrupt row → TRUSTED_LEDGER_CORRUPTION =====

    @Test
    fun M_AD_15() = runTest {
        // Requirement (§10.1): crash between trust-ledger commit and phase-string commit →
        // recovery re-decides from durable carriers via redecideDecidingAttempt → calls
        // recordTrustedCompletion → insertIfAbsent → IGNORE → readback → mismatch →
        // TRUSTED_LEDGER_CORRUPTION (Sol R3 P1-2).
        //
        // seedAdvanceCrash plants a TrustedQuotaEntry with evidenceDigest="ev-31". The durable
        // execution is seeded with evidencePayloadDigest="ev-correct". When recordTrustedCompletion
        // builds its new entry, evidenceDigest="ev-correct" ≠ existing "ev-31" → corruption.
        //
        // Killing mutation: removing the readback allows corrupted rows to pass silently.
        val (planId, _) = seedAdvanceCrash("DECIDING", requiredSuccesses = 1)

        // Seed the FULL durable execution context so redecideDecidingAttempt reaches
        // recordTrustedCompletion (without these, it returns false at the owner-pointer check).
        db.testAttemptDao().markCurrentExecutionId(31L, "exec-current-31")
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        val intentDigest = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                attempt.runSessionId, 31L, planId, anchorScheduleId,
                attempt.startedAt, attempt.startedAt + 90_000L
            )
        )
        seedDurableExecution("exec-current-31", 31L, "ev-correct")
        seedDurableObservation(31L, "PRE", intentDigest)
        seedDurableObservation(31L, "POST", intentDigest)
        seedDurableReceipt(31L, intentDigest)

        // Run the engine — the exception is caught by the top-level handler and triggers aplusPause.
        val engine = buildEngine(planId, VClock())
        engine.run()

        // The engine pauses on corruption, NOT succeeds.
        val session = db.runSessionDao().getLatest()!!
        assertEquals("M-AD-15: engine pauses on corruption", "paused", session.status)
        // The log must name the specific corruption detection.
        assertTrue(
            "M-AD-15: log must contain TRUSTED_LEDGER_CORRUPTION",
            engine.logs.value.any { it.contains("TRUSTED_LEDGER_CORRUPTION") }
        )
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

    // ===== M_AD_19: Cross-fork same-key replay through duplicate insert/readback =====
    //
    // Sol R4 P1-2: the test must exercise the DECIDING → redecideDecidingAttempt path where
    // a pre-existing TrustedQuotaEntry with MATCHING evidenceDigest is already committed. On
    // redecision, recordTrustedCompletion calls insertIfAbsent → IGNORE (row exists) → readback
    // → verify evidenceDigest match → proceed. TrustedCount must stay at 1 (idempotent, no
    // duplicate entry). Both under-target and target-met branches are exercised.
    //
    // Killing mutation: removing the readback-after-IGNORE in recordTrustedCompletion would
    // allow a corrupted row to pass (M_AD_15 kills that), but this test proves that a MATCHING
    // row also proceeds correctly — the same-key cross-fork replay is idempotent.

    @Test
    fun M_AD_19() = runTest {
        // ---- (a) Under-target: required=2, trusted=1 → no advance, proceed, trustedCount=1 ----
        // seedAdvanceCrash at DECIDING plants a pre-existing TrustedQuotaEntry("ev-5001") and
        // anchor triple. The durable execution has evidencePayloadDigest="ev-5001" (MATCHING).
        // redecide → recordTrustedCompletion → insertIfAbsent IGNORE → readback → match → proceed.
        val (planIdA, taskIdA) = seedAdvanceCrash("DECIDING", requiredSuccesses = 2, attemptId = 5001L)
        db.testAttemptDao().markCurrentExecutionId(5001L, "exec-5001")
        val snapA = db.testAttemptDao().getAttemptById(5001L)!!
        val intentA = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                snapA.runSessionId, 5001L, planIdA, anchorScheduleId,
                snapA.startedAt, snapA.startedAt + 90_000L
            )
        )
        seedDurableExecution("exec-5001", 5001L, "ev-5001")   // MATCHING pre-existing TrustedQuotaEntry
        seedDurableObservation(5001L, "PRE", intentA)
        seedDurableObservation(5001L, "POST", intentA)
        seedDurableReceipt(5001L, intentA)

        buildEngine(planIdA, VClock()).run()

        assertEquals("M-AD-19a: under-target → 0 advances", 0, advanceReplays.size)
        val recoveredA = db.testAttemptDao().getAttemptById(5001L)!!
        assertEquals("M-AD-19a: attempt succeeds", "succeeded", recoveredA.status)
        assertEquals("M-AD-19a: CLOSED", "CLOSED", recoveredA.aplusState)
        assertEquals(
            "M-AD-19a: trustedCount stays 1 — insertIfAbsent IGNORE is idempotent, no duplicate",
            1, db.trustedQuotaDao().trustedCountForTask(taskIdA)
        )

        // ---- (b) Target-met: required=1, trusted=1 → 1 advance, trustedCount=1 ----
        advanceReplays.clear()
        val (planIdB, taskIdB) = seedAdvanceCrash("DECIDING", requiredSuccesses = 1, attemptId = 9001L)
        db.testAttemptDao().markCurrentExecutionId(9001L, "exec-9001")
        val snapB = db.testAttemptDao().getAttemptById(9001L)!!
        val intentB = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                snapB.runSessionId, 9001L, planIdB, anchorScheduleId,
                snapB.startedAt, snapB.startedAt + 90_000L
            )
        )
        seedDurableExecution("exec-9001", 9001L, "ev-9001")   // MATCHING pre-existing TrustedQuotaEntry
        seedDurableObservation(9001L, "PRE", intentB)
        seedDurableObservation(9001L, "POST", intentB)
        seedDurableReceipt(9001L, intentB)

        buildEngine(planIdB, VClock()).run()

        assertEquals("M-AD-19b: target-met → 1 advance", 1, advanceReplays.size)
        val recoveredB = db.testAttemptDao().getAttemptById(9001L)!!
        assertEquals("M-AD-19b: attempt succeeds", "succeeded", recoveredB.status)
        assertEquals("M-AD-19b: CLOSED", "CLOSED", recoveredB.aplusState)
        assertEquals(
            "M-AD-19b: trustedCount stays 1 — insertIfAbsent IGNORE is idempotent, no duplicate",
            1, db.trustedQuotaDao().trustedCountForTask(taskIdB)
        )
    }
}
