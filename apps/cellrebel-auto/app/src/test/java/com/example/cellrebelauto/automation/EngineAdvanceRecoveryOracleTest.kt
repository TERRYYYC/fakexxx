package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R46 (Sol R46 P1-1): the ADVANCE_* crash-recovery oracle. A crash between the quota mint and
 * the advance verification leaves the attempt at ADVANCE_PENDING / ADVANCE_OBSERVING /
 * ADVANCE_STATE_READBACK. Recovery MUST:
 *  - replay the SAME durable advance request (identical idempotency key + canonical digest —
 *    killing mutation: a re-discovered triple changes the request and the digest);
 *  - re-run the receipt-digest binding + four-leg verification;
 *  - close the attempt TRUSTED (the mint is durable).
 *
 * Killing mutation: the recovery branch removed ⇒ the attempt stays at ADVANCE_* (never
 * re-advanced, never closed) ⇒ every assertion below fails.
 * # ADVANCE_* 崩溃恢复 oracle：同 key+digest 重放 + 四腿复验 + 可信收尾
 */
@RunWith(RobolectricTestRunner::class)
class EngineAdvanceRecoveryOracleTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: com.example.cellrebelauto.repository.PlanRepository

    private val anchorScheduleId = "sched-recovery-7a"
    private val anchorItemId = "item-recovery-3b"
    private val anchorVersion = 12L

    private val advanceReplays = mutableListOf<CompleteAndAdvanceRequestV1>()
    private data class StoredAdvance(
        val requestDigest: String,
        val receipt: AdvanceReceiptV1
    )
    private val storedAdvances = mutableMapOf<String, StoredAdvance>()
    private var advanceInvocationCount = 0
    private var advanceEffectCount = 0
    private var observeCalls = 0
    private var discoverCalls = 0
    private var advanceAnswer: AdvanceReceiptV1? = AdvanceReceiptV1(
        outcomeWire = 1, advancedFromItemId = "item-recovery-3b", advancedToItemId = "item-after-9z",
        scheduleVersionAfter = 13L, effectiveIntentHash = "eff-recovery",
        effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
    )

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-$attemptId", operationId = "op-$attemptId")
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome =
            ApplyOutcome("RELEASED", false)
        override fun discover(): CapabilitySnapshotV1? {
            discoverCalls++
            return CapabilitySnapshotV1(
                serviceVersion = "fake-1.0",
                supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
                supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
                continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                environmentRevision = 7L,
                profileRefs = listOf("p"), scheduleRefs = listOf("s"),
                currentScheduleId = anchorScheduleId, currentItemId = anchorItemId, scheduleVersion = anchorVersion, exhausted = false
            )
        }
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
            observeCalls++
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
            advanceInvocationCount++
            advanceReplays += request
            storedAdvances[request.idempotencyKey]?.let { stored ->
                return if (stored.requestDigest == request.requestDigest) stored.receipt else null
            }
            val base = advanceAnswer ?: return null
            val receipt = base.copy(
                receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(base, request.requestDigest, request.idempotencyKey)
            )
            storedAdvances[request.idempotencyKey] = StoredAdvance(request.requestDigest, receipt)
            advanceEffectCount++
            return receipt
        }
    }

    private fun expectedAdvanceRequest(): CompleteAndAdvanceRequestV1 {
        val base = CompleteAndAdvanceRequestV1(
            leaseId = "lease-31",
            idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(31L),
            requestDigest = "",
            expectedScheduleId = anchorScheduleId,
            expectedScheduleVersion = anchorVersion,
            expectedCurrentItemId = anchorItemId,
            completionProof = io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1(
                scheduleItemId = anchorItemId,
                trustedSuccessCount = 1,
                quotaRequired = 1,
                ledgerRef = "ledger-31",
                verifiedAtElapsedRealtimeMs = 99999L
            ),
            callerProtocolVersion = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
        )
        return base.copy(requestDigest = CanonicalAdvanceDigestV1.compute(base))
    }

    /** Models the provider effect that happened before the process died at ADVANCE_OBSERVING. */
    private fun seedAdvanceEffect(request: CompleteAndAdvanceRequestV1) {
        val base = checkNotNull(advanceAnswer)
        val receipt = base.copy(
            receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                base,
                request.requestDigest,
                request.idempotencyKey
            )
        )
        storedAdvances[request.idempotencyKey] = StoredAdvance(request.requestDigest, receipt)
        advanceInvocationCount = 1
        advanceEffectCount = 1
    }

    // Minimal non-null source: the ADVANCE_* recovery branch reads DURABLE state only (the mint
    // is committed); it never re-acquires evidence. Production always wires a real source.
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

    /** Seeds a plan/task plus a crashed attempt at [phase] with the full durable advance state:
     *  anchor triple, trusted mint, persisted lease, Room apply receipt (operationId leg). */
    private suspend fun seedCrashedAt(phase: String): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "r.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        val attemptId = 31L
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
        // The durable trusted mint (the advance only ever runs quota-reached).
        db.trustedQuotaDao().insert(
            com.example.cellrebelauto.model.ledger.TrustedQuotaEntry(
                attemptId = attemptId, taskId = task.id, evidenceDigest = "ev-$attemptId", committedAt = 9000L
            )
        )
        // The Room apply receipt carrying the verbatim operationId (the observe tuple's leg).
        db.operationReceiptDao().insertIfAbsent(
            com.example.cellrebelauto.recovery.OperationReceiptRow(
                idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "h", resultOutcome = "APPLIED", createdAt = 1000L,
                leaseId = "lease-$attemptId", operationId = "op-$attemptId"
            )
        )
        db.releaseReceiptDao().insertIfAbsent(
            com.example.cellrebelauto.recovery.ReleaseReceiptRow(
                idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
                    .releaseIdempotencyKey(attemptId),
                leaseId = "lease-$attemptId",
                releaseDigest = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
                    .releaseDigest("lease-$attemptId"),
                resultOutcome = "RELEASED",
                createdAt = 8500L
            )
        )
        repo.completeTaskIfQuotaReached(task.id)
        return planId to task.id
    }

    private fun buildEngine(planId: Long, clock: VClock): AutomationEngine {
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            journeyExecutor, RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao())
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

    @Test
    fun `an ADVANCE_PENDING crash replays the same durable request and closes trusted`() = runTest {
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        val clock = VClock()
        buildEngine(planId, clock).run()

        assertEquals("the recovery REPLAYED the advance (killing mutation: branch removed ⇒ zero replays)", 1, advanceReplays.size)
        val req = advanceReplays[0]
        assertEquals("the replay carries the SAME anchored CAS triple", anchorScheduleId, req.expectedScheduleId)
        assertEquals(anchorItemId, req.expectedCurrentItemId)
        assertEquals(anchorVersion, req.expectedScheduleVersion)
        assertEquals(
            "the replay's digest is the canonical advance framing of the rebuilt request",
            CanonicalAdvanceDigestV1.compute(req), req.requestDigest
        )
        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("the crashed attempt closed TRUSTED (the mint was durable)", "succeeded", attempt.status)
        assertEquals("CLOSED", attempt.aplusState)
        assertEquals(
            listOf(
                "ADVANCE_PENDING->ADVANCE_PENDING",
                "ADVANCE_PENDING->ADVANCE_OBSERVING",
                "ADVANCE_OBSERVING->CLOSED"
            ),
            db.auditEventDao().forAttempt(31L)
                .filter { it.eventType in setOf(
                    AttemptEvent.CRASH_RECOVER.name,
                    AttemptEvent.ADVANCE_RECEIPT_VERIFIED.name,
                    AttemptEvent.OBSERVED_TUPLE_MATCHES.name
                ) }
                .map { it.payloadDigest }
        )
    }

    @Test
    fun `an ADVANCE_OBSERVING crash resumes through the same replay and verification`() = runTest {
        val (planId, taskId) = seedCrashedAt("ADVANCE_OBSERVING")
        val originalRequest = expectedAdvanceRequest()
        seedAdvanceEffect(originalRequest)
        val clock = VClock()
        buildEngine(planId, clock).run()

        assertEquals("the observation-phase crash also replays + verifies (idempotent provider returns the stored receipt)", 1, advanceReplays.size)
        assertEquals(originalRequest.idempotencyKey, advanceReplays.single().idempotencyKey)
        assertEquals(originalRequest.requestDigest, advanceReplays.single().requestDigest)
        assertEquals("one original call plus one recovery replay", 2, advanceInvocationCount)
        assertEquals("the replay must not apply a second provider advance effect", 1, advanceEffectCount)
        assertEquals("closed trusted", "succeeded", db.testAttemptDao().getAttemptById(31L)!!.status)
        val audit = db.auditEventDao().forAttempt(31L)
        assertEquals(
            "recovery must not rewind an already verified receipt to ADVANCE_PENDING",
            0,
            audit.count { it.eventType == AttemptEvent.ADVANCE_RECEIPT_VERIFIED.name }
        )
        assertEquals(
            "ADVANCE_OBSERVING->CLOSED",
            audit.single { it.eventType == AttemptEvent.OBSERVED_TUPLE_MATCHES.name }.payloadDigest
        )
    }

    @Test
    fun `a non-terminal receipt contradicting ADVANCE_STATE_READBACK does not forge a readback mismatch audit`() = runTest {
        val (planId, taskId) = seedCrashedAt("ADVANCE_STATE_READBACK")
        val clock = VClock()

        buildEngine(planId, clock).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals(
            "ADVANCE_RECEIPT_PHASE_CONTRADICTION:ADVANCE_STATE_READBACK:ADVANCED",
            attempt.failureReason
        )
        assertEquals("phase contradiction must not execute observe", 0, observeCalls)
        assertEquals(
            "only the recovery-admission discover runs; phase contradiction must not execute a readback",
            1,
            discoverCalls
        )
        assertTrue(
            "without a readback there must be no EXHAUSTED_STATE_MISMATCH audit",
            db.auditEventDao().forAttempt(31L).none {
                it.eventType == AttemptEvent.EXHAUSTED_STATE_MISMATCH.name
            }
        )
    }

    @Test
    fun `an exhausted receipt contradicting ADVANCE_OBSERVING does not forge an observe mismatch audit`() = runTest {
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1.EXHAUSTED.wire,
            advancedFromItemId = anchorItemId,
            advancedToItemId = null,
            scheduleVersionAfter = anchorVersion + 1,
            effectiveIntentHash = "eff-recovery",
            effectiveEnvironmentRevision = 7L,
            receiptDigest = "filled-at-call"
        )
        val (planId, taskId) = seedCrashedAt("ADVANCE_OBSERVING")
        val clock = VClock()

        buildEngine(planId, clock).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals(
            "ADVANCE_RECEIPT_PHASE_CONTRADICTION:ADVANCE_OBSERVING:EXHAUSTED",
            attempt.failureReason
        )
        assertEquals("phase contradiction must not execute observe", 0, observeCalls)
        assertEquals(
            "only the recovery-admission discover runs; phase contradiction must not execute a readback",
            1,
            discoverCalls
        )
        assertTrue(
            "without an observation there must be no OBSERVED_TUPLE_MISMATCH audit",
            db.auditEventDao().forAttempt(31L).none {
                it.eventType == AttemptEvent.OBSERVED_TUPLE_MISMATCH.name
            }
        )
    }

    @Test
    fun `an unproven replay fail-closes to RECOVERY_REQUIRED - never silently closed`() = runTest {
        advanceAnswer = null // the provider cannot prove the advance
        val (planId, taskId) = seedCrashedAt("ADVANCE_STATE_READBACK")
        val clock = VClock()
        buildEngine(planId, clock).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("fail-closed phase, never a silent trusted close", "RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals("no trusted terminal projection from an unproven advance", "running", attempt.status)
    }

    @Test
    fun `a FORGED receipt digest fail-closes the recovery (R46 P1-2)`() = runTest {
        // The provider returns a receipt whose receiptDigest does NOT bind this request — the
        // four legs may all look healthy, but the receipt is not evidence of THIS replay.
        val realExecutor = journeyExecutor
        val forgedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
                val receipt = realExecutor.completeAndAdvance(request, expectedIntentHash) ?: return null
                return receipt.copy(receiptDigest = "forged-${receipt.receiptDigest}")
            }
        }
        val (planId, taskId) = seedCrashedAt("ADVANCE_PENDING")
        val clock = VClock()
        // Wire the forged executor through a fresh coordinator.
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            forgedExecutor, RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao())
        )
        val engine = AutomationEngine(
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
            bufferGate = com.example.cellrebelauto.automation.plan.BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs, delayMs = clock.delayMs,
            attemptDriver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = minimalEvidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
        engine.run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "a receipt that does not bind this request is not a receipt — fail-closed (killing mutation: digest check removed ⇒ succeeded)",
            "RECOVERY_REQUIRED", attempt.aplusState
        )
        assertEquals(
            "ADVANCE_PENDING->RECOVERY_REQUIRED",
            db.auditEventDao().forAttempt(31L)
                .single { it.eventType == AttemptEvent.ADVANCE_DIGEST_MISMATCH.name }
                .payloadDigest
        )
    }

    // ====================================================================================
    // Group 3 (Sol closure verdict Issue #19): Stable M-AD-14..19 evidence.
    // Exhausted forged-digest, intent-hash/revision independent negatives, recovery/idempotence.
    //
    // M-AD-14/15/19 are covered by EngineQuotaRecoveryRedTest (Groups 1 & 2).
    // M-AD-16: Exhausted receipt with forged digest → RECOVERY_REQUIRED
    // M-AD-17: Non-terminal observe intentHash mismatch → RECOVERY_REQUIRED
    // M-AD-18: Non-terminal observe environmentRevision mismatch → RECOVERY_REQUIRED
    // M-AD-18 supplement: Non-terminal observe scheduleVersion mismatch → RECOVERY_REQUIRED
    //
    // Each test independently proves ONE verification leg — a single-leg checker would miss
    // the others' failure modes. Only the full four-leg conjunction + receipt-digest binding
    // satisfies all assertions simultaneously.
    //
    // # M-AD-16..18 稳定证据：耗尽伪摘要、意图/修订独立反面、恢复/幂等
    // ====================================================================================

    /** Builds an engine with a custom executor (for tamper tests). */
    private fun buildEngineWith(planId: Long, clock: VClock, executor: ExternalApplyExecutor): AutomationEngine {
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            executor, RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao())
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
            bufferGate = com.example.cellrebelauto.automation.plan.BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs, delayMs = clock.delayMs,
            attemptDriver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = minimalEvidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
    }

    // ---- M-AD-16: EXHAUSTED receipt digest mismatch → RECOVERY_REQUIRED ----

    @Test
    fun `M-AD-16 an EXHAUSTED receipt with a forged digest fail-closes the recovery`() = runTest {
        // An exhausted receipt (advancedToItemId == null) whose receiptDigest does not
        // recompute must be rejected BEFORE the terminal readback — the exhausted path
        // must NOT bypass receipt-digest verification (v1.46 defect).
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = 1, advancedFromItemId = anchorItemId, advancedToItemId = null, // EXHAUSTED
            scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-recovery",
            effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
        )
        val realExecutor = journeyExecutor
        val forgedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
                val receipt = realExecutor.completeAndAdvance(request, expectedIntentHash) ?: return null
                return receipt.copy(receiptDigest = "forged-exhausted-${receipt.receiptDigest}")
            }
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        buildEngineWith(planId, VClock(), forgedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "M-AD-16: an exhausted receipt whose digest does not recompute must fail-closed " +
                "(killing mutation: exhausted path skips digest check ⇒ proceeds to readback ⇒ succeeded)",
            "RECOVERY_REQUIRED", attempt.aplusState
        )
        assertEquals("never silently closed as succeeded", "running", attempt.status)
    }

    @Test
    fun `M-AD-16 supplement - an honest EXHAUSTED receipt with a matching readback closes trusted`() = runTest {
        // Control case: proves the M-AD-16 test is not vacuously true. An honest exhausted
        // receipt plus a fresh discover() readback that matches all four legs → succeeded.
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = 1, advancedFromItemId = anchorItemId, advancedToItemId = null, // EXHAUSTED
            scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-recovery",
            effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
        )
        val realExecutor = journeyExecutor
        val exhaustedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                realExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun discover(): CapabilitySnapshotV1? {
                // Aligned terminal-recovery control: provider and local plan are both complete, so
                // Issue #88 must still permit the idempotent replay that closes the crashed attempt.
                return CapabilitySnapshotV1(
                    serviceVersion = "fake-1.0",
                    supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
                    supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
                    continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                    environmentRevision = 7L,
                    profileRefs = listOf("p"), scheduleRefs = listOf("s"),
                    currentScheduleId = anchorScheduleId,
                    currentItemId = anchorItemId, // advanceReceipt.advancedFromItemId
                    scheduleVersion = anchorVersion + 1,
                    exhausted = true
                )
            }
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        val originalRequest = expectedAdvanceRequest()
        seedAdvanceEffect(originalRequest)
        buildEngineWith(planId, VClock(), exhaustedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("honest exhausted receipt + matching readback ⇒ CLOSED", "CLOSED", attempt.aplusState)
        assertEquals("closed trusted", "succeeded", attempt.status)
        assertEquals("one original terminal call plus one same-key recovery replay", 2, advanceInvocationCount)
        assertEquals("same-key recovery must not apply a second terminal provider effect", 1, advanceEffectCount)
        assertEquals(originalRequest.idempotencyKey, advanceReplays.single().idempotencyKey)
        assertEquals(originalRequest.requestDigest, advanceReplays.single().requestDigest)
        assertEquals(
            "aligned local/provider terminal recovery completes the session",
            "completed",
            db.runSessionDao().getLatest()!!.status
        )
        assertEquals(
            listOf(
                "ADVANCE_PENDING->ADVANCE_STATE_READBACK",
                "ADVANCE_STATE_READBACK->CLOSED"
            ),
            db.auditEventDao().forAttempt(31L)
                .filter { it.eventType in setOf(
                    AttemptEvent.ADVANCE_EXHAUSTED_VERIFIED.name,
                    AttemptEvent.EXHAUSTED_STATE_CONFIRMED.name
                ) }
                .map { it.payloadDigest }
        )
    }

    // ---- M-AD-17: Non-terminal observe intentHash mismatch → RECOVERY_REQUIRED ----

    @Test
    fun `M-AD-17 a non-terminal observe with intentHash mismatch fail-closes independently`() = runTest {
        // After a non-terminal advance, observe() returns an observation where
        // acceptedIntentHash ≠ receipt.effectiveIntentHash (but item/version/revision match).
        // Without this leg, wrong intent attribution goes undetected when the item matches.
        val realExecutor = journeyExecutor
        val tamperedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                realExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
                val honest = realExecutor.observe(leaseId, operationId, expectedIntentHash)
                // Tamper ONLY the intentHash leg — all other legs still match
                return honest?.copy(acceptedIntentHash = "wrong-intent-attribution")
            }
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        buildEngineWith(planId, VClock(), tamperedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "M-AD-17: intentHash mismatch must fail-closed (killing mutation: removed " +
                "intentHash leg from the conjunction ⇒ the other 3 legs pass ⇒ succeeded)",
            "RECOVERY_REQUIRED", attempt.aplusState
        )
        assertEquals("running", attempt.status)
        assertEquals(
            "ADVANCE_OBSERVING->RECOVERY_REQUIRED",
            db.auditEventDao().forAttempt(31L)
                .single { it.eventType == AttemptEvent.OBSERVED_TUPLE_MISMATCH.name }
                .payloadDigest
        )
    }

    // ---- M-AD-18: Non-terminal observe environmentRevision mismatch → RECOVERY_REQUIRED ----

    @Test
    fun `M-AD-18 a non-terminal observe with environmentRevision mismatch fail-closes independently`() = runTest {
        // observe().environmentRevision ≠ receipt.effectiveEnvironmentRevision (item, version,
        // and intentHash all match). Distinct from M-AD-17: single-leg readers miss each
        // other's failure mode. Only the full four-leg conjunction catches both.
        val realExecutor = journeyExecutor
        val tamperedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                realExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
                val honest = realExecutor.observe(leaseId, operationId, expectedIntentHash)
                // Tamper ONLY the environmentRevision leg
                return honest?.copy(environmentRevision = 999L)
            }
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        buildEngineWith(planId, VClock(), tamperedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "M-AD-18: revision mismatch must fail-closed (killing mutation: removed " +
                "revision leg from the conjunction ⇒ the other 3 legs pass ⇒ succeeded)",
            "RECOVERY_REQUIRED", attempt.aplusState
        )
        assertEquals("running", attempt.status)
    }

    @Test
    fun `M-AD-18 supplement - a non-terminal observe with scheduleVersion mismatch fail-closes independently`() = runTest {
        // observe().scheduleVersion ≠ receipt.scheduleVersionAfter. This is the fourth leg
        // (v1.68) that stops same-topology reinit from being silently accepted.
        val realExecutor = journeyExecutor
        val tamperedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                realExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
                val honest = realExecutor.observe(leaseId, operationId, expectedIntentHash)
                // Tamper ONLY the scheduleVersion leg
                return honest?.copy(scheduleVersion = 999L)
            }
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        buildEngineWith(planId, VClock(), tamperedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "M-AD-18 supplement: version mismatch must fail-closed (killing mutation: " +
                "removed version leg ⇒ 3 remaining legs pass ⇒ succeeded)",
            "RECOVERY_REQUIRED", attempt.aplusState
        )
        assertEquals("running", attempt.status)
    }
}
