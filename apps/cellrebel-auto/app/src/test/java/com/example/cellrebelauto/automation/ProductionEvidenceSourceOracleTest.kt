package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ApplyOutcome
import com.example.cellrebelauto.recovery.ExternalApplyExecutor
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1
import kotlinx.coroutines.runBlocking
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
 * R44 (DSF review P1-1): the production-adapter evidence-source oracle — drives the shared
 * backend builder's evidenceSource through its real trust/Room/live-observation chain using the
 * explicit [APlusComposition.testOnlyBackend] executor seam. Registry provenance and the actual
 * production factory route are proved separately by ProviderPrincipalRoutingRedTest and
 * ProductionCommitClockFactoryTest:
 *
 *  1. observeLive consumes executor.observe (untrusted signer / null observe / wrong-tuple → null);
 *  2. the trusted result remains live until the engine's PlanRepository phase-boundary transaction;
 *  3. acquireCompletionEvidence assembles from the durable owner row + verbatim receipt.
 *
 * KILLING MUTATIONS (each verified to FAIL a test below):
 *  - observeLive body replaced with `null` (the production observe consumer pulled)
 *  - ProviderTrustGate.isCurrentSignerTrusted bypassed to `true`
 *  - acquireCompletionEvidence body replaced with `null`
 *
 * # 生产证据源 oracle：observe 消费/trust gate/completion 组装三个 mutation 各自反红
 */
@RunWith(RobolectricTestRunner::class)
class ProductionEvidenceSourceOracleTest {

    private lateinit var db: AppDatabase
    private var signerTrusted: Boolean = true
    private val observeCalls = mutableListOf<Triple<String, String, String>>()
    private var rawReleaseCalls: Int = 0
    private var rawAdvanceCalls: Int = 0
    private var observeResult: EnvironmentObservationV1? = null

    /** The fake journey executor: records observe calls, returns the programmed observation. */
    private val fakeExecutor = object : ExternalApplyExecutor {
        override fun apply(
            attemptId: Long,
            intent: EnvironmentIntentV1,
            idempotencyKey: String,
            requestDigest: String,
            now: Long
        ): ApplyOutcome =
            ApplyOutcome("APPLIED", false, "lease-77", operationId = "op-77",
                acceptedIntentHash = expectedHash(), appliedAtEpochMs = 1000L,
                environmentRevision = 7L,
                verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire)
        override fun release(attemptId: Long, idempotencyKey: String, leaseId: String, releaseDigest: String, now: Long): ApplyOutcome {
            rawReleaseCalls++
            return ApplyOutcome("RELEASED", false)
        }
        override fun discover(): CapabilitySnapshotV1? = null
        override fun preflight(intent: EnvironmentIntentV1, idempotencyKey: String, requestDigest: String): PreflightReportV1? = null
        override fun observe(leaseId: String, operationId: String, expectedIntentHash: String): EnvironmentObservationV1? {
            observeCalls += Triple(leaseId, operationId, expectedIntentHash)
            return observeResult
        }
        override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1, expectedIntentHash: String): AdvanceReceiptV1? {
            rawAdvanceCalls++
            return null
        }
    }

    // The REAL owner identity captured during seed (DB-assigned plan/task ids).
    private var seededPlanId: Long = 0L
    private var seededTaskId: Long = 0L
    private var seededSessionId: Long = 0L

    // R45 (Sol R45 P1-1): the attempt validity window width — the SAME value handed to the
    // production backend. The owner recompute MUST use (attempt.startedAt → startedAt + timeout):
    // the seeded attempt started at 600L, NOT the plan import time (1000L). The previous fixture
    // asserted the WRONG window (1000 → MAX), mirroring the production bug it should have killed.
    private val attemptTimeoutMs = 90_000L

    private fun expectedHash(): String = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
        .requestDigest(
            com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.intent(
                5L, 77L, seededPlanId, "qwy-default-schedule", 600L, 600L + attemptTimeoutMs
            )
        )

    private fun backend() = APlusComposition.testOnlyBackend(
        ApplicationProvider.getApplicationContext(),
        db,
        providerExecutor = com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor.wrap(
            io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            fakeExecutor,
        ),
        providerSignerDigest = { if (signerTrusted) "sha256:a9a089195c68d2adeee23beaa2c3a93b1d4cdf09046e7a9e520b3b166dff3e6a" else "sha256:d9298a10d1b0735837dc4bd85dac641b0f3cef27a47e5d53a54f2f3f5b2fcffa" },
        attemptValidityTimeoutMs = attemptTimeoutMs,
    )

    private fun recoveryEngine(): AutomationEngine {
        val backend = backend()
        val (coordinator, evidence) = APlusComposition.engineAplusParams(backend)
        return testOnlyAutomationEngine(
            planId = seededPlanId,
            planRepository = com.example.cellrebelauto.repository.PlanRepository(db),
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onRunningObserved: suspend (Long) -> Unit,
                ): AttemptOutcome = error("manual signer recovery cannot start a new test")
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome =
                    error("manual signer recovery cannot set a new location")
            },
            globalBufferSeconds = 0,
            testTimeoutMs = attemptTimeoutMs,
            gpsSettleMs = 0L,
            stageToggles = {
                com.example.cellrebelauto.model.plan.StageToggles(
                    locationStageEnabled = true,
                    testStageEnabled = true,
                )
            },
            auditDao = db.auditEventDao(),
            aplusCoordinator = coordinator,
            aplusEvidence = evidence,
            bridge = null,
            nowMs = { 2_000L },
            delayMs = {},
            commitClockMs = { 2_000L },
            elapsedClockMs = { 2_000L },
        )
    }

    @Before
    fun setUp() = kotlinx.coroutines.runBlocking {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        // Seed: plan→task→session→attempt (77), an approved (appId, signer) principal, an apply
        // receipt carrying lease+operationId+intent hash, and the owner execution row.
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "p.csv",
                importedAt = 1000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = io.github.terryyyc.fakexxx.contract.v1.ContractV1
                    .PROVIDER_APPLICATION_ID_PRODUCTION,
            ),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1))
        )
        seededPlanId = planId
        val task = db.locationTaskDao().getTasksForPlan(planId).first()
        seededTaskId = task.id
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        seededSessionId = sessionId
        db.testAttemptDao().insert(
            TestAttempt(
                id = 77L, taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = "ENV_APPLIED", aplusLeaseId = "lease-77", currentExecutionId = "exec-77",
                aplusAnchorScheduleId = "qwy-default-schedule",
                providerApplicationId = io.github.terryyyc.fakexxx.contract.v1.ContractV1
                    .PROVIDER_APPLICATION_ID_PRODUCTION,
            )
        )
        // Approve the TRUSTED signer principal for the production provider app id.
        val trustedSigner = "sha256:a9a089195c68d2adeee23beaa2c3a93b1d4cdf09046e7a9e520b3b166dff3e6a"
        val approvedId = db.providerPairingDao().insert(
            com.example.cellrebelauto.model.plan.ProviderPairingRecord(
                applicationId = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                currentSignerDigest = trustedSigner,
                approvedAt = 1000L, revokedAt = null, approvedVersionCode = 1
            )
        )
        assertTrue(approvedId > 0)
        // The apply receipt (with lease + operationId + intent hash) the evidence source consumes.
        db.operationReceiptDao().insertIfAbsent(
            com.example.cellrebelauto.recovery.OperationReceiptRow(
                idempotencyKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity.applyIdempotencyKey(77L),
                requestDigest = expectedHash(), resultOutcome = "APPLIED", createdAt = 1000L,
                leaseId = "lease-77", operationId = "op-77", acceptedIntentHash = expectedHash(),
                appliedAtEpochMs = 1000L, environmentRevision = 7L,
                verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                providerApplicationId = io.github.terryyyc.fakexxx.contract.v1.ContractV1
                    .PROVIDER_APPLICATION_ID_PRODUCTION,
            )
        )
        // The owner execution row (written at COMPLETION_OBSERVED in production).
        db.attemptExecutionDao().insertIfAbsent(
            com.example.cellrebelauto.model.execution.CellRebelExecution(
                executionId = "exec-77", attemptId = 77L, completionEvidenceWire = 1,
                evidencePayloadDigest = "ev-digest", startedAt = 1000L, classifiedAt = 1100L,
                startedAtElapsed = 2000L, runningConfirmedAtElapsed = 2100L, completedAtElapsed = 13000L,
                baselineRunningState = "IDLE", runningMarkerText = "RUNNING", runningDurationMs = 10900L,
                webBrowsingScore = 8.0, videoStreamingScore = 7.0, roundTimestampsElapsed = "2000;13000"
            )
        )
        // A §6.4-canonical observation the fake provider returns for observe().
        observeResult = EnvironmentObservationV1(
            leaseId = "lease-77", acceptedIntentHash = expectedHash(),
            observedAtEpochMs = 900L, observedAtElapsedRealtimeMs = 1000L,
            environmentRevision = 7L, environmentFingerprint = "fp-1",
            continuityCoverageWire = io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = 800L, continuitySinceElapsedRealtimeMs = 500L,
            deliveryModeWire = io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = 39.9, effectiveLongitude = 116.4, isMock = true,
            scheduleDecisionWire = io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = listOf("qwy:store:abc"), scheduleItemId = "task-42", scheduleVersion = 1L
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the production evidence source returns live data and the engine boundary atomically persists carrier plus phase`() = runBlocking {
        val evidence = backend().evidenceSource
        val pre = evidence.acquirePreObservation(77L, 5L)

        // (a) The observe call actually happened — against the RECEIPT tuple (lease, operationId),
        //     with the owner-recompute expected hash. Mutation "observeLive → null" kills (a)+(b).
        assertEquals("exactly one observe call", 1, observeCalls.size)
        assertEquals("the observe tuple binds the durable receipt lease", "lease-77", observeCalls[0].first)
        assertEquals("the observe tuple binds the durable receipt operationId", "op-77", observeCalls[0].second)
        assertEquals("the expected hash is the owner recompute (§6.3.1 preimage)", expectedHash(), observeCalls[0].third)
        // (b) The source adapts but does not independently persist. The engine owns the ONE write
        //     transaction that couples the carrier to the §8.1 owner phase.
        assertNotNull("the §6.4 snapshot came back for the trusted provider", pre)
        assertNull("the source must not create a second storage authority",
            db.durableObservationDao().forAttemptPhase(77L, "PRE"))
        com.example.cellrebelauto.repository.PlanRepository(db)
            .persistObservationAndMarkAplusState(77L, "PRE", pre!!, "PRE_OBSERVED")
        val durable = db.durableObservationDao().forAttemptPhase(77L, "PRE")
        assertNotNull("the trusted observation is PERSISTED to durable_observation_records", durable)
        assertEquals("adapted verification level", "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED", durable!!.verificationLevel)
        assertEquals("adapted coverage", "FULL", durable.coverage)
        assertEquals("audit-only continuity wall clock is preserved verbatim", 800L, durable.continuitySinceEpochMs)
        assertEquals("carrier and owner phase commit together", "PRE_OBSERVED",
            db.testAttemptDao().getAttemptById(77L)!!.aplusState)
        // (c) The SECOND acquisition replays from durability — no second provider call.
        val pre2 = evidence.acquirePreObservation(77L, 5L)
        assertEquals("durable replay: no second observe call", 1, observeCalls.size)
        assertEquals("the replayed snapshot equals the persisted one", pre, pre2)
    }

    @Test
    fun `an UNTRUSTED current signer never consumes observe (the §6-5-3 gate)`() = runBlocking {
        signerTrusted = false // the gate's signer digest no longer matches the approved principal
        val evidence = backend().evidenceSource
        val pre = evidence.acquirePreObservation(77L, 5L)
        assertNull("untrusted signer ⇒ no artifacts enter the trust path", pre)
        assertEquals("observe is NEVER called for an untrusted signer", 0, observeCalls.size)
        assertNull("nothing persisted either", db.durableObservationDao().forAttemptPhase(77L, "PRE"))
    }

    @Test
    fun `signer rotation from approved A to untrusted B blocks raw release and every durable effect`() = runBlocking {
        signerTrusted = false // durable approval is signer A; current package now resolves to signer B
        val releaseKey = com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
            .releaseIdempotencyKey(77L)
        db.testAttemptDao().markAplusState(77L, "RELEASE_PENDING")

        val firstEngine = recoveryEngine()
        firstEngine.run()

        val firstDurable = db.testAttemptDao().getAttemptById(77L)!!
        assertEquals("untrusted release is a durable manual-recovery stop", "RECOVERY_REQUIRED", firstDurable.aplusState)
        assertEquals("the release outcome remains typed across process death", "PROVIDER_SIGNER_UNTRUSTED", firstDurable.failureReason)
        assertEquals(com.example.cellrebelauto.model.AutomationState.PAUSED, firstEngine.state.value)
        val firstSessionStatus = db.openHelper.readableDatabase.query(
            "SELECT status FROM run_sessions WHERE id = $seededSessionId"
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        assertEquals("paused", firstSessionStatus)
        assertEquals("rotated signer B receives zero raw release", 0, rawReleaseCalls)
        assertEquals("rotation cannot persist a release receipt", null, db.releaseReceiptDao().byKey(releaseKey))
        assertEquals("rotation cannot advance a checkpoint", null, db.recoveryCheckpointRoomDao().byAttempt(77L))
        assertEquals("rotation cannot mint quota", 0, db.trustedQuotaDao().countAll())
        assertEquals("rotation cannot advance the provider schedule", 0, rawAdvanceCalls)

        val untrustedExecutor = backend().executor
        // Every other journey leg remains closed while B is untrusted.
        assertNull("discover stays gated", untrustedExecutor.discover())
        val probeIntent = io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1(
            runId = "5", attemptId = "77", profileRef = "p", scheduleRef = "s",
            requiredVerificationWire = io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            notBeforeEpochMs = 0L, deadlineEpochMs = 1L
        )
        assertNull("preflight stays gated", untrustedExecutor.preflight(probeIntent, "key", "digest"))
        val applyOutcome = untrustedExecutor.apply(77L, probeIntent, "key", "digest", 0L)
        assertEquals("apply stays gated — no new trusted work", "PROVIDER_SIGNER_UNTRUSTED", applyOutcome.outcome)

        // Even if B is subsequently approved, that does not prove B owns A's outstanding lease.
        // Only an explicit manual-resolution flow may clear this sticky durable reason.
        db.providerPairingDao().insert(
            com.example.cellrebelauto.model.plan.ProviderPairingRecord(
                applicationId = io.github.terryyyc.fakexxx.contract.v1.ContractV1
                    .PROVIDER_APPLICATION_ID_PRODUCTION,
                currentSignerDigest = "sha256:d9298a10d1b0735837dc4bd85dac641b0f3cef27a47e5d53a54f2f3f5b2fcffa",
                approvedAt = 2_100L,
                revokedAt = null,
                approvedVersionCode = 2,
            )
        )
        assertEquals(
            "the sticky reason must stop Service before registry acquire/bind",
            "PROVIDER_SIGNER_UNTRUSTED",
            com.example.cellrebelauto.repository.PlanRepository(db)
                .recoveryProviderPrincipalFailure(
                    seededPlanId,
                    io.github.terryyyc.fakexxx.contract.v1.ContractV1
                        .PROVIDER_APPLICATION_ID_PRODUCTION,
                    "sha256:d9298a10d1b0735837dc4bd85dac641b0f3cef27a47e5d53a54f2f3f5b2fcffa",
                ),
        )
        val restartedEngine = recoveryEngine()
        restartedEngine.run()
        assertEquals(com.example.cellrebelauto.model.AutomationState.PAUSED, restartedEngine.state.value)
        assertEquals("later approval cannot release A's lease through B", 0, rawReleaseCalls)
        assertNull(db.releaseReceiptDao().byKey(releaseKey))
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(77L))
        assertEquals(0, rawAdvanceCalls)
    }

    @Test
    fun `a null provider observation fails closed without durable write`() = runBlocking {
        observeResult = null // provider unavailable / validator rejected
        val evidence = backend().evidenceSource
        val pre = evidence.acquirePreObservation(77L, 5L)
        assertNull("null observe ⇒ fail-closed null", pre)
        assertNull("no durable row from a failed observation", db.durableObservationDao().forAttemptPhase(77L, "PRE"))
    }

    @Test
    fun `acquireCompletionEvidence ASSEMBLES from the durable owner row + verbatim receipt`() = runBlocking {
        val evidence = backend().evidenceSource
        val completion = evidence.acquireCompletionEvidence(77L, 5L)
        // Mutation "acquireCompletionEvidence → null" kills this test.
        assertNotNull("completion evidence assembles from durable carriers (never a hardcoded null)", completion)
        assertEquals("the execution row is the OWNER row", "exec-77", completion!!.execution.executionId)
        assertEquals("the wire comes from the execution row", 1, completion.completionEvidenceWire)
        assertEquals("the intent hash is the VERBATIM receipt hash", expectedHash(), completion.applyReceiptIntentHash)
        assertEquals("the lease is the VERBATIM receipt lease", "lease-77", completion.applyReceiptLease)
    }

    @Test
    fun `completion evidence fail-closes when the owner row is missing`() = runBlocking {
        db.testAttemptDao().markCurrentExecutionId(77L, "exec-dangling")
        val evidence = backend().evidenceSource
        assertNull("a dangling owner pointer fail-closes completion assembly", evidence.acquireCompletionEvidence(77L, 5L))
    }

    @Test
    fun `completion evidence fail-closes when the signer is untrusted`() = runBlocking {
        signerTrusted = false
        val evidence = backend().evidenceSource
        assertNull("untrusted signer ⇒ no completion artifacts either", evidence.acquireCompletionEvidence(77L, 5L))
    }
}
