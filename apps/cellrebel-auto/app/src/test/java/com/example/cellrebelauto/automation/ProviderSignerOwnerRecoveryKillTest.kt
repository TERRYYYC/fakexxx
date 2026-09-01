package com.example.cellrebelauto.automation

import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import com.example.cellrebelauto.recovery.OperationReceiptRow
import com.example.cellrebelauto.recovery.ProviderExecutorRegistry
import com.example.cellrebelauto.recovery.ProviderPrincipalFailureReason
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import com.example.cellrebelauto.repository.PlanRepository
import com.example.cellrebelauto.ui.PairingUiState
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import kotlinx.coroutines.test.runTest
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Crash-before-first-sticky signer-owner killing oracles.
 *
 * These tests deliberately use the production registry acquisition and production backend. The
 * only Service seam reproduced here is its documented ordering: Room pre-bind check first, then
 * registry acquire/bind. A test-only raw target wrapper is not part of this path.
 */
@RunWith(RobolectricTestRunner::class)
class ProviderSignerOwnerRecoveryKillTest {

    private val provider = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
    private val signerA =
        "sha256:22e67db2ac5fbdf49e8d8a2240a55057b3501e4e2085cead547d19d8853acac8"
    private val signerB =
        "sha256:3b20b06be2531a128426fcf6d873eb2ce27f086b7a0e6ef0f20586076e5f3cd3"

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    private data class Owner(
        val planId: Long,
        val sessionId: Long,
        val attemptId: Long,
        val leaseId: String,
    )

    private class JourneyCounters {
        var discover = 0
        var preflight = 0
        var apply = 0
        var observe = 0
        var release = 0
        var advance = 0
    }

    private open class ReleaseCompletingProvider(
        private val calls: JourneyCounters,
    ) : IEnvironmentControlV1.Stub() {
        override open fun discover(): EnvironmentControlResultV1 {
            calls.discover++
            return EnvironmentControlResultV1.failure(1)
        }

        override fun preflight(request: PreflightRequestV1): EnvironmentControlResultV1 {
            calls.preflight++
            return EnvironmentControlResultV1.failure(1)
        }

        override fun apply(request: ApplyRequestV1): EnvironmentControlResultV1 {
            calls.apply++
            return EnvironmentControlResultV1.failure(1)
        }

        override fun observe(request: ObserveRequestV1): EnvironmentControlResultV1 {
            calls.observe++
            return EnvironmentControlResultV1.failure(1)
        }

        override fun release(request: ReleaseRequestV1): EnvironmentControlResultV1 {
            calls.release++
            return EnvironmentControlResultV1.release(
                ReleaseReceiptV1(
                    operationId = request.operationId,
                    idempotencyKey = request.idempotencyKey,
                    leaseId = request.leaseId,
                    releasedAtEpochMs = 2_000L,
                    environmentRevision = 2L,
                    releaseComplete = true,
                    residualReasonWires = emptyList(),
                )
            )
        }

        override fun completeAndAdvance(
            request: CompleteAndAdvanceRequestV1,
        ): EnvironmentControlResultV1 {
            calls.advance++
            return EnvironmentControlResultV1.failure(1)
        }
    }

    @Before
    fun setUp() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        repo = PlanRepository(db)
        ProviderTrustStore(db.providerPairingDao()).approve(
            applicationId = provider,
            signerDigest = signerA,
            versionCode = 1,
            approvedAt = 100L,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun seedReleasePendingOwner(
        ownerSignerDigest: String?,
        leaseId: String = "lease-owned-by-A",
    ): Owner {
        // The apply/lease is created while signer A is current and approved. Exact-head b3523d3
        // has nowhere to persist that signer fact; the RED captures that missing owner edge.
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "signer-owner.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = provider,
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
                startedAt = 1_100L,
                runningObservedAt = 1_200L,
                endedAt = null,
                status = "running",
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 50.4501,
                longitude = 30.5234,
                aplusState = "RELEASE_PENDING",
                aplusLeaseId = leaseId,
                providerApplicationId = provider,
                providerSignerDigest = ownerSignerDigest,
            )
        )
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "apply-digest-$attemptId",
                resultOutcome = "APPLIED",
                createdAt = 1_300L,
                leaseId = leaseId,
                operationId = "operation-$attemptId",
                providerApplicationId = provider,
                providerSignerDigest = ownerSignerDigest,
            )
        )
        return Owner(planId, sessionId, attemptId, leaseId)
    }

    private suspend fun exerciseProductionRecovery(
        owner: Owner,
        currentSigner: String?,
        expectedFailure: String,
        uiProviderActive: Boolean = false,
    ) {
        val beforeAttempt = db.testAttemptDao().getAttemptById(owner.attemptId)
        val beforeApply = db.operationReceiptDao().byKey(
            APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)
        )
        val beforeReleaseProofs = db.releaseReceiptDao().allByLease(owner.leaseId, provider)
        val releaseKey = APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId)
        val beforeCanonicalRelease = db.releaseReceiptDao().byKey(releaseKey)
        val calls = JourneyCounters()
        var acquireCalls = 0
        var bindCalls = 0
        var unbindCalls = 0

        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val providerBinder = ReleaseCompletingProvider(calls)
        val bindingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                bindCalls++
                conn.onServiceConnected(requireNotNull(service.component), providerBinder)
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbindCalls++
            }
        }

        // Exact AutomationService order: durable Room gate must stop the run before this acquire.
        val failure = repo.guardRecoveryProviderPrincipal(
            owner.planId,
            provider,
            currentSigner,
        )
        if (failure == null) {
            acquireCalls++
            val acquisition = ProviderExecutorRegistry(
                bindingContext,
                currentSignerDigest = { currentSigner },
            ) { applicationId ->
                BinderExternalApplyExecutor(bindingContext, applicationId)
            }.acquire(provider, requireNotNull(currentSigner))
            try {
                assertTrue(acquisition.awaitBound(1_000L))
                val backend = APlusComposition.productionBackend(
                    context = app,
                    db = db,
                    providerAcquisition = acquisition,
                    providerSignerDigest = { currentSigner },
                    attemptValidityTimeoutMs = 90_000L,
                )
                APlusComposition.recoveryCoordinator(backend).releaseLease(
                    attemptId = owner.attemptId,
                    idempotencyKey = APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
                    leaseId = owner.leaseId,
                    releaseDigest = APlusOperationIdentity.releaseDigest(owner.leaseId),
                    now = 2_000L,
                )
            } finally {
                acquisition.close()
            }
        }

        val durableAttempt = db.testAttemptDao().getAttemptById(owner.attemptId)!!
        val sessionStatus = db.openHelper.readableDatabase.query(
            "SELECT status FROM run_sessions WHERE id = ${owner.sessionId}"
        ).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }
        assertEquals("signer owner failure permits zero raw release RPC", 0, calls.release)
        assertEquals("signer owner failure permits zero provider binds", 0, bindCalls)
        assertEquals("signer owner failure permits zero registry acquisitions", 0, acquireCalls)
        assertEquals("typed signer owner failure must be decided pre-registry", expectedFailure, failure)
        assertEquals(0, calls.discover)
        assertEquals(0, calls.preflight)
        assertEquals(0, calls.apply)
        assertEquals(0, calls.observe)
        assertEquals(0, calls.advance)
        assertEquals("no trusted completion can be minted", 0, db.trustedQuotaDao().countAll())
        assertEquals(
            "the canonical release proof remains byte-for-byte immutable",
            beforeCanonicalRelease,
            db.releaseReceiptDao().byKey(releaseKey),
        )
        assertEquals(
            "all pre-existing release proofs remain byte-for-byte immutable",
            beforeReleaseProofs,
            db.releaseReceiptDao().allByLease(owner.leaseId, provider),
        )
        assertNull(
            "no recovery checkpoint may advance",
            db.recoveryCheckpointRoomDao().byAttempt(owner.attemptId),
        )
        assertEquals("the pre-existing apply proof remains byte-for-byte immutable", beforeApply,
            db.operationReceiptDao().byKey(APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)))
        assertEquals("the attempt changes only to the typed manual-recovery owner state",
            beforeAttempt?.copy(aplusState = "RECOVERY_REQUIRED", failureReason = expectedFailure),
            durableAttempt)
        assertEquals("the active recovery session is durably paused", "paused", sessionStatus)
        assertEquals("a rejected acquisition never has anything to unbind", 0, unbindCalls)
        assertTrue(
            "the outstanding lease/manual action outranks replacement-pairing UI",
            PairingUiState.project(
                hasProviderRecord = true,
                providerActive = uiProviderActive,
                crashedAplusState = durableAttempt.aplusState,
                crashedProviderFailure =
                    ProviderPrincipalFailureReason.fromDurableCode(durableAttempt.failureReason),
                hasOutstandingLease = durableAttempt.aplusLeaseId != null,
            ) is PairingUiState.ReleaseIncomplete,
        )
    }

    private suspend fun seedReleaseProof(
        owner: Owner,
        key: String,
        signerDigest: String?,
        releaseDigest: String = APlusOperationIdentity.releaseDigest(owner.leaseId),
        leaseId: String = owner.leaseId,
    ) {
        db.releaseReceiptDao().insertIfAbsent(
            ReleaseReceiptRow(
                idempotencyKey = key,
                leaseId = leaseId,
                releaseDigest = releaseDigest,
                resultOutcome = "RELEASED",
                createdAt = 1_400L,
                providerApplicationId = provider,
                providerSignerDigest = signerDigest,
            )
        )
    }

    private fun productionPreflightSource(): String {
        val relative =
            "app/src/main/java/com/example/cellrebelauto/recovery/" +
                "DurableProviderPrincipalPreflight.kt"
        val candidates = listOf(
            File(relative),
            File(relative.removePrefix("app/")),
            File("apps/cellrebel-auto/$relative"),
            File("../cellrebel-auto/$relative"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("cannot locate $relative from ${File(".").absolutePath}")
    }

    @Test
    fun `A lease cannot be released by B approved before the first recovery`() = runTest {
        val owner = seedReleasePendingOwner(signerA)
        // Crash happens before any trust miss/sticky reason. Package rotates, then the operator
        // approves B before Auto's first recovery. B is valid only for future attempts.
        ProviderTrustStore(db.providerPairingDao()).approve(
            applicationId = provider,
            signerDigest = signerB,
            versionCode = 2,
            approvedAt = 1_500L,
        )

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = signerB,
            expectedFailure = "PROVIDER_SIGNER_OWNER_CONFLICT",
        )
    }

    @Test
    fun `legacy null signer owner pauses before current approved signer can release`() = runTest {
        val owner = seedReleasePendingOwner(null)

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = signerA,
            expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
        )
    }

    @Test
    fun `unresolvable current signer pauses an A-owned lease before registry acquisition`() = runTest {
        val owner = seedReleasePendingOwner(signerA)

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = null,
            expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
        )
    }

    @Test
    fun `wrong-key same-lease proof with null signer preserves owner unknown pre-registry`() = runTest {
        val owner = seedReleasePendingOwner(signerA, "lease-wrong-key-null-signer")
        seedReleaseProof(owner, "wrong-key-null-signer", null)

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = signerA,
            expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
        )
    }

    @Test
    fun `wrong-key same-lease proof with foreign signer preserves owner conflict pre-registry`() = runTest {
        val owner = seedReleasePendingOwner(signerA, "lease-wrong-key-foreign-signer")
        seedReleaseProof(owner, "wrong-key-foreign-signer", signerB)

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = signerA,
            expectedFailure = "PROVIDER_SIGNER_OWNER_CONFLICT",
        )
    }

    @Test
    fun `unknown signer corruption outranks foreign and structural same-lease conflicts`() = runTest {
        listOf(false, true).forEachIndexed { index, nullFirst ->
            val owner = seedReleasePendingOwner(
                signerA,
                "lease-mixed-signer-corruption-$index",
            )
            val corruptions = if (nullFirst) listOf(null, signerB) else listOf(signerB, null)
            corruptions.forEachIndexed { corruptionIndex, signerDigest ->
                seedReleaseProof(
                    owner = owner,
                    key = "wrong-key-mixed-$index-$corruptionIndex",
                    signerDigest = signerDigest,
                )
            }

            exerciseProductionRecovery(
                owner = owner,
                currentSigner = signerA,
                expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
            )
        }
    }

    @Test
    fun `canonical foreign signer cannot hide unknown wrong-key sibling`() = runTest {
        val owner = seedReleasePendingOwner(signerA, "lease-canonical-B-sibling-null")
        seedReleaseProof(
            owner,
            APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
            signerB,
            releaseDigest = "malformed-canonical-release-digest",
        )
        seedReleaseProof(owner, "wrong-key-sibling-null", null)

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = signerA,
            expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
            uiProviderActive = true,
        )
    }

    @Test
    fun `canonical foreign signer cannot hide malformed wrong-key sibling`() = runTest {
        val owner = seedReleasePendingOwner(signerA, "lease-canonical-B-sibling-malformed")
        seedReleaseProof(
            owner,
            APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
            signerB,
        )
        seedReleaseProof(owner, "wrong-key-sibling-malformed", "not-a-canonical-signer")

        exerciseProductionRecovery(
            owner = owner,
            currentSigner = signerA,
            expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
            uiProviderActive = true,
        )
    }

    @Test
    fun `release signer classification is role and row-order independent with one aggregate path`() =
        runTest {
            listOf(false, true).forEachIndexed { index, canonicalFirst ->
                val owner = seedReleasePendingOwner(
                    signerA,
                    "lease-role-reversal-$index",
                )
                val canonical = suspend {
                    seedReleaseProof(
                        owner,
                        APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
                        null,
                    )
                }
                val sibling = suspend {
                    seedReleaseProof(owner, "wrong-key-role-reversal-$index", signerB)
                }
                if (canonicalFirst) {
                    canonical()
                    sibling()
                } else {
                    sibling()
                    canonical()
                }

                exerciseProductionRecovery(
                    owner = owner,
                    currentSigner = signerA,
                    expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
                    uiProviderActive = true,
                )
            }

            val wrongLeaseOwner = seedReleasePendingOwner(
                signerA,
                "lease-canonical-wrong-lease-sibling-null",
            )
            seedReleaseProof(
                wrongLeaseOwner,
                APlusOperationIdentity.releaseIdempotencyKey(wrongLeaseOwner.attemptId),
                signerB,
                leaseId = "different-canonical-lease",
            )
            seedReleaseProof(wrongLeaseOwner, "wrong-key-wrong-lease-sibling-null", null)
            exerciseProductionRecovery(
                owner = wrongLeaseOwner,
                currentSigner = signerA,
                expectedFailure = "PROVIDER_SIGNER_OWNER_UNKNOWN",
                uiProviderActive = true,
            )

            val sourceWithoutComments = productionPreflightSource()
                .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""//.*"""), "")
            val releaseSection = sourceWithoutComments.substringAfter("val canonicalReleaseKey")
            assertFalse(
                "canonical and sibling release rows must not call the generic signer helper",
                Regex("""\bsignerFailure\s*\(""").containsMatchIn(releaseSection),
            )
            assertEquals(
                "release rows have exactly one aggregate helper definition and one call site",
                2,
                Regex("""\breleaseReceiptSignerFailure\s*\(""")
                    .findAll(sourceWithoutComments).count(),
            )
            val canonicalQuery = releaseSection.indexOf("byKey(canonicalReleaseKey)")
            val sameLeaseQuery = releaseSection.indexOf("allByLease(")
            val aggregateCall = releaseSection.indexOf("releaseReceiptSignerFailure(")
            val canonicalStructuralValidation = releaseSection.indexOf("releaseReceipt?.let")
            assertTrue(
                "canonical/same-lease reads and aggregate precede canonical structural checks",
                canonicalQuery >= 0 &&
                    canonicalQuery < sameLeaseQuery &&
                    sameLeaseQuery < aggregateCall &&
                    aggregateCall < canonicalStructuralValidation,
            )
            assertFalse(
                "release rows cannot return before the complete-set signer aggregate",
                Regex("""\breturn\b""").containsMatchIn(
                    releaseSection.substring(0, aggregateCall),
                ),
            )
            assertTrue(
                "canonical and allByLease roles form one set deduplicated by durable key",
                releaseSection.contains(
                    "(sameOwnerLease + listOfNotNull(canonicalSameProvider))." +
                        "distinctBy { it.idempotencyKey }",
                ),
            )
        }

    @Test
    fun `fresh attempt copies registry signer and reads it back before first discover RPC`() = runTest {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "fresh-signer-owner.csv",
                importedAt = 1L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = provider,
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
        var discoverCalls = 0
        var durableOwnerSeenBeforeDiscover = false
        val binder = object : ReleaseCompletingProvider(JourneyCounters()) {
            override fun discover(): EnvironmentControlResultV1 {
                discoverCalls++
                val attempt = kotlinx.coroutines.runBlocking {
                    db.testAttemptDao().getAttemptsForPlan(planId).single()
                }
                durableOwnerSeenBeforeDiscover =
                    attempt.providerApplicationId == provider &&
                        attempt.providerSignerDigest == signerA
                return EnvironmentControlResultV1.failure(1)
            }
        }
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val bindingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                conn.onServiceConnected(requireNotNull(service.component), binder)
                return true
            }

            override fun unbindService(conn: ServiceConnection) = Unit
        }
        val acquisition = ProviderExecutorRegistry(
            bindingContext,
            currentSignerDigest = { signerA },
        ) { applicationId ->
            BinderExternalApplyExecutor(bindingContext, applicationId)
        }.acquire(provider, signerA)
        try {
            assertTrue(acquisition.awaitBound(1_000L))
            val backend = APlusComposition.productionBackend(
                context = app,
                db = db,
                providerAcquisition = acquisition,
                providerSignerDigest = { signerA },
                attemptValidityTimeoutMs = 90_000L,
            )
            val (coordinator, evidence) = APlusComposition.engineAplusParams(backend)
            val engine = AutomationEngineFactory.productionEngine(
                planId = planId,
                planRepository = repo,
                cellRebelRunner = object : CellRebelRunner {
                    override suspend fun runTest(
                        startedAt: Long,
                        testTimeoutMs: Long,
                        onRunningObserved: suspend (Long) -> Unit,
                    ): AttemptOutcome = error("discover failure must stop before CellRebel")
                },
                gpsSetter = object : GpsLocationSetter {
                    override suspend fun setLocation(lat: Double, lng: Double): GpsOutcome =
                        error("discover failure must stop before GPS")
                },
                globalBufferSeconds = 0,
                testTimeoutMs = 90_000L,
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
                nowMs = { 10L },
                delayMs = {},
                commitClockMs = { 11L },
                elapsedClockMs = { 12L },
            )

            engine.run()

            assertEquals(1, discoverCalls)
            assertTrue(
                "attempt (P,S) must be durable and readable before the first provider call",
                durableOwnerSeenBeforeDiscover,
            )
            assertEquals(
                signerA,
                db.testAttemptDao().getAttemptsForPlan(planId).single().providerSignerDigest,
            )
        } finally {
            acquisition.close()
        }
    }
}
