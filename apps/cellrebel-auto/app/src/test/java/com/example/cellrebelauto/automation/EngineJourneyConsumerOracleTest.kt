package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R44/R45 journey-consumer oracles — driven through a REAL AutomationEngine run with a
 * programmable fake executor at the coordinator seam.
 *
 * R45 (Sol R45 exact-HEAD review of b96659f, 5×P1) — each finding's killing mutation fails a test:
 *  - P1-2 preflight NULL pass-through → `a NULL preflight fail-closes before apply`
 *  - P1-3 fabricated CAS triple / wrong-domain digest → `the advance CAS triple is REPLAYED from
 *    the attempt-open discover anchor` (mutate the discover projection ⇒ the request changes ⇒ kill)
 *  - P1-4 advance-before-release / advance-without-quota → `release precedes advance` +
 *    `an intermediate trusted success does NOT advance`
 *  - P1-5 receipt-trusting without verification → `non-terminal advance is independently verified`
 *    + `an exhausted advance is independently verified` (tamper a leg ⇒ RECOVERY_REQUIRED)
 *
 * # 引擎旅程消费者 oracle：R45 五条 P1 的 killing mutations 各自反红
 */
@RunWith(RobolectricTestRunner::class)
class EngineJourneyConsumerOracleTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: com.example.cellrebelauto.repository.PlanRepository

    // ---- Programmable journey surface. DISTINCTIVE anchor values: a request fabricated from
    // plan/task ids ("plan-N"/"task-N"/1L) can NEVER equal these by coincidence. ----
    private val anchorScheduleId = "sched-anchor-9f"
    private val anchorItemId = "item-anchor-4c"
    private val anchorVersion = 41L

    private var discoverAnswer: CapabilitySnapshotV1? = CapabilitySnapshotV1(
        serviceVersion = "fake-1.0",
        supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
        supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
        continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
        environmentRevision = 7L,
        profileRefs = listOf("auto-profile"), scheduleRefs = listOf("auto-schedule"),
        currentScheduleId = anchorScheduleId, currentItemId = anchorItemId, scheduleVersion = anchorVersion, exhausted = false
    )

    /** Armed by a terminal (exhausted) advance — the healthy §6.7.5 four-leg readback. */
    private var discoverReadback: CapabilitySnapshotV1? = null

    private var preflightDecision: Int? = ScheduleDecisionV1.ALLOWED_NOW.wire // null = unavailable (fail-closed)
    private var advanceAnswer: AdvanceReceiptV1? = AdvanceReceiptV1(
        outcomeWire = 1, advancedFromItemId = anchorItemId, advancedToItemId = null,
        scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-hash-1",
        effectiveEnvironmentRevision = 7L, receiptDigest = "rd"
    )

    private val discoverCalls = mutableListOf<Unit>()
    private val preflightCalls = mutableListOf<EnvironmentIntentV1>()
    private val advanceCalls = mutableListOf<CompleteAndAdvanceRequestV1>()
    private val observePostAdvanceCalls = mutableListOf<Triple<String, String, String>>()
    private val events = mutableListOf<String>() // ordered "apply"/"release"/"advance" journey events
    private var armedPostAdvanceObservation: EnvironmentObservationV1? = null

    /** Test hook: mutate the armed post-advance observation (tamper one four-leg). */
    private var observationTamper: (EnvironmentObservationV1) -> EnvironmentObservationV1 = { it }

    private val journeyExecutor = object : ExternalApplyExecutor {
        override fun apply(attemptId: Long, intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String, now: Long): ApplyOutcome {
            events += "apply"
            return ApplyOutcome("APPLIED", false, "lease-$attemptId", operationId = "op-$attemptId")
        }
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome {
            events += "release"
            return ApplyOutcome("RELEASED", false)
        }
        override fun discover(): CapabilitySnapshotV1? {
            discoverCalls += Unit
            return discoverAnswer
        }
        override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? {
            preflightCalls += intent
            val decision = preflightDecision ?: return null
            return PreflightReportV1(
                acceptedIntentHash = requestDigest,
                scheduleDecisionWire = decision,
                waitUntilEpochMs = null,
                achievableVerificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                environmentRevision = 7L, blockingReasonWires = emptyList(),
                scheduleItemId = anchorItemId, scheduleVersion = anchorVersion, exhausted = false
            )
        }
        override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
            observePostAdvanceCalls += Triple(leaseId, operationId, expectedIntentHash)
            val armed = armedPostAdvanceObservation
            if (armed != null) {
                armedPostAdvanceObservation = null
                return observationTamper(armed)
            }
            return null
        }
        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
            advanceCalls += request
            events += "advance"
            val receipt = advanceAnswer ?: return null
            // R46 (Sol R46 P1-2): a HEALTHY provider signs canonically (the preimage excludes
            // receiptDigest itself, so sign-then-fill is the canonical construction).
            val signed = receipt.copy(
                receiptDigest = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1.compute(
                    receipt, request.requestDigest, request.idempotencyKey
                )
            )
            advanceAnswer = signed
            if (signed.advancedToItemId == null) {
                // A HEALTHY provider: after the terminal advance the schedule IS exhausted at V+1.
                discoverAnswer = discoverReadback ?: CapabilitySnapshotV1(
                    serviceVersion = "fake-1.0",
                    supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
                    supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
                    continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                    environmentRevision = 7L,
                    profileRefs = listOf("auto-profile"), scheduleRefs = listOf("auto-schedule"),
                    currentScheduleId = anchorScheduleId,
                    currentItemId = signed.advancedFromItemId,
                    scheduleVersion = signed.scheduleVersionAfter,
                    exhausted = true
                )
            } else {
                // A HEALTHY provider: the post-advance environment matches its own receipt.
                armedPostAdvanceObservation = EnvironmentObservationV1(
                    leaseId = request.leaseId,
                    acceptedIntentHash = signed.effectiveIntentHash,
                    observedAtEpochMs = 0L, observedAtElapsedRealtimeMs = 0L,
                    environmentRevision = signed.effectiveEnvironmentRevision,
                    environmentFingerprint = "post-advance-fp",
                    continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
                    continuitySinceEpochMs = null, continuitySinceElapsedRealtimeMs = null,
                    deliveryModeWire = io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire,
                    verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                    effectiveLatitude = null, effectiveLongitude = null, isMock = true,
                    scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
                    evidenceRefs = emptyList(),
                    scheduleItemId = signed.advancedToItemId!!,
                    scheduleVersion = signed.scheduleVersionAfter
                )
            }
            return signed
        }
    }

    // The §6.4-positive evidence source the trusted path needs. The intent hash is RECOMPUTED
    // from the same durable owner identity the engine uses (attempt → task → plan from the DB),
    // so the INV-23 three-way hash actually agrees.
    private val evidenceSource = object : com.example.cellrebelauto.automation.aplus.APlusEvidenceSource {
        suspend fun hash(attemptId: Long, runSessionId: Long): String {
            val attempt = db.testAttemptDao().getAttemptById(attemptId)!!
            val task = db.locationTaskDao().getTaskById(attempt.taskId)!!
            val plan = db.planDao().getPlanById(task.planId)!!
            return com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.requestDigest(
                com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.intent(
                    runSessionId, attemptId, plan.id, task.id, attempt.startedAt, attempt.startedAt + 90_000L
                )
            )
        }
        suspend fun snap(attemptId: Long, runSessionId: Long, observedAt: Long) =
            com.example.cellrebelauto.environment.ObservationSnapshot(
                leaseId = "lease-$attemptId", acceptedIntentHash = hash(attemptId, runSessionId),
                coverage = "FULL", verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
                deliveryMode = "SYSTEM_MOCK", isMock = true, scheduleDecision = "ALLOWED_NOW",
                effectiveLat = 39.9, effectiveLng = 116.4,
                environmentRevision = 7L, environmentFingerprint = "fp",
                observedAtElapsedRealtimeMs = observedAt, observedAtEpochMs = 900L,
                continuitySinceElapsedRealtimeMs = 500L, evidenceRefs = listOf("qwy:s:1")
            )
        override suspend fun acquirePreObservation(attemptId: Long, runSessionId: Long) =
            snap(attemptId, runSessionId, 1000L)
        override suspend fun acquirePostObservation(attemptId: Long, runSessionId: Long) =
            snap(attemptId, runSessionId, 14000L)
        override suspend fun acquireCompletionEvidence(attemptId: Long, runSessionId: Long) =
            com.example.cellrebelauto.automation.aplus.APlusCompletionEvidence(
                execution = com.example.cellrebelauto.model.execution.CellRebelExecution(
                    executionId = "exec-$attemptId", attemptId = attemptId, completionEvidenceWire = 1,
                    evidencePayloadDigest = "ev-$attemptId", startedAt = 0L, classifiedAt = 0L,
                    startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L, completedAtElapsed = 13000L,
                    baselineRunningState = "IDLE", runningMarkerText = "RUNNING", runningDurationMs = 10900L,
                    webBrowsingScore = 8.0, videoStreamingScore = 7.0, roundTimestampsElapsed = "2000;13000"
                ),
                completionEvidenceWire = 1,
                applyReceiptIntentHash = hash(attemptId, runSessionId),
                applyReceiptLease = "lease-$attemptId"
            )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = com.example.cellrebelauto.repository.PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private class VClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { now += it }
    }

    private suspend fun seedPlan(requiredSuccesses: Int = 1): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "j.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = requiredSuccesses),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = requiredSuccesses))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        return planId to task.id
    }

    private fun buildEngine(planId: Long, clock: VClock, driver: com.example.cellrebelauto.automation.aplus.APlusAttemptDriver?): AutomationEngine {
        // R45: the Room durable log — the apply receipt (with the verbatim operationId) must be
        // durable for the post-advance observe tuple, exactly as in production.
        val coordinator = com.example.cellrebelauto.recovery.RecoveryCoordinator(
            journeyExecutor, RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao())
        )
        return AutomationEngine(
            planId = planId, planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(startedAt: Long, testTimeoutMs: Long, onRunningObserved: suspend (Long) -> Unit): AttemptOutcome {
                    onRunningObserved(4242L)
                    return AttemptOutcome.Success(8.0, 7.0, startedAt, 0L, 4300L)
                }
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) = GpsOutcome.Active
            },
            bufferGate = com.example.cellrebelauto.automation.plan.BufferGate(0, clock.nowMs),
            testTimeoutMs = 90_000L, gpsSettleMs = 0L,
            nowMs = clock.nowMs, delayMs = clock.delayMs,
            attemptDriver = driver,
            recoveryCoordinator = coordinator,
            completionEvidenceSource = evidenceSource,
            elapsedClockMs = { 5000L }, commitClockMs = { 99999L }
        )
    }

    @Test
    fun `the engine CONSUMES discover at run start - an incompatible provider pauses before any attempt`() = runTest {
        val (planId, taskId) = seedPlan()
        discoverAnswer = null // the incompatible/unavailable provider
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertTrue("the discover gate was consulted", discoverCalls.isNotEmpty())
        assertEquals(
            "an unavailable provider pauses BEFORE any attempt is created (killing mutation: consumer removed ⇒ attempts exist)",
            0, db.testAttemptDao().getAttemptsForTask(taskId).size
        )
        assertEquals("no session activity beyond the pause", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `the engine CONSUMES preflight - a DENIED schedule fail-closes before apply`() = runTest {
        val (planId, taskId) = seedPlan()
        preflightDecision = ScheduleDecisionV1.DENIED.wire
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertTrue("preflight was consulted for the intent", preflightCalls.isNotEmpty())
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertTrue(
            "a DENIED preflight fail-closes the attempt (UNTRUSTED, no lease) before any external apply (killing mutation: consumer removed ⇒ lease applied)",
            attempts.all { db.testAttemptDao().getAplusLeaseId(it.id) == null }
        )
    }

    @Test
    fun `a NULL preflight fail-closes before apply (R45 P1-2 - null is not ALLOWED_NOW)`() = runTest {
        val (planId, taskId) = seedPlan()
        preflightDecision = null // unbound transport / validator failure / illegal response
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertTrue("preflight was consulted", preflightCalls.isNotEmpty())
        assertTrue(
            "a NULL preflight dispatches NO apply (killing mutation: null pass-through ⇒ apply event exists)",
            events.none { it == "apply" }
        )
        val attempts = db.testAttemptDao().getAttemptsForTask(taskId)
        assertTrue("no lease was ever acquired", attempts.all { db.testAttemptDao().getAplusLeaseId(it.id) == null })
        assertEquals("no trusted quota from a fail-closed attempt", 0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `the advance CAS triple is REPLAYED from the attempt-open discover anchor with the canonical advance digest (R45 P1-3)`() = runTest {
        val (planId, taskId) = seedPlan()
        val driver = com.example.cellrebelauto.automation.aplus.APlusAttemptDriver(db.auditEventDao())
        val clock = VClock()
        buildEngine(planId, clock, driver).run()

        assertEquals("one trusted mint", 1, db.trustedQuotaDao().countAll())
        assertEquals("the provider's schedule advance was invoked exactly once", 1, advanceCalls.size)
        val req = advanceCalls[0]
        // Killing mutation A (fabricated "plan-N"/"task-N"/1L): the request must carry the DISCOVER
        // projection verbatim — the distinctive anchor values, never derivable from plan/task ids.
        assertEquals("CAS: expectedScheduleId is the discover projection", anchorScheduleId, req.expectedScheduleId)
        assertEquals("CAS: expectedCurrentItemId is the discover projection", anchorItemId, req.expectedCurrentItemId)
        assertEquals("CAS: expectedScheduleVersion is the discover projection", anchorVersion, req.expectedScheduleVersion)
        assertEquals("the proof binds the completed item", anchorItemId, req.completionProof.scheduleItemId)
        assertEquals("the proof binds the ledger projection", 1, req.completionProof.trustedSuccessCount)
        assertEquals("the proof binds the quota", 1, req.completionProof.quotaRequired)
        // Killing mutation B (apply intentDigest reused as the advance digest): the request digest
        // must be the CANONICAL ADVANCE framing — recompute and compare.
        assertEquals(
            "requestDigest is CanonicalAdvanceDigestV1.compute(request), NOT the apply intent digest",
            CanonicalAdvanceDigestV1.compute(req), req.requestDigest
        )
        // The anchor was PERSISTED to the attempt's durable owner row before external execution.
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).first()
        val anchor = db.testAttemptDao().getAplusAdvanceAnchor(attempt.id)!!
        assertEquals(anchorScheduleId, anchor.aplusAnchorScheduleId)
        assertEquals(anchorItemId, anchor.aplusAnchorItemId)
        assertEquals(anchorVersion, anchor.aplusAnchorVersion)

        // A failed advance fail-closes AFTER the local mint (the ledger is Auto's own authority —
        // §6.7.2: the provider RECORDS completion, never recomputes it): the mint may commit, but
        // the run PAUSES instead of continuing to the next task.
        advanceAnswer = null
        val (planId2, taskId2) = seedPlan()
        val clock2 = VClock()
        buildEngine(planId2, clock2, null).run()
        assertEquals(
            "both runs drove their completeAndAdvance (the second one failed and paused)",
            2, advanceCalls.size
        )
        val secondPlanAttempts = db.testAttemptDao().getAttemptsForTask(taskId2)
        assertTrue(
            "the paused plan created at most one attempt (no silent retry loop past a failed advance)",
            secondPlanAttempts.size <= 1
        )
    }

    @Test
    fun `an intermediate trusted success does NOT advance - only the quota-reaching mint advances (R45 P1-4)`() = runTest {
        val (planId, taskId) = seedPlan(requiredSuccesses = 2)
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertEquals("two trusted mints for a 2-quota task", 2, db.trustedQuotaDao().countAll())
        assertEquals(
            "exactly ONE advance — the first (1-of-2) success must NOT move the schedule pointer (killing mutation: unconditional advance ⇒ 2 calls)",
            1, advanceCalls.size
        )
        assertEquals("the advancing proof carries the FULL quota count", 2, advanceCalls[0].completionProof.trustedSuccessCount)
    }

    @Test
    fun `release PRECEDES advance - the frozen §6-7-4a order (R45 P1-4)`() = runTest {
        val (planId, taskId) = seedPlan()
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        val journey = events.filter { it == "release" || it == "advance" }
        assertTrue("both a release and an advance happened", journey.containsAll(listOf("release", "advance")))
        assertEquals(
            "the journey order is release → advance, never advance under an active lease (killing mutation: advance-then-release ⇒ [advance, release])",
            listOf("release", "advance"), journey
        )
    }

    @Test
    fun `a NON-TERMINAL advance is independently verified by a four-leg observe (R45 P1-5)`() = runTest {
        // Healthy: the post-advance observation matches the receipt's four legs → the run completes.
        advanceAnswer = AdvanceReceiptV1(
            outcomeWire = 1, advancedFromItemId = anchorItemId, advancedToItemId = "item-next-7",
            scheduleVersionAfter = anchorVersion + 1, effectiveIntentHash = "eff-hash-next",
            effectiveEnvironmentRevision = 8L, receiptDigest = "rd-next"
        )
        val (planId, taskId) = seedPlan()
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        assertEquals("the advance happened", 1, advanceCalls.size)
        assertEquals("the engine independently OBSERVED the new environment (killing mutation: receipt-only trust ⇒ zero observe calls)", 1, observePostAdvanceCalls.size)
        val tuple = observePostAdvanceCalls[0]
        assertEquals("the observe is bound to the (released) lease the quota was earned under", "lease-${db.testAttemptDao().getAttemptsForTask(taskId).first().id}", tuple.first)
        assertEquals("the observe expects the receipt's effective intent hash", "eff-hash-next", tuple.third)
        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).first()
        assertEquals("healthy four-leg match ⇒ CLOSED", "CLOSED", db.testAttemptDao().getAttemptById(attempt.id)!!.aplusState)

        // Killing mutation: the receipt stays internally consistent, but the INDEPENDENT
        // observation's schedule leg disagrees — the provider's environment shows a DIFFERENT
        // item than its receipt claims. Trusting the receipt alone closes the attempt; the
        // four-leg comparison must fail-closed instead.
        observationTamper = { it.copy(scheduleItemId = "item-liar-9") }
        val (planId2, taskId2) = seedPlan()
        val clock2 = VClock()
        buildEngine(planId2, clock2, null).run()
        val attempt2 = db.testAttemptDao().getAttemptsForTask(taskId2).first()
        assertEquals(
            "a failed independent verification ⇒ RECOVERY_REQUIRED, never CLOSED-on-receipt-alone (killing mutation: verification removed ⇒ CLOSED)",
            "RECOVERY_REQUIRED", db.testAttemptDao().getAttemptById(attempt2.id)!!.aplusState
        )
    }

    @Test
    fun `an EXHAUSTED advance is independently verified by a fresh discover readback (R45 P1-5)`() = runTest {
        // Default advanceAnswer is terminal (advancedToItemId == null) and completeAndAdvance arms a
        // HEALTHY readback: same schedule id, advancedFromItemId, V+1, exhausted=true.
        val (planId, taskId) = seedPlan()
        val clock = VClock()
        buildEngine(planId, clock, null).run()

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).first()
        assertEquals("healthy exhausted readback ⇒ CLOSED", "CLOSED", db.testAttemptDao().getAttemptById(attempt.id)!!.aplusState)
        assertTrue(
            "a fresh discover() readback happened after the terminal advance (run-start + anchor + readback)",
            discoverCalls.size >= 3
        )

        // Killing mutation: the readback's exhausted leg is FALSE — the receipt is internally
        // consistent, but the device still has the last item executable ⇒ RECOVERY_REQUIRED.
        discoverReadback = CapabilitySnapshotV1(
            serviceVersion = "fake-1.0",
            supportedModeWires = listOf(io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire),
            supportedVerificationLevelWires = listOf(io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire),
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            environmentRevision = 7L,
            profileRefs = listOf("auto-profile"), scheduleRefs = listOf("auto-schedule"),
            currentScheduleId = anchorScheduleId, currentItemId = anchorItemId,
            scheduleVersion = anchorVersion + 1, exhausted = false // the tampered leg
        )
        val (planId2, taskId2) = seedPlan()
        val clock2 = VClock()
        buildEngine(planId2, clock2, null).run()
        val attempt2 = db.testAttemptDao().getAttemptsForTask(taskId2).first()
        assertEquals(
            "an unproven terminal state ⇒ RECOVERY_REQUIRED (killing mutation: readback removed ⇒ CLOSED)",
            "RECOVERY_REQUIRED", db.testAttemptDao().getAttemptById(attempt2.id)!!.aplusState
        )
    }
}
