package com.example.cellrebelauto.matrix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.AttemptOutcome
import com.example.cellrebelauto.automation.CellRebelRunner
import com.example.cellrebelauto.automation.GpsLocationSetter
import com.example.cellrebelauto.automation.GpsOutcome
import com.example.cellrebelauto.automation.APlusComposition
import com.example.cellrebelauto.automation.AutomationEngine
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.audit.AutoAuditEvent
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
 * M-CR-03..06 cover recheck / classification / atomic POST decision bundle / durable re-decision.
 * A recovery invocation may resume the plan only after the crashed lease is released and its owner
 * is CLOSED; release failure remains RECOVERY_REQUIRED and blocks every fresh apply (INV-28).
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
        val preCalls = mutableListOf<Long>()
        val postCalls = mutableListOf<Long>()
        val completionCalls = mutableListOf<Long>()

        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? {
            preCalls += attemptId
            return pre
        }

        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? {
            postCalls += attemptId
            return post
        }

        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long): APlusCompletionEvidence? {
            completionCalls += attemptId
            return evidence
        }
    }

    /** Mirrors the production source's durable-first contract while exposing live-provider calls. */
    private class DurableFirstEvidenceSource(
        private val repo: PlanRepository,
        private val live: SeededEvidenceSource
    ) : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? =
            repo.getObservationSnapshot(attemptId, "PRE")
                ?: live.acquirePreObservation(attemptId, runSessionId)

        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long): ObservationSnapshot? =
            repo.getObservationSnapshot(attemptId, "POST")
                ?: live.acquirePostObservation(attemptId, runSessionId)

        override suspend fun acquireCompletionEvidence(
            attemptId: Long,
            runSessionId: Long
        ): APlusCompletionEvidence? = live.acquireCompletionEvidence(attemptId, runSessionId)
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
        commitClockOverride: (() -> Long)? = null,
        attemptDriver: APlusAttemptDriver? = null
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
            attemptDriver = attemptDriver,
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
    // Each mid-observation phase has BOTH polarities. Re-acquiring evidence is not by itself a
    // completed recovery: before the main loop may create another attempt, the crashed attempt's
    // lease must have a durable release receipt and the attempt must be terminal + CLOSED (INV-28).

    private data class PhaseCrashResult(
        val recovered: TestAttempt,
        val executor: RecordingExternalApplyExecutor,
        val log: FakeDurableRecoveryLog
    )

    private suspend fun runPhaseCrash(
        phase: String,
        reacquirable: Boolean
    ): PhaseCrashResult {
        val planId = seedPlan(taskId = 42L)
        val hasExecutionOwner = phase in setOf(
            "CELLREBEL_START_PENDING", "CELLREBEL_RUNNING", "POST_OBSERVE_PENDING"
        )
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = phase,
            aplusLeaseId = "lease-77",
            currentExecutionId = if (hasExecutionOwner) "exec-owner-77" else null
        )
        if (hasExecutionOwner) {
            seedDurableObservation(77L, "PRE", validPre("d"))
        }
        if (phase == "POST_OBSERVE_PENDING") {
            seedDurableExecution("exec-owner-77", 77L, WIRE_VERIFIED, "reacq-digest")
        }
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        // Reacquirable source: returns a valid §6.4 observation/evidence; null source: cannot.
        val evidence = if (!reacquirable) SeededEvidenceSource() else SeededEvidenceSource(
            pre = validPre("d"), post = validPost("d"),
            evidence = APlusCompletionEvidence(
                execution = fullEvidenceExecution("exec-reacq-77", 77L, WIRE_VERIFIED, "reacq-digest"),
                completionEvidenceWire = WIRE_VERIFIED,
                applyReceiptIntentHash = "d",
                applyReceiptLease = "lease-77"
            )
        )
        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, evidence)).run()
        val allAttempts = db.testAttemptDao().getAttemptsForTask(42L)
        return PhaseCrashResult(
            recovered = allAttempts.first { it.id == 77L },
            executor = executor,
            log = log
        )
    }

    private suspend fun seedPhaseCrash(phase: String, reacquirable: Boolean): TestAttempt =
        runPhaseCrash(phase, reacquirable).recovered

    @Test
    fun `ENV_APPLIED positive re-precheck releases and closes before another apply may start`() = runTest {
        val result = runPhaseCrash("ENV_APPLIED", reacquirable = true)
        val recovered = result.recovered

        assertNotNull(
            "the re-acquired PRE observation must be durable before recovery closes",
            db.durableObservationDao().forAttemptPhase(77L, "PRE")
        )
        assertEquals("the crashed lease must have one provider release effect", 1, result.executor.releaseEffectCount(77L))
        assertEquals("the release operation must be invoked once", 1, result.executor.releaseInvocationCount(releaseKey(77L)))
        assertNotNull("the crashed lease release must be durable", result.log.releaseReceiptFor(LEASE_ID))
        assertNotNull("the recovered attempt must be terminal", recovered.endedAt)
        assertEquals("the recovered owner must be CLOSED before the main loop resumes", "CLOSED", recovered.aplusState)
        val releaseIndex = result.executor.lifecycleEvents.indexOf("release:77:$LEASE_ID")
        val freshApplyIndex = result.executor.lifecycleEvents.indexOfFirst {
            it.startsWith("apply:") && it != "apply:77"
        }
        assertTrue("the old lease release must be observable", releaseIndex >= 0)
        assertTrue("the plan should retry through a fresh attempt after recovery", freshApplyIndex >= 0)
        assertTrue(
            "INV-28: old release must happen before the fresh attempt apply",
            releaseIndex < freshApplyIndex
        )
    }

    @Test
    fun `ENV_APPLIED crash with unavailable PRE becomes typed untrusted after durable release`() = runTest {
        val result = runPhaseCrash("ENV_APPLIED", reacquirable = false)
        val recovered = result.recovered

        assertEquals("missing PRE after an ENV_APPLIED crash is a typed failure", "failed", recovered.status)
        assertEquals("the failure must retain the trust reason", "UNTRUSTED", recovered.failureReason)
        assertNotEquals("the crash must never be blindly interrupted", "interrupted", recovered.status)
        assertEquals("the crashed lease must be released exactly once", 1, result.executor.releaseEffectCount(77L))
        assertEquals("the release operation must be invoked once", 1, result.executor.releaseInvocationCount(releaseKey(77L)))
        assertNotNull("the release receipt must be durable before terminalization", result.log.releaseReceiptFor(LEASE_ID))
        assertEquals("typed failure recovery must close the owner lifecycle", "CLOSED", recovered.aplusState)
        assertNotNull("typed failure recovery must be terminal", recovered.endedAt)
        assertNull(
            "missing PRE is an acquisition failure, not a completed trust decision; it must not fabricate an unverified carrier",
            db.unverifiedAttemptRecordDao().getByAttempt(77L)
        )
    }

    @Test
    fun `recovery audit records the real untrusted source state and release close edge`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "ENV_APPLIED",
            aplusLeaseId = LEASE_ID
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = "d",
            now = 1_000L
        )

        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource()),
            attemptDriver = APlusAttemptDriver(db.auditEventDao())
        ).run()

        val audit = db.auditEventDao().forAttempt(77L)
        assertEquals(
            "recovery must audit the actual ENV_APPLIED untrusted edge",
            "ENV_APPLIED->RELEASE_PENDING",
            audit.single { it.eventType == "OBSERVATION_UNTRUSTED" }.payloadDigest
        )
        assertEquals(
            "the durable release receipt closes from RELEASE_PENDING",
            "RELEASE_PENDING->CLOSED",
            audit.single { it.eventType == "RELEASE_RECEIPT" }.payloadDigest
        )
    }

    @Test
    fun `generic unverified recovery audits its real source release chain and durable receipt`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "UNVERIFIED_RECORDED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = "d",
            now = 1_000L
        )

        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource()),
            attemptDriver = APlusAttemptDriver(db.auditEventDao())
        ).run()

        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        val audit = db.auditEventDao().forAttempt(77L)
        assertEquals("failed", recovered.status)
        assertEquals("UNTRUSTED", recovered.failureReason)
        assertEquals("CLOSED", recovered.aplusState)
        assertNotNull("the exact old lease release must be durable", log.releaseReceiptFor(LEASE_ID))
        assertEquals(
            "generic recovery must expose the real durable decision carrier as the release source",
            "UNVERIFIED_RECORDED->RELEASE_PENDING",
            audit.single { it.eventType == "BEGIN_RELEASE" }.payloadDigest
        )
        assertEquals(
            "the release receipt must close only from RELEASE_PENDING",
            "RELEASE_PENDING->CLOSED",
            audit.single { it.eventType == "RELEASE_RECEIPT" }.payloadDigest
        )
    }

    @Test
    fun `RELEASED recovery verifies the exact durable receipt without replaying release audit`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "RELEASED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        db.auditEventDao().insert(
            AutoAuditEvent(
                seq = 1L,
                attemptId = 77L,
                correlationRef = null,
                eventType = "RELEASE_RECEIPT",
                payloadDigest = "RELEASE_PENDING->CLOSED",
                recordedAt = 900L
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val expectedReleaseDigest = APlusOperationIdentity.releaseDigest(LEASE_ID)
        log.seedReleaseReceipt(
            idempotencyKey = releaseKey(77L),
            leaseId = LEASE_ID,
            releaseDigest = expectedReleaseDigest,
            outcome = "RELEASED",
            createdAt = 900L
        )

        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource()),
            attemptDriver = APlusAttemptDriver(db.auditEventDao())
        ).run()

        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("the already-released owner must close from its durable checkpoint", "CLOSED", recovered.aplusState)
        assertEquals("the durable decision carrier still owns terminal projection", "failed", recovered.status)
        assertEquals("an exact durable receipt is verification, not a second provider release", 0, executor.releaseInvocationCount(releaseKey(77L)))
        assertEquals(
            "recovery must preserve the exact release tuple",
            com.example.cellrebelauto.recovery.RecordedReleaseReceipt(
                releaseKey(77L), LEASE_ID, expectedReleaseDigest, "RELEASED", 900L
            ),
            log.releaseReceiptFor(LEASE_ID)
        )
        val releaseAudits = db.auditEventDao().forAttempt(77L)
            .filter { it.eventType == "RELEASE_RECEIPT" }
        assertEquals(
            "RELEASED already means the release transition was audited; recovery must not append it again",
            1,
            releaseAudits.size
        )
        assertEquals("RELEASE_PENDING->CLOSED", releaseAudits.single().payloadDigest)
    }

    @Test
    fun `RELEASED recovery never rewrites the durable checkpoint as RELEASE_PENDING`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "RELEASED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(
            idempotencyKey = releaseKey(77L),
            leaseId = LEASE_ID,
            releaseDigest = APlusOperationIdentity.releaseDigest(LEASE_ID),
            outcome = "RELEASED",
            createdAt = 900L
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_released_checkpoint_regression
            BEFORE UPDATE OF aplusState ON test_attempts
            WHEN OLD.id = 77 AND OLD.aplusState = 'RELEASED' AND NEW.aplusState = 'RELEASE_PENDING'
            BEGIN
                SELECT RAISE(ABORT, 'RELEASED checkpoint regressed to RELEASE_PENDING');
            END
            """.trimIndent()
        )

        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource())
        ).run()

        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals(
            "recovery must validate RELEASED in place and then atomically project CLOSED",
            "CLOSED",
            recovered.aplusState
        )
        assertEquals("failed", recovered.status)
        assertNotNull("the terminal projection must complete", recovered.endedAt)
        assertEquals("checkpoint validation must not call release again", 0, executor.releaseInvocationCount(releaseKey(77L)))
    }

    @Test
    fun `RELEASED recovery with no durable receipt pauses as RECOVERY_REQUIRED`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "RELEASED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()

        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, SeededEvidenceSource())).run()

        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals(
            "RELEASED without its exact durable receipt is an invariant break",
            "RECOVERY_REQUIRED",
            recovered.aplusState
        )
        assertNull("an unproven RELEASED checkpoint must remain non-terminal", recovered.endedAt)
        assertEquals("the owner session must fail closed", "paused", db.runSessionDao().getLatest()!!.status)
        assertEquals(
            "recovery must not repair a missing RELEASED receipt by invoking the provider again",
            0,
            executor.releaseInvocationCount(releaseKey(77L))
        )
        assertNull("a missing historical receipt must not be fabricated", log.releaseReceiptFor(LEASE_ID))
    }

    @Test
    fun `RELEASED recovery with a conflicting durable receipt pauses as RECOVERY_REQUIRED`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "RELEASED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(
            idempotencyKey = releaseKey(77L),
            leaseId = LEASE_ID,
            releaseDigest = "conflicting-release-digest",
            outcome = "RELEASED",
            createdAt = 900L
        )

        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, SeededEvidenceSource())).run()

        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("a conflicting receipt must fail closed", "RECOVERY_REQUIRED", recovered.aplusState)
        assertNull("a conflicting receipt must never terminalize the owner", recovered.endedAt)
        assertEquals("the owner session must pause for operator action", "paused", db.runSessionDao().getLatest()!!.status)
        assertEquals("receipt conflict must be detected before any provider call", 0, executor.releaseInvocationCount(releaseKey(77L)))
        assertEquals(
            "the conflicting historical row remains immutable",
            "conflicting-release-digest",
            log.releaseReceiptFor(LEASE_ID)!!.releaseDigest
        )
    }

    @Test
    fun `RELEASED missing receipt remains fail closed across a second restart`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "RELEASED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val backend = FakeBackend(executor, log, SeededEvidenceSource())
        val driver = APlusAttemptDriver(db.auditEventDao())

        // Restart 1 detects that RELEASED has no exact historical proof and freezes the owner.
        buildEngine(planId, VirtualClock(), backend, attemptDriver = driver).run()
        val afterFirstRestart = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("RECOVERY_REQUIRED", afterFirstRestart.aplusState)
        assertEquals("RELEASED_RECEIPT_MISSING_OR_CONFLICT", afterFirstRestart.failureReason)
        val auditAfterFirstRestart = db.auditEventDao().forAttempt(77L)

        // Restart 2 must preserve that invariant failure. It may not reinterpret the sticky
        // RECOVERY_REQUIRED marker as an ordinary release retry and manufacture the missing proof.
        buildEngine(planId, VirtualClock(), backend, attemptDriver = driver).run()

        val afterSecondRestart = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("the historical invariant failure must stay sticky", "RECOVERY_REQUIRED", afterSecondRestart.aplusState)
        assertEquals(
            "the typed RELEASED-proof failure must survive subsequent restarts",
            "RELEASED_RECEIPT_MISSING_OR_CONFLICT",
            afterSecondRestart.failureReason
        )
        assertNull("the unproven owner must remain non-terminal", afterSecondRestart.endedAt)
        assertEquals("every restart must remain paused", "paused", db.runSessionDao().getLatest()!!.status)
        assertEquals(
            "a second restart must not route the sticky invariant through the generic release path",
            0,
            executor.releaseInvocationCount(releaseKey(77L))
        )
        assertNull("a missing historical receipt must never be fabricated", log.releaseReceiptFor(LEASE_ID))
        assertNull("the operation-key index must also remain empty", log.releaseReceiptForKey(releaseKey(77L)))
        assertEquals(
            "a second restart must not append RELEASE_PENDING or RELEASE_RECEIPT audit edges",
            auditAfterFirstRestart,
            db.auditEventDao().forAttempt(77L)
        )
    }

    @Test
    fun `RELEASED conflicting receipt remains fail closed across a second restart`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "RELEASED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        log.seedReleaseReceipt(
            idempotencyKey = releaseKey(77L),
            leaseId = LEASE_ID,
            releaseDigest = "conflicting-release-digest",
            outcome = "RELEASED",
            createdAt = 900L
        )
        val backend = FakeBackend(executor, log, SeededEvidenceSource())
        val driver = APlusAttemptDriver(db.auditEventDao())

        // Restart 1 detects the immutable conflict and freezes the owner without release effects.
        buildEngine(planId, VirtualClock(), backend, attemptDriver = driver).run()
        val afterFirstRestart = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("RECOVERY_REQUIRED", afterFirstRestart.aplusState)
        assertEquals("RELEASED_RECEIPT_MISSING_OR_CONFLICT", afterFirstRestart.failureReason)
        val auditAfterFirstRestart = db.auditEventDao().forAttempt(77L)

        // Restart 2 must not enter the generic release state machine. Even though coordinator
        // preflight blocks the provider call, entering that path would forge release audit edges.
        buildEngine(planId, VirtualClock(), backend, attemptDriver = driver).run()

        val afterSecondRestart = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("the conflicting historical proof must stay sticky", "RECOVERY_REQUIRED", afterSecondRestart.aplusState)
        assertEquals(
            "the typed RELEASED-proof failure must survive subsequent restarts",
            "RELEASED_RECEIPT_MISSING_OR_CONFLICT",
            afterSecondRestart.failureReason
        )
        assertNull("the conflicted owner must remain non-terminal", afterSecondRestart.endedAt)
        assertEquals("every restart must remain paused", "paused", db.runSessionDao().getLatest()!!.status)
        assertEquals("receipt conflict must stay provider-free", 0, executor.releaseInvocationCount(releaseKey(77L)))
        assertEquals(
            "the conflicting historical receipt must remain immutable",
            "conflicting-release-digest",
            log.releaseReceiptFor(LEASE_ID)!!.releaseDigest
        )
        assertEquals(
            "a second restart must not append synthetic RELEASE_PENDING or RELEASE_RECEIPT audit edges",
            auditAfterFirstRestart,
            db.auditEventDao().forAttempt(77L)
        )
    }

    @Test
    fun `generic recovery terminal projection rolls back when CLOSED persistence aborts`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "UNVERIFIED_RECORDED",
            aplusLeaseId = LEASE_ID
        )
        db.unverifiedAttemptRecordDao().insert(
            UnverifiedAttemptRecord(
                attemptId = 77L,
                reason = "UNTRUSTED",
                evidenceDigest = "unverified-owner-digest"
            )
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = "d",
            now = 1_000L
        )
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_recovery_closed_projection
            BEFORE UPDATE OF aplusState ON test_attempts
            WHEN OLD.id = 77 AND NEW.aplusState = 'CLOSED'
            BEGIN
                SELECT RAISE(ABORT, 'injected crash while persisting CLOSED');
            END
            """.trimIndent()
        )

        // AutomationEngine deliberately catches persistence exceptions and pauses. The durable row
        // after that catch is therefore the crash-boundary oracle.
        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource())
        ).run()

        assertNotNull("the provider release remains durably proven", log.releaseReceiptFor(LEASE_ID))
        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertNull(
            "CLOSED and terminal truth are one transaction: a failed CLOSED write must roll back endedAt",
            recovered.endedAt
        )
        assertEquals(
            "CLOSED and terminal truth are one transaction: a failed CLOSED write must roll back status",
            "starting",
            recovered.status
        )
        assertNotEquals(
            "the failed atomic close must never expose CLOSED independently",
            "CLOSED",
            recovered.aplusState
        )
    }

    @Test
    fun `normal untrusted projection rolls back CLOSED when terminal persistence aborts`() = runTest {
        val planId = seedPlan(taskId = 42L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val invalidPre = validPre("unused-for-structural-rejection").copy(effectiveLat = Double.NaN)
        db.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER abort_normal_terminal_projection
            BEFORE UPDATE OF status ON test_attempts
            WHEN NEW.aplusState = 'CLOSED' AND NEW.status = 'failed'
            BEGIN
                SELECT RAISE(ABORT, 'injected crash while persisting terminal failure');
            END
            """.trimIndent()
        )

        // AutomationEngine deliberately catches persistence exceptions and pauses. The durable row
        // after that catch is therefore the crash-boundary oracle.
        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource(pre = invalidPre))
        ).run()

        val attempt = db.testAttemptDao().getAttemptsForTask(42L).single()
        assertNotNull("the provider release remains durably proven", log.releaseReceiptFor(attempt.aplusLeaseId!!))
        assertNull(
            "CLOSED and terminal truth are one transaction: a failed terminal write must leave endedAt unset",
            attempt.endedAt
        )
        assertEquals(
            "CLOSED and terminal truth are one transaction: a failed terminal write must leave status nonterminal",
            "starting",
            attempt.status
        )
        assertNotEquals(
            "a failed terminal projection must roll back CLOSED instead of exposing CLOSED plus nonterminal",
            "CLOSED",
            attempt.aplusState
        )
    }

    @Test
    fun `recovery release failure pauses with the old owner unresolved and never starts a fresh apply`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "ENV_APPLIED",
            aplusLeaseId = LEASE_ID
        )
        val executor = RecordingExternalApplyExecutor(outcome = "FAILED")
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = "d",
            now = 1_000L
        )

        buildEngine(
            planId,
            VirtualClock(),
            FakeBackend(executor, log, SeededEvidenceSource(pre = validPre("d")))
        ).run()

        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("an unproven release must remain explicitly recoverable", "RECOVERY_REQUIRED", recovered.aplusState)
        assertNull("an unresolved lease must not be terminalized", recovered.endedAt)
        assertNull("a failed release must not fabricate a durable receipt", log.releaseReceiptFor(LEASE_ID))
        assertEquals(
            "the recovery must return before any fresh attempt apply",
            emptyList<String>(),
            executor.lifecycleEvents.filter { it.startsWith("apply:") && it != "apply:77" }
        )
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
    fun `M_CR_03 positive re-precheck releases and closes the crashed owner`() = runTest {
        val result = runPhaseCrash("PRE_OBSERVED", reacquirable = true)
        val recovered = result.recovered

        assertNotNull(
            "M-CR-03 positive: the re-acquired PRE observation must be persisted to the durable carrier",
            db.durableObservationDao().forAttemptPhase(77L, "PRE")
        )
        assertEquals("M-CR-03 positive: one release effect", 1, result.executor.releaseEffectCount(77L))
        assertEquals("M-CR-03 positive: one release invocation", 1, result.executor.releaseInvocationCount(releaseKey(77L)))
        assertNotNull("M-CR-03 positive: durable release receipt", result.log.releaseReceiptFor(LEASE_ID))
        assertNotNull("M-CR-03 positive: terminal attempt", recovered.endedAt)
        assertEquals("M-CR-03 positive: closed owner", "CLOSED", recovered.aplusState)
    }

    @Test
    fun `ENV_APPLIED with durable PRE replays storage without a live PRE observation`() = runTest {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(planId, 42L, attemptId = 77L, aplusState = "ENV_APPLIED", aplusLeaseId = LEASE_ID)
        seedDurableObservation(77L, "PRE", validPre())
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = "d",
            now = 1000L
        )
        val live = SeededEvidenceSource(pre = validPre())
        val source = DurableFirstEvidenceSource(repo, live)

        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, source)).run()

        assertEquals(
            "durable PRE must win; the provider must not be observed again for the crashed attempt",
            0,
            live.preCalls.count { it == 77L }
        )
        assertEquals("the replayed owner must still converge one release", 1, executor.releaseEffectCount(77L))
        assertEquals("the replayed owner must close", "CLOSED", db.testAttemptDao().getAttemptById(77L)!!.aplusState)
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
        seedDurableObservation(77L, "PRE", validPre("d"))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, intent = testApplyIntent(), idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val evidence = SeededEvidenceSource(
            post = validPost("d"),
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
        assertEquals("recovery must finish the decision and release in one invocation", "CLOSED", attempt.aplusState)
        assertNotNull("the recovered attempt must be terminal", attempt.endedAt)
    }

    @Test
    fun `M_CR_full_chain - a CELLREBEL_RUNNING crash recovers through production-written carriers to a DECIDING re-decide mint`() = runTest {
        // The durable bundle the DECIDING re-decide consumes is written by one recovery invocation,
        // never hand-seeded and never exposed as an incomplete intermediate owner state.
        val planId = seedPlan(taskId = 42L, requiredSuccesses = 2)
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

        val evidence = SeededEvidenceSource(
            post = validPost(intentDigest),
            evidence = APlusCompletionEvidence(
                execution = fullEvidenceExecution("exec-src-77", 77L, WIRE_VERIFIED, "src-digest"),
                completionEvidenceWire = WIRE_VERIFIED,
                applyReceiptIntentHash = intentDigest,
                applyReceiptLease = LEASE_ID
            )
        )
        buildEngine(planId, VirtualClock(), FakeBackend(executor, log, evidence)).run()

        assertNotNull(
            "the full recovery chain must mint (re-decide PASS over production-written carriers)",
            db.trustedQuotaDao().getByAttempt(77L)
        )
        assertEquals("the full recovery chain must release once", 1, executor.releaseEffectCount(77L))
        assertEquals("the full recovery chain must close", "CLOSED",
            db.testAttemptDao().getAttemptById(77L)!!.aplusState)
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
        // POSITIVE: POST + completion receipt become one durable decision bundle; recovery then
        // decides, releases, and closes before the plan loop may resume.
        assertNotEquals("M-CR-05 positive: the crash is never interrupted", "interrupted", recovered.status)
        assertNotNull(
            "M-CR-05 positive: the re-acquired POST observation must be persisted to the durable carrier",
            db.durableObservationDao().forAttemptPhase(77L, "POST")
        )
        val finalRow = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertEquals("M-CR-05 positive: recovery must not return an intermediate state", "CLOSED", finalRow.aplusState)
        assertNotNull("M-CR-05 positive: the attempt is terminal", finalRow.endedAt)
    }

    @Test
    fun `POST_OBSERVE_PENDING acquires a complete decision bundle before deciding then releases and closes`() = runTest {
        val planId = seedPlan(taskId = 42L, requiredSuccesses = 2)
        val sessionId = seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "POST_OBSERVE_PENDING",
            aplusLeaseId = LEASE_ID,
            currentExecutionId = "exec-owner-77"
        )
        val intentDigest = ownerIntentDigest(sessionId, planId)
        val executionDigest = "post-recovery-owner-digest"
        seedDurableObservation(77L, "PRE", validPre(intentDigest))
        seedDurableExecution("exec-owner-77", 77L, WIRE_VERIFIED, executionDigest)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = intentDigest,
            now = 1000L
        )
        val evidence = SeededEvidenceSource(
            post = validPost(intentDigest),
            evidence = APlusCompletionEvidence(
                execution = fullEvidenceExecution(
                    "exec-owner-77",
                    77L,
                    WIRE_VERIFIED,
                    executionDigest
                ).copy(attemptId = 77L),
                completionEvidenceWire = WIRE_VERIFIED,
                applyReceiptIntentHash = intentDigest,
                applyReceiptLease = LEASE_ID
            )
        )

        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log, evidence)).run()

        assertEquals("POST must be re-acquired once for the crashed owner", 1, evidence.postCalls.count { it == 77L })
        assertEquals(
            "completion evidence is part of the same decision bundle and must be acquired before DECIDING",
            1,
            evidence.completionCalls.count { it == 77L }
        )
        assertNotNull("the recovered POST carrier must be durable", db.durableObservationDao().forAttemptPhase(77L, "POST"))
        assertNotNull("the completion receipt must be durable before DECIDING", db.durableCompletionReceiptDao().forAttempt(77L))
        val trusted = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("a complete durable bundle must be re-decided", trusted)
        assertEquals("the re-decision must bind the owner execution", executionDigest, trusted!!.evidenceDigest)
        assertEquals("the crashed lease must be released once after the decision", 1, executor.releaseEffectCount(77L))
        assertNotNull("the release receipt must be durable", log.releaseReceiptFor(LEASE_ID))
        val recovered = db.testAttemptDao().getAttemptById(77L)!!
        assertNotNull("the recovered attempt must be terminal", recovered.endedAt)
        assertEquals("the recovered attempt must close before a later apply", "CLOSED", recovered.aplusState)
    }

    @Test
    fun `POST_OBSERVE_PENDING without completion evidence never exposes an incomplete DECIDING state`() = runTest {
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(
            planId,
            42L,
            attemptId = 77L,
            aplusState = "POST_OBSERVE_PENDING",
            aplusLeaseId = LEASE_ID,
            currentExecutionId = "exec-owner-77"
        )
        val intentDigest = ownerIntentDigest(sessionId, planId)
        seedDurableObservation(77L, "PRE", validPre(intentDigest))
        seedDurableExecution("exec-owner-77", 77L, WIRE_VERIFIED, "missing-completion-digest")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(
            attemptId = 77L,
            intent = testApplyIntent(),
            idempotencyKey = applyKey(77L),
            requestDigest = intentDigest,
            now = 1000L
        )
        val evidence = SeededEvidenceSource(post = validPost(intentDigest), evidence = null)

        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log, evidence)).run()

        assertEquals("POST acquisition is attempted once", 1, evidence.postCalls.count { it == 77L })
        assertEquals(
            "the completion leg must be checked before the phase may enter DECIDING",
            1,
            evidence.completionCalls.count { it == 77L }
        )
        assertNull("no completion source means no durable completion receipt", db.durableCompletionReceiptDao().forAttempt(77L))
        assertNotEquals(
            "DECIDING implies a complete durable bundle; a missing receipt must keep the owner out of DECIDING",
            "DECIDING",
            db.testAttemptDao().getAttemptById(77L)!!.aplusState
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
