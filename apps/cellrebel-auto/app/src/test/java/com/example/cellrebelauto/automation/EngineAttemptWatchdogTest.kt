package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.aplus.AttemptEvent
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.automation.selfheal.AttemptWatchdogPolicy
import com.example.cellrebelauto.data.SelfHealConfig
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.repository.PlanRepository
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P1.3 #1 — the attempt-watchdog oracle.
 *
 * Device truth (measured incident): a service-instance rebuild left the engine holding a dead
 * runner callback; the attempt row stayed `running` for 8+ minutes with no timeout and no reaping —
 * only the next manual Resume reaped it. The watchdog must fire at
 * `max(90s, 3× the task's historical median attempt duration)`, terminalize the zombie UNTRUSTED
 * through the §8.2 RECOVERING terminalization path (durable release + typed failure, never a second
 * terminal enum), leave an audit row, and schedule the retry through the normal COOLDOWN gate.
 *
 * # attempt 看门狗 oracle：僵尸 attempt 必须在阈值处被终态化 UNTRUSTED + 审计行 + COOLDOWN 重试
 */
@RunWith(RobolectricTestRunner::class)
class EngineAttemptWatchdogTest {

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

    // ---- fakes ----

    /**
     * Runner whose FIRST call stalls in REAL time (production zombies are real-time hangs: a dead
     * callback never returns no matter how the clock runs); later calls succeed immediately.
     * The stall is cancellable, so the watchdog's cancel reaps it promptly.
     */
    private class StalledRunner(
        private val firstCallStallRealMs: Long
    ) : CellRebelRunner {
        var calls = 0
            private set

        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onStartInteraction: suspend () -> Unit,
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            val call = ++calls
            onStartInteraction()
            onRunningObserved(startedAt + 1_000)
            if (call == 1 && firstCallStallRealMs > 0) {
                withContext(Dispatchers.Default) { delay(firstCallStallRealMs) }
            }
            return AttemptOutcome.Success(
                webScore = 8.0, videoScore = 7.0, runningObservedAt = startedAt + 1_000,
                startedAt = startedAt, endedAt = startedAt + 45_000
            )
        }
    }

    private class FakeGpsSetter : GpsLocationSetter {
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome = GpsOutcome.Active
    }

    // ---- seed helpers ----

    private suspend fun seedPlan(quota: Int, bufferSeconds: Int = 0): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = bufferSeconds, totalRows = 1, totalRequiredSuccesses = quota
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        return planId to taskId
    }

    private suspend fun seedHistoryDuration(taskId: Long, durationMs: Long) {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 0L, planId = null))
        db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = 1, startedAt = 0L, runningObservedAt = 1_000L,
                endedAt = durationMs, status = "succeeded", failureReason = null,
                webBrowsingScore = 8.0, videoStreamingScore = 7.0,
                latitude = 39.9, longitude = 116.4
            )
        )
    }

    // ---- A+ fixture (executor + evidence), positive tuple per TrustPolicy's canonical case ----

    private val scheduleId = "sched-wd-1"
    private val itemId = "item-wd-3b"
    private val version = 12L

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(
            attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String,
            requestDigest: String, now: Long
        ): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-$attemptId", operationId = "op-$attemptId")

        override fun release(
            attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long
        ): ApplyOutcome = ApplyOutcome("RELEASED", false)

        override fun discover(): CapabilitySnapshotV1 = CapabilitySnapshotV1(
            serviceVersion = "fake-1.0",
            supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
            ),
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            profileRefs = listOf("p"), scheduleRefs = listOf("s"),
            currentScheduleId = scheduleId, currentItemId = itemId,
            scheduleVersion = version, exhausted = false
        )

        override fun preflight(
            intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String
        ): PreflightReportV1 = PreflightReportV1(
            acceptedIntentHash = requestDigest,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            waitUntilEpochMs = null,
            achievableVerificationLevelWire =
                VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L, blockingReasonWires = emptyList(),
            scheduleItemId = itemId, scheduleVersion = version, exhausted = false
        )

        override fun observe(
            leaseId: String, operationId: String, expectedIntentHash: String
        ): EnvironmentObservationV1 = EnvironmentObservationV1(
            leaseId = leaseId, acceptedIntentHash = expectedIntentHash,
            observedAtEpochMs = 0L, observedAtElapsedRealtimeMs = 0L,
            environmentRevision = 7L, environmentFingerprint = "fp",
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = null, continuitySinceElapsedRealtimeMs = null,
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = null, effectiveLongitude = null, isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = listOf("qwy:store:abc"),
            scheduleItemId = "item-after-wd", scheduleVersion = version + 1
        )

        override fun completeAndAdvance(
            request: CompleteAndAdvanceRequestV1, expectedIntentHash: String
        ): AdvanceReceiptV1 {
            val base = AdvanceReceiptV1(
                outcomeWire = 1, advancedFromItemId = itemId, advancedToItemId = "item-after-wd",
                scheduleVersionAfter = version + 1, effectiveIntentHash = "eff-wd",
                effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
            )
            return base.copy(
                receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                    base, request.requestDigest, request.idempotencyKey
                )
            )
        }
    }

    /** Positive §6.4 evidence source whose observations carry the given effective coordinates. */
    private inner class ScriptedEvidenceSource(
        private val planId: Long,
        private val effectiveLat: Double = 39.9,
        private val effectiveLng: Double = 116.4
    ) : APlusEvidenceSource {

        override suspend fun acquirePreObservation(
            attemptId: Long, runSessionId: Long
        ): ObservationSnapshot = observation(attemptId, runSessionId, observedAtElapsed = 1_000L)

        override suspend fun acquirePostObservation(
            attemptId: Long, runSessionId: Long
        ): ObservationSnapshot = observation(attemptId, runSessionId, observedAtElapsed = 14_000L)

        override suspend fun acquireCompletionEvidence(
            attemptId: Long, runSessionId: Long
        ): APlusCompletionEvidence {
            val digest = intentDigest(attemptId, runSessionId)
            return APlusCompletionEvidence(
                execution = CellRebelExecution(
                    executionId = "exec-src-$attemptId", attemptId = attemptId,
                    completionEvidenceWire = 1, evidencePayloadDigest = "ev-$attemptId",
                    startedAt = 1000L, classifiedAt = 1100L,
                    startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L,
                    completedAtElapsed = 13000L,
                    baselineRunningState = "IDLE", runningMarkerText = "RUNNING",
                    runningDurationMs = 10900L,
                    webBrowsingScore = 8.0, videoStreamingScore = 7.0,
                    roundTimestampsElapsed = "2000;13000"
                ),
                completionEvidenceWire = 1,
                applyReceiptIntentHash = digest,
                applyReceiptLease = "lease-$attemptId"
            )
        }

        private suspend fun observation(
            attemptId: Long, runSessionId: Long, observedAtElapsed: Long
        ): ObservationSnapshot {
            val digest = intentDigest(attemptId, runSessionId)
            return ObservationSnapshot(
                leaseId = "lease-$attemptId", acceptedIntentHash = digest,
                coverage = "FULL",
                verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK", isMock = true,
                scheduleDecision = "ALLOWED_NOW",
                effectiveLat = effectiveLat, effectiveLng = effectiveLng,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = observedAtElapsed, observedAtEpochMs = 0L,
                continuitySinceElapsedRealtimeMs = 500L,
                evidenceRefs = listOf("qwy:store:abc")
            )
        }

        private suspend fun intentDigest(attemptId: Long, runSessionId: Long): String {
            val attempt = repo.getAttempt(attemptId)!!
            return APlusOperationIdentity.requestDigest(
                APlusOperationIdentity.intent(
                    runSessionId, attemptId, planId, scheduleId,
                    attempt.startedAt, attempt.startedAt + 90_000L
                )
            )
        }
    }

    private fun buildAplusEngine(
        planId: Long,
        runner: CellRebelRunner,
        nowMs: () -> Long,
        delayMs: suspend (Long) -> Unit,
        config: SelfHealConfig = SelfHealConfig()
    ): AutomationEngine {
        val coordinator = RecoveryCoordinator(
            journeyExecutor,
            RoomDurableRecoveryLog(
                db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()
            ),
            observe = ObserveIntentAcquirer { true },
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = TrustedQuotaAcquirer { true }
        )
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = runner,
            gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(0, nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = nowMs, delayMs = delayMs,
            attemptDriver = APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = ScriptedEvidenceSource(planId),
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L },
            selfHealConfig = { config }
        )
    }

    // ==================== threshold formula (pure) ====================

    @Test
    fun `watchdog threshold is max of the 90s floor and 3x median`() {
        assertEquals(90_000L, AttemptWatchdogPolicy.timeoutMs(null))
        assertEquals(90_000L, AttemptWatchdogPolicy.timeoutMs(10_000L))
        assertEquals(180_000L, AttemptWatchdogPolicy.timeoutMs(60_000L))
        assertEquals(null, AttemptWatchdogPolicy.median(emptyList()))
    }

    // ==================== legacy lane ====================

    @Test
    fun `a hung legacy attempt is reaped UNTRUSTED at the 90s floor and retried through cooldown`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1, bufferSeconds = 5)
        val runner = StalledRunner(firstCallStallRealMs = 60_000L)
        val engine = AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = runner, gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(5, { testScheduler.currentTime }),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = { testScheduler.currentTime },
            delayMs = { delay(it) },
            selfHealConfig = { SelfHealConfig() }
        )
        engine.run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("the zombie must be reaped and the retry must run", 2, attempts.size)
        // # 僵尸收尸：终态 failed + UNTRUSTED，恰好在 90s 下限处开枪
        assertEquals("failed", attempts[0].status)
        assertEquals("UNTRUSTED", attempts[0].failureReason)
        assertEquals(90_000L, attempts[0].endedAt)
        // # 审计行：记录是看门狗开的枪
        val watchdogAudit = db.auditEventDao().forAttempt(attempts[0].id)
            .filter { it.eventType == "ATTEMPT_WATCHDOG_TIMEOUT" }
        assertEquals(1, watchdogAudit.size)
        // # 按 COOLDOWN 调度重试：收尸（endedAt=90s）后，重试恰好在完整缓冲（5s）之后开跑（INV-5）
        assertEquals(95_000L, attempts[1].startedAt)
        // # 重试成功完成任务
        assertEquals("succeeded", attempts[1].status)
        assertEquals(1, db.locationTaskDao().getTaskById(taskId)!!.completedSuccesses)
        // # legacy 车道终态语义：无 trusted 铸币 → 计划投影不完整 → error（INV-2 只认 trusted 完成）
        assertEquals(AutomationState.ERROR, engine.state.value)
        assertEquals(2, runner.calls)
    }

    @Test
    fun `watchdog threshold grows with the task historical median duration`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1, bufferSeconds = 0)
        // History: one terminal attempt of 60s → threshold = max(90s, 3×60s) = 180s
        seedHistoryDuration(taskId, durationMs = 60_000L)
        assertEquals(60_000L, repo.medianTerminalAttemptDurationMs(taskId))
        val runner = StalledRunner(firstCallStallRealMs = 60_000L)
        val engine = AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = runner, gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(0, { testScheduler.currentTime }),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = { testScheduler.currentTime },
            delayMs = { delay(it) },
            selfHealConfig = { SelfHealConfig() }
        )
        engine.run()

        // Row 1 is the seeded 60s history; ordinal 2 is the watchdogged zombie; the retry follows.
        // (The seeded row's endedAt sits in the virtual future, so the INV-5 gate waits 60s before
        // the attempt even opens — assert the WATCHED DURATION, not the absolute clock.)
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        val zombie = attempts.single { it.attemptOrdinal == 2 }
        assertEquals("failed", zombie.status)
        assertEquals(
            "the watchdog must fire after 3×median (180s) of running, not at the 90s floor",
            180_000L, zombie.endedAt!! - zombie.startedAt
        )
        assertEquals("UNTRUSTED", zombie.failureReason)
        // # legacy 车道终态语义：同上
        assertEquals(AutomationState.ERROR, engine.state.value)
    }

    @Test
    fun `watchdog off leaves a slow runner alone until it completes`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1, bufferSeconds = 0)
        // Real-time stall past the point where the watchdog WOULD have fired when enabled.
        val runner = StalledRunner(firstCallStallRealMs = 300L)
        val engine = AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = runner, gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(0, { testScheduler.currentTime }),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = { testScheduler.currentTime },
            delayMs = { delay(it) },
            selfHealConfig = { SelfHealConfig(attemptWatchdogEnabled = false) }
        )
        engine.run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals("watchdog OFF must not intervene — the slow run completes untouched", 1, attempts.size)
        assertEquals("succeeded", attempts[0].status)
        assertTrue(
            db.auditEventDao().forAttempt(attempts[0].id)
                .none { it.eventType == "ATTEMPT_WATCHDOG_TIMEOUT" }
        )
        // # legacy 车道既有限状：无 trusted 铸币时计划投影不完整 → error 终态（与看门狗无关的现状语义）
        assertEquals(AutomationState.ERROR, engine.state.value)
    }

    // ==================== A+ lane ====================

    @Test
    fun `a hung A+ attempt is reaped through the RECOVERING release path and the retry mints quota`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1, bufferSeconds = 0)
        val runner = StalledRunner(firstCallStallRealMs = 60_000L)
        val engine = buildAplusEngine(
            planId, runner,
            nowMs = { testScheduler.currentTime },
            delayMs = { delay(it) }
        )
        engine.run()

        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        // # 复用 RECOVERING 的终态化路径：§8.1 TIMEOUT_INTERRUPTED → RECOVERY_REQUIRED → RELEASE_PENDING → CLOSED
        assertEquals("failed", attempts[0].status)
        assertEquals("UNTRUSTED", attempts[0].failureReason)
        assertEquals("CLOSED", attempts[0].aplusState)
        assertEquals(90_000L, attempts[0].endedAt)
        val trail = db.auditEventDao().forAttempt(attempts[0].id)
        assertTrue(
            "the zombie must cross the §8.1 TIMEOUT_INTERRUPTED edge",
            trail.any { it.eventType == AttemptEvent.TIMEOUT_INTERRUPTED.name }
        )
        assertTrue(
            "the lease must be durably released (RELEASE_RECEIPT audit)",
            trail.any { it.eventType == AttemptEvent.RELEASE_RECEIPT.name }
        )
        assertEquals(1, trail.count { it.eventType == "ATTEMPT_WATCHDOG_TIMEOUT" })
        // # 重试走完整正路径并铸币（恰好一枚，绝不重复）
        assertEquals("succeeded", attempts[1].status)
        assertEquals("CLOSED", attempts[1].aplusState)
        assertEquals(1, repo.trustedCountForTask(taskId))
        assertEquals(AutomationState.DONE, engine.state.value)
        assertEquals(2, runner.calls)
    }
}
