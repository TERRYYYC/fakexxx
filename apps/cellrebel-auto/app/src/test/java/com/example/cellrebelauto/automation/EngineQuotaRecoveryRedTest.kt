package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.DurableCompletionReceipt
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.OperationReceiptRow
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import org.json.JSONArray
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Group 1 RED oracle (Sol closure verdict Issue #19): QUOTA_COMMITTED / RELEASED crash recovery
 * must re-compute quota from the durable trusted ledger and dispatch the external advance
 * ([replayAdvanceAndVerify]) when the task's quota is reached.
 *
 * The gap: the current recovery fall-through path ([advanceAfterRelease]) finalizes the attempt
 * as succeeded but NEVER dispatches the external advance. A crash at QUOTA_COMMITTED or RELEASED
 * with quota reached loses the schedule advance permanently — the schedule never moves.
 *
 * Killing mutation: removing the quota-gated advance dispatch from the recovery path ⇒
 * [advanceReplays].size = 0 for the quota-reached tests → assertion failure.
 *
 * # 配额提交/已释放 崩溃恢复 oracle：必须从可信账本重新计算配额并在达成时派发外部推进
 */
@RunWith(RobolectricTestRunner::class)
class EngineQuotaRecoveryRedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: com.example.cellrebelauto.repository.PlanRepository

    private val anchorScheduleId = "sched-quota-7a"
    private val anchorItemId = "item-quota-3b"
    private val anchorVersion = 12L

    private val advanceReplays = mutableListOf<CompleteAndAdvanceRequestV1>()
    private val releaseAttempts = mutableListOf<Long>()
    private var advanceAnswer: AdvanceReceiptV1? = AdvanceReceiptV1(
        outcomeWire = 1, advancedFromItemId = anchorItemId, advancedToItemId = "item-after-9z",
        scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-quota-recovery",
        effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
    )

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-$attemptId", operationId = "op-$attemptId")
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome {
            releaseAttempts += attemptId
            return ApplyOutcome("RELEASED", false)
        }
        override fun discover(): CapabilitySnapshotV1? = CapabilitySnapshotV1(
            serviceVersion = "fake-1.0",
            supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            profileRefs = listOf("p"), scheduleRefs = listOf("s"),
            currentScheduleId = anchorScheduleId, currentItemId = anchorItemId, scheduleVersion = anchorVersion, exhausted = false
        )
        override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? =
            PreflightReportV1(
                acceptedIntentHash = requestDigest,
                scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
                waitUntilEpochMs = null,
                achievableVerificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
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
                continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                continuitySinceEpochMs = null, continuitySinceElapsedRealtimeMs = null,
                deliveryModeWire = io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire,
                verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
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

    private val minimalEvidenceSource = object : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
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

    /**
     * Seeds a plan/task with [requiredSuccesses] and a crashed attempt at [phase] with the full
     * durable state needed for advance recovery: anchor triple, trusted mint, apply receipt.
     */
    private suspend fun seedCrashedAt(phase: String, requiredSuccesses: Int = 1): Pair<Long, Long> {
        val attemptId = 31L
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "q.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = requiredSuccesses),
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
                aplusState = phase, aplusLeaseId = "lease-$attemptId", currentExecutionId = "exec-$attemptId",
                aplusAnchorScheduleId = anchorScheduleId, aplusAnchorItemId = anchorItemId, aplusAnchorVersion = anchorVersion
            )
        )
        // The durable trusted mint (the advance only ever runs quota-reached)
        db.trustedQuotaDao().insert(
            com.example.cellrebelauto.model.ledger.TrustedQuotaEntry(
                attemptId = attemptId, taskId = task.id, evidenceDigest = "ev-$attemptId", committedAt = 9000L
            )
        )
        // The Room apply receipt carrying the verbatim operationId (the observe tuple's leg)
        db.operationReceiptDao().insertIfAbsent(
            com.example.cellrebelauto.recovery.OperationReceiptRow(
                idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "h", resultOutcome = "APPLIED", createdAt = 1000L,
                leaseId = "lease-$attemptId", operationId = "op-$attemptId"
            )
        )
        if (phase == "RELEASED") {
            // A legal legacy RELEASED row was written only after the old engine had durably
            // recorded the release receipt. Seed that carrier so compatibility recovery proves
            // replay from Room instead of silently issuing a fresh provider release.
            db.releaseReceiptDao().insertIfAbsent(
                ReleaseReceiptRow(
                    idempotencyKey = APlusOperationIdentity.releaseIdempotencyKey(attemptId),
                    leaseId = "lease-$attemptId",
                    releaseDigest = APlusOperationIdentity.releaseDigest("lease-$attemptId"),
                    resultOutcome = "RELEASED",
                    createdAt = 1100L
                )
            )
        }
        return planId to task.id
    }

    private fun buildEngine(planId: Long, clock: VClock): AutomationEngine {
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            journeyExecutor, RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()),
            // Wire acquirers to pass the schedule-advance gate after recovery
            observe = com.example.cellrebelauto.recovery.ObserveIntentAcquirer { true },
            receiptRevision = com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = com.example.cellrebelauto.recovery.TrustedQuotaAcquirer { true }
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
            attemptDriver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = minimalEvidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
    }

    // ---- QUOTA_COMMITTED crash + quota REACHED → advance MUST be dispatched ----

    @Test
    fun `a QUOTA_COMMITTED crash with quota reached dispatches the external advance`() = runTest {
        val (planId, _) = seedCrashedAt("QUOTA_COMMITTED", requiredSuccesses = 1)
        buildEngine(planId, VClock()).run()

        assertEquals(
            "recovery MUST dispatch the external advance when quota is reached " +
                "(killing mutation: no advance dispatch in advanceAfterRelease ⇒ 0 replays)",
            1, advanceReplays.size
        )
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("the crashed attempt must close as succeeded (the trusted mint is durable)", "succeeded", attempt.status)
        assertEquals("CLOSED", attempt.aplusState)
        val trail = db.auditEventDao().forAttempt(31L)
        assertEquals(
            "QUOTA_COMMITTED->RELEASE_PENDING",
            trail.single { it.eventType == AttemptEvent.BEGIN_RELEASE.name }.payloadDigest
        )
        assertEquals(
            "RELEASE_PENDING->ADVANCE_PENDING[COMMITTED_QUOTA_REACHED]",
            trail.single { it.eventType == AttemptEvent.RELEASE_RECEIPT.name }.payloadDigest
        )
    }

    // ---- RELEASED crash + quota REACHED → advance MUST be dispatched ----

    @Test
    fun `a RELEASED crash with quota reached dispatches the external advance`() = runTest {
        val (planId, _) = seedCrashedAt("RELEASED", requiredSuccesses = 1)
        buildEngine(planId, VClock()).run()

        assertEquals(
            "recovery from RELEASED MUST dispatch the advance when quota is reached " +
                "(killing mutation: no advance dispatch ⇒ 0 replays)",
            1, advanceReplays.size
        )
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("the crashed attempt must close as succeeded", "succeeded", attempt.status)
        assertEquals("CLOSED", attempt.aplusState)
        assertEquals("legacy RELEASED must replay its durable receipt without a provider call", 0, releaseAttempts.count { it == 31L })
    }

    // ====================================================================================
    // Group 2 (Sol closure verdict Issue #19): Trusted-ledger replay idempotency.
    // Same-key under/over-quota and no-second-advance proof.
    //
    // Under-quota:  trustedCount < requiredSuccesses → no advance dispatched, attempt
    //               still closes succeeded (the durable mint is the authority; the schedule
    //               just doesn't move yet).
    // Over-quota:   trustedCount > requiredSuccesses → advance dispatched exactly once (the
    //               ">=" gate is not a strict "==" gate).
    // No-second-advance: after recovery closes the attempt (CLOSED + succeeded), a second
    //               engine run cannot re-enter the recovery path — the state machine's
    //               terminal projection prevents it.
    //
    // # 可信账本重放幂等：未达配额不推进、超配额只推进一次、终态后不重入
    // ====================================================================================

    // ---- Under-quota: quota NOT reached → no advance dispatched ----

    @Test
    fun `a QUOTA_COMMITTED crash with quota NOT reached dispatches NO advance but closes succeeded`() = runTest {
        // requiredSuccesses=3, only 1 trusted entry → quota NOT reached (1 < 3)
        val (planId, _) = seedCrashedAt("QUOTA_COMMITTED", requiredSuccesses = 3)
        buildEngine(planId, VClock()).run()

        assertEquals(
            "under-quota recovery must NOT dispatch the advance (quota gate: 1 < 3; " +
                "killing mutation: removing the >= guard ⇒ 1 replay when there should be 0)",
            0, advanceReplays.size
        )
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "the attempt closes succeeded regardless of quota (the trusted mint is durable)",
            "succeeded", attempt.status
        )
        assertEquals("CLOSED", attempt.aplusState)
        assertEquals(
            "RELEASE_PENDING->CLOSED[COMMITTED_UNDER_QUOTA]",
            db.auditEventDao().forAttempt(31L)
                .single { it.eventType == AttemptEvent.RELEASE_RECEIPT.name }
                .payloadDigest
        )
    }

    @Test
    fun `a RELEASED crash with quota NOT reached dispatches NO advance but closes succeeded`() = runTest {
        val (planId, _) = seedCrashedAt("RELEASED", requiredSuccesses = 3)
        buildEngine(planId, VClock()).run()

        assertEquals(
            "under-quota RELEASED recovery must NOT dispatch the advance (quota gate: 1 < 3)",
            0, advanceReplays.size
        )
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("succeeded", attempt.status)
        assertEquals("CLOSED", attempt.aplusState)
        assertEquals("legacy RELEASED must replay its durable receipt without a provider call", 0, releaseAttempts.count { it == 31L })
    }

    // ---- Over-quota: more trusted entries than required → advance dispatched exactly once ----

    @Test
    fun `over-quota QUOTA_COMMITTED crash dispatches the advance exactly once`() = runTest {
        val (planId, taskId) = seedCrashedAt("QUOTA_COMMITTED", requiredSuccesses = 1)
        // Seed a second trusted entry from a prior attempt → over-quota (trustedCount=2, required=1)
        db.trustedQuotaDao().insert(
            com.example.cellrebelauto.model.ledger.TrustedQuotaEntry(
                attemptId = 30L, taskId = taskId, evidenceDigest = "ev-prior-30", committedAt = 8000L
            )
        )
        buildEngine(planId, VClock()).run()

        assertEquals(
            "over-quota recovery must dispatch the advance exactly once (2 >= 1 satisfies the gate; " +
                "killing mutation: using == instead of >= ⇒ 0 replays when trustedCount > required)",
            1, advanceReplays.size
        )
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("succeeded", attempt.status)
        assertEquals("CLOSED", attempt.aplusState)
    }

    // ---- No-second-advance: after recovery, re-running the engine cannot dispatch again ----

    @Test
    fun `after quota-recovery closes the attempt a second engine run dispatches NO advance`() = runTest {
        val (planId, _) = seedCrashedAt("QUOTA_COMMITTED", requiredSuccesses = 1)
        val clock = VClock()
        buildEngine(planId, clock).run()

        // First run: advance dispatched once, attempt closed trusted
        assertEquals("first run must dispatch exactly one advance", 1, advanceReplays.size)
        assertEquals("succeeded", db.testAttemptDao().getAttemptById(31L)!!.status)
        assertEquals("CLOSED", db.testAttemptDao().getAttemptById(31L)!!.aplusState)

        // Reset the advance counter for the second run
        advanceReplays.clear()

        // Second engine on the SAME DB — the attempt is already CLOSED + succeeded;
        // the task is completed (normalizeQuotaCompletedTasks at startup catches the
        // quota-full task). The state machine's terminal projection prevents re-entry
        // into the recovery path; the plan completes immediately.
        buildEngine(planId, VClock()).run()

        assertEquals(
            "a second engine run must NOT dispatch another advance — the attempt is CLOSED " +
                "and the state machine prevents re-entry into the recovery path " +
                "(no-second-advance proof: durable terminal state + normalization guard)",
            0, advanceReplays.size
        )
    }

    // ====================================================================================
    // P1-1 (Sol R2→R3): missing task/anchor fail-closed guards.
    //
    // A missing task is always an invariant break. A missing anchor with quota met is
    // ALWAYS an invariant break, regardless of snapshot phase — the crashed.aplusState
    // is stale (immutable from boot). After redecideDecidingAttempt the DB state may
    // differ from the snapshot. The normal path sets anchor BEFORE trust evaluation
    // (L355-L375), so quota-met + no-anchor is unreachable without a lifecycle violation.
    //
    // # P1-1：task 丢失恒 invariant break；anchor 丢失 + quota met 恒 invariant break（不分快照相位）
    // ====================================================================================

    // P1-1a (missing task): not testable in isolation because Room FK constraints prevent
    // inserting an attempt for a non-existent task. The DB schema itself is the structural guard.
    // The production code guard (task == null → RECOVERY_REQUIRED) is defense-in-depth for
    // DB corruption scenarios that bypass FK enforcement.

    @Test
    fun `P1-1b a missing anchor at QUOTA_COMMITTED fail-closes instead of silently succeeding`() = runTest {
        // Seed a crashed attempt at QUOTA_COMMITTED but WITHOUT the advance anchor.
        // This is an invariant break: the lifecycle persists the anchor before QUOTA_COMMITTED.
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "r.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = 31L, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = "QUOTA_COMMITTED", aplusLeaseId = "lease-31",
                // DELIBERATELY omit anchor fields (null) — invariant break for QUOTA_COMMITTED
                aplusAnchorScheduleId = null, aplusAnchorItemId = null, aplusAnchorVersion = null
            )
        )
        db.trustedQuotaDao().insert(
            com.example.cellrebelauto.model.ledger.TrustedQuotaEntry(
                attemptId = 31L, taskId = task.id, evidenceDigest = "ev-31", committedAt = 9000L
            )
        )
        buildEngine(planId, VClock()).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "P1-1b: a missing anchor at QUOTA_COMMITTED must fail-closed " +
                "(killing mutation: removing the anchor guard ⇒ silently succeeds without advance)",
            "RECOVERY_REQUIRED", attempt.aplusState
        )
    }

    @Test
    fun `P1-1c a DECIDING crash with full durable context but missing anchor fail-closes after redecision`() = runTest {
        // Sol R4 P1-1 killing regression test: the old code exempted DECIDING from the anchor
        // guard ("if it's DECIDING, redecision hasn't run yet so no anchor is expected"). But
        // after redecideDecidingAttempt runs (all 5 durable carriers present → mint trust),
        // trustedCount reaches requiredSuccesses, and the MISSING anchor is an invariant break.
        //
        // This test kills the DECIDING exemption: DECIDING + full durable context + quota met
        // + anchor null → RECOVERY_REQUIRED. Without the uniform anchor guard, the engine would
        // silently succeed without dispatching an advance.
        val attemptId = 31L
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "r.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = "DECIDING", aplusLeaseId = "lease-$attemptId",
                // DELIBERATELY omit anchor fields (null) — the invariant break we're testing
                aplusAnchorScheduleId = null, aplusAnchorItemId = null, aplusAnchorVersion = null
            )
        )
        // NO pre-existing TrustedQuotaEntry — redecide will INSERT fresh via recordTrustedCompletion

        // Seed the FULL durable context so redecideDecidingAttempt runs through all 5 carriers
        db.testAttemptDao().markCurrentExecutionId(attemptId, "exec-$attemptId")
        val attempt = db.testAttemptDao().getAttemptById(attemptId)!!
        val intentDigest = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                attempt.runSessionId, attemptId, planId, "qwy-default-schedule",
                attempt.startedAt, attempt.startedAt + 90_000L
            )
        )
        // Execution row with evidencePayloadDigest (carrier 2)
        db.attemptExecutionDao().insert(
            CellRebelExecution(
                executionId = "exec-$attemptId", attemptId = attemptId,
                completionEvidenceWire = 1, evidencePayloadDigest = "ev-$attemptId",
                startedAt = 1000L, classifiedAt = 1100L,
                startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L,
                completedAtElapsed = 13000L,
                baselineRunningState = "IDLE", runningMarkerText = "RUNNING",
                runningDurationMs = 10900L,
                webBrowsingScore = 8.0, videoStreamingScore = 7.0,
                roundTimestampsElapsed = "2000;13000"
            )
        )
        // PRE observation (carrier 3)
        db.durableObservationDao().insert(
            DurableObservationRecord(
                attemptId = attemptId, phase = "PRE",
                leaseId = "lease-$attemptId", acceptedIntentHash = intentDigest,
                coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK", isMock = true,
                scheduleDecision = "ALLOWED_NOW",
                effectiveLat = 39.9, effectiveLng = 116.4,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = 1000L, observedAtEpochMs = 900L,
                continuitySinceElapsedRealtimeMs = 500L, continuitySinceEpochMs = null,
                evidenceRefsJson = JSONArray(listOf("qwy:store:abc")).toString(),
                evidenceRefs = "qwy:store:abc"
            )
        )
        // POST observation (carrier 4)
        db.durableObservationDao().insert(
            DurableObservationRecord(
                attemptId = attemptId, phase = "POST",
                leaseId = "lease-$attemptId", acceptedIntentHash = intentDigest,
                coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK", isMock = true,
                scheduleDecision = "ALLOWED_NOW",
                effectiveLat = 39.9, effectiveLng = 116.4,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = 14000L, observedAtEpochMs = 6500L,
                continuitySinceElapsedRealtimeMs = 500L, continuitySinceEpochMs = null,
                evidenceRefsJson = JSONArray(listOf("qwy:store:abc")).toString(),
                evidenceRefs = "qwy:store:abc"
            )
        )
        // Completion receipt (carrier 5)
        db.durableCompletionReceiptDao().insert(
            DurableCompletionReceipt(
                attemptId = attemptId, completionEvidenceWire = 1,
                acceptedIntentHash = intentDigest, leaseId = "lease-$attemptId"
            )
        )

        buildEngine(planId, VClock()).run()

        val recovered = db.testAttemptDao().getAttemptById(attemptId)!!
        assertEquals(
            "P1-1c: DECIDING + full durable redecision + quota met + missing anchor " +
                "must fail-closed (killing mutation: a DECIDING exemption from the anchor " +
                "guard ⇒ silently succeeds without advance)",
            "RECOVERY_REQUIRED", recovered.aplusState
        )
        assertTrue(
            "P1-1c: failureReason must name the anchor invariant break",
            recovered.failureReason?.contains("ANCHOR_MISSING_QUOTA_MET") == true
        )
    }

    // ====================================================================================
    // P1-2 (Sol closure review R2): DECIDING crash window — ledger-commit → phase-commit.
    //
    // The trusted entry is committed, but the phase string is still DECIDING (the crash
    // happens between TrustedQuotaEntry INSERT and markAplusState("QUOTA_COMMITTED")).
    // On recovery, redecideDecidingAttempt re-runs recordTrustedCompletion which tries
    // to INSERT again. Without insertIfAbsent, this hits UNIQUE ABORT and the recovery
    // transaction rolls back, leaving the attempt stuck at DECIDING forever.
    //
    // # P1-2：DECIDING 崩溃窗口——账本已提交但相位未提交——重新判定不能 UNIQUE ABORT
    // ====================================================================================

    @Test
    fun `P1-2 DECIDING crash with pre-existing trusted entry does NOT abort on re-insert`() = runTest {
        // This test exercises the exact crash window: trusted entry committed, phase still DECIDING.
        // The recovery path (redecideDecidingAttempt) will call recordTrustedCompletion again.
        // With insertIfAbsent, the duplicate insert is a no-op; with plain insert, it ABORTs.
        val (planId, taskId) = seedCrashedAt("QUOTA_COMMITTED", requiredSuccesses = 1)
        // Downgrade the phase to DECIDING to simulate the crash window
        // (trusted entry committed, but phase not yet advanced to QUOTA_COMMITTED)
        db.testAttemptDao().markAplusState(31L, "DECIDING")
        // The trusted entry is ALREADY in DB (seeded by seedCrashedAt).
        // Recovery will try to re-mint → must NOT throw UNIQUE ABORT.
        buildEngine(planId, VClock()).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        // The attempt must close as succeeded (the trusted mint is durable, regardless of
        // whether redecide succeeds or falls through to the terminal projection).
        assertEquals(
            "P1-2: DECIDING crash with pre-existing trusted entry must not ABORT — " +
                "the attempt must close succeeded (killing mutation: using plain insert() instead " +
                "of insertIfAbsent() ⇒ SQLiteConstraintException rolls back recovery)",
            "succeeded", attempt.status
        )
    }
}
