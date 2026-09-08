package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence
import com.example.cellrebelauto.automation.aplus.APlusEvidenceSource
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.automation.selfheal.CoordinateGuard
import com.example.cellrebelauto.data.SelfHealConfig
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ObservationSnapshot
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.execution.CellRebelExecution
import com.example.cellrebelauto.model.ledger.DurableCompletionReceipt
import com.example.cellrebelauto.model.ledger.DurableObservationRecord
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
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * P1.3 #3 — the pre-mint coordinate guard oracle (the 52/51 profile-misalignment incident).
 *
 * The measured incident: the plan had 52 rows but 51 profiles — the anchor still matched, the
 * provider validated against ITS OWN (misaligned) current item, so the engine saw PASS +
 * QUOTA_COMMITTED while the device sat on the wrong row's coordinates, burning quota for dozens of
 * attempts. The agent's hand-run assertion "|observed − expected| ≤ 0.0002°" catches this in one
 * shot; the guard compiles it into the engine at the mint boundary:
 *   - observed (pre + post effectiveLat/Lng) vs plan row expected coordinates, per axis;
 *   - violation ⇒ NO mint, attempt terminalized with the typed ANCHOR_MISMATCH reason,
 *     audit trail, engine PAUSED with a human-readable reason;
 *   - both coordinates within tolerance ⇒ mint proceeds unchanged.
 *
 * # 坐标 commit 前校验 oracle：52/51 错位一击拦截——零配额入账 + ANCHOR_MISMATCH + 暂停
 */
@RunWith(RobolectricTestRunner::class)
class EngineCoordinateGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    private val taskLat = 39.9
    private val taskLng = 116.4

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db, com.example.cellrebelauto.cutover.CutoverAccessGate.open())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- pure threshold checks ----

    @Test
    fun `guard tolerance is 0_0002 degrees per axis`() {
        assertNull(CoordinateGuard.violation(taskLat, taskLng, listOf(Triple("PRE", 39.9001, 116.4002))))
        val violation = CoordinateGuard.violation(
            taskLat, taskLng,
            listOf(Triple("PRE", taskLat + 0.0012, taskLng))
        )
        assertTrue(violation!!.startsWith("ANCHOR_MISMATCH"))
    }

    // ---- shared A+ fixture ----

    private val scheduleId = "sched-cg-1"
    private val itemId = "item-cg-3b"
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
            scheduleItemId = "item-after-cg", scheduleVersion = version + 1
        )

        override fun completeAndAdvance(
            request: CompleteAndAdvanceRequestV1, expectedIntentHash: String
        ): AdvanceReceiptV1 {
            val base = AdvanceReceiptV1(
                outcomeWire = 1, advancedFromItemId = itemId, advancedToItemId = "item-after-cg",
                scheduleVersionAfter = version + 1, effectiveIntentHash = "eff-cg",
                effectiveEnvironmentRevision = 7L, receiptDigest = "filled-at-call"
            )
            return base.copy(
                receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                    base, request.requestDigest, request.idempotencyKey
                )
            )
        }
    }

    private class VoidRunner : CellRebelRunner {
        var calls = 0
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
                webScore = 8.0, videoScore = 7.0, runningObservedAt = startedAt + 1_000,
                startedAt = startedAt, endedAt = startedAt + 45_000
            )
        }
    }

    private class FakeGpsSetter : GpsLocationSetter {
        override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome = GpsOutcome.Active
    }

    /** Canonical-positive §6.4 bundle except the observable effective coordinates, which are scripted. */
    private inner class ScriptedEvidenceSource(
        private val planId: Long,
        private val effectiveLat: Double,
        private val effectiveLng: Double = taskLng
    ) : APlusEvidenceSource {

        override suspend fun acquirePreObservation(
            attemptId: Long, runSessionId: Long
        ): ObservationSnapshot = observation(attemptId, runSessionId, 1_000L)

        override suspend fun acquirePostObservation(
            attemptId: Long, runSessionId: Long
        ): ObservationSnapshot = observation(attemptId, runSessionId, 14_000L)

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
        ): ObservationSnapshot = ObservationSnapshot(
            leaseId = "lease-$attemptId",
            acceptedIntentHash = intentDigest(attemptId, runSessionId),
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

    private fun buildEngine(
        planId: Long,
        evidence: APlusEvidenceSource,
        config: SelfHealConfig = SelfHealConfig()
    ): AutomationEngine {
        val coordinator = RecoveryCoordinator(
            journeyExecutor,
            RoomDurableRecoveryLog(
                db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao(),
                com.example.cellrebelauto.cutover.CutoverAccessGate.open()
            ),
            observe = ObserveIntentAcquirer { true },
            receiptRevision = ReceiptRevisionAcquirer { _, _ -> true },
            trustedQuota = TrustedQuotaAcquirer { true }
        )
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = VoidRunner(),
            gpsSetter = FakeGpsSetter(),
            bufferGate = BufferGate(0, { 0L }),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = { 0L }, delayMs = { },
            attemptDriver = APlusAttemptDriver(db.auditEventDao()),
            recoveryCoordinator = coordinator,
            completionEvidenceSource = evidence,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L },
            selfHealConfig = { config }
        )
    }

    private suspend fun seedPlan(quota: Int): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = quota
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = taskLng, latitude = taskLat,
                    priority = 1, requiredSuccesses = quota
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        return planId to taskId
    }

    // ==================== the 52/51 misalignment scenario ====================

    @Test
    fun `off-plan observed coordinates block the mint and pause with a human reason`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        // 52/51 错位形状：锚点仍匹配，但观察坐标是"下一行"的（Δlat 0.0012 ≫ 0.0002）
        val engine = buildEngine(
            planId,
            ScriptedEvidenceSource(planId, effectiveLat = taskLat + 0.0012)
        )
        engine.run()

        // # 零配额入账
        assertEquals(0, repo.trustedCountForTask(taskId))
        // # attempt 终态化并带 typed 原因（typed reason，不加新终态枚举）
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("failed", attempt.status)
        assertTrue(
            "failureReason must carry the typed ANCHOR_MISMATCH reason, got ${attempt.failureReason}",
            attempt.failureReason?.startsWith("ANCHOR_MISMATCH") == true
        )
        assertEquals("CLOSED", attempt.aplusState)
        assertEquals(
            "the unverified carrier must be bound to the same typed reason",
            attempt.failureReason, repo.getUnverifiedRecord(attempt.id)?.reason
        )
        // # 审计行：TRUST_POLICY_FAIL 边 + release + close 全留痕
        val trail = db.auditEventDao().forAttempt(attempt.id)
        assertTrue(trail.any { it.eventType == "TRUST_POLICY_FAIL" })
        assertTrue(trail.any { it.eventType == "RELEASE_RECEIPT" })
        // # 引擎暂停且带人话原因（日志可见错位嫌疑）
        assertEquals(AutomationState.PAUSED, engine.state.value)
        val session = db.runSessionDao().getLatest()!!
        assertEquals("paused", session.status)
        assertTrue(
            engine.logs.value.joinToString("\n").contains("misalignment")
        )
    }

    @Test
    fun `coordinates within tolerance still mint normally`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        // Δlat = 0.0001 ≤ 0.0002 → 放行
        val engine = buildEngine(
            planId,
            ScriptedEvidenceSource(planId, effectiveLat = taskLat + 0.0001)
        )
        engine.run()

        assertEquals(1, repo.trustedCountForTask(taskId))
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals("succeeded", attempt.status)
        assertEquals(AutomationState.DONE, engine.state.value)
    }

    @Test
    fun `guard off lets a misaligned completion mint (documented off behavior)`() = runTest {
        val (planId, taskId) = seedPlan(quota = 1)
        val engine = buildEngine(
            planId,
            ScriptedEvidenceSource(planId, effectiveLat = taskLat + 0.0012),
            config = SelfHealConfig(coordinateGuardEnabled = false)
        )
        engine.run()

        // 开关 off 时不干预：既有（事故）行为原样保留
        assertEquals(1, repo.trustedCountForTask(taskId))
        assertEquals(AutomationState.DONE, engine.state.value)
    }

    // ==================== recovery re-decide sits behind the same guard ====================

    @Test
    fun `a DECIDING crash re-decision with off-plan durable coordinates cannot mint`() = runTest {
        // 崩溃窗口：DECIDING 已持久化全部 carriers，但 pre/post 观察坐标就是错位行的——
        // 恢复重判（redecideDecidingAttempt）在 recordTrustedCompletion 前必须过同一道 guard。
        val attemptId = 31L
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "r.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = taskLng, latitude = taskLat,
                    priority = 1, requiredSuccesses = 1
                )
            )
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 500L, planId = planId, status = "running")
        )
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null,
                videoStreamingScore = null, latitude = taskLat, longitude = taskLng,
                aplusState = "DECIDING", aplusLeaseId = "lease-$attemptId",
                aplusAnchorScheduleId = scheduleId, aplusAnchorItemId = itemId,
                aplusAnchorVersion = version
            )
        )
        val intentDigest = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                sessionId, attemptId, planId, scheduleId, 600L, 600L + 90_000L
            )
        )
        db.testAttemptDao().markCurrentExecutionId(attemptId, "exec-$attemptId")
        db.attemptExecutionDao().insert(
            CellRebelExecution(
                executionId = "exec-$attemptId", attemptId = attemptId,
                completionEvidenceWire = 1, evidencePayloadDigest = "ev-$attemptId",
                startedAt = 1000L, classifiedAt = 1100L,
                startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L,
                completedAtElapsed = 13000L,
                baselineRunningState = "IDLE", runningMarkerText = "RUNNING",
                runningDurationMs = 10900L,
                webBrowsingScore = 8.0, videoStreamingScore = 7.0,
                roundTimestampsElapsed = "2000;13000"
            )
        )
        val badLat = taskLat + 0.0012
        listOf(1_000L to "PRE", 14_000L to "POST").forEach { (observedAt, phase) ->
            db.durableObservationDao().insert(
                DurableObservationRecord(
                    attemptId = attemptId, phase = phase,
                    leaseId = "lease-$attemptId", acceptedIntentHash = intentDigest,
                    coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                    deliveryMode = "SYSTEM_MOCK", isMock = true,
                    scheduleDecision = "ALLOWED_NOW",
                    effectiveLat = badLat, effectiveLng = taskLng,
                    environmentRevision = 7L, environmentFingerprint = "fp",
                    observedAtElapsedRealtimeMs = observedAt, observedAtEpochMs = 900L,
                    continuitySinceElapsedRealtimeMs = 500L, continuitySinceEpochMs = null,
                    evidenceRefsJson = JSONArray(listOf("qwy:store:abc")).toString(),
                    evidenceRefs = "qwy:store:abc"
                )
            )
        }
        db.durableCompletionReceiptDao().insert(
            DurableCompletionReceipt(
                attemptId = attemptId, completionEvidenceWire = 1,
                acceptedIntentHash = intentDigest, leaseId = "lease-$attemptId"
            )
        )

        val engine = buildEngine(
            planId,
            ScriptedEvidenceSource(planId, effectiveLat = badLat)
        )
        engine.run()

        // # 恢复重判绝不铸币
        assertEquals(0, repo.trustedCountForTask(task.id))
        val attempt = db.testAttemptDao().getAttemptById(attemptId)!!
        // # 恢复路径的投影惯例（advanceAfterRelease）：unverified attempt 终态 failed/UNTRUSTED，
        // # typed 原因持久在只追加 unverified carrier + 审计流上——这里断言 carrier 带typed 原因。
        assertEquals("failed", attempt.status)
        assertEquals("CLOSED", attempt.aplusState)
        assertTrue(
            "the unverified carrier must carry the typed ANCHOR_MISMATCH reason, " +
                "got ${repo.getUnverifiedRecord(attemptId)?.reason}",
            repo.getUnverifiedRecord(attemptId)?.reason?.startsWith("ANCHOR_MISMATCH") == true
        )
        val trail = db.auditEventDao().forAttempt(attemptId)
        assertEquals(
            "DECIDING->UNVERIFIED_RECORDED",
            trail.single { it.eventType == "TRUST_POLICY_FAIL" }.payloadDigest
        )
        // # 引擎停在暂停（后续 fresh attempt 的正路径 guard 也拦截了错位证据源）
        assertEquals(AutomationState.PAUSED, engine.state.value)
    }
}
