package com.example.cellrebelauto.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CompletionProofV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #85: a crash replays the exact persisted advance request, including its audit timestamp. */
@RunWith(RobolectricTestRunner::class)
class AdvanceReplayCarrierTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlanRepository
    private var attemptId = 0L

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = PlanRepository(db)
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "advance.csv", importedAt = 1, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 1.0, latitude = 1.0, priority = 1, requiredSuccesses = 1))
        )
        val task = db.locationTaskDao().getTasksForPlan(planId).single()
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId))
        attemptId = db.testAttemptDao().insert(
            TestAttempt(taskId = task.id, runSessionId = sessionId, attemptOrdinal = 1, successOrdinal = null,
                startedAt = 1, runningObservedAt = null, endedAt = null, status = "running", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null, latitude = 1.0, longitude = 1.0,
                aplusLeaseId = "lease-1")
        )
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = attemptId, taskId = task.id, evidenceDigest = "ev", committedAt = 2))
        db.releaseReceiptDao().insertIfAbsent(
            ReleaseReceiptRow(
                idempotencyKey = APlusOperationIdentity.releaseIdempotencyKey(attemptId), leaseId = "lease-1",
                releaseDigest = APlusOperationIdentity.releaseDigest("lease-1"), resultOutcome = "RELEASED", createdAt = 3
            )
        )
    }
    @After fun tearDown() = db.close()

    private fun request(verifiedAt: Long): CompleteAndAdvanceRequestV1 {
        val base = CompleteAndAdvanceRequestV1(
            leaseId = "lease-1", idempotencyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId), requestDigest = "",
            expectedScheduleId = "schedule-1", expectedScheduleVersion = 7, expectedCurrentItemId = "item-1",
            completionProof = CompletionProofV1("item-1", 1, 1, "ledger-$attemptId", verifiedAt),
            callerProtocolVersion = ContractV1.PROTOCOL_VERSION
        )
        return base.copy(requestDigest = CanonicalAdvanceDigestV1.compute(base))
    }

    @Test fun `stored advance request round trips exactly and forbids a timestamp-only rewrite`() = runTest {
        val original = request(verifiedAt = 123_456L)
        repository.persistAdvanceReplayCarrier(attemptId, original, createdAt = 4)

        assertEquals(original, repository.getAdvanceReplayRequest(attemptId))
        assertThrows(IllegalStateException::class.java) {
            runTest { repository.persistAdvanceReplayCarrier(attemptId, request(verifiedAt = 999_999L), createdAt = 5) }
        }
        assertEquals(original, repository.getAdvanceReplayRequest(attemptId))
    }

    @Test fun `identical advance receipt replays retain the first audit timestamp`() = runTest {
        val request = request(verifiedAt = 123_456L)
        repository.persistAdvanceReplayCarrier(attemptId, request, createdAt = 4)
        val unsignedReceipt = AdvanceReceiptV1(
            outcomeWire = 1,
            advancedFromItemId = "item-1",
            advancedToItemId = "item-2",
            scheduleVersionAfter = 8,
            effectiveIntentHash = "effective-intent",
            effectiveEnvironmentRevision = 2,
            receiptDigest = ""
        )
        val receipt = unsignedReceipt.copy(
            receiptDigest = CanonicalAdvanceReceiptDigestV1.compute(
                unsignedReceipt,
                request.requestDigest,
                request.idempotencyKey
            )
        )

        repository.persistAdvanceReceipt(attemptId, request, receipt, recordedAt = 10)
        repository.persistAdvanceReceipt(attemptId, request, receipt, recordedAt = 20)

        assertEquals(receipt, repository.getAdvanceReceipt(attemptId))
        assertEquals(10, db.advanceReceiptDao().byAttempt(attemptId)!!.recordedAt)
    }

}
