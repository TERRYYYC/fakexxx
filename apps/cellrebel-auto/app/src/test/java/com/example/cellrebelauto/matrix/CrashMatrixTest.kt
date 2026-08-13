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
        // M-CR-06 (R36, Sol R35 BLOCKED): trust PASS but the ledger transaction not yet committed
        // (§8.1 TRUST_POLICY_PASS tx crashed before commit). At DECIDING the DURABLE execution evidence +
        // apply receipt + pre/post observations ALL exist; only the ledger + close decision are missing.
        //
        // R36 fixes (Sol R35 P1-1/P1-2, P2-3/P2-4):
        //  - Durable pre/post observations seeded via SeededEvidenceSource (defeats wire-only bypass)
        //  - DECOY execution seeded alongside current (defeats single-execution assumption)
        //  - currentExecutionId owner ref on TestAttempt (recovery must locate CURRENT, not guess)
        //  - committedAt = commit time (>= completedAtElapsed), NOT == completedAtElapsed (P2-3)
        //  - Receipt outcome from actual ApplyOutcome (P2-4)
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING",
            aplusLeaseId = LEASE_ID, currentExecutionId = "exec-current-77")
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        // DECOY execution (Sol R35 P1-2): same attempt, wire=1, DIFFERENT digest — inserted FIRST.
        // A bypass reading forAttempt(77)[0] gets the decoy → wrong digest → fails the read-back.
        seedDurableExecution("exec-decoy-77", 77L, WIRE_VERIFIED, "decoy-digest-$seededDigest")
        // CURRENT execution: the one currentExecutionId points to.
        seedDurableExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        // Provider world CONSISTENT (Sol R35 P2-4): the executor's actual outcome drives the receipt.
        val applyOutcome = executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, applyOutcome.outcome, 1000L)
        // Durable observations (Sol R35 P1-1): pre/post with full §6.4 field set — a wire-only bypass
        // that skips CompletionTrustContext/TrustPolicy cannot satisfy the observation discriminator test.
        val evidence = APlusCompletionEvidence(
            execution = fullEvidenceExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest).copy(attemptId = 77L),
            completionEvidenceWire = WIRE_VERIFIED,
            applyReceiptIntentHash = intentDigest,
            applyReceiptLease = LEASE_ID
        )
        val evidenceSource = SeededEvidenceSource(pre = validPre(intentDigest), post = validPost(intentDigest), evidence = evidence)
        val clock = VirtualClock(now = 15000L) // recovery AFTER post (14000) — evidence already completed
        buildEngine(planId, clock, FakeBackend(executor, log, evidenceSource)).run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("M-CR-06: re-decide must mint a trusted entry bound to the attempt", entry)
        assertEquals("M-CR-06: the mint must bind the correct task", 42L, entry!!.taskId)
        assertEquals("M-CR-06: the mint digest must derive from the CURRENT execution (not the decoy)", seededDigest, entry.evidenceDigest)
        // committedAt = commit time (Sol R35 P2-3): the ledger row is committed at recovery time (15000),
        // which is AFTER the evidence completion (13000). It must be >= completedAtElapsed, NOT ==.
        assertTrue("M-CR-06: committedAt must be the commit time (>= completedAtElapsed 13000), never == evidence completion clock",
            entry.committedAt >= EXEC_COMPLETED_AT_ELAPSED)
        assertEquals("M-CR-06: the re-decision must insert EXACTLY ONE ledger row", 1, db.trustedQuotaDao().countAll())
        // Both executions survive (decoy + current) — recovery reads, never duplicates.
        assertEquals("M-CR-06: both decoy + current executions survive (never duplicated or deleted)", 2, db.attemptExecutionDao().forAttempt(77L).size)
        // The CURRENT execution (pointed to by currentExecutionId) is the one the mint binds to.
        val persisted = db.attemptExecutionDao().byExecutionId("exec-current-77")
        assertNotNull("M-CR-06: the current execution evidence must survive", persisted)
        assertEquals("M-CR-06 readback: evidence digest is the CURRENT execution's (not the decoy's)", seededDigest, persisted!!.evidencePayloadDigest)
        assertEquals("M-CR-06 readback: wire must be the verified value", WIRE_VERIFIED, persisted.completionEvidenceWire)
        assertEquals("M-CR-06 readback: §7.1 web score must survive", 8.0, persisted.webBrowsingScore!!, 0.001)
    }

    @Test
    fun `M_CR_06_null`() = runTest {
        // M-CR-06 negative polarity: a DECIDING crash whose durable execution evidence is absent (the
        // COMPLETION_OBSERVED carrier never persisted) must FAIL-CLOSED with ZERO mint.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = LEASE_ID)
        val intentDigest = ownerIntentDigest(sessionId)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val applyOutcome = executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, applyOutcome.outcome, 1000L)
        val clock = VirtualClock(now = 15000L)
        buildEngine(planId, clock, FakeBackend(executor, log)).run()

        assertEquals("M-CR-06 null polarity: absent durable evidence must mint ZERO ledger rows (fail-closed)", 0, db.trustedQuotaDao().countAll())
        assertEquals("M-CR-06 null polarity: absent durable evidence must persist NO execution row", 0, db.attemptExecutionDao().forAttempt(77L).size)
        val recovered = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertNotEquals("M-CR-06 null polarity: absent durable evidence must NOT project to succeeded", "succeeded", recovered.status)
    }

    @Test
    fun `M_CR_06_discriminator_invalid`() = runTest {
        // M-CR-06 INV-23 intent discriminator (Sol R35 P1-1): wire STILL 1, observations canonical,
        // EXCEPT the apply receipt's intent hash mismatches the owner recomputation. The discriminator
        // is on applyReceiptIntentHash (from APlusCompletionEvidence), NOT on requestDigest (§6.3.4
        // domain-separated canonical digest ≠ ApplyReceiptV1.acceptedIntentHash). A wire-only bypass
        // that compares requestDigest mints anyway and fails this; only TrustPolicy consuming the full
        // CompletionTrustContext rejects it. FAIL preserves evidence + writes exact UnverifiedAttemptRecord.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING",
            aplusLeaseId = LEASE_ID, currentExecutionId = "exec-current-77")
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedDurableExecution("exec-decoy-77", 77L, WIRE_VERIFIED, "decoy-$seededDigest")
        seedDurableExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        // The completion evidence carries a DIVERGENT applyReceiptIntentHash → INV-23 three-way mismatch.
        val evidence = APlusCompletionEvidence(
            execution = fullEvidenceExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest).copy(attemptId = 77L),
            completionEvidenceWire = WIRE_VERIFIED,
            applyReceiptIntentHash = "DIVERGENT-intent-hash",
            applyReceiptLease = LEASE_ID
        )
        val evidenceSource = SeededEvidenceSource(pre = validPre(intentDigest), post = validPost(intentDigest), evidence = evidence)
        val clock = VirtualClock(now = 15000L)
        buildEngine(planId, clock, FakeBackend(executor, log, evidenceSource)).run()

        assertEquals("M-CR-06 intent discriminator: intent-hash-mismatch must mint ZERO trusted rows (TrustPolicy consumed)", 0, db.trustedQuotaDao().countAll())
        val persisted = db.attemptExecutionDao().byExecutionId("exec-current-77")
        assertNotNull("M-CR-06 intent discriminator: rejected durable execution must be preserved", persisted)
        assertEquals("M-CR-06 intent discriminator: rejected execution digest is the current evidence's exact digest", seededDigest, persisted!!.evidencePayloadDigest)
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(77L)
        assertNotNull("M-CR-06 intent discriminator: rejected completion must write an exact UnverifiedAttemptRecord", unverified)
        assertEquals("M-CR-06 intent discriminator: unverified reason is typed UNTRUSTED", "UNTRUSTED", unverified!!.reason)
        assertEquals("M-CR-06 intent discriminator: unverified evidenceDigest binds the rejected durable digest", seededDigest, unverified.evidenceDigest)
        val recovered = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertNotEquals("M-CR-06 intent discriminator: rejected attempt must NOT project to succeeded", "succeeded", recovered.status)
    }

    @Test
    fun `M_CR_06_discriminator_observation`() = runTest {
        // M-CR-06 observation discriminator (Sol R35 P1-1 NEW): wire=1, intent hash matches, BUT the
        // pre/post observations have a §6.4 cross-observation violation (environmentRevision mismatch:
        // pre.revision=7 ≠ post.revision=99). TrustPolicy MUST reject this — a bypass that skips
        // observations and TrustPolicy (reads wire only) mints anyway and FAILS this test. This is the
        // key oracle that proves TrustPolicy is consumed, not just wire-checked.
        val planId = seedPlan(taskId = 42L)
        val sessionId = seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING",
            aplusLeaseId = LEASE_ID, currentExecutionId = "exec-current-77")
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val intentDigest = ownerIntentDigest(sessionId)
        seedDurableExecution("exec-decoy-77", 77L, WIRE_VERIFIED, "decoy-$seededDigest")
        seedDurableExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = intentDigest, now = 1000L)
        log.seedReceipt(applyKey(77L), intentDigest, "RELEASED", 1000L)
        val evidence = APlusCompletionEvidence(
            execution = fullEvidenceExecution("exec-current-77", 77L, WIRE_VERIFIED, seededDigest).copy(attemptId = 77L),
            completionEvidenceWire = WIRE_VERIFIED,
            applyReceiptIntentHash = intentDigest,
            applyReceiptLease = LEASE_ID
        )
        // §6.4 violation: pre.revision=7 ≠ post.revision=99 (cross-observation consistency broken).
        val evidenceSource = SeededEvidenceSource(
            pre = validPre(intentDigest, revision = 7L),
            post = validPost(intentDigest, revision = 99L), // MISMATCH — §6.4 requires pre.revision == post.revision
            evidence = evidence
        )
        val clock = VirtualClock(now = 15000L)
        buildEngine(planId, clock, FakeBackend(executor, log, evidenceSource)).run()

        assertEquals("M-CR-06 observation discriminator: revision-mismatch observations must mint ZERO trusted rows (TrustPolicy consumed)", 0, db.trustedQuotaDao().countAll())
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(77L)
        assertNotNull("M-CR-06 observation discriminator: rejected completion must write UnverifiedAttemptRecord", unverified)
        val recovered = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertNotEquals("M-CR-06 observation discriminator: rejected attempt must NOT project to succeeded", "succeeded", recovered.status)
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
