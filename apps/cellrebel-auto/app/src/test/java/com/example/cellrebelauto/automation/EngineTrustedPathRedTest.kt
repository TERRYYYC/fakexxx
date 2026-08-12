package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusRunTemplate
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.CompletionTrustContext
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.audit.AutoAuditEvent
import com.example.cellrebelauto.model.execution.CellRebelCompletionEvidenceV1
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
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
 * R7 — production-reachability REDs (Issue #5, §11.7 meta-lesson; Sol round-6 advisory).
 *
 * Sol's round-6 falsification: R6's REDs drove ISOLATED units (repo method / coordinator / driver),
 * so an isolated counterexample — correct local implementation, production NEVER calling it — greened
 * them while the engine kept walking the legacy path:
 *  - F1: `PlanRepository.recordTrustedCompletion` had zero production call sites; `CompletionTrustContext`
 *    was never constructed in main. → R7-F1 drives the REAL completion entry (`AutomationEngine.run`
 *    success path) and asserts the durable execution row + minted ledger entry bound to the REAL
 *    attempt→task identity.
 *  - F2: `RecoveryCoordinator(` / `scheduleAdvanced(` had zero production call sites. → R7-F2 drives the
 *    REAL recovery consumer (the engine sweep, §8.2 RECOVERING) with the coordinator composed through the
 *    engine's production seam, and asserts provider-effect / receipt / checkpoint / gate-acquisition
 *    durable effects through the injected fakes.
 *  - F4: the engine drove only `CREATED→BEGIN_APPLY`; the R6-F4 assertion (`audit.isNotEmpty()`) greened
 *    under that partial lifecycle. → R7-F4 asserts the COMPLETE ordered canonical §8.1 audit trail bound
 *    to the real attempt, checked IN STEP with the lifecycle (the fake runner observes the audit prefix
 *    at run-test entry and right after RUNNING is observed), so "dump the whole trail at creation or at
 *    finalize" attacks also stay RED.
 *
 * The seams under test are the engine's production composition seams ([AutomationEngine.recoveryCoordinator],
 * [AutomationEngine.completionTrustContextProvider], [AutomationEngine.attemptDriver]) — the same params
 * production wiring (AutomationService / GREEN) uses. An attack that implements every unit correctly but
 * leaves the engine disconnected CANNOT green any positive test here; the negatives/guardrails pin the
 * bypass polarities. Polarities that hold under the committed skeleton are labelled GUARDRAIL/ANCHOR.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.7–§11.8.
 *
 * # R7 生产可达性 RED：F1 真实完成入口铸币 / F2 真实恢复消费者+组合 seam / F4 完整有序 §8.1 审计（含 in-step 前缀）
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

    // ---- Fakes (mirror EngineRecoveryTest / RecoveryIdempotencyRedTest; kept local) ----

    /** # 脚本化假 CellRebel 执行器 */
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

    /** # 脚本化假 GPS 设置器 */
    private class FakeGpsSetter(outcomes: List<GpsOutcome>) : GpsLocationSetter {
        private val queue = outcomes.toMutableList()
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome =
            if (queue.size > 1) queue.removeAt(0) else queue.first()
    }

    /** # 虚拟时钟：时间只在 delay 时前进 */
    private class VirtualClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> now += ms }
    }

    /** Identity-keyed observe fake: the fact is LOOKED UP by the identity the coordinator forwards. */
    private class SeededObserve(private val facts: Map<Long, Boolean>) : ObserveIntentAcquirer {
        val calls = mutableListOf<Long>()
        override fun matches(attemptId: Long): Boolean {
            calls += attemptId
            return facts[attemptId] ?: false
        }
    }

    /** Identity-keyed revision fake (keyed by idempotency key). */
    private class SeededRevision(private val facts: Map<String, Boolean>) : ReceiptRevisionAcquirer {
        val calls = mutableListOf<Pair<String, Long>>()
        override fun isFresh(idempotencyKey: String, now: Long): Boolean {
            calls += idempotencyKey to now
            return facts[idempotencyKey] ?: false
        }
    }

    /** Identity-keyed quota fake (keyed by attempt id). */
    private class SeededQuota(private val facts: Map<Long, Boolean>) : TrustedQuotaAcquirer {
        val calls = mutableListOf<Long>()
        override fun hasCapacity(attemptId: Long): Boolean {
            calls += attemptId
            return facts[attemptId] ?: false
        }
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
        coordinator: RecoveryCoordinator? = null,
        trustProvider: (suspend (Long, AttemptOutcome.Success) -> CompletionTrustContext?)? = null
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
        recoveryCoordinator = coordinator,
        completionTrustContextProvider = trustProvider
    )

    // ---- Seed helpers ----

    /** Seeds a single-task plan with an EXPLICIT task id (≠ 1L — kills constant-identity mints). */
    private suspend fun seedPlan(taskId: Long, quota: Int): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "r7.csv", importedAt = 1000L,
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
        check(db.locationTaskDao().getTaskById(taskId) != null) {
            "R7 setup: Room did not honour the explicit task id $taskId"
        }
        return planId
    }

    /** A terminal dummy attempt with an explicit id, pushing subsequent auto ids past 1 (≠ constant 1L). */
    private suspend fun seedTerminalDummyAttempt(taskId: Long, attemptId: Long) {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 400L))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 450L, runningObservedAt = null, endedAt = 470L,
                status = "interrupted", failureReason = "INTERRUPTED",
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )
    }

    /**
     * Seeds an A+ crash window: a NON-terminal attempt whose BEGIN_APPLY audit row carries the apply's
     * idempotency key (correlationRef) + canonical request digest (payloadDigest) — the durable state a
     * crashed process leaves behind after §8.1 `CREATED –BEGIN_APPLY→ APPLY_PENDING` (attempt + key are
     * written FIRST). The recovery sweep recovers (key, digest) from this row (§8.1: 同键重放, never
     * 换键重复 apply).
     */
    private suspend fun seedAPlusCrashAttempt(
        planId: Long,
        taskId: Long,
        attemptId: Long,
        key: String,
        digest: String
    ) {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4
            )
        )
        db.auditEventDao().insert(
            AutoAuditEvent(
                seq = 1L, attemptId = attemptId, correlationRef = key,
                eventType = AttemptEvent.BEGIN_APPLY.name, payloadDigest = digest, recordedAt = 650L
            )
        )
    }

    // ---- §6.4-positive trust context builders (mirror TrustedLedgerRedTest's frozen canonical bundle) ----

    private val WIRE_VERIFIED = CellRebelCompletionEvidenceV1.VERIFIED_NEW_COMPLETION.wire // 1
    private val INTENT_HASH = "intent-h"
    private val LEASE = "L1"
    private val REVISION = 7L
    private val FINGERPRINT = "fp-1"
    private val TARGET_LAT = 40.0
    private val TARGET_LNG = -74.0
    private val EXEC_STARTED_AT_ELAPSED = 2000L
    private val EXEC_RUNNING_CONFIRMED_AT_ELAPSED = 2100L
    private val EXEC_COMPLETED_AT_ELAPSED = 13000L // RUN 10900 ms ≥ §6.4.2 10000 ms floor
    private val PRE_OBSERVED_AT_ELAPSED = 1000L
    private val POST_OBSERVED_AT_ELAPSED = 14000L
    private val CONTINUITY_SINCE_ELAPSED = 500L
    private val DISTINCTIVE_DIGEST = "sha256:r7f1-identity:9c2e7a" // ≠ any constant literal

    private fun validPre(): ObservationSnapshot = ObservationSnapshot(
        leaseId = LEASE,
        acceptedIntentHash = INTENT_HASH,
        coverage = "FULL",
        verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
        deliveryMode = "SYSTEM_MOCK",
        isMock = true,
        scheduleDecision = "ALLOWED_NOW",
        effectiveLat = TARGET_LAT,
        effectiveLng = TARGET_LNG,
        environmentRevision = REVISION,
        environmentFingerprint = FINGERPRINT,
        observedAtElapsedRealtimeMs = PRE_OBSERVED_AT_ELAPSED,
        observedAtEpochMs = 900L,
        continuitySinceElapsedRealtimeMs = CONTINUITY_SINCE_ELAPSED,
        evidenceRefs = listOf("qwy:store:abc")
    )

    private fun validPost(): ObservationSnapshot = validPre().copy(
        observedAtElapsedRealtimeMs = POST_OBSERVED_AT_ELAPSED,
        observedAtEpochMs = 6500L
    )

    private fun fullEvidenceExecution(wire: Int): CellRebelExecution = CellRebelExecution(
        executionId = "exec-r7-$wire",
        attemptId = 0L, // caller binds the REAL attempt id
        completionEvidenceWire = wire,
        evidencePayloadDigest = "payload-digest",
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

    /** The canonical §6.4-positive bundle carrying the FULL §7.1 evidence detail. */
    private fun fullContext(wire: Int = WIRE_VERIFIED): CompletionTrustContext = CompletionTrustContext(
        execution = fullEvidenceExecution(wire),
        completionEvidenceWire = wire,
        applyReceiptIntentHash = INTENT_HASH,
        locallyRecomputedIntentHash = INTENT_HASH,
        applyReceiptLease = LEASE,
        targetLat = TARGET_LAT,
        targetLng = TARGET_LNG,
        locationToleranceMeters = 1.0,
        preObservation = validPre(),
        postObservation = validPost()
    )

    // ---- R7-F1: trusted ledger through the REAL completion entry (the engine success path) ----

    @Test
    fun `R7-F1 the engine success path persists the full evidence row and mints the trusted entry bound to the real attempt-task identity`() = runTest {
        // Sol round-6 F1 kill: R6's repo-level RED greened under "recordTrustedCompletion fully
        // implemented but NEVER called by production". Here the durable effects must appear through
        // the ENGINE's success path — a correct-but-disconnected repo method leaves zero rows (RED).
        // Identity is bound twice: the task id is explicit 42L (kills a hardcoded 1L mint — Sol's
        // round-4 attack), and the real attempt id is pushed past 1L by a terminal dummy (kills a
        // constant-attemptId mint / a constant-ctx provider).
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedTerminalDummyAttempt(taskId = taskId, attemptId = 77L)
        val providerCalls = mutableListOf<Long>()
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(
            planId, runner, gps, clock,
            trustProvider = { attemptId, _ ->
                providerCalls += attemptId
                fullContext().copy(
                    execution = fullEvidenceExecution(WIRE_VERIFIED).copy(
                        attemptId = attemptId,
                        evidencePayloadDigest = DISTINCTIVE_DIGEST
                    )
                )
            }
        ).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId)
            .first { it.status == "succeeded" }.id
        assertTrue("the real attempt id must be past the dummy (≠ 1L)", realAttemptId > 77L)
        assertEquals(
            "the engine must acquire trust evidence exactly once, for the REAL attempt identity " +
                "(a provider consulted for a wrong/constant id is a false oracle)",
            listOf(realAttemptId),
            providerCalls
        )

        val rows = db.attemptExecutionDao().forAttempt(realAttemptId)
        assertEquals(
            "exactly one execution row must be persisted for the real attempt THROUGH the engine " +
                "(skeleton entrypoint persists digest-only; a disconnected-impl attack persists nothing)",
            1, rows.size
        )
        val exec = rows.first()
        // §7.1 detail must survive the production path (skeleton drops it ⇒ null ⇒ RED).
        assertEquals("baseline state must be persisted", "IDLE", exec.baselineRunningState)
        assertEquals("running marker must be persisted", "RUNNING", exec.runningMarkerText)
        assertEquals(
            "RUNNING duration (≥ §6.4.2 floor)",
            EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
            exec.runningDurationMs
        )
        assertEquals("web score", 8.0, exec.webBrowsingScore!!, 0.001)
        assertEquals("video score", 7.0, exec.videoStreamingScore!!, 0.001)
        assertEquals(
            "per-round timestamps",
            "$EXEC_STARTED_AT_ELAPSED;$EXEC_COMPLETED_AT_ELAPSED",
            exec.roundTimestampsElapsed
        )

        val minted = db.trustedQuotaDao().getByAttempt(realAttemptId)
        assertNotNull(
            "a §6.4-positive completion THROUGH the engine must mint exactly one TrustedQuotaEntry " +
                "(skeleton mints none ⇒ RED; disconnected-impl attack mints none ⇒ RED)",
            minted
        )
        val entry = minted!!
        assertEquals("minted taskId must bind the REAL task (42L), not a constant", taskId, entry.taskId)
        assertEquals("minted attemptId must bind the REAL attempt, not a constant", realAttemptId, entry.attemptId)
        assertEquals("minted evidenceDigest must bind the execution evidence", DISTINCTIVE_DIGEST, entry.evidenceDigest)
        assertEquals("minted committedAt must bind the completion clock", EXEC_COMPLETED_AT_ELAPSED, entry.committedAt)
        assertEquals("exactly one TrustedQuotaEntry through the engine path", 1, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `R7-F1 the engine success path mints nothing when the trust predicate fails`() = runTest {
        // GUARDRAIL polarity (passes under the committed skeleton AND under a correct GREEN; RED only
        // under a "mint regardless of decision" attack): a §6.4-FAILING context (HOOK deliveryMode
        // masquerading as independently verified, INV-06) must mint NOTHING through the engine path.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(
            planId, runner, gps, clock,
            trustProvider = { attemptId, _ ->
                fullContext().copy(
                    execution = fullEvidenceExecution(WIRE_VERIFIED).copy(attemptId = attemptId),
                    preObservation = validPre().copy(deliveryMode = "HOOK")
                )
            }
        ).run()

        assertEquals(
            "a §6.4-failing completion must mint no TrustedQuotaEntry through the engine path",
            0, db.trustedQuotaDao().countAll()
        )
    }

    // ---- R7-F2: crash-window reconcile + schedule-advance gate through the REAL recovery consumer ----

    @Test
    fun `R7-F2 crash window b - provider applied but no receipt - engine recovery re-invokes the executor idempotently, records receipt and checkpoint, gates, then advances`() = runTest {
        // M-CR-02 THROUGH the engine: the prior process called the provider (effect 1) but crashed
        // before recording the receipt. Sol round-6 F2 kill: a fully-implemented but NEVER-called
        // coordinator leaves invocation=1 / no receipt / no checkpoint (RED); the committed skeleton
        // coordinator returns INSUFFICIENT_EVIDENCE and the engine fails closed (also RED here).
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, key = "k-77", digest = "digest-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = "k-77", requestDigest = "digest-77", now = 1000L)
        assertNull("M-CR-02: provider applied but no durable receipt exists", log.receiptFor("k-77"))
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf("k-77" to true))
        val quota = SeededQuota(mapOf(77L to true))
        val coordinator = RecoveryCoordinator(executor, log, observe, revision, quota)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, coordinator = coordinator).run()

        assertEquals(
            "M-CR-02: the engine-driven reconcile must RE-INVOKE the executor (receipt was absent) — 1 → 2",
            2, executor.invocationCount("k-77")
        )
        assertEquals("the provider effect must stay at one (provider idempotency, at-most-once)", 1, executor.effectCount(77L))
        assertNotNull("a durable receipt must now exist (recorded through the engine-driven reconcile)", log.receiptFor("k-77"))
        assertEquals("digest-77", log.receiptFor("k-77")!!.requestDigest)
        assertNotNull("a checkpoint must be recorded for the reconciled attempt", log.checkpointFor(77L))
        assertEquals("the checkpoint must bind the receipt key", "k-77", log.checkpointFor(77L)!!.receiptKey)
        // The schedule-advance gate must acquire each fact for the REAL identity (identity-keyed fakes:
        // a wrong/garbage identity returns the default false ⇒ gate holds ⇒ no advance ⇒ earlier asserts fail).
        assertEquals("the gate must acquire the observation fact for the REAL attempt", listOf(77L), observe.calls)
        assertEquals("the gate must acquire the revision fact for the REAL receipt key", listOf("k-77"), revision.calls.map { it.first })
        assertEquals("the gate must acquire the quota fact for the REAL attempt", listOf(77L), quota.calls)
        // Converged: the crashed attempt is terminalized and the plan resumes to completion.
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("interrupted", attempts.first { it.id == 77L }.status)
        assertTrue("the task must be re-attempted after recovery", attempts.any { it.id != 77L && it.status == "succeeded" })
        assertEquals("completed", db.locationTaskDao().getTaskById(taskId)!!.status)
        assertEquals("completed", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `R7-F2 crash window c - receipt present before checkpoint - engine recovery replays without re-invoking the executor, repairs the checkpoint, then advances`() = runTest {
        // Window (c) THROUGH the engine: provider applied AND receipt recorded; crash before checkpoint.
        // Post-crash the engine must REPLAY (no second provider call) and repair the checkpoint.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, key = "k-77", digest = "digest-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = "k-77", requestDigest = "digest-77", now = 1000L)
        log.seedReceipt(idempotencyKey = "k-77", requestDigest = "digest-77", outcome = "RELEASED", createdAt = 1000L)
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf("k-77" to true))
        val quota = SeededQuota(mapOf(77L to true))
        val coordinator = RecoveryCoordinator(executor, log, observe, revision, quota)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, coordinator = coordinator).run()

        assertEquals(
            "REPLAYED_APPLY: the executor must NOT be re-invoked when a receipt already exists",
            1, executor.invocationCount("k-77")
        )
        assertEquals("the provider effect must stay at one", 1, executor.effectCount(77L))
        assertNotNull("window-c reconcile MUST repair the missing checkpoint", log.checkpointFor(77L))
        assertEquals("the repaired checkpoint must bind the replayed receipt key", "k-77", log.checkpointFor(77L)!!.receiptKey)
        assertEquals("the gate must acquire the observation fact for the REAL attempt", listOf(77L), observe.calls)
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("interrupted", attempts.first { it.id == 77L }.status)
        assertTrue("the plan must resume after a replayed recovery", attempts.any { it.id != 77L && it.status == "succeeded" })
        assertEquals("completed", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `R7-F2 the engine does not advance to the next task when the schedule-advance gate holds`() = runTest {
        // GUARDRAIL polarity under the committed skeleton (the skeleton coordinator returns
        // INSUFFICIENT_EVIDENCE ⇒ fail-closed stop ⇒ no advance — passes), RED under both bypass
        // attacks: "coordinator correct but engine never consults it" (legacy sweep re-attempts ⇒
        // runner.calls = 1) and "gate hardcoded ADVANCED" (advances despite the false fact ⇒ 1).
        // §5 boundary: without the observation fact Auto must NOT assume the schedule advanced.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, key = "k-77", digest = "digest-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = "k-77", requestDigest = "digest-77", now = 1000L)
        log.seedReceipt(idempotencyKey = "k-77", requestDigest = "digest-77", outcome = "RELEASED", createdAt = 1000L)
        val observe = SeededObserve(mapOf(77L to false)) // observation does NOT match ⇒ gate must hold
        val revision = SeededRevision(mapOf("k-77" to true))
        val quota = SeededQuota(mapOf(77L to true))
        val coordinator = RecoveryCoordinator(executor, log, observe, revision, quota)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, coordinator = coordinator).run()

        assertEquals(
            "gate held ⇒ the engine must NOT start a fresh attempt (§5 boundary / §8.2: 证据不足走 PAUSED)",
            0, runner.calls
        )
        assertTrue(
            "gate held ⇒ no succeeded attempt may appear",
            db.testAttemptDao().getAttemptsForTask(taskId).none { it.status == "succeeded" }
        )
        assertNotEquals(
            "gate held ⇒ the task must NOT complete",
            "completed",
            db.locationTaskDao().getTaskById(taskId)!!.status
        )
    }

    // ---- R7-F4: the complete ordered §8.1 audit trail through the engine success path ----

    @Test
    fun `R7-F4 the engine success path appends the complete ordered canonical §8_1 audit trail bound to the real attempt, in step with the lifecycle`() = runTest {
        // Sol round-6 F4 kill: the R6 engine test asserted `audit.isNotEmpty()` + first-row attemptId,
        // which greened while the engine drove only ONE of the ten §8.1 transitions. Here the FULL
        // ordered canonical trail must appear bound to the REAL attempt — and it must appear IN STEP:
        // the fake runner observes the durable audit prefix at run-test entry (apply + pre-observe +
        // start MUST already be audited — §8.1 forbids starting CellRebel without a pre-observation)
        // and again right after RUNNING is observed. A "dump the whole trail at creation" attack fails
        // the entry prefix (10 ≠ 4); a "dump at finalize" attack fails it too (0 ≠ 4); a partial
        // lifecycle (Sol's round-6 counterexample) fails both the prefix and the final trail.
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
        buildEngine(
            planId, runner, gps, clock,
            driver = APlusAttemptDriver(auditDao) { 1_000_000L },
            trustProvider = { attemptId, _ ->
                fullContext().copy(execution = fullEvidenceExecution(WIRE_VERIFIED).copy(attemptId = attemptId))
            }
        ).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId)
            .first { it.status == "succeeded" }.id
        val canonical = APlusRunTemplate.CANONICAL_HAPPY_PATH.map { it.name }
        assertEquals(
            "at run-test entry the durable audit must already hold the pre-run §8.1 prefix " +
                "(BEGIN_APPLY → APPLY_RECEIPT → PRE_OBSERVATION_OK → START_CELLREBEL); got $atRunEntry " +
                "(skeleton/empty ⇒ RED; partial lifecycle ⇒ RED; dumped-at-creation ⇒ RED)",
            canonical.take(4),
            atRunEntry
        )
        assertEquals(
            "right after RUNNING is observed the audit must hold the prefix + NEW_RUN_OBSERVED; got $afterRunningObserved",
            canonical.take(5),
            afterRunningObserved
        )
        val trail = auditDao.forAttempt(realAttemptId)
        assertEquals(
            "the engine success path must append the COMPLETE ordered canonical §8.1 trail " +
                "(${canonical.size} transitions) bound to the real attempt",
            canonical,
            trail.map { it.eventType }
        )
        assertTrue(
            "every audit row must bind the REAL attempt identity",
            trail.isNotEmpty() && trail.all { it.attemptId == realAttemptId }
        )
    }
}
