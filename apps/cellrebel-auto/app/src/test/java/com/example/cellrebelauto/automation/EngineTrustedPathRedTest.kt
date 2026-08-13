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
 * R10 — production-reachability REDs with the normal path provider-driven apply→lease→release and the
 * Attempt's PERSISTED current operation as the recovery authority (Issue #5, §11.7; Sol round-9 advisory).
 *
 * Sol's round-9 falsification (6 P1 + 1 P2 + 1 addendum) each map to a repair + RED:
 *  - P1-1 (schema boundary): folded back to exact v5 (no v6 bump).
 *  - P1-2 (normal chain forges receipts): the normal path drives `dispatchApply` → `ApplyOutcome.leaseId`
 *    → persisted lease → `releaseLease` → typed receipt; the RED asserts the provider effect + lease readback.
 *  - P1-3 (M-CR-02 pre-seeded lease): the lease comes back from `reconcile` (typed `ReconcileResult`),
 *    never pre-seeded in the fixture.
 *  - P1-4 (full phase + session): only `APPLY_PENDING` re-applies; later states release-converge; the
 *    recovered session is excluded from the global sweep.
 *  - P1-5 (release durability): exact receipt field assertions + M-CR-08 replay + zero-reinvoke.
 *  - P1-6/P2 (carrier value domain): successOrdinal 1-based; unverified record exact fields; non-constant
 *    attempt identity (dummy attempt + explicit taskId).
 *  - addendum (cancel/throw): A+ cancel/throw leaves the attempt recoverable, never blindly terminalized.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.10–§11.11.
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

    private class FakeEvidenceSource(
        private val lat: Double,
        private val lng: Double,
        private val wire: Int,
        private val deliveryMode: String,
        private val present: Boolean = true
    ) : APlusEvidenceSource {
        // Placeholder run id — the INV-23 three-way hash is GREEN (skeleton TrustPolicy does not check it);
        // the frozen digest preimage (§6.3.4) is contract-owned. The fake only proves the shape is
        // recomputable from owner state, not that the value matches (that is GREEN).
        private fun intentHash(attemptId: Long) = APlusOperationIdentity.requestDigest(lat, lng, attemptId, 0L)

        // The observation/evidence lease MUST match the provider's apply lease (INV-07/23) — Sol round-10
        // P1-1: a fixed "L1" would be rejected by a correct lease binding, so it must be the provider lease.
        private fun providerLease(attemptId: Long) = "lease-$attemptId"

        override suspend fun acquirePreObservation(attemptId: Long): ObservationSnapshot? =
            if (!present) null else ObservationSnapshot(
                leaseId = providerLease(attemptId),
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
                applyReceiptLease = providerLease(attemptId)
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
    ): AutomationEngine {
        // Service-used composition oracle (Sol round-11 P1-1): the SAME engineAplusParams the Service
        // uses, so a Service-disconnect bad impl cannot diverge from what the tests exercise.
        val params = backend?.let { APlusComposition.engineAplusParams(it) }
        return AutomationEngine(
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
            recoveryCoordinator = params?.first,
            completionEvidenceSource = params?.second
        )
    }

    // ---- Seed helpers ----

    private suspend fun seedPlan(taskId: Long, quota: Int): Long {
        val planId = db.planDao().insertPlan(
            LocationPlan(
                sourceFileName = "r10.csv", importedAt = 1000L,
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
     * Seeds an A+ crash window as OWNER state (Sol round-9 P1-3): a non-terminal attempt carrying its
     * persisted current operation (phase), plus a running owner session. NO audit row; the apply/release
     * identity is recomputed from the attempt id + coords.
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

    // ---- §6.4-positive constants ----

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
        private const val DISTINCTIVE_DIGEST = "sha256:r10f1-identity:9c2e7a"

        private fun fullEvidenceExecution(wire: Int): CellRebelExecution = CellRebelExecution(
            executionId = "exec-r10-$wire",
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

    // ---- R10-F1 positive: provider-driven apply→lease + decision RED + terminal-success ----

    @Test
    fun `R10-F1 positive - the normal chain drives apply then decide then release, and a passing completion must populate evidence, mint, and terminalize as succeeded`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedTerminalDummyAttempt(taskId = taskId, attemptId = 77L) // non-constant attempt identity (P1-6)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        val backend = FakeBackend(executor, log, SeededObserve(emptyMap()), SeededRevision(emptyMap()), SeededQuota(emptyMap()), FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = true))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id > 77L }.id
        // Provider effect + lease (P1-2): the normal chain drove the executor and persisted the lease.
        assertEquals("the normal chain must drive the apply executor exactly once", 1, executor.invocationCount(applyKey(realAttemptId)))
        assertEquals("the provider apply effect must happen exactly once", 1, executor.effectCount(realAttemptId))
        assertEquals("the lease must be persisted from the apply (never invented)", "lease-$realAttemptId", db.testAttemptDao().getAplusLeaseId(realAttemptId))
        // Finding #6 (normal receipt durability): the apply receipt must be DURABLE — a drop-receipt bad
        // impl would leave it null.
        assertNotNull("the normal apply must record a durable apply receipt", log.receiptFor(applyKey(realAttemptId)))
        assertEquals("the normal chain must drive the release executor exactly once", 1, executor.releaseInvocationCount(releaseKey(realAttemptId)))
        // RED: the skeleton decision drops the §7.1 detail, never mints, never terminalizes succeeded.
        val rows = db.attemptExecutionDao().forAttempt(realAttemptId)
        assertEquals(1, rows.size)
        assertEquals("§7.1 baseline state must survive (skeleton drops it ⇒ RED)", "IDLE", rows.first().baselineRunningState)
        assertNotNull("a §6.4-passing completion must mint a trusted entry (skeleton ⇒ RED)", db.trustedQuotaDao().getByAttempt(realAttemptId))
        assertEquals("a §6.4-passing completion must terminalize succeeded (skeleton ⇒ RED)", "succeeded", db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == realAttemptId }.status)
        // P2 dormant guardrail (GREEN): successOrdinal is the trusted count, 1-based (never 0).
        assertEquals("a first trusted success must carry successOrdinal = 1", 1, db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == realAttemptId }.successOrdinal)
        // legacy-zero
        assertEquals(0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    // ---- R10-F1 negative: §6.4-failing → unverified record (exact fields) + legacy-zero ----

    @Test
    fun `R10-F1 negative - a failing completion writes a durable unverified record and never mints nor touches the legacy counter`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedTerminalDummyAttempt(taskId = taskId, attemptId = 77L)
        val backend = FakeBackend(
            RecordingExternalApplyExecutor(), FakeDurableRecoveryLog(),
            SeededObserve(emptyMap()), SeededRevision(emptyMap()), SeededQuota(emptyMap()),
            FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "HOOK", present = true)
        )
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        val realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id > 77L }.id
        // RED (P2): a rejected completion must leave a durable UnverifiedAttemptRecord (skeleton writes none).
        val unverified = db.unverifiedAttemptRecordDao().getByAttempt(realAttemptId)
        assertNotNull("a trust-failing completion must leave a durable unverified record (skeleton ⇒ RED)", unverified)
        // P2 dormant guardrail (GREEN): the carrier binds the EXACT evidence, not a constant.
        assertEquals("unverified attemptId binds the real attempt", realAttemptId, unverified!!.attemptId)
        assertEquals("unverified reason is typed UNTRUSTED", "UNTRUSTED", unverified.reason)
        assertTrue("unverified evidenceDigest is non-empty", unverified.evidenceDigest.isNotEmpty())
        assertEquals("never mint on fail", 0, db.trustedQuotaDao().countAll())
        assertEquals("legacy-zero", 0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
        assertEquals("UNTRUSTED", db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == realAttemptId }.failureReason)
    }

    // ---- R10-F2 apply-in-flight (no pre-seeded lease, P1-3) ----

    @Test
    fun `R10-F2 apply-in-flight recovery reconciles the apply and converges a lease-bound durable release`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val sessionId = seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "APPLY_PENDING") // NO lease pre-seeded (P1-3)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        // P1-4: the fixture uses the SAME frozen digest the recovery recomputes (never a divergent constant).
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = APlusOperationIdentity.requestDigest(39.9, 116.4, 77L, sessionId), now = 1000L)
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        // RED: skeleton reconcile returns InsufficientEvidence — never re-invokes, never yields the lease.
        assertEquals("reconcile must re-invoke the apply executor (1 → 2)", 2, executor.invocationCount(applyKey(77L)))
        assertEquals("provider effect stays at one", 1, executor.effectCount(77L))
        // The lease must come BACK from the reconcile (never pre-seeded): skeleton leaves it null ⇒ RED.
        assertNotNull("the lease must come back from the reconcile and be persisted (skeleton ⇒ RED)", db.testAttemptDao().getAplusLeaseId(77L))
        assertEquals("the persisted lease is the provider lease, not a constant", "lease-77", db.testAttemptDao().getAplusLeaseId(77L))
        assertEquals("the crashed owner session must be reused, not duplicated", sessionId, db.runSessionDao().getLatest()!!.id)
    }

    // ---- R10-F2 release-in-flight (P1-4: lease persisted, release exact fields + gate) ----

    @Test
    fun `R10-F2 release-in-flight recovery converges a lease-bound release receipt and never re-applies`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val sessionId = seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "RELEASE_PENDING", aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = APlusOperationIdentity.requestDigest(39.9, 116.4, 77L, sessionId), now = 1000L)
        log.seedReceipt(idempotencyKey = applyKey(77L), requestDigest = APlusOperationIdentity.requestDigest(39.9, 116.4, 77L, sessionId), outcome = "RELEASED", createdAt = 1000L) // durable apply receipt (P1-4 receipt-first)
        val observe = SeededObserve(mapOf(77L to true))
        val revision = SeededRevision(mapOf(applyKey(77L) to true))
        val quota = SeededQuota(mapOf(77L to true))
        val backend = FakeBackend(executor, log, observe, revision, quota, FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        // The release is provider-driven + lease-bound: never re-apply; the release receipt is exact-bound.
        assertEquals("release-in-flight recovery must never re-invoke apply", 1, executor.invocationCount(applyKey(77L)))
        val receipt = log.releaseReceiptFor("lease-77")
        assertNotNull("the release must converge a durable receipt bound to the lease", receipt)
        assertEquals("release receipt idempotencyKey", releaseKey(77L), receipt!!.idempotencyKey)
        assertEquals("release receipt leaseId", "lease-77", receipt.leaseId)
        assertEquals("release receipt digest over the lease", releaseDigest("lease-77"), receipt.releaseDigest)
        assertEquals("release effect once", 1, executor.releaseEffectCount(77L))
        // P1-5: the provider call args must bind operation key ↔ lease ↔ digest, not a bare call count.
        val releaseCall = executor.releaseCallsFor(77L).single()
        assertEquals("release call key", releaseKey(77L), releaseCall.idempotencyKey)
        assertEquals("release call lease", "lease-77", releaseCall.leaseId)
        assertEquals("release call digest over the lease", releaseDigest("lease-77"), releaseCall.releaseDigest)
        // RED: the schedule gate must acquire each fact for the REAL identity and ADVANCE
        // (skeleton NOT_ADVANCED acquires nothing → PAUSED, never resumed).
        assertEquals("the gate must acquire the observation fact for the REAL attempt", listOf(77L), observe.calls)
        assertEquals("the schedule gate must advance → the plan resumes → completed", "completed", db.runSessionDao().getLatest()!!.status)
    }

    // ---- R10-F2 pre-BEGIN-APPLY (aplusState null → never apply-reconciled) ----

    @Test
    fun `R10-F2 pre-BEGIN-APPLY attempt is never reconciled as an apply`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = null)
        val executor = RecordingExternalApplyExecutor()
        val backend = FakeBackend(executor, FakeDurableRecoveryLog(), SeededObserve(emptyMap()), SeededRevision(emptyMap()), SeededQuota(emptyMap()), FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        assertEquals("a pre-BEGIN-APPLY attempt must never be apply-reconciled", 0, executor.invocationCount(applyKey(77L)))
        assertEquals("interrupted", db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }.status)
    }

    // ---- R10-F2 DECIDING crash: release-only, never re-apply (P1-4 full phase) ----

    @Test
    fun `R10-F2 a DECIDING crash is release-converged and never re-applied`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val sessionId = seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = APlusOperationIdentity.requestDigest(39.9, 116.4, 77L, sessionId), now = 1000L)
        log.seedReceipt(idempotencyKey = applyKey(77L), requestDigest = APlusOperationIdentity.requestDigest(39.9, 116.4, 77L, sessionId), outcome = "RELEASED", createdAt = 1000L) // durable apply receipt (P1-4 receipt-first)
        val backend = FakeBackend(executor, log, SeededObserve(mapOf(77L to true)), SeededRevision(mapOf(applyKey(77L) to true)), SeededQuota(mapOf(77L to true)), FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        // DECIDING already has a durable apply → release-only, never re-applied (P1-4).
        assertEquals("a DECIDING crash must not re-invoke apply", 1, executor.invocationCount(applyKey(77L)))
        assertNotNull("a DECIDING crash must converge a release receipt", log.releaseReceiptFor("lease-77"))
    }

    // ---- R10-F4: the complete ordered §8.1 audit trail (driver no-op ⇒ RED) ----

    @Test
    fun `R10-F4 the engine A+ path appends the complete ordered canonical section8-1 audit trail in step`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedTerminalDummyAttempt(taskId = taskId, attemptId = 77L) // force real id ≠ 1 (P1-6)
        val auditDao = db.auditEventDao()
        var atRunEntry: List<String>? = null
        var afterRunningObserved: List<String>? = null
        var realAttemptId = -1L
        val clock = VirtualClock()
        val runner = object : CellRebelRunner {
            override suspend fun runTest(
                startedAt: Long,
                testTimeoutMs: Long,
                onRunningObserved: suspend (Long) -> Unit
            ): AttemptOutcome {
                // P1-6: query the REAL attempt's audit (never global auditDao.all()), so a wrong-id
                // prefix attack cannot green the in-step checks.
                realAttemptId = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id != 77L }.id
                atRunEntry = auditDao.forAttempt(realAttemptId).map { it.eventType }
                onRunningObserved(4242L)
                afterRunningObserved = auditDao.forAttempt(realAttemptId).map { it.eventType }
                return AttemptOutcome.Success(
                    webScore = 8.0, videoScore = 7.0, runningObservedAt = 4242L,
                    startedAt = startedAt, endedAt = 4300L
                )
            }
        }
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, driver = APlusAttemptDriver(auditDao) { 1_000_000L }, backend = passingBackend()).run()

        assertTrue("the real attempt id must be past the dummy (≠ 1L)", realAttemptId > 77L)
        val canonical = APlusRunTemplate.CANONICAL_HAPPY_PATH.map { it.name }
        assertEquals("at run-test entry the audit must hold the pre-run §8.1 prefix; got $atRunEntry (skeleton/empty ⇒ RED)", canonical.take(4), atRunEntry)
        assertEquals("after RUNNING the audit must hold prefix + NEW_RUN_OBSERVED; got $afterRunningObserved", canonical.take(5), afterRunningObserved)
        val trail = auditDao.forAttempt(realAttemptId)
        assertEquals("the A+ path must append the COMPLETE ordered §8.1 trail", canonical, trail.map { it.eventType })
        assertTrue("every audit row binds the REAL attempt", trail.isNotEmpty() && trail.all { it.attemptId == realAttemptId })
        // P1-6: no foreign audit rows for any other attempt (the dummy must have zero rows).
        assertEquals("the audit stream must have zero foreign rows (dummy attempt must carry none)", trail.size, auditDao.all().size)
        assertEquals("the terminal dummy must carry no audit rows", 0, auditDao.forAttempt(77L).size)
    }

    // ---- R10-P1-1: the SHIPPED production backend is non-null fail-closed ----

    @Test
    fun `R10-P1-1 the shipped production backend is non-null and fails closed - no lease, no mint, PAUSED`() = runTest {
        // The shipped skeleton (APlusComposition.productionBackend()) must be exercised: it yields NO lease
        // (fail-closed executor) → the A+ path PAUSES at apply, never reaches decide, never mints, never
        // touches the legacy counter. A green-but-disconnected helper cannot satisfy this.
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        val backend = APlusComposition.productionBackend()
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = backend).run()

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).first()
        assertEquals("the shipped backend must fail closed at apply (no lease) — aplusState stays APPLY_PENDING", "APPLY_PENDING", attempt.aplusState)
        assertNull("the shipped skeleton must never persist a lease", db.testAttemptDao().getAplusLeaseId(attempt.id))
        assertEquals("no trusted mint through the shipped skeleton", 0, db.trustedQuotaDao().countAll())
        assertEquals("legacy-zero through the shipped skeleton", 0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
        assertEquals("paused", db.runSessionDao().getLatest()!!.status)
    }

    // ---- R17 crash-matrix: recover terminal truth from the append-only carrier (Sol round-16 P1-1) ----

    private fun crashBackend(executor: RecordingExternalApplyExecutor, log: FakeDurableRecoveryLog): FakeBackend =
        FakeBackend(executor, log, SeededObserve(emptyMap()), SeededRevision(emptyMap()), SeededQuota(emptyMap()), FakeEvidenceSource(TARGET_LAT, TARGET_LNG, WIRE_VERIFIED, "SYSTEM_MOCK", present = false))

    @Test
    fun `R17 M-CR-07 a committed trusted entry projects to succeeded regardless of the phase string`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        val insertedId = db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L))
        val seededEntry = TrustedQuotaEntry(id = insertedId, attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L)
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = crashBackend(executor, log)).run()

        // The ledger is the authority: the phase string "DECIDING" must NOT degrade a committed truth to interrupted.
        assertEquals("a committed trusted entry must project to succeeded (not interrupted)", "succeeded", db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }.status)
        // P1-6 preservation: full data-class equality (id/attemptId/taskId/digest/committedAt) + per-task/legacy-zero.
        assertEquals("the trusted carrier must be byte-for-byte preserved (no tamper)", seededEntry, db.trustedQuotaDao().getByAttempt(77L))
        assertEquals("the trusted ledger count must stay 1 (no re-mint)", 1, db.trustedQuotaDao().countAll())
        assertEquals("per-task trusted count preserved", 1, db.trustedQuotaDao().trustedCountForTask(taskId))
        assertEquals("legacy-zero", 0, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
    }

    @Test
    fun `R18 a foreign-attempt trusted carrier must not fake-green the recovery`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        // A decoy: a trusted entry for a DIFFERENT attempt (99). The recovery must NOT let it fake-green 77.
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 99L, taskId = taskId, evidenceDigest = "decoy", committedAt = 1000L))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = crashBackend(executor, log)).run()

        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertNotEquals("a foreign-attempt carrier must NOT project 77 to succeeded", "succeeded", recovered.status)
    }

    @Test
    fun `R21 a foreign-attempt unverified record must not fake-green the recovery`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.unverifiedAttemptRecordDao().insert(UnverifiedAttemptRecord(attemptId = 99L, reason = "UNTRUSTED", evidenceDigest = "decoy"))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = crashBackend(executor, log)).run()

        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertEquals("a foreign unverified record must NOT project 77 to failed (no own carrier → interrupted)", "interrupted", recovered.status)
    }

    @Test
    fun `R17 unverified record projects to failed UNTRUSTED regardless of the phase string`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.unverifiedAttemptRecordDao().insert(UnverifiedAttemptRecord(attemptId = 77L, reason = "UNTRUSTED", evidenceDigest = "d"))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = crashBackend(executor, log)).run()

        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertEquals("an unverified record must project to failed (not interrupted)", "failed", recovered.status)
        assertEquals("UNTRUSTED", recovered.failureReason)
    }

    @Test
    fun `R18 a wrong-task trusted carrier fails closed`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = 999L, evidenceDigest = "d", committedAt = 1000L))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = crashBackend(executor, log)).run()

        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertNotEquals("a wrong-task carrier must NOT project to succeeded", "succeeded", recovered.status)
        assertEquals("a wrong-task carrier must persist RECOVERY_REQUIRED", "RECOVERY_REQUIRED", recovered.aplusState)
        assertEquals("a wrong-task carrier must leave the attempt NON-terminal (recoverable, not terminalized)", "starting", recovered.status)
    }

    @Test
    fun `R18 a conflicting trusted + unverified carrier fails closed`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L))
        db.unverifiedAttemptRecordDao().insert(UnverifiedAttemptRecord(attemptId = 77L, reason = "UNTRUSTED", evidenceDigest = "d"))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        buildEngine(planId, runner, gps, clock, backend = crashBackend(executor, log)).run()

        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertNotEquals("conflicting append-only truths must NOT be promoted to trusted", "succeeded", recovered.status)
        assertEquals("conflicting truths must persist RECOVERY_REQUIRED", "RECOVERY_REQUIRED", recovered.aplusState)
        assertEquals("conflicting truths must leave the attempt NON-terminal (recoverable, not terminalized)", "starting", recovered.status)
    }

    @Test
    fun `R22 a wrong-task carrier is re-selected on second restart without creating a new attempt`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = 999L, evidenceDigest = "d", committedAt = 1000L))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        val backend = crashBackend(executor, log)
        buildEngine(planId, runner, gps, clock, backend = backend).run()
        buildEngine(planId, runner, gps, clock, backend = backend).run() // second restart

        assertEquals("the attempt count must stay 1 (no new attempt on second restart)", 1, db.testAttemptDao().getAttemptsForTask(taskId).size)
        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertEquals("RECOVERY_REQUIRED must still be selected by recovery", "RECOVERY_REQUIRED", recovered.aplusState)
        assertEquals("the attempt must stay non-terminal (recoverable)", "starting", recovered.status)
    }

    @Test
    fun `R22 conflicting truths are re-selected on second restart without creating a new attempt`() = runTest {
        val taskId = 42L
        val planId = seedPlan(taskId = taskId, quota = 1)
        seedAPlusCrashAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L))
        db.unverifiedAttemptRecordDao().insert(UnverifiedAttemptRecord(attemptId = 77L, reason = "UNTRUSTED", evidenceDigest = "d"))
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.apply(attemptId = 77L, idempotencyKey = applyKey(77L), requestDigest = "d", now = 1000L)
        val clock = VirtualClock()
        val runner = FakeCellRebelRunner(listOf(successTemplate), clock.nowMs)
        val gps = FakeGpsSetter(listOf(GpsOutcome.Active))
        val backend = crashBackend(executor, log)
        buildEngine(planId, runner, gps, clock, backend = backend).run()
        buildEngine(planId, runner, gps, clock, backend = backend).run() // second restart

        assertEquals("the attempt count must stay 1 (no new attempt on second restart)", 1, db.testAttemptDao().getAttemptsForTask(taskId).size)
        val recovered = db.testAttemptDao().getAttemptsForTask(taskId).first { it.id == 77L }
        assertEquals("RECOVERY_REQUIRED must still be selected by recovery", "RECOVERY_REQUIRED", recovered.aplusState)
        assertEquals("the attempt must stay non-terminal (recoverable)", "starting", recovered.status)
    }
}
