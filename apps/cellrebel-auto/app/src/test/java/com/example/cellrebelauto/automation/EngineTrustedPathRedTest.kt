package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.aplus.APlusRunTemplate
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.execution.CellRebelCompletionEvidenceV1
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.DurableRecoveryLog
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
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
 * R9 — production-reachability REDs driven through the SINGLE composition root, with the Attempt's
 * PERSISTED current operation as the recovery authority (Issue #5, §11.7; Sol round-8 advisory).
 *
 * Sol's round-8 falsification (7 findings) each map to a concrete repair + RED:
 *  - P1-1 (composition still disconnected): `APlusComposition.productionBackend()` is now NON-NULL
 *    fail-closed; `AutomationService` composes through it; the tests compose through the SAME
 *    `recoveryCoordinator` / `completionEvidenceSource` functions.
 *  - P1-2 (F1 not a satisfiable positive): split positive PASS/mint/terminal-success from negative
 *    FAIL/unverified/legacy-zero; the §6.4 fixture is CONSISTENT (observation coords == task target
 *    39.9/116.4, and the intent hash is the engine's `APlusOperationIdentity.requestDigest`, recomputed
 *    from the attempt id — not a divergent constant).
 *  - P1-3 (no persisted current operation): `TestAttempt.aplusState` / `aplusLeaseId` (schema v6) are
 *    the recovery authority. The crash seed sets the phase; recovery branches on it (apply vs release
 *    vs pre-apply).
 *  - P1-4 (release not lease-bound/durable): `releaseLease` takes `leaseId` + returns a typed
 *    `RecordedReleaseReceipt`; the RED asserts receipt readback, not a call count.
 *  - P1-5 (terminal/crash ownership unsafe): release happens BEFORE terminalize; missing evidence
 *    releases + terminalizes; PASS terminalizes the attempt.
 *  - P1-6 (two active PlanRuns): recovery reuses the crashed running session (supersede).
 *  - P2 (no unverified carrier): `UnverifiedAttemptRecord` readback oracle.
 *
 * Under the pre-freeze skeletons the A+ lifecycle is fail-closed, so every positive assertion stays RED.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.9–§11.10.
 */
@RunWith(RobolectricTestRunner::class)
class EngineTrustedPathRedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

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

    // ---- Fakes ----

    private class FakeCellRebelRunner(
        templates: List<AttemptOutcome>,
        private val nowMs: () -> Long
    ) : CellRebelRunner {
        private val queue = templates.toMutableList()
        var calls = 0
            private set

        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            calls++
            val template = if (queue.size > 1) queue.removeAt(0) else queue.first()
            return when (template) {
                is AttemptOutcome.Success -> template.copy(startedAt = startedAt, endedAt = nowMs())
                is AttemptOutcome.Failure -> template.copy(startedAt = startedAt, endedAt = nowMs())
            }
        }
    }

    private class FakeGpsSetter(outcomes: List<GpsOutcome>) : GpsLocationSetter {
        private val queue = outcomes.toMutableList()
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome =
            if (queue.size > 1) queue.removeAt(0) else queue.first()
    }

    private class VirtualClock(initialNow: Long = 1000L) {
        var now = initialNow
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> now += ms }
    }

    private class SeededObserve(private val facts: Map<Long, Boolean>) : ObserveIntentAcquirer {
        val calls = mutableListOf<Long>()
        override fun matches(attemptId: Long): Boolean {
            calls += attemptId
            return facts[attemptId] ?: false
        }
    }

    private class SeededRevision(private val facts: Map<String, Boolean>) : ReceiptRevisionAcquirer {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun isFresh(idempotencyKey: String, now: Long): Boolean {
            calls += idempotencyKey to now
            return facts[idempotencyKey] ?: false
        }
    }

    private class SeededQuota(private val facts: Map<Long, Boolean>) : TrustedQuotaAcquirer {
        val calls = mutableListOf<Long>()
        override fun hasCapacity(attemptId: Long): Boolean {
            calls += attemptId
            return facts[attemptId] ?: false
        }
    }

    /**
     * A+ evidence source fake that recomputes the INV-23 intent hash from the attempt id, so the
     * §6.4-positive fixture is CONSISTENT with the engine's locally-recomputed hash (Sol round-8 P1-2).
     * [deliveryMode] lets the negative case masquerade HOOK as verified (INV-06).
     */
    private class FakeEvidenceSource(
        private val lat: Double,
        private val lng: Double,
        private val wire: Int,
        private val deliveryMode: String,
        private val present: Boolean = true
    ) : APlusEvidenceSource {
        private fun intentHash(attemptId: Long) = APlusOperationIdentity.requestDigest(lat, lng, attemptId)

        override suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot? =
            if (!present) null else ObservationSnapshot(
                leaseId = LEASE,
                acceptedIntentHash = intentHash(attemptId),
                coverage = "FULL",
                verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = deliveryMode,
                isMock = true,
                scheduleDecision = "ALLOWED_NOW",
                effectiveLat = lat,
                effectiveLng = lng,
                environmentRevision = REVISION,
                environmentFingerprint = FINGERPRINT,
                observedAtElapsedRealtimeMs = PRE_OBSERVED_AT_ELAPSED,
                observedAtEpochMs = 900L,
                continuitySinceElapsedRealtimeMs = CONTINUITY_SINCE_ELAPSED,
                evidenceRefs = listOf("qwy:store:abc")
            )

        override suspend fun acquirePostObservation(attemptId: Long): ObservationSnapshot? =
            if (!present) null else acquirePreObservation(attemptId)!!.copy(
                observedAtElapsedRealtimeMs = POST_OBSERVED_AT_ELAPSED,
                observedAtEpochMs = 6500L
            )

        override suspend fun acquireCompletionEvidence(attemptId: Long): APlusCompletionEvidence? =
            if (!present) null else APlusCompletionEvidence(
                execution = fullEvidenceExecution(wire),
                completionEvidenceWire = wire,
                applyReceiptIntentHash = intentHash(attemptId),
                applyReceiptLease = LEASE
            )
    }

    private class FakeBackend(
        private val exec: RecordingExternalApplyExecutor,
        private val log: FakeDurableRecoveryLog,
        private val observe: SeededObserve,
        private val revision: SeededRevision,
        private val quota: SeededQuota,
        private val evidence: FakeEvidenceSource
    ) : APlusBackend {
        override val executor: ExternalApplyExecutor = exec
        override val recoveryLog: DurableRecoveryLog = log
        override val observeIntent: ObserveIntentAcquirer = observe
        override val receiptRevision: ReceiptRevisionAcquirer = revision
        override val trustedQuota: TrustedQuotaAcquirer = quota
        override val evidenceSource: APlusEvidenceSource = evidence
    }

    private val successTemplate = AttemptOutcome.Success(
        webScore = 8.0, videoScore = 7.0, runningObservedAt = 0L, startedAt = 0L, endedAt = 0L
    )

    private fun buildEngine(
        planId: Long,
        runner: CellRebelRunner,
        gps: GpsLocationSetter,
        clock: VirtualClock,
        driver: APlusAttemptDriver? = null,
        backend: APlusBackend? = null
    ) = AutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = runner,
        gpsSetter = gps,
        bufferGate = BufferGate(0, clock.nowMs),
        testTimeoutMs = 90_000L,
        gpsSettleMs = 0L,
        nowMs = clock.nowMs,
        delayMs = clock.delayMs,
        attemptDriver = driver,
        recoveryCoordinator = backend?.let { APlusComposition.recoveryCoordinator(it) },
        completionEvidenceSource = backend?.let { APlusComposition.completionEvidenceSource(it) }
    )

    // ---- Seed helpers ----

    private suspend fun seedPlan(taskId: Long, quota: Int): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "r9.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = quota
            )
        )
        db.planDao().insertTasks(
            listOf(
                LocationTask(
                    id = taskId, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        return planId
    }

    /**
     * Seeds an A+ crash window as OWNER state (Sol round-8 P1-3): a non-terminal attempt carrying its
     * persisted current operation (phase + lease), plus a running owner session. The apply/release
     * identity is recomputed by the engine from the attempt id + coords — never read from the audit
     * stream, and the seed writes NO audit row.
     */
    private suspend fun seedAPlusCrashAttempt(
        planId: Long,
        taskId: Long,
        attemptId: Long,
        aplusState: String?,
        aplusLeaseId: String? = null
    ): Long {
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

    // ---- §6.4-positive constants (§6.4.2 clocks; target = task coords 39.9/116.4) ----

    companion object {
        private val WIRE_VERIFIED = CellRebelCompletionEvidenceV1.VERIFIED_NEW_COMPLETION.wire // 1
        private const val LEASE = "L1"
        private const val REVISION = 7L
        private const val FINGERPRINT = "fp-1"
        private const val TARGET_LAT = 39.9
        private const val TARGET_LNG = 116.4
        private const val EXEC_STARTED_AT_ELAPSED = 2000L
        private const val EXEC_RUNNING_CONFIRMED_AT_ELAPSED = 2100L
        private const val EXEC_COMPLETED_AT_ELAPSED = 13000L
        private const val PRE_OBSERVED_AT_ELAPSED = 1000L
        private const val POST_OBSERVED_AT_ELAPSED = 14000L
        private const val CONTINUITY_SINCE_ELAPSED = 500L
        private const val DISTINCTIVE_DIGEST = "sha256:r9f1-identity:9c2e7a"

        private fun fullEvidenceExecution(wire: Int): CellRebelExecution = CellRebelExecution(
            executionId = "exec-r9-$wire",
            attemptId = 0L,
            completionEvidenceWire = wire,
            evidencePayloadDigest = DISTINCTIVE_DIGEST,
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
    }

    private fun passingBackend(): FakeBackend = FakeBackend(
        RecordingExternalApplyExecutor(),
        FakeDurableRecoveryLog(),
        SeededObserve(emptyMap()),
        SeededRevision(emptyMap()),
        SeededQuota(emptyMap()),
        FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = true)
    )

    private fun applyKey(attemptId: Long): String = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    private fun releaseKey(attemptId: Long): String = APlusOperationIdentity.releaseIdempotencyKey(attemptId)
    private fun releaseDigest(leaseId: String): String = APlusOperationIdentity.releaseDigest(leaseId)

    // ---- R9-F1 positive: §6.4-passing must PASS → mint + terminal-success (P1-2 split) ----

    @Test
    fun `R9-F1 positive - a section6-4-passing completion through the engine A+ path must populate evidence, mint, and terminalize as succeeded`() = runTest {
        // The fixture is CONSISTENT (P1-2): observation coords == task target (39.9/116.4), intent hash
        // is the engine's requestDigest. A correct §6.4/INV-23 policy must PASS. Under the skeleton
        // recordTrustedCompletion drops the §7.1 detail, never mints, and TrustPolicy returns FAIL → the
        // attempt finalizes UNTRUSTED instead of succeeded. All three are RED.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = passingBackend()).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first().id
        val rows = db.attemptExecutionDao().forAttempt(realAttemptId)
        assertEquals("the A+ path must persist exactly one execution row through the trusted entry", 1, rows.size)
        val exec = rows.first()
        // RED: the skeleton drops the §7.1 evidence detail.
        assertEquals("§7.1 baseline state must survive (skeleton drops it ⇒ RED)", "IDLE", exec.baselineRunningState)
        assertEquals("§7.1 marker must survive (skeleton drops it ⇒ RED)", "RUNNING", exec.runningMarkerText)
        assertEquals(
            "§7.1 RUNNING duration must survive (skeleton drops it ⇒ RED)",
            EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
            exec.runningDurationMs
        )
        // RED: a §6.4-passing completion must mint exactly one trusted entry (skeleton mints none).
        assertNotNull("a §6.4-passing completion must mint a trusted entry (skeleton mints none ⇒ RED)", db.trustedQuotaDao().getByAttempt(realAttemptId))
        // RED: terminal-success (P1-5: a PASS must terminalize the attempt as succeeded).
        assertEquals(
            "a §6.4-passing completion must terminalize as succeeded (skeleton FAIL ⇒ RED)",
            "succeeded",
            db.testAttemptDao().getAttemptsForTask(taskId).first().status
        )
        // P1-3 legacy-zero: A+ mode never touches the legacy counter.
        assertEquals("the legacy counter must stay 0 in A+ mode (Sol round-8 P1-3)", 0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    // ---- R9-F1 negative: §6.4-failing must FAIL → unverified + legacy-zero (P1-2 split + P2) ----

    @Test
    fun `R9-F1 negative - a section6-4-failing completion writes a durable unverified record and never mints nor touches the legacy counter`() = runTest {
        // HOOK masquerading as verified (INV-06). A correct policy must FAIL. REDs: the unverified
        // record is never written (P2 — the skeleton discards the evidence binding); guardrails: no
        // mint, legacy-zero, terminalized UNTRUSTED.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        val backend = FakeBackend(
            RecordingExternalApplyExecutor(), FakeDurableRecoveryLog(),
            SeededObserve(emptyMap()), SeededRevision(emptyMap()), SeededQuota(emptyMap()),
            FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "HOOK", present = true)
        )
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first().id
        // RED (P2): a rejected completion must leave a durable UnverifiedAttemptRecord with the evidence
        // digest — the skeleton writes none.
        assertNotNull(
            "a trust-failing completion must leave a durable unverified record (skeleton writes none ⇒ RED)",
            db.unverifiedAttemptRecordDao().getByAttempt(realAttemptId)
        )
        // Guardrails: no trusted mint, legacy-zero, typed UNTRUSTED terminalization.
        assertEquals("a §6.4-failing completion must never mint", 0, db.trustedQuotaDao().countAll())
        assertEquals("legacy counter stays 0", 0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
        assertEquals("UNTRUSTED", db.testAttemptDao().getAttemptsForTask(taskId).first().failureReason)
    }

    // ---- R9-F2 apply-recovery: APPLY_PENDING crash → reconcile apply, then release receipt (P1-3/P1-4) ----

    @Test
    fun `R9-F2 apply-in-flight recovery reconciles the apply and converges a lease-bound durable release`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val sessionId = seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "APPLY_PENDING", aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "digest-77", now = 1000L)
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        // RED: the skeleton reconcile returns INSUFFICIENT_EVIDENCE and never re-invokes the executor.
        assertEquals("reconcile must re-invoke the apply executor (1 → 2)", 2, executor.invocationCount(applyKey(77L)))
        assertEquals("provider effect stays at one", 1, executor.effectCount(77L))
        // RED (P1-4): the release must be lease-bound + durable — readback, not a call count.
        assertNotNull("a lease-bound release receipt must be durable (skeleton ⇒ RED)", log.releaseReceiptFor("lease-77"))
        assertEquals(
            "the release must invoke the provider with the LEASE (not the apply intent digest)",
            1, executor.releaseInvocationCount(releaseKey(77L))
        )
        // The crashed session is superseded (P1-6), never duplicated.
        assertEquals("the crashed owner session must be reused, not duplicated", sessionId, db.runSessionDao().getLatest()!!.id)
        assertEquals("reconcile INSUFFICIENT ⇒ durable PAUSED", "paused", db.runSessionDao().getLatest()!!.status)
    }

    // ---- R9-F2 release-recovery: RELEASE_PENDING crash → reconcile RELEASE, never apply (P1-3) ----

    @Test
    fun `R9-F2 release-in-flight recovery reconciles the release and never re-applies`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "RELEASE_PENDING", aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "digest-77", now = 1000L)
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        // RED: the skeleton releaseLease returns null. The release-in-flight path must NOT re-apply.
        assertNotNull("release-in-flight recovery must converge a durable release receipt (skeleton ⇒ RED)", log.releaseReceiptFor("lease-77"))
        assertEquals("release-in-flight recovery must never re-invoke apply", 1, executor.invocationCount(applyKey(77L)))
    }

    // ---- R9-F2 pre-apply: an attempt that never began apply is NOT apply-reconciled (P1-3) ----

    @Test
    fun `R9-F2 pre-BEGIN-APPLY attempt is never reconciled as an apply`() = runTest {
        // aplusState = null → the attempt never entered the A+ lifecycle → the A+ recovery query excludes
        // it, and the legacy sweep terminalizes it WITHOUT invoking the executor.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = null)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val backend = FakeBackend(executor, log, SeededObserve(emptyMap()), SeededRevision(emptyMap()), SeededQuota(emptyMap()), FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        assertEquals("a pre-BEGIN-APPLY attempt must never be apply-reconciled", 0, executor.invocationCount(applyKey(77L)))
        assertEquals("a pre-BEGIN-APPLY attempt must never release", 0, executor.releaseInvocationCount(releaseKey(77L)))
        assertEquals("interrupted", db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }.status)
    }

    // ---- R9-F4: the complete ordered §8.1 audit trail (driver no-op ⇒ RED) ----

    @Test
    fun `R9-F4 the engine A+ path appends the complete ordered canonical section8-1 audit trail in step`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val auditDao = db.auditEventDao()
        var atRunEntry: List<String>? = null
        var afterRunningObserved: List<String>? = null
        val clock = VirtualClock()
        val runner = object : CellRebelRunner {
            override suspend fun runTest(
                startedAt: Long,
                testTimeoutMs: Long,
                onRunningObserved: suspend (Long) -> Unit
            ): AttemptOutcome {
                atRunEntry = auditDao.all().map { it.eventType }
                onRunningObserved(4242L)
                afterRunningObserved = auditDao.all().map { it.eventType }
                return AttemptOutcome.Success(
                    webScore = 8.0, videoScore = 7.0, runningObservedAt = 4242L,
                    startedAt = startedAt, endedAt = 4300L
                )
            }
        }
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, driver = APlusAttemptDriver(auditDao) { 1_000_000L }, backend = passingBackend()).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first().id
        val canonical = APlusRunTemplate.CANONICAL_HAPPY_PATH.map { it.name }
        assertEquals(
            "at run-test entry the audit must hold the pre-run §8.1 prefix; got $atRunEntry (skeleton/empty ⇒ RED)",
            canonical.take(4),
            atRunEntry
        )
        assertEquals("after RUNNING the audit must hold prefix + NEW_RUN_OBSERVED; got $afterRunningObserved", canonical.take(5), afterRunningObserved)
        val trail = auditDao.forAttempt(realAttemptId)
        assertEquals("the A+ path must append the COMPLETE ordered §8.1 trail", canonical, trail.map { it.eventType })
        assertTrue("every audit row binds the REAL attempt", trail.isNotEmpty() && trail.all { it.attemptId == realAttemptId })
    }
}
