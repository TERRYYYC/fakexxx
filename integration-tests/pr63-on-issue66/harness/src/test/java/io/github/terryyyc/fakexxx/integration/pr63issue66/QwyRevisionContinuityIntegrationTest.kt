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
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import name.caiyao.fakegps.integration.v1.EnvironmentControlHandler
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QwyRevisionContinuityIntegrationTest {

    @Test
    fun `exact Auto advance commits exactly R plus one`() =
        kotlinx.coroutines.test.runTest {
            withJourney("exact") { owner, qwy, auto, request, applyDigest ->
                val before = qwy.tracker.snapshot().revision
                val receipt = auto.completeAndAdvance(
                    owner.attemptId,
                    request,
                    applyDigest,
                )

                assertNotNull(receipt)
                assertEquals(before + 1L, receipt!!.effectiveEnvironmentRevision)
                assertEquals(before + 1L, qwy.tracker.snapshot().revision)
                assertEquals(1, qwy.env.advanceCount)
                assertNull(qwy.tracker.activeAuthoritativeReservation())

                val replay = auto.completeAndAdvance(owner.attemptId, request, applyDigest)
                assertEquals(receipt, replay)
                assertEquals("same-key replay must not allocate R+2", before + 1L, qwy.tracker.snapshot().revision)
                assertEquals("same-key replay must not move the pointer twice", 1, qwy.env.advanceCount)
            }
        }

    @Test
    fun `exact QWY owner recovery replay remains R plus one for durable Auto owner`() =
        kotlinx.coroutines.test.runTest {
            withJourney("owner-recovery") { owner, qwy, auto, request, applyDigest ->
                val before = qwy.tracker.snapshot().revision
                failPendingFinalizeOnce(qwy)

                assertNull(auto.completeAndAdvance(owner.attemptId, request, applyDigest))
                qwy.kv.failOnWrite = null
                val reservation = requireNotNull(qwy.tracker.activeAuthoritativeReservation())
                assertEquals(before + 1L, reservation.reservedRevision)
                assertEquals(before, qwy.tracker.snapshot().revision)

                qwy.restart(cleanlinessProvable = false)
                val replay = auto.completeAndAdvance(owner.attemptId, request, applyDigest)

                assertNotNull(replay)
                assertEquals(before + 1L, replay!!.effectiveEnvironmentRevision)
                assertEquals(before + 1L, qwy.tracker.snapshot().revision)
                assertEquals(1, qwy.env.advanceCount)
                assertNull(qwy.tracker.activeAuthoritativeReservation())
                assertTrue(!qwy.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))

                val first = requireNotNull(
                    auto.observe(
                        owner.attemptId,
                        request.leaseId,
                        "owner-recovery-observe-1",
                        applyDigest,
                    ),
                )
                val second = requireNotNull(
                    auto.observe(
                        owner.attemptId,
                        request.leaseId,
                        "owner-recovery-observe-2",
                        applyDigest,
                    ),
                )
                assertEquals(before + 1L, first.environmentRevision)
                assertEquals(ContinuityCoverageV1.NONE.wire, first.continuityCoverageWire)
                assertEquals(before + 1L, second.environmentRevision)
                assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
            }
        }

    @Test
    fun `uncorrelatable QWY reboot quarantines at R plus two without changing Auto owner`() =
        kotlinx.coroutines.test.runTest {
            withJourney("quarantine") { owner, qwy, auto, request, applyDigest ->
                val before = qwy.tracker.snapshot().revision
                failPendingFinalizeOnce(qwy)

                assertNull(auto.completeAndAdvance(owner.attemptId, request, applyDigest))
                qwy.kv.failOnWrite = null
                val reservation = requireNotNull(qwy.tracker.activeAuthoritativeReservation())

                qwy.restart(cleanlinessProvable = false, reboot = true)

                assertEquals(before + 2L, qwy.tracker.snapshot().revision)
                assertTrue(qwy.tracker.isAuthoritativeMutationQuarantined(reservation.mutationId))
                assertNull(qwy.tracker.activeAuthoritativeReservation())
                assertEquals(1, qwy.env.advanceCount)
                assertNull(
                    "the stale R+1 Auto replay must fail loudly instead of becoming R+2 proof",
                    auto.completeAndAdvance(owner.attemptId, request, applyDigest),
                )
                val first = requireNotNull(
                    auto.observe(
                        owner.attemptId,
                        request.leaseId,
                        "quarantine-observe-1",
                        applyDigest,
                    ),
                )
                val second = requireNotNull(
                    auto.observe(
                        owner.attemptId,
                        request.leaseId,
                        "quarantine-observe-2",
                        applyDigest,
                    ),
                )
                assertEquals(before + 2L, first.environmentRevision)
                assertEquals(ContinuityCoverageV1.NONE.wire, first.continuityCoverageWire)
                assertEquals(before + 2L, second.environmentRevision)
                assertEquals(ContinuityCoverageV1.FULL.wire, second.continuityCoverageWire)
            }
        }

    private suspend fun withJourney(
        label: String,
        block: suspend (
            owner: Owner,
            qwy: ProviderHarness,
            auto: AutoDurableJourneyBridge,
            request: CompleteAndAdvanceRequestV1,
            applyDigest: String,
        ) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val qwy = ProviderHarness.createWithExternalEnvStore()
        qwy.env.authoritativeSemanticMutations = true
        val autoApplicationId = AutoIntegrationBridge.autoApplicationId()
        qwy.resolver.register(AUTO_UID, autoApplicationId, REPO_SIGNER)
        qwy.pair(autoApplicationId, REPO_SIGNER)
        check(qwy.semanticCoordinator.isReadyFor(
            requireNotNull(qwy.env.authoritativeSemanticDigest(qwy.tracker.generation)),
        ))
        val calls = JourneyRpcCounters()
        val routing = RoutingContext(
            app,
            mapOf(BENCH to LocalQwyBinder(qwy, AUTO_UID, calls)),
        )
        try {
            val owner = seedOwner(db, label)
            AutoDurableJourneyBridge.connect(routing, db, owner.planId) { REPO_SIGNER }.use { auto ->
                val anchor = requireNotNull(auto.discover(owner.attemptId))
                db.testAttemptDao().markAplusAdvanceAnchor(
                    owner.attemptId,
                    requireNotNull(anchor.currentScheduleId),
                    requireNotNull(anchor.currentItemId),
                    requireNotNull(anchor.scheduleVersion),
                )
                val intent = APlusOperationIdentity.intent(
                    owner.sessionId,
                    owner.attemptId,
                    owner.planId,
                    anchor.currentScheduleId!!,
                    owner.startedAt,
                    owner.startedAt + 90_000L,
                )
                val applyKey = APlusOperationIdentity.applyIdempotencyKey(owner.attemptId)
                val applyDigest = APlusOperationIdentity.requestDigest(intent)
                val applied = auto.dispatchApply(
                    owner.attemptId,
                    intent,
                    applyKey,
                    applyDigest,
                    qwy.clock.epochMs(),
                )
                assertEquals("APPLIED", applied.outcome)
                val lease = requireNotNull(applied.leaseId)
                db.testAttemptDao().markAplusLease(owner.attemptId, lease)
                db.testAttemptDao().markAplusState(owner.attemptId, "RELEASE_PENDING")
                assertNotNull(
                    auto.release(
                        owner.attemptId,
                        APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
                        lease,
                        APlusOperationIdentity.releaseDigest(lease),
                        qwy.clock.epochMs(),
                    ),
                )
                val request = advanceRequest(qwy, lease, applyKey)
                val beforeAttempt = requireNotNull(db.testAttemptDao().getAttemptById(owner.attemptId))
                val beforeApply = requireNotNull(db.operationReceiptDao().byKey(applyKey))
                val beforeRelease = requireNotNull(
                    db.releaseReceiptDao().byKey(
                        APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
                    ),
                )

                block(owner, qwy, auto, request, applyDigest)

                assertEquals(BENCH, db.planDao().getPlanById(owner.planId)?.providerApplicationId)
                assertEquals(beforeAttempt, db.testAttemptDao().getAttemptById(owner.attemptId))
                assertEquals(beforeApply, db.operationReceiptDao().byKey(applyKey))
                assertEquals(
                    beforeRelease,
                    db.releaseReceiptDao().byKey(
                        APlusOperationIdentity.releaseIdempotencyKey(owner.attemptId),
                    ),
                )
                assertEquals(REPO_SIGNER, beforeAttempt.providerSignerDigest)
                assertTrue(PRODUCTION !in routing.bindAttempts)
                assertEquals(1, calls.apply)
                assertEquals(1, calls.release)
                assertTrue(calls.advance >= 1)
            }
        } finally {
            db.close()
        }
    }

    private fun failPendingFinalizeOnce(qwy: ProviderHarness) {
        var pendingWrites = 0
        qwy.kv.failOnWrite = { namespace, key ->
            namespace == EnvironmentControlHandler.ADVANCE_PENDING_NAMESPACE &&
                key == EnvironmentControlHandler.ADVANCE_PENDING_KEY &&
                ++pendingWrites == 2
        }
    }

    private fun advanceRequest(
        qwy: ProviderHarness,
        leaseId: String,
        key: String,
    ): CompleteAndAdvanceRequestV1 {
        val currentItemId = requireNotNull(qwy.env.currentItemId)
        val bare = CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = key,
            requestDigest = "",
            expectedScheduleId = qwy.env.scheduleId,
            expectedScheduleVersion = qwy.env.scheduleVersion,
            expectedCurrentItemId = currentItemId,
            completionProof = CompletionProofV1(
                scheduleItemId = currentItemId,
                trustedSuccessCount = 1,
                quotaRequired = 1,
                ledgerRef = "auto:ledger:$key:$currentItemId",
                verifiedAtElapsedRealtimeMs = qwy.clock.elapsedRealtimeMs(),
            ),
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION,
        )
        return bare.copy(requestDigest = CanonicalAdvanceDigestV1.compute(bare))
    }

    private suspend fun seedOwner(db: AppDatabase, label: String): Owner {
        ProviderTrustStore(db.providerPairingDao()).approve(BENCH, REPO_SIGNER, 1, 1L)
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "$label.csv",
                importedAt = 1L,
                globalBufferSeconds = 0,
                totalRows = 1,
                totalRequiredSuccesses = 1,
                providerApplicationId = BENCH,
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
        val startedAt = 1_000L
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = startedAt, status = "running", planId = planId),
        )
        val attemptId = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId,
                runSessionId = sessionId,
                attemptOrdinal = 1,
                successOrdinal = null,
                startedAt = startedAt,
                runningObservedAt = startedAt,
                endedAt = null,
                status = "running",
                failureReason = null,
                webBrowsingScore = null,
                videoStreamingScore = null,
                latitude = 50.4501,
                longitude = 30.5234,
                aplusState = "APPLY_PENDING",
                providerApplicationId = BENCH,
                providerSignerDigest = REPO_SIGNER,
            ),
        )
        return Owner(planId, sessionId, attemptId, startedAt)
    }

    private data class Owner(
        val planId: Long,
        val sessionId: Long,
        val attemptId: Long,
        val startedAt: Long,
    )

    private companion object {
        const val AUTO_UID = 10101
        const val BENCH = ContractV1.PROVIDER_APPLICATION_ID_BENCH
        const val PRODUCTION = ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        const val REPO_SIGNER =
            "sha256:7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41"
    }
}
