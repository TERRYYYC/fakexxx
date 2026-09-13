package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.automation.selfheal.ZombieAttemptPolicy
import com.example.cellrebelauto.cutover.CutoverAccessGate
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #138 — the zombie attempt SAFE finalization oracle (A+ lane, end to end).
 *
 * Field truth (issue #138, device ZY22JHW9M4, attempt 875): an APPLY_PENDING owner whose anchor
 * drifted from the live provider schedule reconciles to `InsufficientEvidence` on EVERY Resume —
 * a permanent PAUSED death loop whose only escape was operator DB surgery. The engine must now
 * perform that exact surgery itself, ONCE reconcile has failed AND the owner is provably dead
 * (receipt-free, lease-free, execution-free, stale past its intent window), and then CONTINUE
 * recovery so the plan mints a FRESH attempt for the same task.
 *
 * Ordering safety (the load-bearing assertion): a FRESH zombie (< threshold) must still pause —
 * reconcile keeps its self-heal chances (crash window (b) same-key replay; transient binder
 * recovery on a later Resume).
 *
 * # 僵尸安全终结 oracle：超龄僵尸在 reconcile 失败后被引擎内化手术终结且恢复继续；
 * # 新鲜僵尸仍走 PAUSED（自愈优先）；绝无收据、绝不推配额
 */
@RunWith(RobolectricTestRunner::class)
class EngineZombieAttemptFinalizationTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db, CutoverAccessGate.open())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- fakes ----

    private class FakeCellRebelRunner : CellRebelRunner {
        var calls = 0
            private set

        override suspend fun runTest(
            startedAt: Long,
            testTimeoutMs: Long,
            onStartInteraction: suspend () -> Unit,
            onRunningObserved: suspend (Long) -> Unit
        ): AttemptOutcome {
            calls++
            onStartInteraction()
            onRunningObserved(startedAt + 1_000)
            return AttemptOutcome.Success(
                webScore = 8.0, videoScore = 7.0,
                runningObservedAt = startedAt + 1_000,
                startedAt = startedAt, endedAt = startedAt + 45_000
            )
        }
    }

    private class FakeGpsSetter : GpsLocationSetter {
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome = GpsOutcome.Active
    }

    // ---- A+ fixture: the live schedule has ADVANCED past the zombie's anchor (#138 form 1) ----

    private val scheduleId = "sched-zb-1"

    /** The zombie's durable anchor — the schedule has since drifted past this item. */
    private val zombieAnchorItem = "item-zb-old"

    /** The LIVE provider item every fresh discover/preflight now reports. */
    private val currentItem = "item-zb-cur"
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
            currentScheduleId = scheduleId, currentItemId = currentItem,
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
            scheduleItemId = currentItem, scheduleVersion = version, exhausted = false
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
            scheduleItemId = "item-zb-next", scheduleVersion = version + 1
        )

        override fun completeAndAdvance(
            request: CompleteAndAdvanceRequestV1, expectedIntentHash: String
        ): AdvanceReceiptV1 {
            val base = AdvanceReceiptV1(
                outcomeWire = 1, advancedFromItemId = currentItem, advancedToItemId = "item-zb-next",
                scheduleVersionAfter = version + 1, effectiveIntentHash = "eff-zb",
                effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
            )
            return base.copy(
                receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                    base, request.requestDigest, request.idempotencyKey
                )
            )
        }
    }

    /** Positive §6.4 evidence source (canonical TrustPolicy case), as in the watchdog oracle. */
    private inner class ScriptedEvidenceSource(
        private val planId: Long
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
                effectiveLat = 39.9, effectiveLng = 116.4,
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
        delayMs: suspend (Long) -> Unit
    ): AutomationEngine {
        val coordinator = RecoveryCoordinator(
            journeyExecutor,
            RoomDurableRecoveryLog(
                db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao(),
                CutoverAccessGate.open()
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
            selfHealConfig = { SelfHealConfig() }
        )
    }

    // ---- seed helpers ----

    private suspend fun seedPlanWithTask(): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 1
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        return planId to taskId
    }

    /** Seeds the #138 field shape: crashed process → paused session + APPLY_PENDING owner. */
    private suspend fun seedPausedSessionWithZombie(
        planId: Long,
        taskId: Long,
        zombieStartedAt: Long
    ): Triple<Long, Long, Long> {
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = zombieStartedAt, endedAt = null, status = "paused", planId = planId)
        )
        val zombieId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = zombieStartedAt, runningObservedAt = null,
                endedAt = null, status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = "APPLY_PENDING",
                aplusLeaseId = null,
                aplusAnchorScheduleId = scheduleId,
                aplusAnchorItemId = zombieAnchorItem,
                aplusAnchorVersion = version
            )
        )
        return Triple(sessionId, zombieId, taskId)
    }

    // ==================== the #138 oracle ====================

    @Test
    fun `stale drifted APPLY_PENDING zombie is finalized by the engine and recovery continues to mint a fresh attempt`() = runTest {
        val (planId, taskId) = seedPlanWithTask()
        // Zombie intent window [-10min, -10min+90s]: irrevocably expired at the run-start instant 0.
        val (sessionId, zombieId, _) = seedPausedSessionWithZombie(planId, taskId, zombieStartedAt = -600_000L)
        val runner = FakeCellRebelRunner()
        val engine = buildAplusEngine(planId, runner, nowMs = { testScheduler.currentTime }, delayMs = { delay(it) })

        engine.run()

        // 1. 手术等价终态：interrupted + endedAt + typed reason；死亡现场（aplusState）保留
        val zombie = db.testAttemptDao().getAttemptById(zombieId)!!
        assertEquals("interrupted", zombie.status)
        assertEquals(ZombieAttemptPolicy.FAILURE_REASON, zombie.failureReason)
        assertTrue(zombie.endedAt != null)
        assertEquals("APPLY_PENDING", zombie.aplusState)
        // 2. 引擎开的枪有审计；幂等的手术恰一行
        assertEquals(
            1, db.auditEventDao().forAttempt(zombieId)
                .count { it.eventType == ZombieAttemptPolicy.AUDIT_EVENT_TYPE }
        )
        // 3. 绝不产生收据、绝不推可信配额、绝不标 completed
        assertNull(repo.getTrustedEntry(zombieId))
        assertNull(repo.getCompletionReceipt(zombieId))
        assertEquals(0, zombie.successOrdinal ?: 0)
        // 4. 恢复继续：同一任务铸出全新 attempt 并成功 —— 不再是 InsufficientEvidence 死循环
        assertEquals(AutomationState.DONE, engine.state.value)
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertEquals(2, attempts.size)
        assertEquals("succeeded", attempts[1].status)
        assertEquals(1, repo.trustedCountForTask(taskId))
        // 5. 会话被复用（不 mint 第二个 active run）
        assertEquals("completed", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `a FRESH drifted zombie is NOT finalized — reconcile keeps its self-heal chances and pauses`() = runTest {
        val (planId, taskId) = seedPlanWithTask()
        // Zombie is only 60s old at run start — well inside the 5 min stall floor.
        val (sessionId, zombieId, _) = seedPausedSessionWithZombie(planId, taskId, zombieStartedAt = -60_000L)
        val runner = FakeCellRebelRunner()
        val engine = buildAplusEngine(planId, runner, nowMs = { testScheduler.currentTime }, delayMs = { delay(it) })

        engine.run()

        // Existing fail-closed semantics preserved: PAUSED, owner untouched, zero external effects.
        assertEquals(AutomationState.PAUSED, engine.state.value)
        val zombie = db.testAttemptDao().getAttemptById(zombieId)!!
        assertEquals("starting", zombie.status)
        assertNull(zombie.endedAt)
        assertTrue(
            db.auditEventDao().forAttempt(zombieId)
                .none { it.eventType == ZombieAttemptPolicy.AUDIT_EVENT_TYPE }
        )
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
        // No fresh attempt was minted past the paused owner.
        assertEquals(1, db.testAttemptDao().getAttemptsForTask(taskId).size)
        assertEquals(0, runner.calls)
    }
}
