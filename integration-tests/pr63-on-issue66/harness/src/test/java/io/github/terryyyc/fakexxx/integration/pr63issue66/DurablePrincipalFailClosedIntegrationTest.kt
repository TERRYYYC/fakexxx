package io.github.terryyyc.fakexxx.integration.pr63issue66

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.OperationReceiptRow
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DurablePrincipalFailClosedIntegrationTest {

    @Test
    fun `legacy foreign and half principals stop before registry or QWY`() =
        kotlinx.coroutines.test.runTest {
            val cases = listOf(
                Corruption(
                    label = "legacy-null-plan",
                    planProvider = null,
                    attemptProvider = null,
                    attemptSigner = null,
                    receiptProvider = null,
                    receiptSigner = null,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_PRINCIPAL_UNKNOWN",
                ),
                Corruption(
                    label = "half-null-attempt-signer",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = null,
                    receiptProvider = BENCH,
                    receiptSigner = null,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_SIGNER_OWNER_UNKNOWN",
                ),
                Corruption(
                    label = "foreign-attempt-provider",
                    planProvider = BENCH,
                    attemptProvider = PRODUCTION,
                    attemptSigner = SIGNER_A,
                    receiptProvider = PRODUCTION,
                    receiptSigner = SIGNER_A,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_PRINCIPAL_CONFLICT",
                ),
                Corruption(
                    label = "foreign-operation-receipt-provider",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = SIGNER_A,
                    receiptProvider = PRODUCTION,
                    receiptSigner = SIGNER_A,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_PRINCIPAL_CONFLICT",
                ),
                Corruption(
                    label = "half-null-receipt-provider",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = SIGNER_A,
                    receiptProvider = null,
                    receiptSigner = SIGNER_A,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_PRINCIPAL_UNKNOWN",
                ),
                Corruption(
                    label = "half-null-receipt-signer",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = SIGNER_A,
                    receiptProvider = BENCH,
                    receiptSigner = null,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_SIGNER_OWNER_UNKNOWN",
                ),
                Corruption(
                    label = "A-owner-after-B-approved",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = SIGNER_A,
                    receiptProvider = BENCH,
                    receiptSigner = SIGNER_A,
                    currentSigner = SIGNER_B,
                    expectedReason = "PROVIDER_SIGNER_OWNER_CONFLICT",
                    approveReplacement = true,
                ),
                Corruption(
                    label = "same-lease-null-signer-release-proof",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = SIGNER_A,
                    receiptProvider = BENCH,
                    receiptSigner = SIGNER_A,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_SIGNER_OWNER_UNKNOWN",
                    seedReleaseProof = true,
                    releaseSigner = null,
                ),
                Corruption(
                    label = "same-lease-foreign-signer-release-proof",
                    planProvider = BENCH,
                    attemptProvider = BENCH,
                    attemptSigner = SIGNER_A,
                    receiptProvider = BENCH,
                    receiptSigner = SIGNER_A,
                    currentSigner = SIGNER_A,
                    expectedReason = "PROVIDER_SIGNER_OWNER_CONFLICT",
                    seedReleaseProof = true,
                    releaseSigner = SIGNER_B,
                ),
            )

            cases.forEach { corruption -> exercise(corruption) }
        }

    private suspend fun exercise(corruption: Corruption) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val qwy = ProviderHarness.createWithExternalEnvStore()
        val calls = JourneyRpcCounters()
        val autoApplicationId = AutoIntegrationBridge.autoApplicationId()
        qwy.resolver.register(AUTO_UID, autoApplicationId, corruption.currentSigner)
        qwy.pair(autoApplicationId, corruption.currentSigner)
        val routing = RoutingContext(
            app,
            mapOf(BENCH to LocalQwyBinder(qwy, AUTO_UID, calls)),
        )
        try {
            if (corruption.planProvider != null) {
                ProviderTrustStore(db.providerPairingDao()).approve(
                    corruption.planProvider,
                    SIGNER_A,
                    1,
                    100L,
                )
                if (corruption.approveReplacement) {
                    ProviderTrustStore(db.providerPairingDao()).approve(
                        corruption.planProvider,
                        SIGNER_B,
                        2,
                        200L,
                    )
                }
            }
            val planId = db.planDao().insertPlanWithTasks(
                LocationPlan(
                    sourceFileName = "${corruption.label}.csv",
                    importedAt = 1L,
                    globalBufferSeconds = 0,
                    totalRows = 1,
                    totalRequiredSuccesses = 1,
                    providerApplicationId = corruption.planProvider,
                ),
                listOf(
                    LocationTask(
                        planId = 0,
                        csvRow = 1,
                        longitude = 30.5234,
                        latitude = 50.4501,
                        priority = 1,
                        requiredSuccesses = 1,
                    ),
                ),
            )
            val taskId = db.locationTaskDao().getTasksForPlan(planId).single().id
            val sessionId = db.runSessionDao().insert(
                RunSession(startedAt = 1L, status = "running", planId = planId),
            )
            val lease = "lease-${corruption.label}"
            val attemptId = db.testAttemptDao().insert(
                TestAttempt(
                    taskId = taskId,
                    runSessionId = sessionId,
                    attemptOrdinal = 1,
                    successOrdinal = null,
                    startedAt = 2L,
                    runningObservedAt = 3L,
                    endedAt = null,
                    status = "running",
                    failureReason = null,
                    webBrowsingScore = null,
                    videoStreamingScore = null,
                    latitude = 50.4501,
                    longitude = 30.5234,
                    aplusState = "RELEASE_PENDING",
                    aplusLeaseId = lease,
                    providerApplicationId = corruption.attemptProvider,
                    providerSignerDigest = corruption.attemptSigner,
                ),
            )
            val applyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId)
            db.operationReceiptDao().insertIfAbsent(
                OperationReceiptRow(
                    idempotencyKey = applyKey,
                    requestDigest = "digest-${corruption.label}",
                    resultOutcome = "APPLIED",
                    createdAt = 4L,
                    leaseId = lease,
                    operationId = "operation-${corruption.label}",
                    providerApplicationId = corruption.receiptProvider,
                    providerSignerDigest = corruption.receiptSigner,
                ),
            )
            if (corruption.seedReleaseProof) {
                db.releaseReceiptDao().insertIfAbsent(
                    ReleaseReceiptRow(
                        idempotencyKey = "wrong-release-key-${corruption.label}",
                        leaseId = lease,
                        releaseDigest = APlusOperationIdentity.releaseDigest(lease),
                        resultOutcome = "RELEASED",
                        createdAt = 5L,
                        providerApplicationId = BENCH,
                        providerSignerDigest = corruption.releaseSigner,
                    ),
                )
            }
            val beforeAttempt = requireNotNull(db.testAttemptDao().getAttemptById(attemptId))
            val beforeApply = requireNotNull(db.operationReceiptDao().byKey(applyKey))
            val beforeReleaseProofs = db.releaseReceiptDao().allByLease(lease, BENCH)

            val failure = runCatching {
                AutoDurableJourneyBridge.connect(routing, db, planId) {
                    corruption.currentSigner
                }.close()
            }.exceptionOrNull()

            assertTrue(corruption.label, failure is IllegalStateException)
            assertTrue(
                "${corruption.label}: exact durable reason missing from ${failure?.message}",
                failure?.message?.contains(corruption.expectedReason) == true ||
                    (corruption.planProvider == null && failure?.message?.contains("unknown") == true),
            )
            assertEquals("${corruption.label}: no bind", emptyList<String>(), routing.bindAttempts)
            assertEquals("${corruption.label}: no unbind", 0, routing.unbindCount)
            assertEquals(JourneyRpcCounters(), calls)
            assertEquals(0, qwy.env.applyCount)
            assertEquals(0, qwy.env.cleanupCount)
            assertEquals(0, qwy.env.advanceCount)
            assertEquals(0, db.trustedQuotaDao().countAll())
            assertEquals(beforeApply, db.operationReceiptDao().byKey(applyKey))
            assertNull(db.releaseReceiptDao().byKey(APlusOperationIdentity.releaseIdempotencyKey(attemptId)))
            assertNull(db.recoveryCheckpointRoomDao().byAttempt(attemptId))
            assertEquals(beforeReleaseProofs, db.releaseReceiptDao().allByLease(lease, BENCH))
            assertEquals(
                beforeAttempt.copy(
                    aplusState = "RECOVERY_REQUIRED",
                    failureReason = corruption.expectedReason,
                ),
                db.testAttemptDao().getAttemptById(attemptId),
            )
            assertEquals("paused", db.runSessionDao().getById(sessionId)?.status)
        } finally {
            db.close()
        }
    }

    private data class Corruption(
        val label: String,
        val planProvider: String?,
        val attemptProvider: String?,
        val attemptSigner: String?,
        val receiptProvider: String?,
        val receiptSigner: String?,
        val currentSigner: String,
        val expectedReason: String,
        val approveReplacement: Boolean = false,
        val seedReleaseProof: Boolean = false,
        val releaseSigner: String? = null,
    )

    private companion object {
        const val AUTO_UID = 10101
        const val BENCH = ContractV1.PROVIDER_APPLICATION_ID_BENCH
        const val PRODUCTION = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        const val SIGNER_A =
            "sha256:7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41"
        const val SIGNER_B =
            "sha256:3b20b06be2531a128426fcf6d873eb2ce27f086b7a0e6ef0f20586076e5f3cd3"
    }
}
