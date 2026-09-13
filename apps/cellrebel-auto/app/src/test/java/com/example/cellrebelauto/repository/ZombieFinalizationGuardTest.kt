package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.selfheal.ZombieAttemptPolicy
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #138 — storage-layer guard matrix for the zombie attempt SAFE finalization. Every ops-iron-rule
 * guard must be enforced by the ATOMIC UPDATE itself, not by caller discipline:
 * finalize only stale + APPLY_PENDING + receipt-free + lease-free + execution-free non-terminal rows;
 * never a lease holder (A-line iron rule), never a receipt holder (reconcile self-heals), never a
 * terminal row, and never twice (idempotency: second call = zero side effects).
 *
 * # 僵尸终结守卫矩阵：全部铁律守卫必须由原子 UPDATE 自身强制；幂等；绝不触碰持 lease/有收据/终态行
 */
@RunWith(RobolectricTestRunner::class)
class ZombieFinalizationGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db, CutoverAccessGate.open())
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- seed helpers ----

    private suspend fun seedPlanAndTask(): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "sites.csv", importedAt = 1000L,
                globalBufferSeconds = 0, totalRows = 1, totalRequiredSuccesses = 1
            ),
            listOf(
                LocationTask(
                    planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9,
                    priority = 1, requiredSuccesses = 1
                )
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        return planId to taskId
    }

    private fun attemptTemplate(
        taskId: Long,
        runSessionId: Long,
        startedAt: Long,
        aplusState: String? = "APPLY_PENDING",
        aplusLeaseId: String? = null,
        currentExecutionId: String? = null,
        status: String = "starting"
    ) = TestAttempt(
        taskId = taskId, runSessionId = runSessionId, attemptOrdinal = 1,
        successOrdinal = null, startedAt = startedAt, runningObservedAt = null,
        endedAt = null, status = status, failureReason = null,
        webBrowsingScore = null, videoStreamingScore = null,
        latitude = 39.9, longitude = 116.4,
        aplusState = aplusState, aplusLeaseId = aplusLeaseId,
        currentExecutionId = currentExecutionId
    )

    /** 10 minutes before the judgment instant `now` — stale past the 5 min floor. */
    private val staleStartedAt = -600_000L
    private val now = 0L
    private val staleBeforeMs = now - ZombieAttemptPolicy.stallThresholdMs(90_000L)

    // ==================== positive: the documented zombie shape ====================

    @Test
    fun `stale receipt-free lease-free APPLY_PENDING zombie is finalized interrupted`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        val zombieId = db.testAttemptDao().insert(
            attemptTemplate(taskId, sessionId, startedAt = staleStartedAt)
        )

        assertTrue(repo.finalizeZombieAttempt(zombieId, staleBeforeMs, now))

        val row = db.testAttemptDao().getAttemptById(zombieId)!!
        // 手术等价语义：interrupted + endedAt + typed reason；aplusState 保留死亡现场
        assertEquals("interrupted", row.status)
        assertEquals(now, row.endedAt)
        assertEquals(ZombieAttemptPolicy.FAILURE_REASON, row.failureReason)
        assertEquals("APPLY_PENDING", row.aplusState)
        // 绝不推配额：无 trusted 铸币、无收据
        assertNull(repo.getTrustedEntry(zombieId))
        assertNull(repo.getCompletionReceipt(zombieId))
        // 审计行：引擎开的枪有据可查
        val audits = db.auditEventDao().forAttempt(zombieId)
            .filter { it.eventType == ZombieAttemptPolicy.AUDIT_EVENT_TYPE }
        assertEquals(1, audits.size)
    }

    // ==================== guard matrix ====================

    @Test
    fun `a lease-holding attempt is NEVER finalized — A-line iron rule`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        val holderId = db.testAttemptDao().insert(
            attemptTemplate(
                taskId, sessionId, startedAt = staleStartedAt,
                aplusState = "APPLY_PENDING", aplusLeaseId = "lease-held"
            )
        )

        assertFalse(repo.finalizeZombieAttempt(holderId, staleBeforeMs, now))
        val row = db.testAttemptDao().getAttemptById(holderId)!!
        assertEquals("starting", row.status)
        assertNull(row.endedAt)
        assertTrue(db.auditEventDao().forAttempt(holderId).isEmpty())
    }

    @Test
    fun `an attempt with a durable apply receipt is NEVER finalized — reconcile replays it`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        val recoverableId = db.testAttemptDao().insert(
            attemptTemplate(taskId, sessionId, startedAt = staleStartedAt)
        )
        val log = RoomDurableRecoveryLog(
            db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao(),
            CutoverAccessGate.open()
        )
        // Crash window (c): the apply DID land and the receipt IS durable — same-key replay
        // recovers the lease locally, so this owner is recoverable, never a zombie.
        // #179 rebase: the seeded attempt carries the default NULL epoch, so the receipt is
        // recorded under the exact legacy literal — the same key finalizeZombieAttempt now
        // recomputes for a NULL-epoch attempt.
        log.recordReceipt(
            APlusOperationIdentity.applyIdempotencyKey(recoverableId, null),
            requestDigest = "digest-x", outcome = "APPLIED", now = staleStartedAt, leaseId = "lease-r"
        )

        assertFalse(repo.finalizeZombieAttempt(recoverableId, staleBeforeMs, now))
        assertEquals("starting", db.testAttemptDao().getAttemptById(recoverableId)!!.status)
    }

    @Test
    fun `a FRESH zombie is preserved — recovery retry chances come first`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = -1_000L, planId = null)
        )
        val freshId = db.testAttemptDao().insert(
            attemptTemplate(taskId, sessionId, startedAt = -1_000L)
        )

        assertFalse(repo.finalizeZombieAttempt(freshId, staleBeforeMs, now))
        assertEquals("starting", db.testAttemptDao().getAttemptById(freshId)!!.status)
    }

    @Test
    fun `an execution-dispatched attempt is NEVER finalized`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        val dispatchedId = db.testAttemptDao().insert(
            attemptTemplate(
                taskId, sessionId, startedAt = staleStartedAt,
                currentExecutionId = "exec-1"
            )
        )

        assertFalse(repo.finalizeZombieAttempt(dispatchedId, staleBeforeMs, now))
        assertEquals("starting", db.testAttemptDao().getAttemptById(dispatchedId)!!.status)
    }

    @Test
    fun `non-APPLY_PENDING phases are NEVER finalized`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        for (phase in listOf("CREATED", "ENV_APPLIED", "RECOVERY_REQUIRED", "RELEASE_PENDING")) {
            val id = db.testAttemptDao().insert(
                attemptTemplate(taskId, sessionId, startedAt = staleStartedAt, aplusState = phase)
            )
            assertFalse("phase $phase must not be finalizable", repo.finalizeZombieAttempt(id, staleBeforeMs, now))
            assertEquals("starting", db.testAttemptDao().getAttemptById(id)!!.status)
        }
    }

    @Test
    fun `terminal rows are NEVER touched`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        for (terminal in listOf("succeeded", "failed", "interrupted")) {
            val base = attemptTemplate(taskId, sessionId, startedAt = staleStartedAt, status = terminal)
            val id = db.testAttemptDao().insert(
                base.copy(endedAt = staleStartedAt + 1_000, successOrdinal = if (terminal == "succeeded") 1 else null)
            )
            assertFalse(repo.finalizeZombieAttempt(id, staleBeforeMs, now))
        }
    }

    // ==================== idempotency ====================

    @Test
    fun `re-finalizing the same zombie is a no-op — no side-effect stacking`() = runTest {
        val (_, taskId) = seedPlanAndTask()
        val sessionId = db.runSessionDao().insert(
            com.example.cellrebelauto.model.RunSession(startedAt = staleStartedAt, planId = null)
        )
        val zombieId = db.testAttemptDao().insert(
            attemptTemplate(taskId, sessionId, startedAt = staleStartedAt)
        )

        assertTrue(repo.finalizeZombieAttempt(zombieId, staleBeforeMs, now))
        val firstEnd = db.testAttemptDao().getAttemptById(zombieId)!!.endedAt
        assertNotNull(firstEnd)

        // Repeat trigger: zero rows matched → no endedAt rewrite, no second audit row.
        assertFalse(repo.finalizeZombieAttempt(zombieId, staleBeforeMs, now + 60_000L))
        val row = db.testAttemptDao().getAttemptById(zombieId)!!
        assertEquals(firstEnd, row.endedAt)
        assertEquals(
            1, db.auditEventDao().forAttempt(zombieId)
                .count { it.eventType == ZombieAttemptPolicy.AUDIT_EVENT_TYPE }
        )
    }
}
