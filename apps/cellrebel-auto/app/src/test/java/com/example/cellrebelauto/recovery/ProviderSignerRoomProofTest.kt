package com.example.cellrebelauto.recovery

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.repository.PlanRepository
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProviderSignerRoomProofTest {

    private val provider = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
    private val bench = ContractV1.PROVIDER_APPLICATION_ID_BENCH
    private val signerA =
        "sha256:22e67db2ac5fbdf49e8d8a2240a55057b3501e4e2085cead547d19d8853acac8"
    private val signerB =
        "sha256:3b20b06be2531a128426fcf6d873eb2ce27f086b7a0e6ef0f20586076e5f3cd3"

    private enum class Proof { OPERATION, CHECKPOINT, RELEASE }

    @Test
    fun `every existing proof row with null or foreign signer stops prebind without mutation`() = runTest {
        for (proof in Proof.entries) {
            for (recordedSigner in listOf<String?>(null, signerB)) {
                val app = ApplicationProvider.getApplicationContext<android.app.Application>()
                val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
                try {
                    ProviderTrustStore(db.providerPairingDao()).approve(provider, signerA, 1, 1L)
                    val planId = db.planDao().insertPlanWithTasks(
                        LocationPlan(
                            sourceFileName = "proof-$proof.csv",
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
                                longitude = 30.5,
                                latitude = 50.4,
                                priority = 1,
                                requiredSuccesses = 1,
                            )
                        ),
                    )
                    val taskId = db.locationTaskDao().getTasksForPlan(planId).single().id
                    val sessionId = db.runSessionDao().insert(
                        RunSession(startedAt = 1L, status = "running", planId = planId)
                    )
                    val lease = "lease-$proof-${recordedSigner ?: "null"}"
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
                            latitude = 50.4,
                            longitude = 30.5,
                            aplusState = "RELEASE_PENDING",
                            aplusLeaseId = lease,
                            providerApplicationId = provider,
                            providerSignerDigest = signerA,
                        )
                    )
                    db.operationReceiptDao().insertIfAbsent(
                        OperationReceiptRow(
                            idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                            requestDigest = "apply-$attemptId",
                            resultOutcome = "APPLIED",
                            createdAt = 4L,
                            leaseId = lease,
                            operationId = "operation-$attemptId",
                            providerApplicationId = provider,
                            providerSignerDigest = if (proof == Proof.OPERATION) recordedSigner else signerA,
                        )
                    )
                    if (proof == Proof.CHECKPOINT) {
                        db.recoveryCheckpointRoomDao().insertIfAbsent(
                            RecoveryCheckpointRow(
                                attemptId = attemptId,
                                lastDurableStage = "RELEASE_PENDING",
                                receiptKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                                recordedAt = 5L,
                                providerApplicationId = provider,
                                providerSignerDigest = recordedSigner,
                            )
                        )
                    }
                    if (proof == Proof.RELEASE) {
                        db.releaseReceiptDao().insertIfAbsent(
                            ReleaseReceiptRow(
                                idempotencyKey = APlusOperationIdentity.releaseIdempotencyKey(attemptId),
                                leaseId = lease,
                                releaseDigest = APlusOperationIdentity.releaseDigest(lease),
                                resultOutcome = "RELEASED",
                                createdAt = 6L,
                                providerApplicationId = provider,
                                providerSignerDigest = recordedSigner,
                            )
                        )
                    }
                    val beforeOperation = db.operationReceiptDao().byKey(
                        APlusOperationIdentity.applyIdempotencyKey(attemptId)
                    )
                    val beforeCheckpoint = db.recoveryCheckpointRoomDao().byAttempt(attemptId)
                    val beforeRelease = db.releaseReceiptDao().byKey(
                        APlusOperationIdentity.releaseIdempotencyKey(attemptId)
                    )

                    val failure = PlanRepository(db).guardRecoveryProviderPrincipal(
                        planId,
                        provider,
                        signerA,
                    )
                    val expected = if (recordedSigner == null) {
                        PROVIDER_SIGNER_OWNER_UNKNOWN_FAILURE
                    } else {
                        PROVIDER_SIGNER_OWNER_CONFLICT_FAILURE
                    }

                    assertEquals("$proof/$recordedSigner", expected, failure)
                    assertEquals(beforeOperation, db.operationReceiptDao().byKey(
                        APlusOperationIdentity.applyIdempotencyKey(attemptId)))
                    assertEquals(beforeCheckpoint, db.recoveryCheckpointRoomDao().byAttempt(attemptId))
                    assertEquals(beforeRelease, db.releaseReceiptDao().byKey(
                        APlusOperationIdentity.releaseIdempotencyKey(attemptId)))
                    assertEquals(0, db.trustedQuotaDao().countAll())
                    assertEquals("RECOVERY_REQUIRED", db.testAttemptDao().getAttemptById(attemptId)?.aplusState)
                    assertEquals(expected, db.testAttemptDao().getAttemptById(attemptId)?.failureReason)
                    assertEquals("paused", db.runSessionDao().getById(sessionId)?.status)
                    // The Service calls registry acquire only after this returns null.
                    val acquireCalls = if (failure == null) 1 else 0
                    assertEquals(0, acquireCalls)
                } finally {
                    db.close()
                }
            }
        }
    }

    @Test
    fun `same key and digest cannot replay across signer and checkpoint cannot overwrite across signer`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val key = "apply-cross-signer"
            val storedReceipt = OperationReceiptRow(
                idempotencyKey = key,
                requestDigest = "same-digest",
                resultOutcome = "APPLIED",
                createdAt = 1L,
                providerApplicationId = provider,
                providerSignerDigest = signerB,
            )
            db.operationReceiptDao().insertIfAbsent(storedReceipt)
            val storedCheckpoint = RecoveryCheckpointRow(
                attemptId = 7L,
                lastDurableStage = "BEFORE",
                receiptKey = key,
                recordedAt = 2L,
                providerApplicationId = provider,
                providerSignerDigest = signerB,
            )
            db.recoveryCheckpointRoomDao().insertIfAbsent(storedCheckpoint)
            val logA = RoomDurableRecoveryLog(
                db.operationReceiptDao(),
                db.recoveryCheckpointRoomDao(),
                db.releaseReceiptDao(),
                signerA,
            )

            assertNull(logA.recordReceipt(
                key, "same-digest", "APPLIED", 3L,
                null, null, null, null, null, null, provider,
            ))
            assertNull(logA.recordCheckpoint(7L, "AFTER", key, 4L, provider))
            assertEquals(storedReceipt, db.operationReceiptDao().byKey(key))
            assertEquals(storedCheckpoint, db.recoveryCheckpointRoomDao().byAttempt(7L))
        } finally {
            db.close()
        }
    }

    @Test
    fun `same provider lease proof is never reused across signer`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val lease = "lease-cross-signer"
            val key = "release-cross-signer"
            val stored = ReleaseReceiptRow(
                idempotencyKey = key,
                leaseId = lease,
                releaseDigest = APlusOperationIdentity.releaseDigest(lease),
                resultOutcome = "RELEASED",
                createdAt = 1L,
                providerApplicationId = provider,
                providerSignerDigest = signerA,
            )
            db.releaseReceiptDao().insertIfAbsent(stored)
            val logB = RoomDurableRecoveryLog(
                db.operationReceiptDao(),
                db.recoveryCheckpointRoomDao(),
                db.releaseReceiptDao(),
                signerB,
            )

            assertNull(logB.releaseReceiptFor(lease, provider))
            assertNull(logB.releaseReceiptForKey(key))
            assertNull(logB.recordReleaseReceipt(
                key,
                lease,
                APlusOperationIdentity.releaseDigest(lease),
                "RELEASED",
                2L,
                provider,
            ))
            assertEquals(stored, db.releaseReceiptDao().byKey(key))
            assertEquals(1, db.releaseReceiptDao().allByLease(lease, provider).size)
        } finally {
            db.close()
        }
    }

    @Test
    fun `same provider lease cannot be inserted under a different signer and key`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val lease = "lease-cross-signer-different-key"
            val stored = ReleaseReceiptRow(
                idempotencyKey = "release-owned-by-A",
                leaseId = lease,
                releaseDigest = APlusOperationIdentity.releaseDigest(lease),
                resultOutcome = "RELEASED",
                createdAt = 1L,
                providerApplicationId = provider,
                providerSignerDigest = signerA,
            )
            db.releaseReceiptDao().insertIfAbsent(stored)
            val logB = RoomDurableRecoveryLog(
                db.operationReceiptDao(),
                db.recoveryCheckpointRoomDao(),
                db.releaseReceiptDao(),
                signerB,
            )

            assertNull(
                "lease identity is (P,leaseId); B cannot append beside A using another key",
                logB.recordReleaseReceipt(
                    "release-owned-by-B",
                    lease,
                    APlusOperationIdentity.releaseDigest(lease),
                    "RELEASED",
                    2L,
                    provider,
                ),
            )
            assertEquals(listOf(stored), db.releaseReceiptDao().allByLease(lease, provider))
        } finally {
            db.close()
        }
    }

    @Test
    fun `release lease scope stays per provider while idempotency keys stay global`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        try {
            val log = RoomDurableRecoveryLog(
                db.operationReceiptDao(),
                db.recoveryCheckpointRoomDao(),
                db.releaseReceiptDao(),
                signerA,
            )
            val sharedLease = "lease-valid-in-two-provider-namespaces"
            val sharedDigest = APlusOperationIdentity.releaseDigest(sharedLease)
            val production = requireNotNull(log.recordReleaseReceipt(
                "release-production",
                sharedLease,
                sharedDigest,
                "RELEASED",
                1L,
                provider,
            ))

            assertNull(
                "an idempotency key is global even when the other provider uses another lease",
                log.recordReleaseReceipt(
                    production.idempotencyKey,
                    "bench-other-lease",
                    APlusOperationIdentity.releaseDigest("bench-other-lease"),
                    "RELEASED",
                    2L,
                    bench,
                ),
            )
            assertEquals(
                "cross-P key conflict preserves the original owner",
                provider,
                db.releaseReceiptDao().byKey(production.idempotencyKey)?.providerApplicationId,
            )
            val benchReceipt = log.recordReleaseReceipt(
                "release-bench",
                sharedLease,
                sharedDigest,
                "RELEASED",
                3L,
                bench,
            )
            assertEquals(bench, benchReceipt?.providerApplicationId)
            assertEquals(1, db.releaseReceiptDao().allByLease(sharedLease, provider).size)
            assertEquals(1, db.releaseReceiptDao().allByLease(sharedLease, bench).size)
        } finally {
            db.close()
        }
    }
}
