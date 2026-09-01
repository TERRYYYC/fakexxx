package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ObserveIntentAcquirer
import com.example.cellrebelauto.recovery.OperationReceiptRow
import com.example.cellrebelauto.recovery.ProviderScopedExternalApplyExecutor
import com.example.cellrebelauto.recovery.PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE
import com.example.cellrebelauto.recovery.ProviderPrincipalFailureReason
import com.example.cellrebelauto.recovery.ReceiptRevisionAcquirer
import com.example.cellrebelauto.recovery.ReconcileResult
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import com.example.cellrebelauto.recovery.RecoveryCheckpointRow
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import com.example.cellrebelauto.recovery.RoomDurableProviderPrincipalPreflight
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import com.example.cellrebelauto.recovery.ScheduleAdvanceState
import com.example.cellrebelauto.recovery.TrustedQuotaAcquirer
import com.example.cellrebelauto.recovery.testApplyIntent
import com.example.cellrebelauto.repository.PlanRepository
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import kotlinx.coroutines.runBlocking
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
 * Real-Room killing oracles for the durable provider-owner join. Proof-only fakes are insufficient:
 * a plan/attempt owner can be null or foreign while every existing receipt/checkpoint still carries
 * the executor target. Every such mixed boundary must stop before the first provider/acquisition or
 * durable proof mutation.
 */
@RunWith(RobolectricTestRunner::class)
class RoomProviderPrincipalBoundaryKillTest {

    private val production = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
    private val bench = ContractV1.PROVIDER_APPLICATION_ID_BENCH
    private val signer = "sha256:95ddffa5db56c991b114854eddf6760ea7749abeca86e931ad0aa09a5d5b0443"
    private val foreignSigner =
        "sha256:cb72bbdd3f592d513a776f5a110027f759c490215fd8fab1b7bc3414946c9b47"

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository
    private lateinit var rawProvider: RecordingExternalApplyExecutor
    private lateinit var productionBackend: APlusBackend

    @Before
    fun setUp() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repo = PlanRepository(db)
        ProviderTrustStore(db.providerPairingDao()).approve(
            applicationId = production,
            signerDigest = signer,
            versionCode = 1,
            approvedAt = 1L,
        )
        rawProvider = RecordingExternalApplyExecutor()
        productionBackend = APlusComposition.testOnlyBackend(
            context = app,
            db = db,
            providerExecutor = ProviderScopedExternalApplyExecutor.wrap(production, rawProvider),
            providerSignerDigest = { signer },
            attemptValidityTimeoutMs = 90_000L,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private data class Seed(
        val planId: Long,
        val taskId: Long,
        val sessionId: Long,
        val attemptId: Long,
    )

    private suspend fun seedAttempt(
        planPrincipal: String?,
        attemptPrincipal: String?,
        phase: String = "APPLY_PENDING",
        leaseId: String? = null,
        anchorScheduleId: String? = null,
        attemptSigner: String? = null,
    ): Seed {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "principal-boundary.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = planPrincipal,
            ),
            listOf(
                LocationTask(
                    planId = 0,
                    csvRow = 1,
                    longitude = 30.5234,
                    latitude = 50.4501,
                    priority = 1,
                    requiredSuccesses = 1,
                )
            ),
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 1_000L, status = "running", planId = planId)
        )
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId,
                runSessionId = sessionId,
                attemptOrdinal = 1,
                successOrdinal = null,
                startedAt = 1_000L,
                runningObservedAt = 1_100L,
                endedAt = null,
                status = "running",
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 50.4501,
                longitude = 30.5234,
                aplusState = phase,
                aplusLeaseId = leaseId,
                aplusAnchorScheduleId = anchorScheduleId,
                providerApplicationId = attemptPrincipal,
                providerSignerDigest = attemptSigner,
            )
        )
        return Seed(planId, taskId, sessionId, attemptId)
    }

    private suspend fun seedApplyReceipt(
        attemptId: Long,
        leaseId: String,
        receiptSigner: String? = null,
    ) {
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "digest-$attemptId",
                resultOutcome = "APPLIED",
                createdAt = 1_100L,
                leaseId = leaseId,
                operationId = "op-$attemptId",
                providerApplicationId = production,
                providerSignerDigest = receiptSigner,
            )
        )
    }

    /** Strict signer-scoped coordinator fixture; it cannot be promoted by the production factory. */
    private fun signerScopedCoordinator(): RecoveryCoordinator = RecoveryCoordinator(
        executor = productionBackend.executor,
        log = RoomDurableRecoveryLog(
            db.operationReceiptDao(),
            db.recoveryCheckpointRoomDao(),
            db.releaseReceiptDao(),
            signer,
        ),
        providerPrincipalPreflight = RoomDurableProviderPrincipalPreflight(db, signer),
        providerSignerDigest = signer,
    )

    private suspend fun runEngine(planId: Long) {
        val (coordinator, evidence) = APlusComposition.engineAplusParams(productionBackend)
        testOnlyAutomationEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onRunningObserved: suspend (Long) -> Unit,
                ) = error("malformed durable release proof cannot start a new execution")
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) =
                    error("malformed durable release proof cannot set location")
            },
            globalBufferSeconds = 0,
            testTimeoutMs = 90_000L,
            gpsSettleMs = 0L,
            stageToggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
            auditDao = db.auditEventDao(),
            aplusCoordinator = coordinator,
            aplusEvidence = evidence,
            bridge = null,
            nowMs = { 2_000L },
            delayMs = {},
            commitClockMs = { 2_000L },
            elapsedClockMs = { 2_000L },
        ).run()
    }

    private fun recoveryEngine(
        planId: Long,
        coordinator: RecoveryCoordinator,
        nowMs: () -> Long = { 2_000L },
        commitClockMs: () -> Long = { 2_000L },
    ): AutomationEngine = testOnlyAutomationEngine(
        planId = planId,
        planRepository = repo,
        cellRebelRunner = object : CellRebelRunner {
            override suspend fun runTest(
                startedAt: Long,
                testTimeoutMs: Long,
                onRunningObserved: suspend (Long) -> Unit,
            ) = error("recovery owner failure cannot start a new execution")
        },
        gpsSetter = object : GpsLocationSetter {
            override suspend fun setLocation(lat: Double, lng: Double) =
                error("recovery owner failure cannot set location")
        },
        globalBufferSeconds = 0,
        testTimeoutMs = 90_000L,
        gpsSettleMs = 0L,
        stageToggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
        auditDao = db.auditEventDao(),
        aplusCoordinator = coordinator,
        aplusEvidence = productionBackend.evidenceSource,
        bridge = null,
        nowMs = nowMs,
        delayMs = {},
        commitClockMs = commitClockMs,
        elapsedClockMs = { 2_000L },
    )

    private suspend fun sessionStatus(sessionId: Long): String =
        db.openHelper.readableDatabase.query(
            "SELECT status FROM run_sessions WHERE id = $sessionId"
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    @Test
    fun `Room null and foreign owners block every coordinator effect despite empty or same-P proofs`() =
        runTest {
            var observations = 0
            var revisions = 0
            var quotas = 0
            val trackingBackend = object : APlusBackend by productionBackend {
                override val observeIntent = ObserveIntentAcquirer { observations++; true }
                override val receiptRevision = ReceiptRevisionAcquirer { _, _ -> revisions++; true }
                override val trustedQuota = TrustedQuotaAcquirer { quotas++; true }
            }
            val coordinator = APlusComposition.recoveryCoordinator(trackingBackend)

            val applyOwner = seedAttempt(planPrincipal = null, attemptPrincipal = production)
            val applyKey = APlusOperationIdentity.applyIdempotencyKey(applyOwner.attemptId)
            val apply = coordinator.dispatchApply(
                applyOwner.attemptId,
                testApplyIntent(attemptId = applyOwner.attemptId),
                applyKey,
                "digest-${applyOwner.attemptId}",
                2_000L,
            )
            assertEquals("PROVIDER_PRINCIPAL_UNKNOWN", apply.outcome)

            val reconcileOwner = seedAttempt(planPrincipal = production, attemptPrincipal = null)
            val reconcileKey = APlusOperationIdentity.applyIdempotencyKey(reconcileOwner.attemptId)
            assertEquals(
                ReconcileResult.ProviderFailure(
                    ProviderPrincipalFailureReason.PRINCIPAL_UNKNOWN
                ),
                coordinator.reconcile(
                    reconcileOwner.attemptId,
                    testApplyIntent(attemptId = reconcileOwner.attemptId),
                    reconcileKey,
                    "digest-${reconcileOwner.attemptId}",
                    2_100L,
                ),
            )

            val releaseOwner = seedAttempt(
                planPrincipal = production,
                attemptPrincipal = bench,
                phase = "RELEASE_PENDING",
                leaseId = "lease-release",
            )
            seedApplyReceipt(releaseOwner.attemptId, "lease-release")
            val releaseKey = APlusOperationIdentity.releaseIdempotencyKey(releaseOwner.attemptId)
            assertNull(
                coordinator.releaseLease(
                    releaseOwner.attemptId,
                    releaseKey,
                    "lease-release",
                    APlusOperationIdentity.releaseDigest("lease-release"),
                    2_200L,
                )
            )

            val scheduleOwner = seedAttempt(
                planPrincipal = bench,
                attemptPrincipal = production,
                phase = "QUOTA_COMMITTED",
                leaseId = "lease-schedule",
            )
            seedApplyReceipt(scheduleOwner.attemptId, "lease-schedule")
            val scheduleApplyKey = APlusOperationIdentity.applyIdempotencyKey(scheduleOwner.attemptId)
            assertEquals(
                ScheduleAdvanceState.NOT_ADVANCED,
                coordinator.scheduleAdvanced(scheduleOwner.attemptId, scheduleApplyKey, 2_300L),
            )

            assertEquals("null/foreign owner blocks apply RPC", 0, rawProvider.invocationCount(applyKey))
            assertEquals(
                "null/foreign owner blocks reconcile RPC",
                0,
                rawProvider.invocationCount(reconcileKey),
            )
            assertEquals(
                "foreign owner blocks release RPC",
                0,
                rawProvider.releaseInvocationCount(releaseKey),
            )
            assertEquals("principal join precedes observe acquisition", 0, observations)
            assertEquals("principal join precedes revision acquisition", 0, revisions)
            assertEquals("principal join precedes quota/mint acquisition", 0, quotas)
            assertEquals("coordinator owner preflight itself performs no discover", 0, rawProvider.discoverCalls)
            assertTrue("coordinator owner preflight itself performs no preflight", rawProvider.preflightCalls.isEmpty())
            assertTrue("coordinator owner preflight itself performs no advance", rawProvider.advanceCalls.isEmpty())
            assertEquals("no trusted mint", 0, db.trustedQuotaDao().countAll())

            assertNull("failed apply cannot create an operation receipt", db.operationReceiptDao().byKey(applyKey))
            assertNull(
                "failed reconcile cannot create an operation receipt",
                db.operationReceiptDao().byKey(reconcileKey),
            )
            assertNull(
                "failed release cannot create a release receipt",
                db.releaseReceiptDao().byKey(releaseKey),
            )
            assertNull(
                "failed reconcile cannot create a recovery checkpoint",
                db.recoveryCheckpointRoomDao().byAttempt(reconcileOwner.attemptId),
            )
            assertNull(
                "failed schedule cannot create an advance checkpoint",
                db.recoveryCheckpointRoomDao().byAttempt(scheduleOwner.attemptId),
            )
        }

    @Test
    fun `normal insert is durably read back before attempt-scoped discover preflight or apply`() = runTest {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "normal-readback.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = production,
            ),
            listOf(
                LocationTask(
                    planId = 0,
                    csvRow = 1,
                    longitude = 30.5234,
                    latitude = 50.4501,
                    priority = 1,
                    requiredSuccesses = 1,
                )
            ),
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).single().id
        // Deterministic corruption seam: the insert returns normally, but its durable readback is
        // foreign. An implementation that trusts the object it just inserted proceeds to the second
        // discover/preflight/apply; a correct one performs the durable readback before any discover.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER force_foreign_attempt AFTER INSERT ON test_attempts " +
                "BEGIN UPDATE test_attempts SET providerApplicationId = '$bench' WHERE id = NEW.id; END"
        )

        val (coordinator, evidence) = APlusComposition.engineAplusParams(productionBackend)
        testOnlyAutomationEngine(
            planId = planId,
            planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onRunningObserved: suspend (Long) -> Unit,
                ) = AttemptOutcome.Failure(
                    FailureReason.UNTRUSTED,
                    "must not execute",
                    startedAt,
                    startedAt,
                )
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) = GpsOutcome.Active
            },
            globalBufferSeconds = 0,
            testTimeoutMs = 90_000L,
            gpsSettleMs = 0L,
            stageToggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
            auditDao = db.auditEventDao(),
            aplusCoordinator = coordinator,
            aplusEvidence = evidence,
            bridge = null,
            nowMs = { 2_000L },
            delayMs = {},
            commitClockMs = { 2_000L },
            elapsedClockMs = { 2_000L },
        ).run()

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals(bench, attempt.providerApplicationId)
        assertEquals("RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals("PROVIDER_PRINCIPAL_CONFLICT", attempt.failureReason)
        assertEquals(
            "discover is attempt-scoped and cannot precede the durable attempt readback",
            0,
            rawProvider.discoverCalls,
        )
        assertTrue("foreign durable attempt gets no preflight", rawProvider.preflightCalls.isEmpty())
        assertEquals(
            "foreign durable attempt gets no apply",
            0,
            rawProvider.invocationCount(APlusOperationIdentity.applyIdempotencyKey(attempt.id)),
        )
        assertNull(
            "foreign durable attempt creates no receipt",
            db.operationReceiptDao().byKey(APlusOperationIdentity.applyIdempotencyKey(attempt.id)),
        )
        assertNull(
            "foreign durable attempt creates no checkpoint",
            db.recoveryCheckpointRoomDao().byAttempt(attempt.id),
        )
        assertEquals("foreign durable attempt mints no quota", 0, db.trustedQuotaDao().countAll())
        assertTrue("foreign durable attempt never advances", rawProvider.advanceCalls.isEmpty())
    }

    @Test
    fun `fresh attempt signer is durably read back before the first provider action`() = runTest {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "signer-readback.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = production,
            ),
            listOf(
                LocationTask(
                    planId = 0,
                    csvRow = 1,
                    longitude = 30.5234,
                    latitude = 50.4501,
                    priority = 1,
                    requiredSuccesses = 1,
                )
            ),
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).single().id
        // Simulate storage/race corruption after insert but before the engine's readback. Removing
        // the post-insert Room join makes this test reach discover/apply and therefore fail.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER force_foreign_attempt_signer AFTER INSERT ON test_attempts " +
                "BEGIN UPDATE test_attempts SET providerSignerDigest = '$foreignSigner' " +
                "WHERE id = NEW.id; END"
        )

        recoveryEngine(planId, signerScopedCoordinator()).run()

        val attempt = db.testAttemptDao().getAttemptsForTask(taskId).single()
        assertEquals(foreignSigner, attempt.providerSignerDigest)
        assertEquals("RECOVERY_REQUIRED", attempt.aplusState)
        assertEquals("PROVIDER_SIGNER_OWNER_CONFLICT", attempt.failureReason)
        assertEquals("signer readback precedes discover", 0, rawProvider.discoverCalls)
        assertTrue(rawProvider.preflightCalls.isEmpty())
        assertEquals(
            0,
            rawProvider.invocationCount(APlusOperationIdentity.applyIdempotencyKey(attempt.id)),
        )
        assertTrue(rawProvider.observeCalls.isEmpty())
        assertEquals(0, rawProvider.releaseInvocationCount(
            APlusOperationIdentity.releaseIdempotencyKey(attempt.id)))
        assertTrue(rawProvider.advanceCalls.isEmpty())
        assertNull(db.operationReceiptDao().byKey(
            APlusOperationIdentity.applyIdempotencyKey(attempt.id)))
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(attempt.id))
        assertNull(db.releaseReceiptDao().byKey(
            APlusOperationIdentity.releaseIdempotencyKey(attempt.id)))
        assertEquals(0, db.trustedQuotaDao().countAll())
    }

    @Test
    fun `revoke between recovery preflight and reconcile preserves signer typed sticky reason`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "APPLY_PENDING",
            anchorScheduleId = "qwy-default-schedule",
            attemptSigner = signer,
        )
        val applyKey = APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)
        val trustStore = ProviderTrustStore(db.providerPairingDao())
        var clockCalls = 0
        val engine = recoveryEngine(
            planId = owner.planId,
            coordinator = signerScopedCoordinator(),
            nowMs = {
                clockCalls++
                runBlocking {
                    assertTrue(
                        "the deterministic race revokes A after the outer preflight",
                        trustStore.revoke(production, signer, 1_900L),
                    )
                }
                2_000L
            },
        )

        engine.run()

        val durable = db.testAttemptDao().getAttemptById(owner.attemptId)!!
        assertEquals(1, clockCalls)
        assertEquals("RECOVERY_REQUIRED", durable.aplusState)
        assertEquals(
            "the inner preflight reason must not be downgraded to a P-only result",
            PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE,
            durable.failureReason,
        )
        assertEquals("paused", sessionStatus(owner.sessionId))
        assertEquals("revoke race stops before reconcile apply", 0, rawProvider.invocationCount(applyKey))
        assertNull(db.operationReceiptDao().byKey(applyKey))
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))
        assertEquals(0, db.trustedQuotaDao().countAll())

        trustStore.approve(production, signer, versionCode = 2, approvedAt = 2_100L)
        assertEquals(
            "re-approval alone cannot clear the signer incident before registry bind",
            PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE,
            repo.recoveryProviderPrincipalFailure(owner.planId, production, signer),
        )
    }

    @Test
    fun `revoke at deciding mint boundary persists sticky signer reason before any mint`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "DECIDING",
            leaseId = "lease-mint-revoke",
            anchorScheduleId = "qwy-default-schedule",
            attemptSigner = signer,
        )
        val intentDigest = APlusOperationIdentity.requestDigest(
            APlusOperationIdentity.intent(
                owner.sessionId,
                owner.attemptId,
                owner.planId,
                "qwy-default-schedule",
                1_000L,
                91_000L,
            )
        )
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(owner.attemptId),
                requestDigest = intentDigest,
                resultOutcome = "APPLIED",
                createdAt = 1_100L,
                leaseId = "lease-mint-revoke",
                operationId = "operation-${owner.attemptId}",
                acceptedIntentHash = intentDigest,
                appliedAtEpochMs = 1_100L,
                environmentRevision = 7L,
                verificationLevelWire =
                    io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
                        .SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
                providerApplicationId = production,
                providerSignerDigest = signer,
            )
        )
        val executionId = "exec-${owner.attemptId}"
        db.attemptExecutionDao().insertIfAbsent(
            com.example.cellrebelauto.model.execution.CellRebelExecution(
                executionId = executionId,
                attemptId = owner.attemptId,
                completionEvidenceWire = 1,
                evidencePayloadDigest = "evidence-${owner.attemptId}",
                startedAt = 1_000L,
                classifiedAt = 1_100L,
                startedAtElapsed = 2_000L,
                runningConfirmedAtElapsed = 2_100L,
                completedAtElapsed = 13_000L,
                baselineRunningState = "IDLE",
                runningMarkerText = "RUNNING",
                runningDurationMs = 10_900L,
                webBrowsingScore = 8.0,
                videoStreamingScore = 7.0,
                roundTimestampsElapsed = "2000;13000",
            )
        )
        db.testAttemptDao().markCurrentExecutionId(owner.attemptId, executionId)
        fun snapshot(phase: String) = com.example.cellrebelauto.environment.ObservationSnapshot(
            leaseId = "lease-mint-revoke",
            acceptedIntentHash = intentDigest,
            coverage = "FULL",
            verificationLevel = "SYSTEM_MOCK_INDEPENDENTLY_VERIFIED",
            deliveryMode = "SYSTEM_MOCK",
            isMock = true,
            scheduleDecision = "ALLOWED_NOW",
            effectiveLat = 50.4501,
            effectiveLng = 30.5234,
            environmentRevision = 7L,
            environmentFingerprint = "fp-${owner.attemptId}",
            observedAtElapsedRealtimeMs = if (phase == "PRE") 1_000L else 14_000L,
            observedAtEpochMs = 900L,
            continuitySinceElapsedRealtimeMs = 500L,
            evidenceRefs = listOf("qwy:store:${owner.attemptId}"),
        )
        repo.persistObservation(owner.attemptId, "PRE", snapshot("PRE"))
        repo.persistObservation(owner.attemptId, "POST", snapshot("POST"))
        repo.persistCompletionReceipt(
            owner.attemptId,
            wire = 1,
            acceptedIntentHash = intentDigest,
            leaseId = "lease-mint-revoke",
        )

        val trustStore = ProviderTrustStore(db.providerPairingDao())
        var commitCalls = 0
        val engine = recoveryEngine(
            planId = owner.planId,
            coordinator = signerScopedCoordinator(),
            commitClockMs = {
                commitCalls++
                runBlocking {
                    assertTrue(
                        "the deterministic race revokes A immediately before the mint transaction",
                        trustStore.revoke(production, signer, 1_950L),
                    )
                }
                2_000L
            },
        )

        engine.run()

        val durable = db.testAttemptDao().getAttemptById(owner.attemptId)!!
        assertEquals(1, commitCalls)
        assertEquals("RECOVERY_REQUIRED", durable.aplusState)
        assertEquals(
            "mint-boundary trust loss must remain signer-typed and sticky",
            PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE,
            durable.failureReason,
        )
        assertEquals("paused", sessionStatus(owner.sessionId))
        assertEquals("revoke precedes trusted quota mint", 0, db.trustedQuotaDao().countAll())
        assertEquals(0, rawProvider.releaseInvocationCount(
            APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)))
        assertTrue(rawProvider.advanceCalls.isEmpty())
        assertNull(db.releaseReceiptDao().byKey(
            APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)))
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))

        trustStore.approve(production, signer, versionCode = 2, approvedAt = 2_100L)
        assertEquals(
            "re-approval cannot make a pre-mint signer incident bindable",
            PROVIDER_SIGNER_UNTRUSTED_RELEASE_FAILURE,
            repo.recoveryProviderPrincipalFailure(owner.planId, production, signer),
        )
    }

    @Test
    fun `recovery joins a null Room attempt owner before every provider and decision consumer`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = null,
            phase = "DECIDING",
            leaseId = "lease-recovery",
        )
        seedApplyReceipt(owner.attemptId, "lease-recovery")
        db.recoveryCheckpointRoomDao().insertIfAbsent(
            RecoveryCheckpointRow(
                attemptId = owner.attemptId,
                lastDurableStage = "DECIDING",
                receiptKey = APlusOperationIdentity.applyIdempotencyKey(owner.attemptId),
                recordedAt = 1_200L,
                providerApplicationId = production,
            )
        )

        val (coordinator, evidence) = APlusComposition.engineAplusParams(productionBackend)
        testOnlyAutomationEngine(
            planId = owner.planId,
            planRepository = repo,
            cellRebelRunner = object : CellRebelRunner {
                override suspend fun runTest(
                    startedAt: Long,
                    testTimeoutMs: Long,
                    onRunningObserved: suspend (Long) -> Unit,
                ) = error("invalid recovery owner cannot start a new execution")
            },
            gpsSetter = object : GpsLocationSetter {
                override suspend fun setLocation(lat: Double, lng: Double) =
                    error("invalid recovery owner cannot set location")
            },
            globalBufferSeconds = 0,
            testTimeoutMs = 90_000L,
            gpsSettleMs = 0L,
            stageToggles = { StageToggles(locationStageEnabled = true, testStageEnabled = true) },
            auditDao = db.auditEventDao(),
            aplusCoordinator = coordinator,
            aplusEvidence = evidence,
            bridge = null,
            nowMs = { 2_000L },
            delayMs = {},
            commitClockMs = { 2_000L },
            elapsedClockMs = { 2_000L },
        ).run()

        val durable = db.testAttemptDao().getAttemptById(owner.attemptId)!!
        assertEquals("RECOVERY_REQUIRED", durable.aplusState)
        assertEquals("PROVIDER_PRINCIPAL_UNKNOWN", durable.failureReason)
        assertEquals("invalid recovery owner gets no discover", 0, rawProvider.discoverCalls)
        assertTrue("invalid recovery owner gets no preflight", rawProvider.preflightCalls.isEmpty())
        assertEquals(
            "invalid recovery owner gets no apply/reconcile",
            0,
            rawProvider.invocationCount(APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)),
        )
        assertEquals(
            "invalid recovery owner gets no release",
            0,
            rawProvider.releaseInvocationCount(APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)),
        )
        assertTrue("invalid recovery owner gets no observe", rawProvider.observeCalls.isEmpty())
        assertTrue("invalid recovery owner gets no advance", rawProvider.advanceCalls.isEmpty())
        assertEquals("invalid recovery owner gets no mint", 0, db.trustedQuotaDao().countAll())
        assertNull(
            "same-P proof remains immutable; no release receipt is added",
            db.releaseReceiptDao().byKey(APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)),
        )
        assertEquals(
            "same-P checkpoint is not advanced",
            "DECIDING",
            db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId)?.lastDurableStage,
        )
    }

    @Test
    fun `service pre-bind projection persists owner and readiness failures without acquisition`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = null,
            phase = "RELEASE_PENDING",
            leaseId = "lease-prebind",
        )
        seedApplyReceipt(owner.attemptId, "lease-prebind")

        assertEquals(
            "PROVIDER_PRINCIPAL_UNKNOWN",
            repo.testOnlyRecoveryProviderApplicationFailure(owner.planId, production),
        )
        assertEquals(
            1,
            AutomationService.persistProviderPrincipalRecovery(
                owner.planId,
                repo,
                "PROVIDER_PRINCIPAL_UNKNOWN",
            ),
        )
        assertEquals(
            "PROVIDER_PRINCIPAL_UNKNOWN",
            db.testAttemptDao().getAttemptById(owner.attemptId)?.failureReason,
        )
        assertEquals(
            "paused",
            db.runSessionDao().findActiveRunningSession(owner.planId)?.status
                ?: db.runSessionDao().let { dao ->
                    // The active query intentionally stops returning a paused row; read it via SQL.
                    db.openHelper.readableDatabase.query(
                        "SELECT status FROM run_sessions WHERE id = ${owner.sessionId}"
                    ).use { cursor ->
                        cursor.moveToFirst()
                        cursor.getString(0)
                    }
                },
        )

        // A separate ready-timeout projection uses a typed durable reason rather than leaving a
        // migrated/recovered attempt in RELEASE_PENDING with a running session.
        db.testAttemptDao().markAplusState(owner.attemptId, "RELEASE_PENDING")
        db.runSessionDao().updateStatus(owner.sessionId, "running")
        assertEquals(
            1,
            AutomationService.persistProviderUnavailableRecovery(owner.planId, repo),
        )
        assertEquals(
            "PROVIDER_BIND_NOT_READY",
            db.testAttemptDao().getAttemptById(owner.attemptId)?.failureReason,
        )
        val sessionStatus = db.openHelper.readableDatabase.query(
            "SELECT status FROM run_sessions WHERE id = ${owner.sessionId}"
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        assertEquals("paused", sessionStatus)
    }

    @Test
    fun `durable coordinator rejects a noncanonical release identity before every effect`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "RELEASE_PENDING",
            leaseId = "lease-canonical-release",
            attemptSigner = signer,
        )
        seedApplyReceipt(owner.attemptId, "lease-canonical-release", signer)
        val coordinator = signerScopedCoordinator()
        val wrongKey = "caller-selected-release-key"

        assertNull(
            coordinator.releaseLease(
                attemptId = owner.attemptId,
                idempotencyKey = wrongKey,
                leaseId = "lease-canonical-release",
                releaseDigest = APlusOperationIdentity.releaseDigest("lease-canonical-release"),
                now = 2_000L,
            )
        )

        assertEquals(0, rawProvider.releaseInvocationCount(wrongKey))
        assertTrue(rawProvider.advanceCalls.isEmpty())
        assertEquals(0, db.trustedQuotaDao().countAll())
        assertNull(db.releaseReceiptDao().byKey(wrongKey))
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))
        assertEquals(
            production,
            db.operationReceiptDao().byKey(
                APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)
            )?.providerApplicationId,
        )
    }

    @Test
    fun `durable coordinator rejects a noncanonical advance key before every effect`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "QUOTA_COMMITTED",
            leaseId = "lease-canonical-advance",
        )
        seedApplyReceipt(owner.attemptId, "lease-canonical-advance")
        val coordinator = APlusComposition.recoveryCoordinator(productionBackend)
        val wrongKey = "caller-selected-advance-key"
        val request = CompleteAndAdvanceRequestV1(
            leaseId = "lease-canonical-advance",
            idempotencyKey = wrongKey,
            requestDigest = "caller-selected-digest",
            expectedScheduleId = "schedule-1",
            expectedScheduleVersion = 1L,
            expectedCurrentItemId = "item-1",
            completionProof = CompletionProofV1(
                scheduleItemId = "item-1",
                trustedSuccessCount = 1,
                quotaRequired = 1,
                ledgerRef = "ledger-${owner.attemptId}",
                verifiedAtElapsedRealtimeMs = 2_000L,
            ),
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        )

        assertNull(
            coordinator.completeAndAdvanceForAttempt(
                owner.attemptId,
                request,
                expectedIntentHash = "intent-${owner.attemptId}",
            )
        )

        assertTrue(rawProvider.advanceCalls.isEmpty())
        assertEquals(0, rawProvider.releaseInvocationCount(
            APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)
        ))
        assertEquals(0, db.trustedQuotaDao().countAll())
        assertNull(
            db.releaseReceiptDao().byKey(
                APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)
            )
        )
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))
        assertEquals(
            production,
            db.operationReceiptDao().byKey(
                APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)
            )?.providerApplicationId,
        )
    }

    @Test
    fun `canonical release key with a wrong stored digest blocks before evidence or provider work`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "ENV_APPLIED",
            leaseId = "lease-stored-digest",
            anchorScheduleId = "qwy-default-schedule",
        )
        seedApplyReceipt(owner.attemptId, "lease-stored-digest")
        val canonicalKey = APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)
        db.releaseReceiptDao().insertIfAbsent(
            ReleaseReceiptRow(
                idempotencyKey = canonicalKey,
                leaseId = "lease-stored-digest",
                releaseDigest = "tampered-stored-digest",
                resultOutcome = "RELEASED",
                createdAt = 1_300L,
                providerApplicationId = production,
            )
        )

        val preBindFailure = repo.testOnlyRecoveryProviderApplicationFailure(owner.planId, production)
        runEngine(owner.planId)

        assertEquals("PROVIDER_PRINCIPAL_CONFLICT", preBindFailure)
        val durable = db.testAttemptDao().getAttemptById(owner.attemptId)!!
        assertEquals("RECOVERY_REQUIRED", durable.aplusState)
        assertEquals("PROVIDER_PRINCIPAL_CONFLICT", durable.failureReason)
        assertEquals("malformed stored proof blocks discover", 0, rawProvider.discoverCalls)
        assertTrue("malformed stored proof blocks preflight", rawProvider.preflightCalls.isEmpty())
        assertTrue("malformed stored proof blocks evidence observe", rawProvider.observeCalls.isEmpty())
        assertEquals("malformed stored proof blocks release RPC", 0, rawProvider.releaseInvocationCount(canonicalKey))
        assertTrue("malformed stored proof blocks advance", rawProvider.advanceCalls.isEmpty())
        assertEquals("malformed stored proof blocks mint", 0, db.trustedQuotaDao().countAll())
        assertEquals(
            "tampered proof is inert and immutable",
            "tampered-stored-digest",
            db.releaseReceiptDao().byKey(canonicalKey)?.releaseDigest,
        )
        assertNull("no checkpoint can advance", db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))
    }

    @Test
    fun `same provider and lease stored under a wrong release key blocks before evidence or provider work`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "ENV_APPLIED",
            leaseId = "lease-wrong-stored-key",
            anchorScheduleId = "qwy-default-schedule",
        )
        seedApplyReceipt(owner.attemptId, "lease-wrong-stored-key")
        val canonicalKey = APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)
        val wrongKey = "release-for-another-owner"
        db.releaseReceiptDao().insertIfAbsent(
            ReleaseReceiptRow(
                idempotencyKey = wrongKey,
                leaseId = "lease-wrong-stored-key",
                releaseDigest = APlusOperationIdentity.releaseDigest("lease-wrong-stored-key"),
                resultOutcome = "RELEASED",
                createdAt = 1_300L,
                providerApplicationId = production,
            )
        )

        val preBindFailure = repo.testOnlyRecoveryProviderApplicationFailure(owner.planId, production)
        runEngine(owner.planId)

        assertEquals("PROVIDER_PRINCIPAL_CONFLICT", preBindFailure)
        val durable = db.testAttemptDao().getAttemptById(owner.attemptId)!!
        assertEquals("RECOVERY_REQUIRED", durable.aplusState)
        assertEquals("PROVIDER_PRINCIPAL_CONFLICT", durable.failureReason)
        assertEquals(0, rawProvider.discoverCalls)
        assertTrue(rawProvider.preflightCalls.isEmpty())
        assertTrue("wrong-key proof blocks evidence observe", rawProvider.observeCalls.isEmpty())
        assertEquals(0, rawProvider.releaseInvocationCount(canonicalKey))
        assertEquals(0, rawProvider.releaseInvocationCount(wrongKey))
        assertTrue(rawProvider.advanceCalls.isEmpty())
        assertEquals(0, db.trustedQuotaDao().countAll())
        assertNull("canonical release receipt is never fabricated", db.releaseReceiptDao().byKey(canonicalKey))
        assertEquals("wrong-key row is preserved for manual recovery", wrongKey, db.releaseReceiptDao().byKey(wrongKey)?.idempotencyKey)
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))
    }

    @Test
    fun `wrong caller release digest is rejected before every provider and durable effect`() = runTest {
        val owner = seedAttempt(
            planPrincipal = production,
            attemptPrincipal = production,
            phase = "RELEASE_PENDING",
            leaseId = "lease-wrong-caller-digest",
            attemptSigner = signer,
        )
        seedApplyReceipt(owner.attemptId, "lease-wrong-caller-digest", signer)
        val canonicalKey = APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)
        val coordinator = signerScopedCoordinator()

        assertNull(
            coordinator.releaseLease(
                owner.attemptId,
                canonicalKey,
                "lease-wrong-caller-digest",
                "caller-supplied-wrong-digest",
                2_000L,
            )
        )
        assertEquals(0, rawProvider.releaseInvocationCount(canonicalKey))
        assertTrue(rawProvider.observeCalls.isEmpty())
        assertTrue(rawProvider.advanceCalls.isEmpty())
        assertEquals(0, db.trustedQuotaDao().countAll())
        assertNull(db.releaseReceiptDao().byKey(canonicalKey))
        assertNull(db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId))
    }
}
