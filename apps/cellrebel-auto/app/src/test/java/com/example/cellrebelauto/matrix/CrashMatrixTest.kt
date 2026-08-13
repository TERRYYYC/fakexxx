package com.example.cellrebelauto.matrix

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.ledger.UnverifiedAttemptRecord
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.FakeDurableRecoveryLog
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Frozen §10.1 owner-red crash matrix entry (Issue #5, `matrix/CrashMatrixTest.kt`). Each test id maps to
 * a §10 M-CR-xx row.
 *
 * BANKED (testable pre-freeze, carrier/ledger authority):
 *  - M-CR-07 — crash after the ledger commit, before the attempt-state update: recovery projects the
 *    terminal truth from the append-only trusted ledger, NOT the phase string.
 *  - M-CR-08 — crash after the provider release EFFECT, before Auto saves the receipt: recovery re-invokes
 *    the release (idempotent), effect stays 1, receipt null→present.
 *
 * GREEN-BOUND (not yet testable — the re-observe/classify/post-observe/decide body is GREEN):
 *  - M-CR-03 (pre-observe → CellRebel click), M-CR-04 (click → running evidence), M-CR-05 (complete →
 *    post-observe), M-CR-06 (trust pass → ledger transaction).
 */
@RunWith(RobolectricTestRunner::class)
class CrashMatrixTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun applyKey(attemptId: Long) = APlusOperationIdentity.applyIdempotencyKey(attemptId)
    private fun releaseKey(attemptId: Long) = APlusOperationIdentity.releaseIdempotencyKey(attemptId)

    private suspend fun seedAttempt(planId: Long, taskId: Long, attemptId: Long, aplusState: String?, aplusLeaseId: String? = null) {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 500L, planId = planId, status = "running"))
        db.testAttemptDao().insert(
            TestAttempt(
                id = attemptId, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 600L, runningObservedAt = null, endedAt = null,
                status = "starting", failureReason = null,
                webBrowsingScore = null, videoStreamingScore = null,
                latitude = 39.9, longitude = 116.4,
                aplusState = aplusState, aplusLeaseId = aplusLeaseId
            )
        )
    }

    @Test
    fun `M-CR-07 recovery projects the committed trusted entry to succeeded`() = runTest {
        val planId = db.planDao().insertPlan(
            LocationPlan(sourceFileName = "m.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1)
        )
        db.planDao().insertTasks(listOf(LocationTask(id = 42L, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1)))
        val taskId = 42L
        seedAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = 77L, taskId = taskId, evidenceDigest = "d", committedAt = 1000L))

        // M-CR-07 crash window: the ledger is committed, the phase is still DECIDING.
        val repo = PlanRepository(db)
        val recovered = repo.getTrustedEntry(77L)
        assertNotNull("the committed trusted entry is the durable authority", recovered)
        assertEquals("the carrier binds the attempt", 77L, recovered!!.attemptId)
        assertEquals("the carrier binds the task", taskId, recovered.taskId)
    }

    @Test
    fun `M-CR-08 recovery re-invokes the release after the provider released but before the receipt`() = runTest {
        val executor = RecordingExternalApplyExecutor()
        val log = FakeDurableRecoveryLog()
        executor.release(attemptId = 1L, idempotencyKey = releaseKey(1L), leaseId = "lease-1", releaseDigest = "rd-1", now = 1000L)
        assertNull("M-CR-08: provider released but Auto has no durable receipt", log.releaseReceiptFor("lease-1"))

        val rc = RecoveryCoordinator(executor, log)
        val receipt = rc.releaseLease(attemptId = 1L, idempotencyKey = releaseKey(1L), leaseId = "lease-1", releaseDigest = "rd-1", now = 2000L)

        assertNotNull("the re-invoked release must record a durable receipt", receipt)
        assertEquals("release re-invoked (1 → 2)", 2, executor.releaseInvocationCount(releaseKey(1L)))
        assertEquals("release effect stays at one (at-most-once)", 1, executor.releaseEffectCount(1L))
    }

    @Test
    fun `unverified carrier is the durable authority for a rejected completion`() = runTest {
        val planId = db.planDao().insertPlan(
            LocationPlan(sourceFileName = "m.csv", importedAt = 1000L, globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1)
        )
        db.planDao().insertTasks(listOf(LocationTask(id = 42L, planId = planId, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = 1)))
        val taskId = 42L
        seedAttempt(planId, taskId, attemptId = 77L, aplusState = "DECIDING", aplusLeaseId = "lease-77")
        db.unverifiedAttemptRecordDao().insert(UnverifiedAttemptRecord(attemptId = 77L, reason = "UNTRUSTED", evidenceDigest = "d"))

        val repo = PlanRepository(db)
        val unverified = repo.getUnverifiedRecord(77L)
        assertNotNull("the unverified record is the durable authority", unverified)
        assertEquals("UNTRUSTED", unverified!!.reason)
        assertEquals("d", unverified.evidenceDigest)
    }
}
