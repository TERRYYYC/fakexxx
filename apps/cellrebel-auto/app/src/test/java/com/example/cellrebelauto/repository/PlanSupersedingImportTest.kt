package com.example.cellrebelauto.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.model.plan.WorklistRow
import com.example.cellrebelauto.model.audit.AutoAuditEvent
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.recovery.OperationReceiptRow
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

/** #97: a confirmed replacement archives history; it never resets or deletes the old plan. */
@RunWith(RobolectricTestRunner::class)
class PlanSupersedingImportTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlanRepository
    private var oldPlanId = 0L

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = PlanRepository(db, com.example.cellrebelauto.cutover.CutoverAccessGate.open())
        oldPlanId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "old.csv", importedAt = 100L, globalBufferSeconds = 5,
                totalRows = 1, totalRequiredSuccesses = 2
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 30.5, latitude = 50.4,
                    priority = 1, requiredSuccesses = 2
                )
            )
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `confirmed replacement archives old plan and makes new plan current without deleting history`() = runTest {
        val oldTaskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val stoppedSession = db.runSessionDao().insert(
            RunSession(startedAt = 200L, endedAt = 300L, status = "stopped", planId = oldPlanId)
        )

        val verification = repository.verifyAndStopForSupersession(
            requestId = "replace-stopped",
            expectedPlanId = oldPlanId,
            expectedSessionId = stoppedSession,
            stoppedAt = 300L
        ) as PlanRepository.SupersessionStopVerification.Verified

        val result = repository.confirmSupersedingImport(
            expectedOldPlanId = oldPlanId,
            sourceFileName = "new.csv",
            globalBufferSeconds = 5,
            rows = listOf(WorklistRow(31.5, 51.4, 1, 1, 1)),
            importedAt = 400L,
            supersededAt = 400L,
            stopProof = verification.proof
        )

        val imported = result as PlanRepository.SupersedingImportResult.Imported
        val oldPlan = db.planDao().getPlanById(oldPlanId)!!
        assertEquals("old plan is retained as an auditable record", 400L, oldPlan.supersededAt)
        assertEquals(imported.planId, oldPlan.supersededByPlanId)
        assertEquals("old task survives archival", oldTaskId, db.locationTaskDao().getTasksForPlan(oldPlanId).single().id)
        assertEquals("old session survives archival", stoppedSession, db.runSessionDao().getById(stoppedSession)!!.id)
        assertEquals("new.csv", db.planDao().getLatestPlan()!!.sourceFileName)
        assertNull("newly imported plan is active", db.planDao().getPlanById(imported.planId)!!.supersededAt)
    }

    @Test
    fun `active old session refuses replacement and leaves old plan current`() = runTest {
        val activeSession = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )

        val result = repository.confirmSupersedingImport(
            expectedOldPlanId = oldPlanId,
            sourceFileName = "new.csv",
            globalBufferSeconds = 5,
            rows = listOf(WorklistRow(31.5, 51.4, 1, 1, 1)),
            importedAt = 400L,
            supersededAt = 400L
        )

        val rejected = result as PlanRepository.SupersedingImportResult.ActiveSession
        assertEquals(activeSession, rejected.sessionId)
        assertNull(db.planDao().getPlanById(oldPlanId)!!.supersededAt)
        assertEquals("old.csv", db.planDao().getLatestPlan()!!.sourceFileName)
        assertTrue(db.locationTaskDao().getTasksForPlan(oldPlanId).isNotEmpty())
        assertNotNull(db.runSessionDao().getById(activeSession))
    }

    @Test
    fun `explicit stop verification terminalizes an idle active session and returns bound proof`() = runTest {
        val activeSession = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )

        val result = repository.verifyAndStopForSupersession(
            requestId = "replace-1",
            expectedPlanId = oldPlanId,
            expectedSessionId = activeSession,
            stoppedAt = 300L
        )

        val verified = result as PlanRepository.SupersessionStopVerification.Verified
        assertEquals("replace-1", verified.proof.requestId)
        assertEquals(oldPlanId, verified.proof.planId)
        assertEquals(activeSession, verified.proof.sessionId)
        assertEquals(200L, verified.proof.sessionStartedAt)
        assertTrue(verified.proof.evidenceDigest.matches(Regex("[0-9a-f]{64}")))
        val stopped = db.runSessionDao().getById(activeSession)!!
        assertEquals("stopped", stopped.status)
        assertEquals(300L, stopped.endedAt)
    }

    @Test
    fun `proof digest is bound to its request id even for the same stopped snapshot`() = runTest {
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val first = (repository.verifyAndStopForSupersession(
            "replace-a", oldPlanId, sessionId, 300L
        ) as PlanRepository.SupersessionStopVerification.Verified).proof
        val second = (repository.verifyAndStopForSupersession(
            "replace-b", oldPlanId, sessionId, 999L
        ) as PlanRepository.SupersessionStopVerification.Verified).proof

        assertTrue(first.evidenceDigest != second.evidenceDigest)
        assertEquals("replace-a", first.requestId)
        assertEquals("replace-b", second.requestId)
    }

    @Test
    fun `stop verification interrupts a created owner only when durable shape proves no external effect`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val attemptId = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CREATED")
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-created", oldPlanId, sessionId, 300L
        )

        assertTrue(result is PlanRepository.SupersessionStopVerification.Verified)
        assertEquals("interrupted", db.testAttemptDao().getAttemptById(attemptId)!!.status)
        assertEquals("stopped", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `created no-effect row with an incomplete terminal projection blocks proof`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CREATED", status = "failed", endedAt = null)
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-incomplete-created", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.contains("PROJECTION_INCOMPLETE"))
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `lease without exact release receipt blocks even when attempt projection ended`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CLOSED", status = "failed", endedAt = 250L, leaseId = "lease-unsafe")
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-unsafe", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.contains("RELEASE"))
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `closed attempt without lease still blocks when a current execution owner remains`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        db.testAttemptDao().insert(
            attempt(
                taskId,
                sessionId,
                state = "CLOSED",
                status = "failed",
                endedAt = 250L
            ).copy(currentExecutionId = "execution-still-owned")
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-current-execution", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.contains("EXTERNAL_EFFECT_UNKNOWN"))
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `closed attempt without lease blocks when its audit proves an A plus transition occurred`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val attemptId = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CLOSED", status = "failed", endedAt = 250L)
        )
        db.auditEventDao().insert(
            AutoAuditEvent(
                seq = 1L,
                attemptId = attemptId,
                correlationRef = null,
                eventType = "BEGIN_APPLY",
                payloadDigest = "CREATED->APPLY_PENDING",
                recordedAt = 220L
            )
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-audited-effect", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.contains("EXTERNAL_EFFECT_UNKNOWN"))
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `closed unverified outcome with exact release remains history and permits stop proof`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val leaseId = "lease-negative"
        val attemptId = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CLOSED", status = "failed", endedAt = 250L, leaseId = leaseId)
        )
        repository.recordUnverifiedOutcome(attemptId, "UNTRUSTED", "negative-digest")
        repository.persistReleaseReceipt(
            APlusOperationIdentity.releaseIdempotencyKey(attemptId),
            leaseId,
            APlusOperationIdentity.releaseDigest(leaseId),
            "RELEASED",
            240L
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-negative", oldPlanId, sessionId, 300L
        )

        assertTrue(result is PlanRepository.SupersessionStopVerification.Verified)
        assertNotNull(db.unverifiedAttemptRecordDao().getByAttempt(attemptId))
    }

    @Test
    fun `unverified outcome with any advance carrier fails closed`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val leaseId = "lease-unverified-carrier"
        val attemptId = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "RELEASE_PENDING", status = "failed",
                endedAt = 250L, leaseId = leaseId)
        )
        repository.markAplusAdvanceAnchor(attemptId, "schedule-x", "item-x", 3L)
        repository.recordUnverifiedOutcome(attemptId, "UNTRUSTED", "negative-digest")
        repository.commitReleaseReceipt(
            attemptId,
            com.example.cellrebelauto.recovery.ProviderReleaseHandoff(
                APlusOperationIdentity.releaseIdempotencyKey(attemptId), leaseId,
                APlusOperationIdentity.releaseDigest(leaseId), "RELEASED", 240L,
                alreadyDurable = false
            ),
            com.example.cellrebelauto.automation.aplus.ReleaseReceiptRoute.NOT_COMMITTED,
            verifiedAtElapsedRealtimeMs = 235L,
            recordedAt = 240L
        )
        val unsigned = io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1(
            leaseId = leaseId,
            idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
            requestDigest = "",
            expectedScheduleId = "schedule-x",
            expectedScheduleVersion = 3L,
            expectedCurrentItemId = "item-x",
            completionProof = io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1(
                "item-x", 0, 2, "ledger-$attemptId", 235L
            ),
            callerProtocolVersion = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROTOCOL_VERSION
        )
        repository.persistAdvanceReplayCarrier(
            attemptId,
            unsigned.copy(requestDigest =
                io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1.compute(unsigned)),
            245L
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-unverified-carrier", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.contains("UNVERIFIED_ADVANCE_CONFLICT"))
    }

    @Test
    fun `closed trusted outcome below quota needs no advance and permits stop proof`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val attemptId = seedReleasedClosedAttempt(taskId, sessionId, "lease-trusted-under-quota")
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(attemptId = attemptId, taskId = taskId, evidenceDigest = "trusted-1", committedAt = 245L)
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-trusted-under-quota", oldPlanId, sessionId, 300L
        )

        assertTrue(result is PlanRepository.SupersessionStopVerification.Verified)
    }

    @Test
    fun `closed trusted outcome at quota blocks until durable advance is verified`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        db.openHelper.writableDatabase.execSQL(
            "UPDATE location_tasks SET requiredSuccesses = 1 WHERE id = ?",
            arrayOf(taskId)
        )
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val attemptId = seedReleasedClosedAttempt(taskId, sessionId, "lease-trusted-at-quota")
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(attemptId = attemptId, taskId = taskId, evidenceDigest = "trusted-1", committedAt = 245L)
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-trusted-at-quota", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.contains("ADVANCE"))
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `later quota crossing does not retroactively require advance from an earlier trusted attempt`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val firstAttempt = seedReleasedClosedAttempt(taskId, sessionId, "lease-trusted-first")
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(
                attemptId = firstAttempt,
                taskId = taskId,
                evidenceDigest = "trusted-1",
                committedAt = 245L
            )
        )
        val crossingAttempt = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CLOSED", status = "succeeded", endedAt = 260L,
                leaseId = "lease-trusted-crossing").copy(attemptOrdinal = 2)
        )
        repository.persistReleaseReceipt(
            APlusOperationIdentity.releaseIdempotencyKey(crossingAttempt),
            "lease-trusted-crossing",
            APlusOperationIdentity.releaseDigest("lease-trusted-crossing"),
            "RELEASED",
            255L
        )
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(attemptId = crossingAttempt, taskId = taskId,
                evidenceDigest = "trusted-2", committedAt = 265L)
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-crossing", oldPlanId, sessionId, 300L
        )

        val blocked = result as PlanRepository.SupersessionStopVerification.Blocked
        assertTrue(blocked.reason.startsWith("ATTEMPT_${crossingAttempt}_ADVANCE"))
    }

    @Test
    fun `closed quota crossing with exact release carrier and verified advance permits stop proof`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        db.openHelper.writableDatabase.execSQL(
            "UPDATE location_tasks SET requiredSuccesses = 1 WHERE id = ?",
            arrayOf(taskId)
        )
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val leaseId = "lease-advanced"
        val attemptId = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "RELEASE_PENDING", leaseId = leaseId)
        )
        repository.markAplusAdvanceAnchor(attemptId, "schedule-1", "item-1", 7L)
        db.trustedQuotaDao().insert(
            TrustedQuotaEntry(attemptId = attemptId, taskId = taskId,
                evidenceDigest = "trusted-advance", committedAt = 230L)
        )
        repository.commitReleaseReceipt(
            attemptId,
            com.example.cellrebelauto.recovery.ProviderReleaseHandoff(
                APlusOperationIdentity.releaseIdempotencyKey(attemptId),
                leaseId,
                APlusOperationIdentity.releaseDigest(leaseId),
                "RELEASED",
                240L,
                alreadyDurable = false
            ),
            com.example.cellrebelauto.automation.aplus.ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED,
            verifiedAtElapsedRealtimeMs = 235L,
            recordedAt = 240L
        )
        val request = repository.getAdvanceReplayRequest(attemptId)!!
        val unsignedReceipt = io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1(
            outcomeWire = 1,
            advancedFromItemId = "item-1",
            advancedToItemId = "item-2",
            scheduleVersionAfter = 8L,
            effectiveIntentHash = "effective-2",
            effectiveEnvironmentRevision = 8L,
            receiptDigest = ""
        )
        val receipt = unsignedReceipt.copy(
            receiptDigest = io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1.compute(
                unsignedReceipt,
                request.requestDigest,
                request.idempotencyKey
            )
        )
        repository.persistAdvanceReceipt(attemptId, request, receipt, 250L)
        repository.markAplusState(attemptId, "CLOSED")
        repository.finalizeAplusSuccess(attemptId, taskId, 260L, null, null)

        val result = repository.verifyAndStopForSupersession(
            "replace-advanced", oldPlanId, sessionId, 300L
        )

        assertTrue(result is PlanRepository.SupersessionStopVerification.Verified)
    }

    @Test
    fun `apply receipt makes a created owner external-unknown and blocks no-effect shortcut`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val attemptId = db.testAttemptDao().insert(attempt(taskId, sessionId, state = "CREATED"))
        db.operationReceiptDao().insertIfAbsent(
            OperationReceiptRow(
                idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId),
                requestDigest = "unknown-apply",
                resultOutcome = "APPLIED",
                createdAt = 210L,
                leaseId = "lease-hidden"
            )
        )

        val result = repository.verifyAndStopForSupersession(
            "replace-unknown", oldPlanId, sessionId, 300L
        )

        assertTrue(result is PlanRepository.SupersessionStopVerification.Blocked)
        assertEquals("starting", db.testAttemptDao().getAttemptById(attemptId)!!.status)
    }

    @Test
    fun `persisted non-created owner requests stop-only convergence without terminalizing session`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        db.testAttemptDao().insert(attempt(taskId, sessionId, state = "APPLY_PENDING"))

        val result = repository.verifyAndStopForSupersession(
            "replace-needs-convergence", oldPlanId, sessionId, 300L
        )

        assertTrue(result is PlanRepository.SupersessionStopVerification.NeedsConvergence)
        assertEquals("paused", db.runSessionDao().getById(sessionId)!!.status)
    }

    @Test
    fun `proof becomes stale when durable owner evidence changes before atomic import`() = runTest {
        val taskId = db.locationTaskDao().getTasksForPlan(oldPlanId).single().id
        val sessionId = db.runSessionDao().insert(
            RunSession(startedAt = 200L, status = "paused", planId = oldPlanId)
        )
        val proof = (repository.verifyAndStopForSupersession(
            "replace-race", oldPlanId, sessionId, 300L
        ) as PlanRepository.SupersessionStopVerification.Verified).proof
        db.testAttemptDao().insert(attempt(taskId, sessionId, state = "CREATED"))

        val result = repository.confirmSupersedingImport(
            expectedOldPlanId = oldPlanId,
            sourceFileName = "new.csv",
            globalBufferSeconds = 5,
            rows = listOf(WorklistRow(31.5, 51.4, 1, 1, 1)),
            importedAt = 400L,
            supersededAt = 400L,
            stopProof = proof
        )

        assertTrue(result is PlanRepository.SupersedingImportResult.StaleStopProof)
        assertNull(db.planDao().getPlanById(oldPlanId)!!.supersededAt)
        assertEquals("old.csv", db.planDao().getLatestPlan()!!.sourceFileName)
    }

    private fun attempt(
        taskId: Long,
        sessionId: Long,
        state: String,
        status: String = "starting",
        endedAt: Long? = null,
        leaseId: String? = null
    ) = TestAttempt(
        taskId = taskId,
        runSessionId = sessionId,
        attemptOrdinal = 1,
        successOrdinal = null,
        startedAt = 210L,
        runningObservedAt = null,
        endedAt = endedAt,
        status = status,
        failureReason = null,
        webBrowsingScore = null,
        videoStreamingScore = null,
        latitude = 50.4,
        longitude = 30.5,
        aplusState = state,
        aplusLeaseId = leaseId
    )

    private suspend fun seedReleasedClosedAttempt(
        taskId: Long,
        sessionId: Long,
        leaseId: String
    ): Long {
        val attemptId = db.testAttemptDao().insert(
            attempt(taskId, sessionId, state = "CLOSED", status = "succeeded", endedAt = 250L, leaseId = leaseId)
        )
        repository.persistReleaseReceipt(
            APlusOperationIdentity.releaseIdempotencyKey(attemptId),
            leaseId,
            APlusOperationIdentity.releaseDigest(leaseId),
            "RELEASED",
            240L
        )
        return attemptId
    }
}
