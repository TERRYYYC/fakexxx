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
    private var lastEvidence: FakeEvidenceSource? = null

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

    private class FakeEvidenceSource : APlusEvidenceSource {
        var preCalls = 0
        var postCalls = 0
        var completionCalls = 0
        var completionRequests = mutableListOf<Long>()
        var completionEvidence: APlusCompletionEvidence? = null
        override suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot? { preCalls++; return null }
        override suspend fun acquirePostObservation(attemptId: Long): ObservationSnapshot? { postCalls++; return null }
        override suspend fun acquireCompletionEvidence(attemptId: Long): APlusCompletionEvidence? {
            completionCalls++
            completionRequests.add(attemptId)
            return completionEvidence
        }
    }

    private class FakeBackend(
        private val exec: RecordingExternalApplyExecutor,
        private val log: FakeDurableRecoveryLog
    ) : APlusBackend {
        val evidence = FakeEvidenceSource()
        override val executor: ExternalApplyExecutor = exec
        override val recoveryLog: DurableRecoveryLog = log
        override val observeIntent: ObserveIntentAcquirer = SeededObserve(emptyMap())
        override val receiptRevision: ReceiptRevisionAcquirer = SeededRevision(emptyMap())
        override val trustedQuota: TrustedQuotaAcquirer = SeededQuota(emptyMap())
        override val evidenceSource: APlusEvidenceSource = evidence
    }

    private fun buildEngine(planId: Long, clock: VirtualClock, backend: APlusBackend): AutomationEngine {
        val params = APlusComposition.engineAplusParams(backend)
        lastCoordinator = params.first
        lastEvidence = (backend as FakeBackend).evidence
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

    private suspend fun seedAttempt(planId: Long, taskId: Long, attemptId: Long, aplusState: String?, aplusLeaseId: String? = null): Long {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = aplusState, aplusLeaseId = aplusLeaseId
            )
        )
        return sessionId
    }

    private fun applyKey(attemptId: Long) = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    private fun releaseKey(attemptId: Long) = APlusOperationIdentity.releaseIdempotencyKey(attemptId)

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
        // M-CR-06: trust PASS but the ledger transaction not yet committed (phase DECIDING, no carrier).
        // FROZEN SEMANTIC (Sol R30 re-review): the GREEN writes execution + ledger in the SAME Room
        // transaction (PlanRepository.recordTrustedCompletion), so "execution durable / ledger absent" is
        // UNREACHABLE. The reachable pre-transaction crash is: DECIDING persisted, NEITHER execution NOR
        // ledger row present. Recovery must RE-DECIDE: re-acquire a non-null, complete, dynamically-unique
        // PASS bundle from the source, run TrustPolicy, then write execution + ledger atomically.
        //
        // The digest is a NON-LITERAL (random per-test) value so a call-and-discard / constant-digest mint
        // cannot fake a re-decision; the commit clock is the INJECTED virtual-clock value (exact), not a
        // non-zero constant.
        val planId = seedPlan(taskId = 42L)
        seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        // NO pre-seeded execution row — the crash happened BEFORE the atomic execution+ledger write.
        val seededDigest = "ev-" + java.util.UUID.randomUUID().toString()
        val execution = CellRebelExecution(
            executionId = "exec-77", attemptId = 77L, completionEvidenceWire = 1,
            evidencePayloadDigest = seededDigest, startedAt = 1L, classifiedAt = 2L
        )
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock(now = 4242L)
        val backend = FakeBackend(executor, log)
        backend.evidence.completionEvidence = APlusCompletionEvidence(
            execution = execution, completionEvidenceWire = 1,
            applyReceiptIntentHash = "h", applyReceiptLease = "lease-77"
        )
        buildEngine(planId, clock, backend).run()

        val entry = db.trustedQuotaDao().getByAttempt(77L)
        assertNotNull("M-CR-06: re-decide must mint a trusted entry bound to the attempt", entry)
        assertEquals("M-CR-06: the mint must bind the correct task", 42L, entry!!.taskId)
        assertEquals("M-CR-06: the mint digest must derive EXACTLY from the re-acquired evidence (read back, never hardcoded)", seededDigest, entry.evidenceDigest)
        assertEquals("M-CR-06: the mint must commit with the INJECTED virtual-clock value (exact), never a non-zero constant", 4242L, entry.committedAt)
        assertEquals("M-CR-06: the re-decision must insert EXACTLY ONE ledger row (no unrelated rows)", 1, db.trustedQuotaDao().countAll())
        assertEquals("M-CR-06: the recovery must RE-OBSERVE the EXACT crashed attempt (not some other attempt, never forge from nothing)", listOf(77L), lastEvidence!!.completionRequests)
        // The re-decide writes execution + ledger atomically, so the durable evidence the mint is bound to
        // must ALSO be persisted (the §7.1 read-back source), not just a bare ledger row.
        assertEquals("M-CR-06: the re-decide must persist the execution evidence it read (same atomic write)", 1, db.attemptExecutionDao().forAttempt(77L).size)
    }

    @Test
    fun `M_CR_06_null`() = runTest {
        // M-CR-06 negative polarity: a DECIDING crash whose completion evidence is unavailable (source
        // returns null) must FAIL-CLOSED with ZERO mint — never forge a trusted entry from nothing.
        val planId = seedPlan(taskId = 42L)
        seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock(now = 4242L)
        val backend = FakeBackend(executor, log)
        // backend.evidence.completionEvidence stays null → acquireCompletionEvidence returns null.
        buildEngine(planId, clock, backend).run()

        assertEquals("M-CR-06 null polarity: null evidence must mint ZERO ledger rows (fail-closed)", 0, db.trustedQuotaDao().countAll())
        assertEquals("M-CR-06 null polarity: null evidence must persist NO execution row", 0, db.attemptExecutionDao().forAttempt(77L).size)
        val recovered = db.testAttemptDao().getAttemptsForTask(42L).first { it.id == 77L }
        assertNotEquals("M-CR-06 null polarity: null evidence must NOT project to succeeded", "succeeded", recovered.status)
    }

    @Test
    fun `M_CR_06_wrong_attempt`() = runTest {
        // M-CR-06 negative polarity: the source returns evidence for a DIFFERENT attempt (caller-inconsistent
        // context, INV-23) → the recovery must NOT mint for the crashed attempt (zero mint for attempt 77).
        val planId = seedPlan(taskId = 42L)
        seedAttempt(planId, 42L, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock(now = 4242L)
        val backend = FakeBackend(executor, log)
        backend.evidence.completionEvidence = APlusCompletionEvidence(
            execution = CellRebelExecution(
                executionId = "exec-other", attemptId = 999L, completionEvidenceWire = 1,
                evidencePayloadDigest = "ev-" + java.util.UUID.randomUUID().toString(), startedAt = 1L, classifiedAt = 2L
            ),
            completionEvidenceWire = 1, applyReceiptIntentHash = "h", applyReceiptLease = "lease-77"
        )
        buildEngine(planId, clock, backend).run()

        assertEquals("M-CR-06 wrong-attempt polarity: wrong-attempt evidence must mint ZERO rows for the crashed attempt", 0, db.trustedQuotaDao().countAll())
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
