package com.example.cellrebelauto.matrix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AttemptOutcome
import com.example.cellrebelauto.automation.CellRebelRunner
import com.example.cellrebelauto.automation.GpsLocationSetter
import com.example.cellrebelauto.automation.GpsOutcome
import com.example.cellrebelauto.automation.APlusComposition
import com.example.cellrebelauto.automation.AutomationEngine
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.DurableCompletionReceipt
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.example.cellrebelauto.recovery.testApplyIntent
import org.robolectric.RobolectricTestRunner

/**
 * Frozen §10.1 owner-red crash matrix entry (Issue #5, `matrix/CrashMatrixTest.kt`). Each `M_CR_NN()`
 * method maps to a §10 M-CR-xx row and drives the REAL recovery path.
 *
 * M-CR-03..06 are GREEN-body (re-preobserve / classify / post-observe / re-decide) — their recovery body is
 * not yet written, so these REDs assert the GREEN projection (attempt must NOT be collapsed to interrupted)
 * and genuinely FAIL pre-freeze. M-CR-07 (ledger truth → succeeded) and M-CR-08 (release replay) are banked.
 */
@RunWith(RobolectricTestRunner::class)
class CrashMatrixTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository
    private lateinit var lastCoordinator: RecoveryCoordinator

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

    // ---- minimal engine harness (mirrors EngineTrustedPathRedTest) ----

    private class FakeCellRebelRunner(private val outcome: AttemptOutcome) : CellRebelRunner {
        override suspend fun runTest(startedAt: Long, testTimeoutMs: Long, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome = outcome
    }

    private class FakeGpsSetter : GpsLocationSetter {
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome = GpsOutcome.Active
    }

    private class VirtualClock(var now: Long = 1000L) {
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> now += ms }
    }

    private class SeededObserve(private val facts: Map<Long, Boolean>) : ObserveIntentAcquirer {
        override fun matches(attemptId: Long): Boolean = facts[attemptId] ?: false
    }
    private class SeededRevision(private val facts: Map<String, Boolean>) : ReceiptRevisionAcquirer {
        override fun isFresh(idempotencyKey: String, now: Long): Boolean = facts[idempotencyKey] ?: false
    }
    private class SeededQuota(private val facts: Map<Long, Boolean>) : TrustedQuotaAcquirer {
        override fun hasCapacity(attemptId: Long): Boolean = facts[attemptId] ?: false
    }

    // ---- canonical §6.4-positive evidence (mirrors EngineTrustedPathRedTest / TrustedLedgerRedTest) ----

    private companion object {
        val WIRE_VERIFIED = com.example.cellrebelauto.model.execution.CellRebelCompletionEvidenceV1.VERIFIED_NEW_COMPLETION.wire // 1
        const val TARGET_LAT = 39.9
        const val TARGET_LNG = 116.4
        const val EXEC_STARTED_AT_ELAPSED = 2000L
        const val EXEC_RUNNING_CONFIRMED_AT_ELAPSED = 2100L
        const val EXEC_COMPLETED_AT_ELAPSED = 13000L
        const val PRE_OBSERVED_AT_ELAPSED = 1000L
        const val POST_OBSERVED_AT_ELAPSED = 14000L
        const val CONTINUITY_SINCE_ELAPSED = 500L
        const val REVISION = 7L
        const val FINGERPRINT = "fp-1"
        const val INTENT_HASH = "intent-h"
        const val LEASE_ID = "lease-77"
        const val RECOVERY_NOW = 15000L

        // The canonical durable execution evidence (§8.1 COMPLETION_OBSERVED carrier): FULL §7.1 detail +
        // wire=1 + legal monotonic window. `attemptId` is 0 as the SOURCE entity default; the durable seed
        // overwrites it to the crashed attempt id (matching the normal path AutomationEngine.kt:400).
        fun fullEvidenceExecution(execId: String, attemptId: Long, wire: Int, digest: String): CellRebelExecution = CellRebelExecution(
            executionId = execId,
            attemptId = 0L,
            completionEvidenceWire = wire,
            evidencePayloadDigest = digest,
            startedAt = 1000L,
            classifiedAt = 1100L,
            startedAtElapsed = EXEC_STARTED_AT_ELAPSED,
            runningConfirmedAtElapsed = EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
            completedAtElapsed = EXEC_COMPLETED_AT_ELAPSED,
            baselineRunningState = "IDLE",
            runningMarkerText = "RUNNING",
            runningDurationMs = EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
            webBrowsingScore = 8.0,
            videoStreamingScore = 7.0,
            roundTimestampsElapsed = "$EXEC_STARTED_AT_ELAPSED;$EXEC_COMPLETED_AT_ELAPSED"
        )

        /** A fully §6.4-valid pre observation (mirrors TrustedLedgerRedTest). */
        fun validPre(intentHash: String = INTENT_HASH, revision: Long = REVISION): ObservationSnapshot = ObservationSnapshot(
            leaseId = LEASE_ID,
            acceptedIntentHash = intentHash,
            coverage = "FULL",
            verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
            deliveryMode = "SYSTEM_MOCK",
            isMock = true,
            scheduleDecision = "ALLOWED_NOW",
            effectiveLat = TARGET_LAT,
            effectiveLng = TARGET_LNG,
            environmentRevision = revision,
            environmentFingerprint = FINGERPRINT,
            observedAtElapsedRealtimeMs = PRE_OBSERVED_AT_ELAPSED,
            observedAtEpochMs = 900L,
            continuitySinceElapsedRealtimeMs = CONTINUITY_SINCE_ELAPSED,
            evidenceRefs = listOf("qwy:store:abc")
        )

        /** A fully §6.4-valid post observation; revision/fingerprint/continuity match pre. */
        fun validPost(intentHash: String = INTENT_HASH, revision: Long = REVISION): ObservationSnapshot =
            validPre(intentHash, revision).copy(
                observedAtElapsedRealtimeMs = POST_OBSERVED_AT_ELAPSED,
                observedAtEpochMs = 6500L
            )
    }

    /**
     * Configurable evidence source for M-CR-06: returns seeded observations + completion evidence for
     * the crashed attempt, simulating DURABLE data persisted during the normal path (§8.1
     * PRE_OBSERVED/POST_OBSERVATION_OK/COMPLETION_OBSERVED). The GREEN recovery MUST read these from
     * durable storage — this fake is a stand-in for the GREEN durable observation store.
     */
    private class SeededEvidenceSource(
        private val pre: ObservationSnapshot? = null,
        private val post: ObservationSnapshot? = null,
        private val evidence: APlusCompletionEvidence? = null
    ) : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = pre
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? = post
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? = evidence
    }

    private class FakeBackend(
        private val exec: RecordingExternalApplyExecutor,
        private val log: FakeDurableRecoveryLog,
        override val evidenceSource: APlusEvidenceSource = SeededEvidenceSource()
    ) : APlusBackend {
        override val executor: ExternalApplyExecutor = exec
        override val recoveryLog: DurableRecoveryLog = log
        override val observeIntent: ObserveIntentAcquirer = SeededObserve(emptyMap())
        override val receiptRevision: ReceiptRevisionAcquirer = SeededRevision(emptyMap())
        override val trustedQuota: TrustedQuotaAcquirer = SeededQuota(emptyMap())
    }

    private fun buildEngine(
        planId: Long, clock: VirtualClock, backend: APlusBackend,
        commitClockOverride: (() -> Long)? = null
    ): AutomationEngine {
        val params = APlusComposition.engineAplusParams(backend)
        lastCoordinator = params.first
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = FakeCellRebelRunner(AttemptOutcome.Success(8.0, 7.0, 0L, 0L, 0L)),
            gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs,
            commitClockMs = commitClockOverride ?: clock.nowMs,
            delayMs = clock.delayMs,
            attemptDriver = null,
            recoveryCoordinator = params.first,
            completionEvidenceSource = params.second
        )
    }

    private suspend fun seedPlan(taskId: Long, requiredSuccesses: Int = 1): Long {
        val planId = db.planDao().insertPlan(LocationPlan(sourceFileName = "m.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = requiredSuccesses))
        db.planDao().insertTasks(listOf(LocationTask(id = taskId, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = requiredSuccesses)))
        return planId
    }

    private suspend fun seedAttempt(planId: Long, taskId: Long, attemptId: Long, aplusState: String?, aplusLeaseId: String? = null, currentExecutionId: String? = null, aplusAnchorScheduleId: String? = "qwy-default-schedule"): Long {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = aplusState, aplusLeaseId = aplusLeaseId,
                currentExecutionId = currentExecutionId,
                aplusAnchorScheduleId = aplusAnchorScheduleId
            )
        )
        return sessionId
    }

    private fun applyKey(attemptId: Long) = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    private fun releaseKey(attemptId: Long) = APlusOperationIdentity.releaseIdempotencyKey(attemptId)
    // The owner-state intent digest the recovery recomputes from the durable attempt identity.
    // R44 (Sol GREEN-review-3 F2): identical inputs to the engine's recompute — plan/task refs +
    // the seeded attempt's own validity window (startedAt=600 from seedAttempt, timeout=90s from buildEngine).
    private fun ownerIntentDigest(sessionId: Long, planId: Long, attemptId: Long = 77L) =
        APlusOperationIdentity.requestDigest(testApplyIntent(attemptId, sessionId, planId, "qwy-default-schedule", 600L, 90_000L))

    /**
     * Seed the DURABLE execution evidence (§8.1 COMPLETION_OBSERVED already persisted it) so the M-CR-06
     * crash boundary is reachable: evidence durable, only the TRUST_POLICY_PASS ledger tx missing. The
     * recovery re-decides from this durable owner — never from a live source returning stale pre/post.
     */
    private suspend fun seedDurableExecution(execId: String, attemptId: Long, wire: Int, digest: String) {
        db.attemptExecutionDao().insert(fullEvidenceExecution(execId, attemptId, wire, digest).copy(attemptId = attemptId))
    }

    /** Seed a durable observation record in the DB (R37: recovery reads from here, NOT from a live source). */
    private suspend fun seedDurableObservation(attemptId: Long, phase: String, snapshot: ObservationSnapshot) {
        db.durableObservationDao().insertIfAbsent(
            DurableObservationRecord(
                attemptId = attemptId, phase = phase,
                leaseId = snapshot.leaseId,
                acceptedIntentHash = snapshot.acceptedIntentHash,
                coverage = snapshot.coverage,
                verificationLevel = snapshot.verificationLevel,
                deliveryMode = snapshot.deliveryMode,
                isMock = snapshot.isMock,
                scheduleDecision = snapshot.scheduleDecision,
                effectiveLat = snapshot.effectiveLat,
                effectiveLng = snapshot.effectiveLng,
                environmentRevision = snapshot.environmentRevision,
                environmentFingerprint = snapshot.environmentFingerprint,
                observedAtElapsedRealtimeMs = snapshot.observedAtElapsedRealtimeMs,
                observedAtEpochMs = snapshot.observedAtEpochMs,
                continuitySinceElapsedRealtimeMs = snapshot.continuitySinceElapsedRealtimeMs,
                continuitySinceEpochMs = null,
                evidenceRefsJson = org.json.JSONArray(snapshot.evidenceRefs).toString(),
                evidenceRefs = snapshot.evidenceRefs.joinToString(";")
            )
        )
    }

    /** Seed a durable completion receipt in the DB (R37: recovery reads acceptedIntentHash from here). */
    private suspend fun seedDurableReceipt(attemptId: Long, wire: Int, intentHash: String, leaseId: String) {
        db.durableCompletionReceiptDao().insertIfAbsent(
            DurableCompletionReceipt(
                attemptId = attemptId, completionEvidenceWire = wire,
                acceptedIntentHash = intentHash, leaseId = leaseId
            )
        )
    }

    /**
     * Full M-CR-06 crash fixture: seeds plan/attempt with DECIDING state + currentExecutionId,
     * TWO executions (CURRENT first, DECOY second — order varies to defeat positional bypass),
     * durable observations, durable receipt, executor apply receipt.
     *
     * @param currentFirst true = insert current execution first (R38 default); false = decoy first.
     *        Varying order defeats .last()/.first() bypass.
     * @param ownerExecId which executionId the attempt's currentExecutionId points to.
     * @param preOverride/postOverride mutated observations for discriminator tests.
     * @param receiptIntentHash the acceptedIntentHash in the durable receipt (default = intentDigest).
     */
    private suspend fun seedMcr06Fixture(
        sessionId: Long,
        intentDigest: String,
        seededDigest: String,
        ownerExecId: String = "exec-current-77",
        currentFirst: Boolean = true,
        preOverride: ObservationSnapshot.() -> ObservationSnapshot = { this },
        postOverride: ObservationSnapshot.() -> ObservationSnapshot = { this },
        receiptIntentHash: String = intentDigest,
        execWire: Int = WIRE_VERIFIED,
        receiptWire: Int = WIRE_VERIFIED
    ) {
        val decoyDigest = "decoy-$seededDigest"
        if (currentFirst) {
            seedDurableExecution("exec-current-77", 77L, execWire, seededDigest)
            seedDurableExecution("exec-decoy-77", 77L, execWire, decoyDigest)
        } else {
            seedDurableExecution("exec-decoy-77", 77L, execWire, decoyDigest)
            seedDurableExecution("exec-current-77", 77L, execWire, seededDigest)
        }
        db.testAttemptDao().markCurrentExecutionId(77L, ownerExecId)
        seedDurableObservation(77L, "PRE", validPre(intentDigest).preOverride())
        seedDurableObservation(77L, "POST", validPost(intentDigest).postOverride())
        seedDurableReceipt(77L, receiptWire, receiptIntentHash, LEASE_ID)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
    }

    // ---- M-CR-03..06: recovery projections ----
    //
    // R43 (Sol GREEN-review P1-2): each mid-observation phase has BOTH polarities:
    //  - POSITIVE: the live source CAN re-acquire the phase evidence ⇒ the evidence is PERSISTED to
    //    the durable carrier AND the phase ADVANCES (never interrupted, never discarded).
    //  - NEGATIVE: the source cannot ⇒ UNTRUSTED typed failure, the release converges durably
    //    (a non-durable release = PAUSE, never silent advance), still never interrupted.

    private suspend fun seedPhaseCrash(
        phase: String,
        reacquirable: Boolean
    ): TestAttempt {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(planId, 42L, attemptId = 77L, aplusState = phase, aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        // Reacquirable source: returns a valid §6.4 observation/evidence; null source: cannot.
        val evidence = if (!reacquirable) SeededEvidenceSource() else SeededEvidenceSource(
            pre = validPre(), post = validPost(),
            evidence = APlusCompletionEvidence(
                execution = fullEvidenceExecution("exec-reacq-77", 77L, WIRE_VERIFIED, "reacq-digest"),
                completionEvidenceWire = WIRE_VERIFIED,
                applyReceiptIntentHash = "d",
                applyReceiptLease = "lease-77"
            )
        )
        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, evidence)).run()
        return db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
    }

    @Test
    fun `M_CR_03`() = runTest {
        val recovered = seedPhaseCrash("PRE_OBSERVED", reacquirable = false)
        // NEGATIVE: re-preobserve unavailable ⇒ UNTRUSTED typed failure — never interrupted.
        assertNotEquals("M-CR-03: a PRE_OBSERVED crash must re-preobserve, not be interrupted", "interrupted", recovered.status)
        assertEquals("M-CR-03 negative: re-preobserve failure is a typed UNTRUSTED failure", "failed", recovered.status)
        assertEquals("M-CR-03 negative: the reason is typed UNTRUSTED", "UNTRUSTED", recovered.failureReason)
    }

    @Test
    fun `M_CR_03_positive_continuation`() = runTest {
        val recovered = seedPhaseCrash("PRE_OBSERVED", reacquirable = true)
        // POSITIVE (Sol GREEN-review P1-2): the re-acquired pre-observation is PERSISTED durably and
        // the phase ADVANCES to CELLREBEL_START_PENDING — the continuation is observable, not discarded.
        assertNotEquals("M-CR-03 positive: the crash is never interrupted", "interrupted", recovered.status)
        assertEquals(
            "M-CR-03 positive: the phase must ADVANCE to CELLREBEL_START_PENDING (re-acquired evidence consumed)",
            "CELLREBEL_START_PENDING",
            recovered.aplusState
        )
        assertNotNull(
            "M-CR-03 positive: the re-acquired PRE observation must be persisted to the durable carrier",
            db.durableObservationDao().forAttemptPhase(77L, "PRE")
        )
    }

    @Test
    fun `M_CR_04`() = runTest {
        val recovered = seedPhaseCrash("CELLREBEL_START_PENDING", reacquirable = false)
        assertNotEquals("M-CR-04: a CELLREBEL_START_PENDING crash must classify, not be interrupted", "interrupted", recovered.status)
        assertEquals("M-CR-04 negative: classification failure is a typed UNTRUSTED failure", "failed", recovered.status)
    }

    @Test
    fun `M_CR_04_positive_continuation`() = runTest {
        val recovered = seedPhaseCrash("CELLREBEL_START_PENDING", reacquirable = true)
        // POSITIVE: re-classification persists the completion receipt durably; the attempt continues.
        assertNotEquals("M-CR-04 positive: the crash is never interrupted", "interrupted", recovered.status)
        assertNotNull(
            "M_CR-04 positive: the re-classified completion receipt must be persisted durably",
            db.durableCompletionReceiptDao().forAttempt(77L)
        )
    }

    @Test
    fun `M_CR_04_positive_execution_evidence - the recovery re-classification persists the owner-bound execution evidence row (production write chain, never hand-seeded)`() = runTest {
        // R44 (Sol GREEN-review-3 F3): a CELLREBEL_RUNNING crash must persist the §7.1 execution
        // evidence bound to the OWNER execution pointer — otherwise the later DECIDING re-decide
        // finds no owner row and the durable bundle can never complete.
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId, 42L, attemptId = 77L, aplusState = "CELLREBEL_RUNNING",
            aplusLeaseId = LEASE_ID, currentExecutionId = "exec-owner-77"
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val evidence = SeededEvidenceSource(
            evidence = APlusCompletionEvidence(
                execution = fullEvidenceExecution("exec-src-77", 77L, WIRE_VERIFIED, "src-digest"),
                completionEvidenceWire = WIRE_VERIFIED,
                applyReceiptIntentHash = "d",
                applyReceiptLease = LEASE_ID
            )
        )
        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, evidence)).run()

        assertNotNull(
            "the re-classified completion receipt must be durable",
            db.durableCompletionReceiptDao().forAttempt(77L)
        )
        // THE oracle: the execution evidence row is persisted BY THE RECOVERY PATH, bound to the
        // owner pointer (never the evidence source's own executionId). Killing mutations: dropping
        // the persist (row == null), or binding the source's executionId (owner lookup misses).
        val row = db.attemptExecutionDao().byExecutionId("exec-owner-77")
        assertNotNull("recovery must persist the §7.1 execution evidence bound to the owner pointer", row)
        row!!
        assertEquals(77L, row.attemptId)
        assertEquals(EXEC_STARTED_AT_ELAPSED, row.startedAtElapsed)
        assertEquals(EXEC_RUNNING_CONFIRMED_AT_ELAPSED, row.runningConfirmedAtElapsed)
        assertEquals(EXEC_COMPLETED_AT_ELAPSED, row.completedAtElapsed)
        val attempt = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertEquals("POST_OBSERVE_PENDING", attempt.aplusState)
        assertNotEquals("interrupted", attempt.status)
    }

    @Test
    fun `M_CR_full_chain - a CELLREBEL_RUNNING crash recovers through production-written carriers to a DECIDING re-decide mint`() = runTest {
        // R44 (Sol GREEN-review-3 F3): the strongest form — the durable bundle the DECIDING re-decide
        // consumes was WRITTEN BY the recovery production chain (run 1), never hand-seeded. Run 2 is
        // the process restart: POST re-observation → DECIDING → re-decide must PASS and mint.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(
            planId, 42L, attemptId = 77L, aplusState = "CELLREBEL_RUNNING",
            aplusLeaseId = LEASE_ID, currentExecutionId = "exec-owner-77"
        )
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedDurableObservation(77L, "PRE", validPre(intentDigest))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)

        // Run 1 (successor process): re-classify → persist receipt + execution evidence → advance.
        val evidence1 = SeededEvidenceSource(
            evidence = APlusCompletionEvidence(
                execution = fullEvidenceExecution("exec-src-77", 77L, WIRE_VERIFIED, "src-digest"),
                completionEvidenceWire = WIRE_VERIFIED,
                applyReceiptIntentHash = intentDigest,
                applyReceiptLease = LEASE_ID
            )
        )
        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, evidence1)).run()
        assertEquals(
            "POST_OBSERVE_PENDING",
            db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }.aplusState
        )

        // Run 2 (restart): POST_OBSERVE_PENDING re-observes POST, advances to DECIDING, re-decides
        // from the durable bundle — which now includes the run-1-written execution evidence row.
        val evidence2 = SeededEvidenceSource(post = validPost(intentDigest))
        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, evidence2)).run()

        assertNotNull(
            "the full recovery chain must mint (re-decide PASS over production-written carriers)",
            db.trustedQuotaDao().getByAttempt(77L)
        )
    }

    @Test
    fun `M_CR_05`() = runTest {
        val recovered = seedPhaseCrash("POST_OBSERVE_PENDING", reacquirable = false)
        assertNotEquals("M-CR-05: a POST_OBSERVE_PENDING crash must post-observe, not be interrupted", "interrupted", recovered.status)
        assertEquals("M-CR-05 negative: post-observe failure is a typed UNTRUSTED failure", "failed", recovered.status)
    }

    @Test
    fun `M_CR_05_positive_continuation`() = runTest {
        val recovered = seedPhaseCrash("POST_OBSERVE_PENDING", reacquirable = true)
        // POSITIVE: the re-acquired post-observation is persisted AND the phase advances into DECIDING
        // (the trust decision then runs over the durable bundle — here it lacks the full §8.6 carrier,
        // so the re-decide fail-closes to UNVERIFIED, which is the correct §8.1 outcome — the point
        // is the phase ADVANCED and the evidence was CONSUMED, not discarded).
        assertNotEquals("M-CR-05 positive: the crash is never interrupted", "interrupted", recovered.status)
        assertNotNull(
            "M-CR-05 positive: the re-acquired POST observation must be persisted to the durable carrier",
            db.durableObservationDao().forAttemptPhase(77L, "POST")
        )
        val finalRow = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertTrue(
            "M-CR-05 positive: the phase must leave POST_OBSERVE_PENDING (advanced into DECIDING → decided)",
            finalRow.aplusState == "DECIDING" || finalRow.aplusState == "UNVERIFIED_RECORDED" || finalRow.aplusState == "QUOTA_COMMITTED" || finalRow.aplusState == "CLOSED"
        )
    }

    @Test
    fun `M_CR_06`() = runTest {
        // M-CR-06 (R38): positive trust PASS. CURRENT execution seeded FIRST, DECOY second (reversed
        // from R37 to defeat .last() bypass). committedAt == RECOVERY_NOW (exact). KB-8 additionally
        // keeps the durable plan at (39.9, 116.4) while the provider observations report a valid,
        // independently verified Kyiv coordinate, proving recovery has no Auto-local distance gate.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedMcr06Fixture(
            sessionId,
            intentDigest,
            seededDigest,
            currentFirst = true,
            preOverride = { copy(effectiveLat = 50.4501, effectiveLng = 30.5234) },
            postOverride = { copy(effectiveLat = 50.4501, effectiveLng = 30.5234) }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("M-CR-06: re-decide must mint a trusted entry bound to the attempt", entry)
        assertEquals("M-CR-06: the mint must bind the correct task", 42L, entry!!.taskId)
        assertEquals("M-CR-06: the mint digest must derive from the CURRENT execution (owner lookup, not positional)", seededDigest, entry.evidenceDigest)
        assertEquals("M-CR-06: committedAt must be the exact recovery commit time (15000)", RECOVERY_NOW, entry.committedAt)
        assertEquals("M-CR-06: the re-decision must insert EXACTLY ONE ledger row", 1, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `M_CR_06_owner_mismatch`() = runTest {
        // R39 (Sol R38 P1-1): currentExecutionId points to DECOY → mint MUST appear and bind DECOY digest.
        // This is a POSITIVE test (mint occurs) that defeats positional bypasses. No if-null guard.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, ownerExecId = "exec-decoy-77", currentFirst = true)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("M-CR-06 owner mismatch: recovery MUST mint for a valid §6.4 completion even when owner points to a non-default execution (defeats refuse-all bypass)", entry)
        assertEquals("M-CR-06 owner mismatch: mint must bind the OWNER-pointed DECOY digest (not positional current)", "decoy-$seededDigest", entry!!.evidenceDigest)
    }

    @Test
    fun `M_CR_06_owner_mismatch_reversed`() = runTest {
        // R39 NEW: same as owner_mismatch but DECOY first, CURRENT second, owner → DECOY.
        // Cross both insertion-order AND owner identity to defeat .first()/.last() simultaneously.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, ownerExecId = "exec-decoy-77", currentFirst = false)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("M-CR-06 owner mismatch reversed: recovery MUST mint regardless of insertion order", entry)
        assertEquals("M-CR-06 owner mismatch reversed: mint must bind the OWNER-pointed DECOY digest (both orders)", "decoy-$seededDigest", entry!!.evidenceDigest)
    }

    @Test
    fun `M_CR_06_null`() = runTest {
        // Null polarity: absent evidence → zero mint. This stays GREEN on skeleton (no evidence = no mint).
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val intentDigest = ownerIntentDigest(sessionId, planId)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val applyOutcome = executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, applyOutcome.outcome, 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 null polarity: absent durable evidence must mint ZERO ledger rows", 0, db.trustedQuotaDao().countAll())
        val recovered = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertNotEquals("M-CR-06 null polarity: absent durable evidence must NOT project to succeeded", "succeeded", recovered.status)
    }

    // ---- §6.4 discriminator negatives: each asserts BOTH zero-mint AND UnverifiedAttemptRecord ----
    // R39: A refuse-all bypass passes zero-mint but FAILS UnverifiedAttemptRecord.
    // R40 (Sol R39): Asymmetric PRE-only and POST-only inversions for each field family.
    //   A bypass checking only PRE passes POST-only-violation but FAILS PRE-only-violation, and vice versa.
    //   Together with symmetric (both) inversions, every field must be independently validated in BOTH phases.

    private suspend fun assertDiscriminatorReject(
        label: String,
        violation: String,
        preOverride: ObservationSnapshot.() -> ObservationSnapshot = { this },
        postOverride: ObservationSnapshot.() -> ObservationSnapshot = { this },
        receiptIntentHash: String? = null,
        execWire: Int = WIRE_VERIFIED,
        receiptWire: Int = WIRE_VERIFIED
    ) {
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = preOverride, postOverride = postOverride,
            receiptIntentHash = receiptIntentHash ?: intentDigest,
            execWire = execWire, receiptWire = receiptWire
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("$label: $violation must mint ZERO trusted rows", 0, db.trustedQuotaDao().countAll())
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(77L)
        assertNotNull("$label: rejected completion MUST write an UnverifiedAttemptRecord (defeats refuse-all bypass)", unverified)
        assertEquals("$label: unverified reason must be typed UNTRUSTED", "UNTRUSTED", unverified!!.reason)
    }

    // ---- Symmetric inversions (both PRE + POST mutated) ----

    @Test fun `M_CR_06_discriminator_invalid`() = runTest {
        assertDiscriminatorReject("M-CR-06 intent discriminator", "INV-23 receipt intent-hash mismatch",
            receiptIntentHash = "DIVERGENT-hash")
    }

    @Test fun `M_CR_06_discriminator_observation`() = runTest {
        assertDiscriminatorReject("M-CR-06 revision discriminator", "pre.revision ≠ post.revision",
            preOverride = { copy(environmentRevision = 7L) },
            postOverride = { copy(environmentRevision = 99L) })
    }

    @Test fun `M_CR_06_discriminator_delivery`() = runTest {
        assertDiscriminatorReject("M-CR-06 deliveryMode discriminator", "deliveryMode=HOOK masquerading",
            preOverride = { copy(deliveryMode = "HOOK") },
            postOverride = { copy(deliveryMode = "HOOK") })
    }

    @Test fun `M_CR_06_discriminator_coverage`() = runTest {
        assertDiscriminatorReject("M-CR-06 coverage discriminator", "coverage=PARTIAL",
            preOverride = { copy(coverage = "PARTIAL") },
            postOverride = { copy(coverage = "PARTIAL") })
    }

    @Test fun `M_CR_06_discriminator_isMock`() = runTest {
        assertDiscriminatorReject("M-CR-06 isMock discriminator", "isMock=false",
            preOverride = { copy(isMock = false) },
            postOverride = { copy(isMock = false) })
    }

    @Test fun `M_CR_06_discriminator_verification`() = runTest {
        assertDiscriminatorReject("M-CR-06 verificationLevel discriminator", "verificationLevel=HOOK_VERIFIED",
            preOverride = { copy(verificationLevel = "HOOK_VERIFIED") },
            postOverride = { copy(verificationLevel = "HOOK_VERIFIED") })
    }

    @Test fun `M_CR_06_discriminator_schedule`() = runTest {
        assertDiscriminatorReject("M-CR-06 scheduleDecision discriminator", "scheduleDecision=DENIED",
            preOverride = { copy(scheduleDecision = "DENIED") },
            postOverride = { copy(scheduleDecision = "DENIED") })
    }

    @Test fun `M_CR_06_discriminator_lease`() = runTest {
        assertDiscriminatorReject("M-CR-06 lease discriminator", "observation lease ≠ receipt lease (INV-07)",
            preOverride = { copy(leaseId = "WRONG-LEASE") },
            postOverride = { copy(leaseId = "WRONG-LEASE") })
    }

    @Test fun `M_CR_06_discriminator_fingerprint`() = runTest {
        assertDiscriminatorReject("M-CR-06 fingerprint discriminator", "pre.fingerprint ≠ post.fingerprint",
            preOverride = { copy(environmentFingerprint = "fp-pre") },
            postOverride = { copy(environmentFingerprint = "fp-post") })
    }

    @Test fun `M_CR_06_discriminator_bracketing`() = runTest {
        assertDiscriminatorReject("M-CR-06 bracketing discriminator", "post.observedAt < execution.completedAt (monotonic window violated)",
            postOverride = { copy(observedAtElapsedRealtimeMs = 5000L) })
    }

    @Test fun `M_CR_06_discriminator_continuity`() = runTest {
        assertDiscriminatorReject("M-CR-06 continuity discriminator", "continuitySince mismatch (pre ≠ post)",
            preOverride = { copy(continuitySinceElapsedRealtimeMs = 500L) },
            postOverride = { copy(continuitySinceElapsedRealtimeMs = 9000L) })
    }

    @Test fun `M_CR_06_discriminator_coords`() = runTest {
        assertDiscriminatorReject("M-CR-06 coordinates discriminator", "latitude outside the geographic range",
            preOverride = { copy(effectiveLat = 91.0) },
            postOverride = { copy(effectiveLat = 91.0) })
    }

    @Test fun `M_CR_06_discriminator_null_coords`() = runTest {
        assertDiscriminatorReject("M-CR-06 null coordinates", "durable effective coordinates are absent",
            preOverride = { copy(effectiveLat = null, effectiveLng = null) },
            postOverride = { copy(effectiveLat = null, effectiveLng = null) })
    }

    @Test fun `M_CR_06_discriminator_pre_only_NaN`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only NaN", "PRE latitude is non-finite, POST canonical",
            preOverride = { copy(effectiveLat = Double.NaN) })
    }

    @Test fun `M_CR_06_discriminator_post_only_infinity`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only infinity", "POST longitude is non-finite, PRE canonical",
            postOverride = { copy(effectiveLng = Double.POSITIVE_INFINITY) })
    }

    @Test fun `M_CR_06_discriminator_evidence_refs`() = runTest {
        assertDiscriminatorReject("M-CR-06 evidenceRefs discriminator", "empty evidenceRefs",
            preOverride = { copy(evidenceRefs = emptyList()) },
            postOverride = { copy(evidenceRefs = emptyList()) })
    }

    // ---- Wire disagreement (Sol R39: execution.wire ≠ receipt.wire) ----

    @Test fun `M_CR_06_discriminator_wire_disagreement`() = runTest {
        assertDiscriminatorReject("M-CR-06 wire discriminator", "execution.wire=1 but receipt.wire=2 (disagreement)",
            receiptWire = 2)
    }

    // ---- Reverse wire disagreement (Sol R40 P1-1): execution.wire=2 / receipt.wire=1 ----
    //   A mutant using receipt.wire as sole authority passes the forward case (receipt.wire=2 → reject)
    //   but MINTS in the reverse case (receipt.wire=1 → accept). This probe kills that mutant.

    @Test fun `M_CR_06_discriminator_wire_reverse_disagreement`() = runTest {
        assertDiscriminatorReject("M-CR-06 wire reverse discriminator", "execution.wire=2 but receipt.wire=1 (reverse disagreement)",
            execWire = 2)
    }

    // ---- Asymmetric PRE-only inversions (R40: Sol R39 P1-1) ----
    // PRE is violated, POST stays canonical. A bypass checking only POST passes this but the
    // symmetric version catches it. A bypass checking only PRE fails here.
    // Together with POST-only, each field must be validated in BOTH phases independently.

    @Test fun `M_CR_06_discriminator_pre_only_delivery`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only deliveryMode", "PRE deliveryMode=HOOK, POST canonical",
            preOverride = { copy(deliveryMode = "HOOK") })
    }

    @Test fun `M_CR_06_discriminator_post_only_delivery`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only deliveryMode", "POST deliveryMode=HOOK, PRE canonical",
            postOverride = { copy(deliveryMode = "HOOK") })
    }

    @Test fun `M_CR_06_discriminator_pre_only_coverage`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only coverage", "PRE coverage=PARTIAL, POST canonical",
            preOverride = { copy(coverage = "PARTIAL") })
    }

    @Test fun `M_CR_06_discriminator_post_only_coverage`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only coverage", "POST coverage=PARTIAL, PRE canonical",
            postOverride = { copy(coverage = "PARTIAL") })
    }

    @Test fun `M_CR_06_discriminator_pre_only_isMock`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only isMock", "PRE isMock=false, POST canonical",
            preOverride = { copy(isMock = false) })
    }

    @Test fun `M_CR_06_discriminator_post_only_isMock`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only isMock", "POST isMock=false, PRE canonical",
            postOverride = { copy(isMock = false) })
    }

    @Test fun `M_CR_06_discriminator_pre_only_verification`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only verificationLevel", "PRE HOOK_VERIFIED, POST canonical",
            preOverride = { copy(verificationLevel = "HOOK_VERIFIED") })
    }

    @Test fun `M_CR_06_discriminator_post_only_verification`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only verificationLevel", "POST HOOK_VERIFIED, PRE canonical",
            postOverride = { copy(verificationLevel = "HOOK_VERIFIED") })
    }

    @Test fun `M_CR_06_discriminator_pre_only_schedule`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only scheduleDecision", "PRE DENIED, POST canonical",
            preOverride = { copy(scheduleDecision = "DENIED") })
    }

    @Test fun `M_CR_06_discriminator_post_only_schedule`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only scheduleDecision", "POST DENIED, PRE canonical",
            postOverride = { copy(scheduleDecision = "DENIED") })
    }

    @Test fun `M_CR_06_discriminator_pre_only_lease`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only lease binding", "PRE wrong lease, POST canonical",
            preOverride = { copy(leaseId = "WRONG-LEASE") })
    }

    @Test fun `M_CR_06_discriminator_post_only_lease`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only lease binding", "POST wrong lease, PRE canonical",
            postOverride = { copy(leaseId = "WRONG-LEASE") })
    }

    @Test fun `M_CR_06_discriminator_pre_only_coords`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only coordinates", "PRE latitude out of range, POST canonical",
            preOverride = { copy(effectiveLat = 91.0) })
    }

    @Test fun `M_CR_06_discriminator_post_only_coords`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only coordinates", "POST latitude out of range, PRE canonical",
            postOverride = { copy(effectiveLat = -91.0) })
    }

    @Test fun `M_CR_06_discriminator_pre_only_lng`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only longitude", "PRE longitude out of range, POST canonical",
            preOverride = { copy(effectiveLng = 181.0) })
    }

    @Test fun `M_CR_06_discriminator_post_only_lng`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only longitude", "POST longitude out of range, PRE canonical",
            postOverride = { copy(effectiveLng = -181.0) })
    }

    @Test fun `M_CR_06_discriminator_pre_only_evidence_refs`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only evidenceRefs", "PRE empty evidenceRefs, POST canonical",
            preOverride = { copy(evidenceRefs = emptyList()) })
    }

    @Test fun `M_CR_06_discriminator_post_only_evidence_refs`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only evidenceRefs", "POST empty evidenceRefs, PRE canonical",
            postOverride = { copy(evidenceRefs = emptyList()) })
    }

    @Test fun `M_CR_06_discriminator_pre_only_bracketing`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only bracketing", "PRE observedAt > execution.startedAt (violates pre < startedAt)",
            preOverride = { copy(observedAtElapsedRealtimeMs = 3000L) }) // 3000 > startedAtElapsed(2000)
    }

    @Test fun `M_CR_06_discriminator_pre_only_continuity_null`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only continuity null", "PRE continuitySince=null (§6.4.1 requires non-null)",
            preOverride = { copy(continuitySinceElapsedRealtimeMs = null) })
    }

    @Test fun `M_CR_06_discriminator_post_only_continuity_null`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only continuity null", "POST continuitySince=null (§6.4.1 requires non-null)",
            postOverride = { copy(continuitySinceElapsedRealtimeMs = null) })
    }

    // ---- Asymmetric observation acceptedIntentHash (Sol R40 P1-2): ----
    //   A mutant that discards durable PRE/POST acceptedIntentHash and copies receipt.acceptedIntentHash
    //   passes the receipt-only intent test (M_CR_06_discriminator_invalid) but MINTS when the observation
    //   hash diverges in only one phase. These two probes kill that mutant in both polarities.

    @Test fun `M_CR_06_discriminator_pre_only_intent_hash`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only intent hash", "PRE acceptedIntentHash ≠ receipt, POST canonical",
            preOverride = { copy(acceptedIntentHash = "DIVERGENT-pre-hash") })
    }

    @Test fun `M_CR_06_discriminator_post_only_intent_hash`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only intent hash", "POST acceptedIntentHash ≠ receipt, PRE canonical",
            postOverride = { copy(acceptedIntentHash = "DIVERGENT-post-hash") })
    }

    // ---- Production commit clock seam (Sol R41 P2) ----
    //   R41-2: the previous test was disconnected (only compared raw clock values, never observed
    //   engine wiring). This test constructs AutomationEngine with SEPARATE wall and commit clocks,
    //   runs the M-CR-06 positive path, and pins committedAt to the COMMIT clock — proving the seam
    //   is threaded independently through the engine to recordTrustedCompletion.
    //
    //   Killing mutations:
    //   - Remove commitClockMs from AutomationEngine constructor (revert to nowMs()): committedAt
    //     binds RECOVERY_NOW (wall), assertion fails (expected COMMIT_CLOCK).
    //   - Revert AutomationService injection to nowMs: the default commitClockMs = nowMs() in
    //     buildEngine, so this test with explicit commitClockMs still passes, but the M_CR_06 positive
    //     (which uses default) would bind wall not monotonic — the production seam is tested separately.

    @Test fun `M_CR_06_commit_clock_separates_from_wall_clock`() = runTest {
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, currentFirst = true)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)

        val COMMIT_CLOCK_VALUE = 99999L // deliberately different from RECOVERY_NOW (15000L)
        buildEngine(
            planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log),
            commitClockOverride = { COMMIT_CLOCK_VALUE }
        ).run()

        // The engine threaded commitClockMs to recordTrustedCompletion. When GREEN mints, committedAt
        // MUST bind COMMIT_CLOCK_VALUE (monotonic domain), NOT RECOVERY_NOW (wall domain).
        // The mint's PRESENCE is asserted unconditionally (Sol R42 P1-1): a regression that silently
        // stops minting must FAIL here, not pass by skipping the assertion.
        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull(
            "the re-decide must mint a trusted entry (absence of the row is a failure, never a pass — Sol R42 P1-1)",
            entry
        )
        assertEquals(
            "committedAt must bind the dedicated monotonic commit clock ($COMMIT_CLOCK_VALUE), not wall ($RECOVERY_NOW)",
            COMMIT_CLOCK_VALUE, entry!!.committedAt
        )
    }

    // ---- M-CR-07: ledger truth projects to succeeded through the engine recovery ----

    @Test
    fun `M_CR_07`() = runTest {
        val taskId = 42L
        // requiredSuccesses=2: one trusted entry (trustedCount=1) < required → quota NOT met →
        // skips the advance path entirely (no anchor needed). M_CR_07 tests trusted-entry projection
        // to succeeded, not the advance; the advance path is M_AD_14's domain.
        // Sol R3 P1-1 fix: anchor null + quota met = invariant break → RECOVERY_REQUIRED. Before
        // this fix, DECIDING was exempted; after, all phases are treated uniformly. Setting quota=2
        // avoids the anchor gate while preserving the test's core semantics.
        val planId = seedPlan(taskId = taskId, requiredSuccesses = 2)
        seedAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        val insertedId = db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L))
        val seededEntry = TrustedQuotaEntry(id = insertedId, attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        buildEngine(planId, clock, FakeBackend(executor, log)).run()

        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertEquals("M-CR-07: the committed ledger must project to succeeded", "succeeded", recovered.status)
        assertEquals("M-CR-07: no illegal reconcile for a DECIDING crash", 0, lastCoordinator.reconcileInvocationCount)
        assertEquals("no re-mint", 1, db.trustedQuotaDao().countAll())
        assertEquals("row preserved byte-for-byte", seededEntry, db.trustedQuotaDao().getByAttempt(77L))
        assertEquals("legacy-zero", 0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    // ---- M-CR-08: release replay after provider released but before receipt ----

    @Test
    fun `M_CR_08`() = runTest {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.release(attemptId = 1L, idempotencyKey = releaseKey(1L), leaseId = "lease-1", releaseDigest = "rd-1", now = 1000L)
        assertNull("M-CR-08: provider released but Auto has no durable receipt", log.releaseReceiptFor("lease-1"))

        val rc = com.example.cellrebelauto.recovery.RecoveryCoordinator(executor, log)
        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = releaseKey(1L), leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNotNull("the re-invoked release must record a durable receipt", receipt)
        assertEquals("release re-invoked (1 → 2)", 2, executor.releaseInvocationCount(releaseKey(1L)))
        assertEquals("release effect stays at one (at-most-once)", 1, executor.releaseEffectCount(1L))
    }
}
