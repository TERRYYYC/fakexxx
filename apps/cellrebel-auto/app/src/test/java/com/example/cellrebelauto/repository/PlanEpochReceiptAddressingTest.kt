package com.example.cellrebelauto.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.OperationReceiptRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #179 design-plan tests 2/3/5, Room-backed:
 *  2. a legacy-format receipt + a null-epoch attempt → the recompute ADDRESSES the original
 *     receipt (replay hit, never IDEMPOTENCY_CONFLICT);
 *  3. a cross-epoch re-import (epoch A receipt durable, plan re-imported as epoch B) → the same
 *     ordinal attempt derives a NEW key, the old receipt is untouched;
 *  5. the reservation pristine guard includes the epoch column: reserve→delete stays idempotent
 *     for an epoch-bearing template and refuses an epoch-mismatched row.
 *
 * # 计划纪元寻址：旧收据重放命中、跨纪元重导不扰旧收据、reservation 守卫纳入纪元列
 */
@RunWith(RobolectricTestRunner::class)
class PlanEpochReceiptAddressingTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: PlanRepository

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = PlanRepository(db, com.example.cellrebelauto.cutover.CutoverAccessGate.open())
    }

    @After fun tearDown() = db.close()

    private suspend fun seedPlan(importedAt: Long): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "epoch-$importedAt.csv", importedAt = importedAt,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 1.0, latitude = 1.0, priority = 1, requiredSuccesses = 1))
        )
        return planId to db.locationTaskDao().getTasksForPlan(planId).single().id
    }

    private suspend fun insertAttempt(
        taskId: Long,
        planId: Long,
        id: Long,
        planEpoch: Long?,
        aplusState: String? = null,
        leaseId: String? = null
    ): Long {
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId))
        return db.testAttemptDao().insert(
            TestAttempt(
                id = id, taskId = taskId, runSessionId = sessionId, attemptOrdinal = 1,
                successOrdinal = null, startedAt = 1, runningObservedAt = null, endedAt = null,
                status = "running", failureReason = null, webBrowsingScore = null,
                videoStreamingScore = null, latitude = 1.0, longitude = 1.0,
                aplusState = aplusState, aplusLeaseId = leaseId, aplusPlanEpoch = planEpoch
            )
        )
    }

    private fun receipt(key: String) = OperationReceiptRow(
        idempotencyKey = key, requestDigest = "digest", resultOutcome = "APPLIED",
        createdAt = 100, leaseId = "lease-for-$key", operationId = "op-for-$key"
    )

    @Test
    fun `legacy receipt plus null-epoch attempt replays the ORIGINAL receipt`() = runTest {
        // Design test 2: the pre-#179 receipt lives under the legacy key; the null-epoch attempt
        // recomputes exactly that key, so the provider's find returns the ORIGINAL receipt
        // (same key, digest matches → idempotent replay), never IDEMPOTENCY_CONFLICT.
        val (planId, taskId) = seedPlan(importedAt = 1726200000000L)
        val attemptId = insertAttempt(taskId, planId, id = 5L, planEpoch = null)
        val legacyKey = APlusOperationIdentity.applyIdempotencyKey(attemptId, null)
        assertEquals("auto-aplus-apply-5", legacyKey)
        db.operationReceiptDao().insertIfAbsent(receipt(legacyKey))

        val replayed = db.operationReceiptDao().byKey(
            APlusOperationIdentity.applyIdempotencyKey(attemptId, db.testAttemptDao().getAttemptById(attemptId)!!.aplusPlanEpoch)
        )!!
        assertEquals("op-for-auto-aplus-apply-5", replayed.operationId)
        assertEquals("digest", replayed.requestDigest)
        // And the epoch key of a FRESH attempt at the same id can never re-address it.
        assertNull(db.operationReceiptDao().byKey(APlusOperationIdentity.applyIdempotencyKey(attemptId, 999L)))
    }

    @Test
    fun `re-import with a new epoch derives a new key and never disturbs the epoch-A receipt`() = runTest {
        // Design test 3: g54's actual shape — same attempt ordinal after a plan re-import
        // (and the same ordinal an Auto DB reset would recycle). Epoch B must be a NEW
        // operation from the provider's point of view; epoch A's receipt is untouched.
        val epochA = 1726200000000L
        val epochB = 1765100000000L
        val attemptId = 5L
        val keyA = APlusOperationIdentity.applyIdempotencyKey(attemptId, epochA)
        db.operationReceiptDao().insertIfAbsent(receipt(keyA))
        val releaseKeyA = APlusOperationIdentity.releaseIdempotencyKey(attemptId, epochA)
        val keyB = APlusOperationIdentity.applyIdempotencyKey(attemptId, epochB)

        assertNotEquals(keyA, keyB)
        assertNotEquals(keyA, APlusOperationIdentity.releaseIdempotencyKey(attemptId, epochB))
        // The epoch-A receipt is still there, byte-identical, addressed only by its own key.
        assertEquals("op-for-$keyA", db.operationReceiptDao().byKey(keyA)!!.operationId)
        assertNull("epoch B gets a fresh provider operation", db.operationReceiptDao().byKey(keyB))
        // RELEASE is symmetric: epoch B's release key is fresh too.
        assertNull(db.releaseReceiptDao().byKey(APlusOperationIdentity.releaseIdempotencyKey(attemptId, epochB)))
    }

    @Test
    fun `admission persists the epoch the keys were minted under`() = runTest {
        // The epoch must be durable AT ADMISSION so crash recovery recomputes identical keys.
        val importedAt = 1726200000000L
        val (planId, taskId) = seedPlan(importedAt)
        val template = TestAttempt(
            taskId = taskId, runSessionId = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId)),
            attemptOrdinal = 1, successOrdinal = null, startedAt = 1, runningObservedAt = null,
            endedAt = null, status = "starting", failureReason = null, webBrowsingScore = null,
            videoStreamingScore = null, latitude = 1.0, longitude = 1.0, aplusPlanEpoch = importedAt
        )
        val reservedId = repository.reserveAplusAttemptId(template)
        assertEquals("reservation leaves no attempt row", null, db.testAttemptDao().getAttemptById(reservedId))
        val admittedId = repository.insertAdmittedAplusAttempt(
            template.copy(id = reservedId), activateTask = true,
            scheduleId = "schedule-1", itemId = "item-1", version = 7,
            intentProfileRef = null
        )
        assertEquals(reservedId, admittedId)
        assertEquals(importedAt, db.testAttemptDao().getAttemptById(admittedId)!!.aplusPlanEpoch)
        // Keys minted BEFORE admission (preflight) and AFTER recovery read identically.
        assertEquals(
            APlusOperationIdentity.applyIdempotencyKey(admittedId, importedAt),
            APlusOperationIdentity.applyIdempotencyKey(admittedId, db.testAttemptDao().getAttemptById(admittedId)!!.aplusPlanEpoch)
        )
    }

    @Test
    fun `reservation pristine guard includes the epoch column`() = runTest {
        // Design test 5: the guarded delete must match the reservation template EXACTLY —
        // an epoch-mismatched row is lifecycle data and must survive (guard returns 0).
        val (planId, taskId) = seedPlan(importedAt = 1726200000000L)
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId))
        val template = TestAttempt(
            taskId = taskId, runSessionId = sessionId,
            attemptOrdinal = 1, successOrdinal = null, startedAt = 1, runningObservedAt = null,
            endedAt = null, status = "starting", failureReason = null, webBrowsingScore = null,
            videoStreamingScore = null, latitude = 1.0, longitude = 1.0, aplusPlanEpoch = 1726200000000L
        )
        val id = db.testAttemptDao().insert(template)

        val wrongEpoch = db.testAttemptDao().deletePristineIdReservation(id, taskId, sessionId, 42L)
        assertEquals("epoch mismatch is NOT pristine", 0, wrongEpoch)
        assertEquals("the row survives", id, db.testAttemptDao().getAttemptById(id)!!.id)

        val matchingEpoch = db.testAttemptDao().deletePristineIdReservation(id, taskId, sessionId, 1726200000000L)
        assertEquals("matching template deletes exactly once", 1, matchingEpoch)
        assertEquals(null, db.testAttemptDao().getAttemptById(id))
        // reserve→delete is idempotent: the second guarded delete finds nothing.
        assertEquals(0, db.testAttemptDao().deletePristineIdReservation(id, taskId, sessionId, 1726200000000L))
    }

    @Test
    fun `pristine guard never deletes a lifecycle row even with a matching epoch`() = runTest {
        // The epoch leg widens the guard, it does not weaken it: a row that entered the A+
        // lifecycle (aplusState set) is owner state and survives every guarded delete.
        val (planId, taskId) = seedPlan(importedAt = 1726200000000L)
        val sessionId = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId))
        val owner = db.testAttemptDao().insert(
            TestAttempt(
                taskId = taskId, runSessionId = sessionId,
                attemptOrdinal = 1, successOrdinal = null, startedAt = 1, runningObservedAt = null,
                endedAt = null, status = "starting", failureReason = null, webBrowsingScore = null,
                videoStreamingScore = null, latitude = 1.0, longitude = 1.0,
                aplusState = "APPLY_PENDING", aplusPlanEpoch = 1726200000000L
            )
        )
        assertEquals(
            0,
            db.testAttemptDao().deletePristineIdReservation(owner, taskId, sessionId, 1726200000000L)
        )
        assertEquals(
            "APPLY_PENDING",
            db.testAttemptDao().getAttemptById(owner)!!.aplusState
        )
    }
}
