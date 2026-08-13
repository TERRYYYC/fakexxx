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
        override suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot? = pre
        override suspend fun acquirePostObservation(attemptId: Long): ObservationSnapshot? = post
        override suspend fun acquireCompletionEvidence(attemptId: Long): APlusCompletionEvidence? = evidence
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

    private fun buildEngine(planId: Long, clock: VirtualClock, backend: APlusBackend): AutomationEngine {
        val params = APlusComposition.engineAplusParams(backend)
        lastCoordinator = params.first
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = FakeCellRebelRunner(AttemptOutcome.Success(8.0, 7.0, 0L, 0L, 0L)),
            gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs, delayMs = clock.delayMs,
            attemptDriver = null,
            recoveryCoordinator = params.first,
            completionEvidenceSource = params.second
        )
    }

    private suspend fun seedPlan(taskId: Long): Long {
        val planId = db.planDao().insertPlan(LocationPlan(sourceFileName = "m.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1))
        db.planDao().insertTasks(listOf(LocationTask(id = taskId, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1)))
        return planId
    }

    private suspend fun seedAttempt(planId: Long, taskId: Long, attemptId: Long, aplusState: String?, aplusLeaseId: String? = null, currentExecutionId: String? = null): Long {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = aplusState, aplusLeaseId = aplusLeaseId,
                currentExecutionId = currentExecutionId
            )
        )
        return sessionId
    }

    private fun applyKey(attemptId: Long) = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    private fun releaseKey(attemptId: Long) = APlusOperationIdentity.releaseIdempotencyKey(attemptId)
    // The owner-state intent digest the recovery recomputes from the durable attempt coords + id + session.
    private fun ownerIntentDigest(sessionId: Long, attemptId: Long = 77L) =
        APlusOperationIdentity.requestDigest(TARGET_LAT, TARGET_LNG, attemptId, sessionId)

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
        db.durableObservationDao().insert(
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
                evidenceRefsJson = snapshot.evidenceRefs.joinToString(";"),
                evidenceRefs = snapshot.evidenceRefs.joinToString(";")
            )
        )
    }

    /** Seed a durable completion receipt in the DB (R37: recovery reads acceptedIntentHash from here). */
    private suspend fun seedDurableReceipt(attemptId: Long, wire: Int, intentHash: String, leaseId: String) {
        db.durableCompletionReceiptDao().insert(
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
        receiptIntentHash: String = intentDigest
    ) {
        val decoyDigest = "decoy-$seededDigest"
        if (currentFirst) {
            seedDurableExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest)
            seedDurableExecution("exec-decoy-77", 77L, WIRE_VERIFIED, decoyDigest)
        } else {
            seedDurableExecution("exec-decoy-77", 77L, WIRE_VERIFIED, decoyDigest)
            seedDurableExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest)
        }
        db.testAttemptDao().markCurrentExecutionId(77L, ownerExecId)
        seedDurableObservation(77L, "PRE", validPre(intentDigest).preOverride())
        seedDurableObservation(77L, "POST", validPost(intentDigest).postOverride())
        seedDurableReceipt(77L, WIRE_VERIFIED, receiptIntentHash, LEASE_ID)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
    }

    // ---- M-CR-03..06: GREEN-body recovery projections (genuinely RED pre-freeze) ----

    private suspend fun seedObservePhaseCrash(phase: String): TestAttempt {
        val planId = seedPlan(taskId = 42L)
        seedAttempt(planId, 42L, attemptId = 77L, aplusState = phase, aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        buildEngine(planId, clock, FakeBackend(executor, log)).run()
        return db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
    }

    @Test
    fun `M_CR_03`() = runTest {
        val recovered = seedObservePhaseCrash("PRE_OBSERVED")
        // GREEN: re-preobserve the environment, never collapse an observed crash to interrupted.
        assertNotEquals("M-CR-03: a PRE_OBSERVED crash must re-preobserve, not be interrupted", "interrupted", recovered.status)
    }

    @Test
    fun `M_CR_04`() = runTest {
        val recovered = seedObservePhaseCrash("CELLREBEL_START_PENDING")
        assertNotEquals("M-CR-04: a CELLREBEL_START_PENDING crash must classify, not be interrupted", "interrupted", recovered.status)
    }

    @Test
    fun `M_CR_05`() = runTest {
        val recovered = seedObservePhaseCrash("POST_OBSERVE_PENDING")
        assertNotEquals("M-CR-05: a POST_OBSERVE_PENDING crash must post-observe, not be interrupted", "interrupted", recovered.status)
    }

    @Test
    fun `M_CR_06`() = runTest {
        // M-CR-06 (R38): positive trust PASS. CURRENT execution seeded FIRST, DECOY second (reversed
        // from R37 to defeat .last() bypass). committedAt == RECOVERY_NOW (exact).
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, currentFirst = true)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
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
        // R38 NEW (Sol R37 P1-2): currentExecutionId points to the DECOY, not "exec-current-77".
        // Recovery MUST read the owner and bind the DECOY digest (not the current by position).
        // This defeats both .first() and .last() positional bypasses.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        // Owner points to DECOY. A bypass using .first() or .last() picks the WRONG execution.
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, ownerExecId = "exec-decoy-77", currentFirst = true)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        if (entry != null) {
            assertEquals("M-CR-06 owner mismatch: mint must bind the OWNER-pointed DECOY digest (not positional current)", "decoy-$seededDigest", entry.evidenceDigest)
        }
    }

    @Test
    fun `M_CR_06_null`() = runTest {
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val intentDigest = ownerIntentDigest(sessionId)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val applyOutcome = executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, applyOutcome.outcome, 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 null polarity: absent durable evidence must mint ZERO ledger rows", 0, db.trustedQuotaDao().countAll())
        val recovered = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertNotEquals("M-CR-06 null polarity: absent durable evidence must NOT project to succeeded", "succeeded", recovered.status)
    }

    @Test
    fun `M_CR_06_discriminator_invalid`() = runTest {
        // INV-23 intent hash mismatch in durable receipt.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, receiptIntentHash = "DIVERGENT-hash")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 intent discriminator: intent-hash-mismatch must mint ZERO trusted rows", 0, db.trustedQuotaDao().countAll())
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(77L)
        assertNotNull("M-CR-06 intent discriminator: rejected completion must write UnverifiedAttemptRecord", unverified)
    }

    @Test
    fun `M_CR_06_discriminator_observation`() = runTest {
        // §6.4 revision mismatch: pre.revision=7 ≠ post.revision=99.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(environmentRevision = 7L) },
            postOverride = { copy(environmentRevision = 99L) }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 revision discriminator: revision-mismatch must mint ZERO trusted rows", 0, db.trustedQuotaDao().countAll())
        assertNotNull("M-CR-06 revision discriminator: must write UnverifiedAttemptRecord", db.unverifiedAttemptRecordDao().getByAttempt(77L))
    }

    @Test
    fun `M_CR_06_discriminator_delivery`() = runTest {
        // deliveryMode="HOOK" (§6.4.1 HOOK masquerading).
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(deliveryMode = "HOOK") },
            postOverride = { copy(deliveryMode = "HOOK") }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 deliveryMode discriminator: HOOK masquerading must mint ZERO", 0, db.trustedQuotaDao().countAll())
        assertNotNull("M-CR-06 deliveryMode discriminator: must write UnverifiedAttemptRecord", db.unverifiedAttemptRecordDao().getByAttempt(77L))
    }

    @Test
    fun `M_CR_06_discriminator_coverage`() = runTest {
        // R38 NEW: coverage="PARTIAL" instead of "FULL" (§6.4.1). A mutation special-casing
        // intent + revision + deliveryMode does NOT check coverage → mints → FAILS.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(coverage = "PARTIAL") },
            postOverride = { copy(coverage = "PARTIAL") }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 coverage discriminator: PARTIAL coverage must mint ZERO", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `M_CR_06_discriminator_isMock`() = runTest {
        // R38 NEW: isMock=false (§6.4.1). A mutation checking intent + revision + deliveryMode + coverage
        // still doesn't check isMock → mints → FAILS.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(isMock = false) },
            postOverride = { copy(isMock = false) }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 isMock discriminator: isMock=false must mint ZERO", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `M_CR_06_discriminator_verification`() = runTest {
        // R38 NEW: verificationLevel="HOOK_VERIFIED" instead of SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(verificationLevel = "HOOK_VERIFIED") },
            postOverride = { copy(verificationLevel = "HOOK_VERIFIED") }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 verificationLevel discriminator: HOOK_VERIFIED must mint ZERO", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `M_CR_06_discriminator_schedule`() = runTest {
        // R38 NEW: scheduleDecision="DENIED" instead of "ALLOWED_NOW" (§6.4.1).
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(scheduleDecision = "DENIED") },
            postOverride = { copy(scheduleDecision = "DENIED") }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 scheduleDecision discriminator: DENIED must mint ZERO", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `M_CR_06_discriminator_lease`() = runTest {
        // R38 NEW (Sol R37 P1-1): pre.leaseId="WRONG-LEASE" ≠ receipt.leaseId (INV-07).
        // A mutation checking observation fields but not receipt-lease binding passes this → FAILS.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = { copy(leaseId = "WRONG-LEASE") },
            postOverride = { copy(leaseId = "WRONG-LEASE") }
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        buildEngine(planId, VirtualClock(now = RECOVERY_NOW), FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 lease discriminator: observation lease ≠ receipt lease must mint ZERO (INV-07)", 0, db.trustedQuotaDao().countAll())
    }

    // ---- M-CR-07: ledger truth projects to succeeded through the engine recovery ----

    @Test
    fun `M_CR_07`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId)
        seedAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        val insertedId = db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L))
        val seededEntry = TrustedQuotaEntry(id = insertedId, attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
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
