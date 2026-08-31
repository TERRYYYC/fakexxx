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
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
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
    private var applyCalls = 0
    private var releaseCalls = 0
    private var advanceAnswer: AdvanceReceiptV1? = AdvanceReceiptV1(
        outcomeWire = 1, advancedFromItemId = "item-recovery-3b", advancedToItemId = "item-after-9z",
        scheduleVersionAfter = 13L, effectiveIntentHash = "eff-recovery",
        effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
    )

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome {
            applyCalls += 1
            return ApplyOutcome("APPLIED", false, "lease-$attemptId", operationId = "op-$attemptId")
        }
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome {
            releaseCalls += 1
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

    // Minimal non-null source: the ADVANCE_* recovery branch reads DURABLE state only (the mint
    // is committed); it never re-acquires evidence. Production always wires a real source.
    private val minimalEvidenceSource = object : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) = null
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) = null
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) = null
    }

    private class CountingNullEvidenceSource : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
        var preCalls = 0
        var postCalls = 0
        var completionCalls = 0

        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
            null.also { preCalls += 1 }

        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
            null.also { postCalls += 1 }

        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) =
            null.also { completionCalls += 1 }
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

    private enum class ReleaseProof { EXACT, MISSING, CONFLICTING }

    /** Seeds a plan/task plus a crashed attempt at [phase] with the full durable advance state:
     *  anchor triple, trusted mint, persisted lease, Room apply receipt (operationId leg), and the
     *  exact release receipt that MUST precede every ADVANCE_* checkpoint. */
    private suspend fun seedCrashedAt(
        phase: String,
        releaseProof: ReleaseProof = ReleaseProof.EXACT
    ): Pair<Long, Long> {
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
        if (releaseProof != ReleaseProof.MISSING) {
            val leaseId = "lease-$attemptId"
            db.releaseReceiptDao().insertIfAbsent(
                com.example.cellrebelauto.recovery.ReleaseReceiptRow(
                    idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
                        .releaseIdempotencyKey(attemptId),
                    leaseId = leaseId,
                    releaseDigest = if (releaseProof == ReleaseProof.EXACT) {
                        com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
                            .releaseDigest(leaseId)
                    } else {
                        "conflicting-release-digest"
                    },
                    resultOutcome = "RELEASED",
                    createdAt = 1001L
                )
            )
        }
        return planId to task.id
    }

    private suspend fun seedSingleTaskPlan(source: String): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = source,
                importedAt = 1000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1
            ),
            listOf(
                LocationTask(
                    planId = 0,
                    csvRow = 1,
                    longitude = 116.4,
                    latitude = 39.9,
                    priority = 1,
                    requiredSuccesses = 1
                )
            )
        )
        return planId to db.locationTaskDao().getTasksForPlan(planId).single().id
    }

    private fun installReleasePendingCrashTrigger(name: String) {
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER $name
            BEFORE UPDATE OF aplusState ON test_attempts
            WHEN NEW.aplusState = 'RELEASE_PENDING'
            BEGIN
                SELECT RAISE(ABORT, 'injected crash before RELEASE_PENDING owner commit');
            END
            """.trimIndent()
        )
    }

    private fun buildEngine(
        planId: Long,
        clock: VClock,
        evidenceSource: com.example.cellrebelauto.automation.aplus.APlusEvidenceSource = minimalEvidenceSource
    ): AutomationEngine {
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            journeyExecutor, RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao())
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
            attemptDriver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = evidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
    }

    @Test
    fun `an ADVANCE_PENDING crash replays the same durable request and closes trusted`() = runTest {
        val (planId, taskId) = seedCrashedAt("ADVANCE_PENDING")
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
    }

    @Test
    fun `an ADVANCE_OBSERVING crash resumes through the same replay and verification`() = runTest {
        val (planId, taskId) = seedCrashedAt("ADVANCE_OBSERVING")
        val clock = VClock()
        buildEngine(planId, clock).run()

        assertEquals("the observation-phase crash also replays + verifies (idempotent provider returns the stored receipt)", 1, advanceReplays.size)
        assertEquals("closed trusted", "succeeded", db.testAttemptDao().getAttemptById(31L)!!.status)
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

    private suspend fun assertAdvanceRecoveryRejectsReleaseProof(
        phase: String,
        releaseProof: ReleaseProof
    ) {
        val (planId, _) = seedCrashedAt(phase, releaseProof)

        buildEngine(planId, VClock()).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals(
            "$phase must prove release-first from the exact durable tuple before any advance replay",
            "RECOVERY_REQUIRED",
            attempt.aplusState
        )
        assertEquals(
            "$phase must retain the typed release-proof invariant failure",
            "RELEASED_RECEIPT_MISSING_OR_CONFLICT",
            attempt.failureReason
        )
        assertEquals("an unproven release checkpoint must remain non-terminal", "running", attempt.status)
        assertEquals("an unproven release checkpoint must not close", null, attempt.endedAt)
        assertEquals("release proof is a precondition: no advance may be dispatched", 0, advanceReplays.size)
        assertEquals("recovery must not fresh-apply while proving release-first", 0, applyCalls)
        assertEquals("recovery must not repair historical release proof by releasing again", 0, releaseCalls)
        assertEquals("the owner session must pause on provenance failure", "paused", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `ADVANCE_PENDING with a missing release receipt pauses before replay`() = runTest {
        assertAdvanceRecoveryRejectsReleaseProof("ADVANCE_PENDING", ReleaseProof.MISSING)
    }

    @Test
    fun `ADVANCE_PENDING with a conflicting release receipt pauses before replay`() = runTest {
        assertAdvanceRecoveryRejectsReleaseProof("ADVANCE_PENDING", ReleaseProof.CONFLICTING)
    }

    @Test
    fun `ADVANCE_OBSERVING with a missing release receipt pauses before replay`() = runTest {
        assertAdvanceRecoveryRejectsReleaseProof("ADVANCE_OBSERVING", ReleaseProof.MISSING)
    }

    @Test
    fun `ADVANCE_OBSERVING with a conflicting release receipt pauses before replay`() = runTest {
        assertAdvanceRecoveryRejectsReleaseProof("ADVANCE_OBSERVING", ReleaseProof.CONFLICTING)
    }

    @Test
    fun `ADVANCE_STATE_READBACK with a missing release receipt pauses before replay`() = runTest {
        assertAdvanceRecoveryRejectsReleaseProof("ADVANCE_STATE_READBACK", ReleaseProof.MISSING)
    }

    @Test
    fun `ADVANCE_STATE_READBACK with a conflicting release receipt pauses before replay`() = runTest {
        assertAdvanceRecoveryRejectsReleaseProof("ADVANCE_STATE_READBACK", ReleaseProof.CONFLICTING)
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
                override suspend fun runTest(startedAt: Long, testTimeoutMs: Long, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome =
                    AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 4300L)
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
    }

    @Test
    fun `normal UNTRUSTED failure survives a crash after RELEASED without a fresh apply`() = runTest {
        val (planId, taskId) = seedSingleTaskPlan("failure-continuation.csv")
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_normal_failure_close
            BEFORE UPDATE OF status ON test_attempts
            WHEN NEW.aplusState = 'CLOSED' AND NEW.status = 'failed'
            BEGIN
                SELECT RAISE(ABORT, 'injected crash between RELEASED and closeAplusFailure');
            END
            """.trimIndent()
        )

        // The null PRE from minimalEvidenceSource takes the real normal UNTRUSTED path. The trigger
        // aborts only the atomic terminal projection; release + its exact receipt remain committed.
        buildEngine(planId, VClock()).run()

        val crashed = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("the injected crash boundary must be the post-release checkpoint", "RELEASED", crashed.aplusState)
        assertEquals("the terminal transaction must have rolled back", "starting", crashed.status)
        assertEquals("the terminal transaction must have rolled back", null, crashed.endedAt)
        val durableContinuationReasonAtCrash = crashed.failureReason
        assertNotNull(
            "the exact release tuple must already be durable before terminal projection",
            db.releaseReceiptDao().byLease(crashed.aplusLeaseId!!)
        )
        assertEquals("one normal apply reached the crash boundary", 1, applyCalls)
        assertEquals("one normal release reached the crash boundary", 1, releaseCalls)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_normal_failure_close")

        // Process restart: recover the same owner. Its typed continuation is safety state, so the
        // engine must close it as UNTRUSTED and remain PAUSED — never reinterpret it as an ordinary
        // interrupted retry and never enter the main loop to dispatch a fresh apply.
        buildEngine(planId, VClock()).run()

        val attemptsAfterRestart = db.testAttemptDao().getAttemptsForTask(crashed.taskId)
        assertEquals("recovery must not mint a fresh attempt for a released typed failure", 1, attemptsAfterRestart.size)
        assertEquals(
            "the typed continuation must be durable before the terminal transaction starts",
            "UNTRUSTED",
            durableContinuationReasonAtCrash
        )
        val recovered = attemptsAfterRestart.single()
        assertEquals("the original owner must close atomically", "CLOSED", recovered.aplusState)
        assertEquals("the original failure remains a failure, never interrupted", "failed", recovered.status)
        assertEquals("the original typed continuation reason must survive restart", "UNTRUSTED", recovered.failureReason)
        assertEquals("restart must not fresh-apply", 1, applyCalls)
        assertEquals("restart must not release the historical lease twice", 1, releaseCalls)
        assertEquals("typed failure recovery remains fail-closed", "paused", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `normal typed failure crash before RELEASE_PENDING converges without evidence replay or fresh apply`() = runTest {
        val (planId, taskId) = seedSingleTaskPlan("normal-pre-release-continuation.csv")
        val source = CountingNullEvidenceSource()
        installReleasePendingCrashTrigger("abort_normal_release_pending_owner")

        // Null PRE enters the real normal UNTRUSTED branch. The trigger rejects the single
        // failureReason + RELEASE_PENDING publication statement, so both fields must roll back.
        buildEngine(planId, VClock(), source).run()

        val checkpoint = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("the owner phase update was the injected crash point", "ENV_APPLIED", checkpoint.aplusState)
        assertEquals(
            "typed continuation and RELEASE_PENDING are one atomic boundary",
            null,
            checkpoint.failureReason
        )
        assertEquals("the owner remains non-terminal at the crash point", "starting", checkpoint.status)
        assertEquals("release was not dispatched before its owner phase committed", 0, releaseCalls)
        assertEquals("normal execution acquired PRE exactly once", 1, source.preCalls)
        assertEquals("the original normal attempt applied once", 1, applyCalls)
        assertEquals(null, db.releaseReceiptDao().byLease(checkpoint.aplusLeaseId!!))

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_normal_release_pending_owner")

        // Compatibility fixture for a split row produced by the previous build. New production
        // writes cannot create it, but an upgrade must consume it before phase-shaped recovery.
        db.openHelper.writableDatabase.execSQL(
            "UPDATE test_attempts SET failureReason = 'UNTRUSTED' WHERE id = ${checkpoint.id}"
        )

        // Restart must consume the durable continuation before phase-shaped recovery. It may
        // release and close this owner, but it may not reacquire evidence, mint another attempt,
        // or return to the main loop for a fresh apply.
        buildEngine(planId, VClock(), source).run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("restart must converge the same attempt", 1, attempts.size)
        assertEquals("typed continuation bypasses PRE reacquisition", 1, source.preCalls)
        assertEquals("typed continuation never requests POST", 0, source.postCalls)
        assertEquals("typed continuation never requests completion evidence", 0, source.completionCalls)
        assertEquals("restart must not fresh-apply", 1, applyCalls)
        assertEquals("the historical lease is released exactly once", 1, releaseCalls)
        assertEquals("typed failure must never mint quota", 0, db.trustedQuotaDao().trustedCountForTask(taskId))
        val recovered = attempts.single()
        assertEquals("CLOSED", recovered.aplusState)
        assertEquals("failed", recovered.status)
        assertEquals("UNTRUSTED", recovered.failureReason)
        assertEquals("typed failure recovery remains paused", "paused", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `recovery typed failure crash before RELEASE_PENDING converges without reacquiring evidence`() = runTest {
        val (planId, taskId) = seedSingleTaskPlan("recovery-pre-release-continuation.csv")
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 500L, planId = planId, status = "running")
        )
        val attemptId = 71L
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId,
                taskId = taskId,
                runSessionId = sessionId,
                attemptOrdinal = 1,
                successOrdinal = null,
                startedAt = 600L,
                runningObservedAt = null,
                endedAt = null,
                status = "starting",
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 39.9,
                longitude = 116.4,
                aplusState = "ENV_APPLIED",
                aplusLeaseId = "lease-$attemptId"
            )
        )
        val source = CountingNullEvidenceSource()
        installReleasePendingCrashTrigger("abort_recovery_release_pending_owner")

        // ENV_APPLIED recovery classifies the unavailable PRE as UNTRUSTED. The trigger rejects
        // the atomic continuation + RELEASE_PENDING publication, so neither field may leak.
        buildEngine(planId, VClock(), source).run()

        val checkpoint = db.testAttemptDao().getAttemptById(attemptId)!!
        assertEquals("ENV_APPLIED", checkpoint.aplusState)
        assertEquals(
            "closeRecoveredAfterRelease must roll back both boundary fields together",
            null,
            checkpoint.failureReason
        )
        assertEquals("starting", checkpoint.status)
        assertEquals("the first recovery acquired PRE once to classify the continuation", 1, source.preCalls)
        assertEquals("release cannot run before RELEASE_PENDING commits", 0, releaseCalls)
        assertEquals("a recovery attempt must not call apply", 0, applyCalls)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER abort_recovery_release_pending_owner")
        db.openHelper.writableDatabase.execSQL(
            "UPDATE test_attempts SET failureReason = 'UNTRUSTED' WHERE id = $attemptId"
        )

        buildEngine(planId, VClock(), source).run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("second restart must converge the same recovery owner", 1, attempts.size)
        assertEquals("durable continuation prevents a second PRE acquisition", 1, source.preCalls)
        assertEquals("no POST evidence is needed", 0, source.postCalls)
        assertEquals("no completion evidence is needed", 0, source.completionCalls)
        assertEquals("recovery must never fall into a fresh normal apply", 0, applyCalls)
        assertEquals("the old lease is released exactly once", 1, releaseCalls)
        assertEquals("typed recovery failure must never mint quota", 0, db.trustedQuotaDao().trustedCountForTask(taskId))
        val recovered = attempts.single()
        assertEquals("CLOSED", recovered.aplusState)
        assertEquals("failed", recovered.status)
        assertEquals("UNTRUSTED", recovered.failureReason)
        assertEquals("paused", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `DECIDING anchor-missing pre-release failure stays sticky across two restarts`() = runTest {
        val (planId, taskId) = seedSingleTaskPlan("deciding-anchor-sticky.csv")
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 500L, planId = planId, status = "running")
        )
        val attemptId = 81L
        val anchorFailure = "ANCHOR_MISSING_QUOTA_MET:phase=DECIDING"
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId,
                taskId = taskId,
                runSessionId = sessionId,
                attemptOrdinal = 1,
                successOrdinal = null,
                startedAt = 600L,
                runningObservedAt = null,
                endedAt = null,
                status = "running",
                failureReason = anchorFailure,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 39.9,
                longitude = 116.4,
                aplusState = "RECOVERY_REQUIRED",
                aplusLeaseId = "lease-$attemptId"
            )
        )
        db.trustedQuotaDao().insert(
            com.example.cellrebelauto.model.ledger.TrustedQuotaEntry(
                attemptId = attemptId,
                taskId = taskId,
                evidenceDigest = "trusted-before-anchor-check",
                committedAt = 9000L
            )
        )
        val source = CountingNullEvidenceSource()

        buildEngine(planId, VClock(), source).run()
        val afterFirstRestart = db.testAttemptDao().getAttemptById(attemptId)!!
        val firstReason = afterFirstRestart.failureReason

        buildEngine(planId, VClock(), source).run()
        val afterSecondRestart = db.testAttemptDao().getAttemptById(attemptId)!!

        assertEquals("first restart must preserve the release-before anchor reason", anchorFailure, firstReason)
        assertEquals("second restart must preserve the same reason", anchorFailure, afterSecondRestart.failureReason)
        assertEquals("the invariant remains operator-owned and non-terminal", "RECOVERY_REQUIRED", afterSecondRestart.aplusState)
        assertEquals("running", afterSecondRestart.status)
        assertEquals(null, afterSecondRestart.endedAt)
        assertEquals("a pre-release invariant must never call release", 0, releaseCalls)
        assertEquals("a pre-release invariant must never call apply", 0, applyCalls)
        assertEquals("a pre-release invariant must never dispatch advance", 0, advanceReplays.size)
        assertEquals("a sticky invariant must not reacquire PRE", 0, source.preCalls)
        assertEquals("a sticky invariant must not reacquire POST", 0, source.postCalls)
        assertEquals("a sticky invariant must not reacquire completion", 0, source.completionCalls)
        assertEquals("no release receipt may be synthesized", null, db.releaseReceiptDao().byLease("lease-$attemptId"))
        val releaseAudit = db.auditEventDao().forAttempt(attemptId).filter {
            it.eventType == "BEGIN_RELEASE" || it.eventType == "RELEASE_RECEIPT"
        }
        assertTrue("no release provenance may be synthesized", releaseAudit.isEmpty())
        assertEquals("both restarts remain paused", "paused", db.runSessionDao().getLatest()!!.status)
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
                override suspend fun runTest(startedAt: Long, testTimeoutMs: Long, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome =
                    AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 4300L)
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
            outcomeWire = AdvanceOutcomeV1.EXHAUSTED.wire,
            advancedFromItemId = anchorItemId, advancedToItemId = null,
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
            outcomeWire = AdvanceOutcomeV1.EXHAUSTED.wire,
            advancedFromItemId = anchorItemId, advancedToItemId = null,
            scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-recovery",
            effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
        )
        val realExecutor = journeyExecutor
        val exhaustedExecutor = object : ExternalApplyExecutor by realExecutor {
            override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? =
                realExecutor.completeAndAdvance(request, expectedIntentHash)
            override fun discover(): CapabilitySnapshotV1? {
                // In the ADVANCE_PENDING recovery path, the ONLY discover() call IS the readback
                // (no prior capability check). Return the matching exhausted state directly.
                return CapabilitySnapshotV1(
                    serviceVersion = "fake-1.0",
                    supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
                    supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
                    continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                    environmentRevision = 7L,
                    profileRefs = listOf("p"), scheduleRefs = listOf("s"),
                    currentScheduleId = anchorScheduleId,
                    currentItemId = anchorItemId, // advanceReceipt.advancedFromItemId
                    scheduleVersion = anchorVersion + 1, // advanceReceipt.scheduleVersionAfter
                    exhausted = true
                )
            }
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")
        buildEngineWith(planId, VClock(), exhaustedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("honest exhausted receipt + matching readback ⇒ CLOSED", "CLOSED", attempt.aplusState)
        assertEquals("closed trusted", "succeeded", attempt.status)
    }

    @Test
    fun `ADVANCED without a target fails closed before exhausted readback can bless it`() = runTest {
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = AdvanceOutcomeV1.ADVANCED.wire,
            advancedFromItemId = anchorItemId,
            advancedToItemId = null,
            scheduleVersionAfter = anchorVersion + 1,
            effectiveIntentHash = "eff-recovery",
            effectiveEnvironmentRevision = 7L,
            receiptDigest = "filled-at-call"
        )
        val malformedExecutor = object : ExternalApplyExecutor by journeyExecutor {
            override fun discover(): CapabilitySnapshotV1 = CapabilitySnapshotV1(
                serviceVersion = "fake-1.0",
                supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
                supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
                continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                environmentRevision = 7L,
                profileRefs = listOf("p"),
                scheduleRefs = listOf("s"),
                currentScheduleId = anchorScheduleId,
                currentItemId = anchorItemId,
                scheduleVersion = anchorVersion + 1,
                exhausted = true
            )
        }
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")

        buildEngineWith(planId, VClock(), malformedExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals(
            "ADVANCE_OUTCOME_TARGET_MISMATCH:ADVANCED_WITHOUT_TARGET",
            attempt.failureReason
        )
        assertEquals("a contradictory receipt must never terminalize trusted", "running", attempt.status)
    }

    @Test
    fun `EXHAUSTED with a target fails closed before non-terminal observe can bless it`() = runTest {
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = AdvanceOutcomeV1.EXHAUSTED.wire,
            advancedFromItemId = anchorItemId,
            advancedToItemId = "item-after-9z",
            scheduleVersionAfter = anchorVersion + 1,
            effectiveIntentHash = "eff-recovery",
            effectiveEnvironmentRevision = 7L,
            receiptDigest = "filled-at-call"
        )
        val (planId, _) = seedCrashedAt("ADVANCE_PENDING")

        buildEngineWith(planId, VClock(), journeyExecutor).run()

        val attempt = db.testAttemptDao().getAttemptById(31L)!!
        assertEquals("RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals(
            "ADVANCE_OUTCOME_TARGET_MISMATCH:EXHAUSTED_WITH_TARGET",
            attempt.failureReason
        )
        assertEquals("a contradictory receipt must never terminalize trusted", "running", attempt.status)
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
