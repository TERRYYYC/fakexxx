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
import com.example.cellrebelauto.environment.CompletionTrustContext
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
 * R8 — production-reachability REDs driven through the SINGLE composition root (Issue #5, §11.7;
 * Sol round-7 advisory).
 *
 * Sol's round-7 falsification: the engine seams (`recoveryCoordinator` / `completionTrustContextProvider`)
 * defaulted null in production while tests injected real objects directly — so a fully-implemented-but-
 * disconnected attack greened every TrustedLedger + coordinator test while `AutomationService` still
 * passed null and the engine kept walking the legacy counter path. R8 closes that with ONE composition
 * point ([APlusComposition]) wired from an [APlusBackend]: production and tests both go through it, so
 * there is no hand-wired alternate path a test can take that production does not also take.
 *
 * The five Sol round-7 findings each map to a concrete repair + RED:
 *  - P1-1 (composition) → the engine's A+ seams are produced by `APlusComposition` from a `FakeBackend`
 *    here, the same path `AutomationService` takes; a disconnected impl cannot green the positive tests.
 *  - P1-2 (state-owner identity) → recovery identity (apply/release key + intent digest) is RECOMPUTED
 *    from the durable attempt row ([APlusOperationIdentity]), never read from the append-only audit
 *    stream. The crash seed writes NO audit row — the attempt owner state is the only source.
 *  - P1-3 (legacy-zero / unverified) → the A+ mode NEVER calls `finalizeAttemptSuccess`; a trust-fail is
 *    finalized `UNTRUSTED` and the legacy `completedSuccesses` counter stays 0.
 *  - P1-4 (release convergence) → after a recovered apply the engine must `releaseLease` before
 *    advancing (dormant under the skeleton coordinator, asserted as the release-invocation gate).
 *  - P1-5 (clock) → the virtual clock starts at 1000, after the seeded session (500) and dummy attempt
 *    timestamps, so `getLatest()` resolves the engine's own session, not the seed.
 *
 * Under the pre-freeze skeletons the A+ lifecycle is fail-closed: the recovery coordinator's
 * [com.example.cellrebelauto.recovery.RecoveryCoordinator.reconcile] returns INSUFFICIENT_EVIDENCE and
 * [com.example.cellrebelauto.repository.PlanRepository.recordTrustedCompletion] drops the §7.1 evidence
 * detail and never mints — so every positive assertion here stays RED, and the guardrail polarities
 * (at-most-once effect, legacy-zero, fail-closed PAUSED) hold.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.7–§11.8.
 *
 * # R8 生产可达性 RED（经单一组合根）：F1 可信完成入口 / F2 恢复 owner 态身份+release 收敛 / F4 完整 §8.1 审计
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

    /** # 虚拟时钟：时间只在 delay 时前进；初始时刻在 seed 时间戳之后（P1-5） */
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

    /** # A+ 证据源假：返回脚本化的 pre/post observation + 完成证据 */
    private class FakeEvidenceSource(
        private val pre: ObservationSnapshot?,
        private val post: ObservationSnapshot?,
        private val completion: APlusCompletionEvidence?
    ) : APlusEvidenceSource {
        override suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot? = pre
        override suspend fun acquirePostObservation(attemptId: Long): ObservationSnapshot? = post
        override suspend fun acquireCompletionEvidence(attemptId: Long): APlusCompletionEvidence? = completion
    }

    /**
     * # A+ 后端假束：把 recovery 五件套 + 证据源捆成一个 APlusBackend，经 APlusComposition 组合（P1-1）
     */
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

    /** # 单一组合点：生产与测试都从这里把 backend 接成 coordinator + evidence source（P1-1） */
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

    /** Seeds a single-task plan with an EXPLICIT task id (≠ 1L — kills constant-identity mints). */
    private suspend fun seedPlan(taskId: Long, quota: Int): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "r8.csv", importedAt = 1000L,
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
            "R8 setup: Room did not honour the explicit task id $taskId"
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
     * Seeds an A+ crash window: a NON-terminal attempt (status `starting`) with NO audit row. Its apply/
     * release identity is RECOMPUTED by the engine from this durable owner state via
     * [APlusOperationIdentity] (§7.1: the Attempt owns its 当前 operation; `AutoAuditEvent` is append-only,
     * never a state owner — Sol round-7 P1-4). The seed therefore cannot smuggle a key/digest in through
     * the audit stream.
     */
    private suspend fun seedAPlusCrashAttempt(planId: Long, taskId: Long, attemptId: Long) {
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
    private val EXEC_COMPLETED_AT_ELAPSED = 13000L
    private val PRE_OBSERVED_AT_ELAPSED = 1000L
    private val POST_OBSERVED_AT_ELAPSED = 14000L
    private val CONTINUITY_SINCE_ELAPSED = 500L
    private val DISTINCTIVE_DIGEST = "sha256:r8f1-identity:9c2e7a"

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
        executionId = "exec-r8-$wire",
        attemptId = 0L, // the engine binds the REAL attempt id
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

    /** # 后端供的完成证据 artifact（不含目标坐标与本地重算 hash——由持久 intent 组装，INV-23） */
    private fun fullCompletionEvidence(wire: Int = WIRE_VERIFIED): APlusCompletionEvidence =
        APlusCompletionEvidence(
            execution = fullEvidenceExecution(wire),
            completionEvidenceWire = wire,
            applyReceiptIntentHash = INTENT_HASH,
            applyReceiptLease = LEASE
        )

    /** # §6.4-passing 后端：观察 + 完成证据齐全；recovery 五件套空实现（正路径用不到 reconcile 门） */
    private fun passingBackend(): FakeBackend = FakeBackend(
        RecordingExternalApplyExecutor(),
        FakeDurableRecoveryLog(),
        SeededObserve(emptyMap()),
        SeededRevision(emptyMap()),
        SeededQuota(emptyMap()),
        FakeEvidenceSource(validPre(), validPost(), fullCompletionEvidence())
    )

    private fun applyKey(attemptId: Long): String = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    private fun releaseKey(attemptId: Long): String = APlusOperationIdentity.releaseIdempotencyKey(attemptId)

    // ---- R8-F1: trusted ledger through the REAL A+ completion entry (P1-1/2/3) ----

    @Test
    fun `R8-F1 the engine A+ mode drives the trusted completion entry - section7-1 detail and mint are RED and the legacy counter stays zero`() = runTest {
        // Sol round-7 P1-1/P1-3: the engine's A+ mode (composed via APlusComposition) must reach
        // recordTrustedCompletion and decide PASS/FAIL — never the legacy finalizeAttemptSuccess. Under
        // the skeleton the §7.1 detail is dropped (null), nothing mints, and the legacy counter stays 0.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedTerminalDummyAttempt(taskId = taskId, attemptId = 77L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = passingBackend()).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id > 77L }.id
        assertTrue("the real attempt id must be past the dummy (≠ 1L)", realAttemptId > 77L)

        // The A+ mode reached recordTrustedCompletion: exactly one execution row persisted.
        val rows = db.attemptExecutionDao().forAttempt(realAttemptId)
        assertEquals(
            "the A+ mode must persist exactly one execution row through the engine's trusted completion entry",
            1, rows.size
        )
        val exec = rows.first()
        // RED: the skeleton drops the §7.1 evidence detail.
        assertEquals("§7.1 baseline state must survive the production path (skeleton drops it ⇒ RED)", "IDLE", exec.baselineRunningState)
        assertEquals("§7.1 running marker must survive (skeleton drops it ⇒ RED)", "RUNNING", exec.runningMarkerText)
        assertEquals(
            "§7.1 RUNNING duration must survive (skeleton drops it ⇒ RED)",
            EXEC_COMPLETED_AT_ELAPSED - EXEC_RUNNING_CONFIRMED_AT_ELAPSED,
            exec.runningDurationMs
        )
        assertEquals("§7.1 web score must survive (skeleton drops it ⇒ RED)", 8.0, exec.webBrowsingScore ?: -1.0, 0.001)
        assertEquals("§7.1 video score must survive (skeleton drops it ⇒ RED)", 7.0, exec.videoStreamingScore ?: -1.0, 0.001)
        assertEquals(
            "§7.1 per-round timestamps must survive (skeleton drops it ⇒ RED)",
            "$EXEC_STARTED_AT_ELAPSED;$EXEC_COMPLETED_AT_ELAPSED",
            exec.roundTimestampsElapsed
        )
        // RED: a §6.4-passing completion must mint exactly one TrustedQuotaEntry (skeleton never mints).
        assertNotNull(
            "a §6.4-passing completion must mint a trusted entry (skeleton mints none ⇒ RED)",
            db.trustedQuotaDao().getByAttempt(realAttemptId)
        )
        // P1-3 legacy-zero: the A+ mode never touches the legacy completedSuccesses counter.
        assertEquals(
            "trust-fail must never increment the legacy counter (Sol round-7 P1-3)",
            0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses
        )
        // P1-3 unverified: the attempt is finalized UNTRUSTED, never finalizeAttemptSuccess.
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == realAttemptId }
        assertEquals("a trust-failing completion is UNVERIFIED_RECORDED (failed), never succeeded", "failed", attempt.status)
        assertEquals("UNTRUSTED", attempt.failureReason)
        // Fail-closed: the plan PAUSED rather than silently retrying or walking the legacy counter.
        assertEquals("paused", db.runSessionDao().getLatest()!!.status)
    }

    // ---- R8-F2: crash-window reconcile + release convergence through the REAL recovery consumer (P1-2/4) ----

    @Test
    fun `R8-F2 crash window b - engine recovery reconciles from OWNER state, re-invokes the executor, converges release, and gates`() = runTest {
        // M-CR-02 through the engine, with the identity recomputed from the durable attempt (no audit
        // row). Sol round-7 P1-4: the reconcile must re-invoke with the SAME recomputed key; P1-4/P1-6
        // release convergence: after ADVANCED the engine must releaseLease before advancing. Under the
        // skeleton reconcile returns INSUFFICIENT_EVIDENCE ⇒ fail-closed PAUSED ⇒ every positive is RED.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "digest-77", now = 1000L)
        assertNull("M-CR-02: provider applied but no durable receipt exists", log.receiptFor(applyKey(77L)))
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(null, null, null))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        assertEquals(
            "the engine-driven reconcile must RE-INVOKE the executor (receipt absent) — 1 → 2",
            2, executor.invocationCount(applyKey(77L))
        )
        assertEquals("the provider effect must stay at one (idempotency, at-most-once)", 1, executor.effectCount(77L))
        assertNotNull("a durable receipt must be recorded through the engine-driven reconcile", log.receiptFor(applyKey(77L)))
        assertNotNull("a checkpoint must be recorded for the reconciled attempt", log.checkpointFor(77L))
        // P1-4 release convergence: after ADVANCED the engine must releaseLease (skeleton never reaches it).
        assertEquals(
            "the engine must converge release exactly once after a recovered apply (RED under skeleton)",
            1, executor.releaseInvocationCount(releaseKey(77L))
        )
        // The gate acquires each fact for the REAL recomputed identity.
        assertEquals("the gate must acquire the observation fact for the REAL attempt", listOf(77L), observe.calls)
        assertEquals("the gate must acquire the revision fact for the REAL receipt key", listOf(applyKey(77L)), revision.calls.map { it.first })
        assertEquals("the gate must acquire the quota fact for the REAL attempt", listOf(77L), quota.calls)
        // Fail-closed under the skeleton: the plan PAUSED and did not resume.
        assertEquals("reconcile INSUFFICIENT_EVIDENCE ⇒ durable PAUSED", "paused", db.runSessionDao().getLatest()!!.status)
    }

    @Test
    fun `R8-F2 crash window c - receipt present - engine recovery replays without re-invoking and repairs the checkpoint`() = runTest {
        // Window (c): provider applied AND receipt recorded; crash before checkpoint. Post-crash the
        // engine must REPLAY (no second provider call) and repair the checkpoint.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "digest-77", now = 1000L)
        log.seedReceipt(idempotencyKey = applyKey(77L), requestDigest = "digest-77", outcome = "RELEASED", createdAt = 1000L)
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(null, null, null))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        assertEquals("REPLAYED_APPLY: the executor must NOT be re-invoked when a receipt exists", 1, executor.invocationCount(applyKey(77L)))
        assertEquals("the provider effect must stay at one", 1, executor.effectCount(77L))
        assertNotNull("window-c reconcile MUST repair the missing checkpoint", log.checkpointFor(77L))
        assertEquals("the repaired checkpoint must bind the replayed receipt key", applyKey(77L), log.checkpointFor(77L)!!.receiptKey)
    }

    @Test
    fun `R8-F2 the engine does not advance to the next task when the recovery cannot converge`() = runTest {
        // GUARDRAIL polarity: under the skeleton the reconcile returns INSUFFICIENT_EVIDENCE ⇒ the engine
        // fails closed (PAUSED) and must NOT start a fresh attempt (§8.2 RECOVERING / §5 boundary). RED
        // under a "hardcode ADVANCED" attack (which would resume and start a fresh runner call).
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "digest-77", now = 1000L)
        log.seedReceipt(idempotencyKey = applyKey(77L), requestDigest = "digest-77", outcome = "RELEASED", createdAt = 1000L)
        val observe = SeededObserve(mapOf(77L to false)) // observation does NOT match ⇒ gate would hold
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(null, null, null))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        assertEquals("the engine must NOT start a fresh attempt when recovery cannot converge", 0, runner.calls)
        assertTrue(
            "no succeeded attempt may appear when recovery fails closed",
            db.testAttemptDao().getAttemptsForTask(taskId).none { it.status == "succeeded" }
        )
        assertNotEquals("the task must NOT complete", "completed", db.locationTaskDao().getTaskById(taskId)!!.status)
    }

    // ---- R8-F4: the complete ordered §8.1 audit trail through the engine A+ path ----

    @Test
    fun `R8-F4 the engine A+ path appends the complete ordered canonical section8-1 audit trail in step with the lifecycle`() = runTest {
        // The driver is a no-op skeleton, so the durable audit trail is EMPTY — RED. The fake runner
        // observes the audit prefix at run-test entry (apply + pre-observe + start MUST already be
        // audited) and right after RUNNING is observed. A correct GREEN driver consults AttemptTransitions
        // and appends the canonical trail in step; a partial-lifecycle or dump-at-creation attack fails
        // the in-step prefix.
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
            backend = passingBackend()
        ).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first().id
        val canonical = APlusRunTemplate.CANONICAL_HAPPY_PATH.map { it.name }
        assertEquals(
            "at run-test entry the durable audit must already hold the pre-run §8.1 prefix " +
                "(BEGIN_APPLY → APPLY_RECEIPT → PRE_OBSERVATION_OK → START_CELLREBEL); got $atRunEntry " +
                "(skeleton/empty ⇒ RED)",
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
            "the engine A+ path must append the COMPLETE ordered canonical §8.1 trail bound to the real attempt",
            canonical,
            trail.map { it.eventType }
        )
        assertTrue(
            "every audit row must bind the REAL attempt identity",
            trail.isNotEmpty() && trail.all { it.attemptId == realAttemptId }
        )
    }
}
