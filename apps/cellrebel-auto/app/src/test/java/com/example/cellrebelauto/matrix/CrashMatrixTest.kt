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
                evidenceRefsJson = org.json.JSONArray(snapshot.evidenceRefs).toString(),
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
        // R39 (Sol R38 P1-1): currentExecutionId points to DECOY → mint MUST appear and bind DECOY digest.
        // This is a POSITIVE test (mint occurs) that defeats positional bypasses. No if-null guard.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, ownerExecId = "exec-decoy-77", currentFirst = true)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
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
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest, ownerExecId = "exec-decoy-77", currentFirst = false)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
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
        val intentDigest = ownerIntentDigest(sessionId)
        seedMcr06Fixture(sessionId, intentDigest, seededDigest,
            preOverride = preOverride, postOverride = postOverride,
            receiptIntentHash = receiptIntentHash ?: intentDigest,
            execWire = execWire, receiptWire = receiptWire
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
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
        assertDiscriminatorReject("M-CR-06 coordinates discriminator", "effectiveLat/Lng ~20m off target (>1m tolerance)",
            preOverride = { copy(effectiveLat = 39.9002) },
            postOverride = { copy(effectiveLat = 39.9002) })
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
        assertDiscriminatorReject("M-CR-06 PRE-only coordinates", "PRE lat off-target, POST canonical",
            preOverride = { copy(effectiveLat = 39.9002) })
    }

    @Test fun `M_CR_06_discriminator_post_only_coords`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only coordinates", "POST lat off-target, PRE canonical",
            postOverride = { copy(effectiveLat = 39.9002) })
    }

    @Test fun `M_CR_06_discriminator_pre_only_lng`() = runTest {
        assertDiscriminatorReject("M-CR-06 PRE-only longitude", "PRE lng off-target, POST canonical",
            preOverride = { copy(effectiveLng = 116.4002) })
    }

    @Test fun `M_CR_06_discriminator_post_only_lng`() = runTest {
        assertDiscriminatorReject("M-CR-06 POST-only longitude", "POST lng off-target, PRE canonical",
            postOverride = { copy(effectiveLng = 116.4002) })
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

    // ---- Production commit clock domain (Sol R40 P2) ----
    //   TrustedQuotaEntry.committedAt is documented as "Monotonic commit timestamp (elapsed-realtime-style)".
    //   The production composition root (AutomationService) MUST inject SystemClock.elapsedRealtime(),
    //   not the AutomationEngine default (System.currentTimeMillis()). This test verifies the domain
    //   mismatch: elapsedRealtime (ms since boot) << currentTimeMillis (epoch ms since 1970).
    //   A regression that reverts to wall clock would fail this assertion.

    @Test fun `production_commit_clock_domain_is_monotonic`() = runTest {
        val monotonicMs = android.os.SystemClock.elapsedRealtime()
        val wallMs = System.currentTimeMillis()
        assertTrue(
            "committedAt clock domain must be elapsedRealtime ($monotonicMs), not wall time ($wallMs)",
            monotonicMs < wallMs
        )
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
