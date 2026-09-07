package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.aplus.APlusOperationIdentity
import com.example.cellrebelauto.automation.aplus.AttemptState
import com.example.cellrebelauto.automation.aplus.ReleaseReceiptRoute
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.RunSession
import com.example.cellrebelauto.model.ledger.TrustedQuotaEntry
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.TestAttempt
import com.example.cellrebelauto.recovery.ProviderReleaseHandoff
import com.example.cellrebelauto.recovery.RecordingExternalApplyExecutor
import com.example.cellrebelauto.recovery.RecoveryCoordinator
import com.example.cellrebelauto.recovery.ReleaseReceiptRow
import com.example.cellrebelauto.recovery.RoomDurableRecoveryLog
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #85 owner transaction, exercising the production provider handoff and real Room rollback. */
@RunWith(RobolectricTestRunner::class)
class AtomicReleaseCommitTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository
    private val executor = RecordingExternalApplyExecutor()
    private lateinit var coordinator: RecoveryCoordinator
    private var attemptId = 0L
    private val lease = "lease-atomic"
    private val key get() = APlusOperationIdentity.releaseIdempotencyKey(attemptId)
    private val digest get() = APlusOperationIdentity.releaseDigest(lease)

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = PlanRepository(db)
        coordinator = RecoveryCoordinator(executor,
            RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()))
    }

    @After fun tearDown() = db.close()

    private suspend fun seed(quota: Int = 1, trusted: Boolean = true, phase: String = "RELEASE_PENDING") {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(sourceFileName = "atomic.csv", importedAt = 1, globalBufferSeconds = 0,
                totalRows = 1, totalRequiredSuccesses = quota),
            listOf(LocationTask(planId = 0, csvRow = 1, longitude = 1.0, latitude = 1.0,
                priority = 1, requiredSuccesses = quota)))
        val task = repo.getTasks(planId).single()
        val session = db.runSessionDao().insert(RunSession(startedAt = 1, planId = planId))
        attemptId = db.testAttemptDao().insert(TestAttempt(
            taskId = task.id, runSessionId = session, attemptOrdinal = 1, successOrdinal = null,
            startedAt = 1, runningObservedAt = null, endedAt = null, status = "running", failureReason = null,
            webBrowsingScore = null, videoStreamingScore = null, latitude = 1.0, longitude = 1.0,
            aplusLeaseId = lease, aplusState = phase, aplusAnchorScheduleId = "schedule-atomic",
            aplusAnchorItemId = "item-atomic", aplusAnchorVersion = 73))
        if (trusted) {
            db.trustedQuotaDao().insert(TrustedQuotaEntry(attemptId = attemptId, taskId = task.id,
                evidenceDigest = "evidence", committedAt = 2))
        } else {
            repo.recordUnverifiedOutcome(attemptId, "NO_COMPLETION_EVIDENCE", "negative-evidence")
        }
    }

    private fun prepare(now: Long = 3): ProviderReleaseHandoff =
        requireNotNull(coordinator.prepareReleaseLease(attemptId, key, lease, digest, now))

    private suspend fun commit(handoff: ProviderReleaseHandoff, clock: Long = 123_456,
        route: ReleaseReceiptRoute = ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED) =
        repo.commitReleaseReceipt(attemptId, handoff, route, clock, recordedAt = 4)

    @Test fun `death after provider release before Auto commit retries same key with one effect`() = runTest {
        seed()
        prepare() // lost with the first process before it could commit
        assertNull(db.releaseReceiptDao().byKey(key))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals("RELEASE_PENDING", repo.getAttempt(attemptId)!!.aplusState)

        commit(prepare(now = 30))

        assertEquals(2, executor.releaseInvocationCount(key))
        assertEquals(1, executor.releaseEffectCount(attemptId))
        assertEquals(1, executor.releaseCallsFor(attemptId).distinct().size)
        assertEquals(AttemptState.ADVANCE_PENDING.name, repo.getAttempt(attemptId)!!.aplusState)
        val request = requireNotNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals(123_456L, request.completionProof.verifiedAtElapsedRealtimeMs)
        assertEquals("schedule-atomic", request.expectedScheduleId)
        assertEquals(73L, request.expectedScheduleVersion)
        assertNotNull(db.releaseReceiptDao().byKey(key))
        assertEquals("RELEASE_PENDING->ADVANCE_PENDING[COMMITTED_QUOTA_REACHED]",
            db.auditEventDao().forAttempt(attemptId).single().payloadDigest)
    }

    @Test fun `same release replay keeps every request field and does not rewind later owner phases`() = runTest {
        seed()
        commit(prepare())
        val original = repo.getAdvanceReplayRequest(attemptId)
        for (state in listOf("ADVANCE_PENDING", "ADVANCE_OBSERVING", "ADVANCE_STATE_READBACK", "CLOSED")) {
            repo.markAplusState(attemptId, state) // a later lifecycle transition, before a stale release caller resumes
            assertEquals(AttemptState.valueOf(state), commit(prepare(now = 50), clock = 999_999))
            assertEquals(state, repo.getAttempt(attemptId)!!.aplusState)
            assertEquals(original, repo.getAdvanceReplayRequest(attemptId))
        }
        assertEquals(1, executor.releaseInvocationCount(key))
        assertEquals(1, db.auditEventDao().forAttempt(attemptId).size)
        assertEquals(3L, db.releaseReceiptDao().byKey(key)!!.createdAt)
    }

    @Test fun `carrier insert failure leaves no release or owner transition`() = runTest {
        seed()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_carrier BEFORE INSERT ON advance_replay_carriers
            BEGIN SELECT RAISE(ABORT, 'injected carrier failure'); END
        """.trimIndent())
        val failure = runCatching { commit(prepare()) }.exceptionOrNull()
        assertNotNull("fault injection must actually execute", failure)
        assertNull(db.releaseReceiptDao().byKey(key))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals("RELEASE_PENDING", repo.getAttempt(attemptId)!!.aplusState)
        assertTrue(db.auditEventDao().forAttempt(attemptId).isEmpty())
    }

    @Test fun `racing same-key release handoffs share one immutable carrier and audit`() = runTest {
        seed()
        val first = prepare()
        val second = prepare(now = 30)
        coroutineScope {
            val a = async { commit(first, clock = 123_456) }
            val b = async { commit(second, clock = 999_999) }
            assertEquals(AttemptState.ADVANCE_PENDING, a.await())
            assertEquals(AttemptState.ADVANCE_PENDING, b.await())
        }
        val original = requireNotNull(repo.getAdvanceReplayRequest(attemptId))
        assertTrue(original.completionProof.verifiedAtElapsedRealtimeMs in setOf(123_456L, 999_999L))
        commit(prepare(now = 50), clock = 777_777)
        assertEquals(original, repo.getAdvanceReplayRequest(attemptId))
        assertEquals(1, db.auditEventDao().forAttempt(attemptId).size)
        assertEquals(1, executor.releaseEffectCount(attemptId))
    }

    @Test fun `audit insert failure rolls back the whole release then same-key retry commits`() = runTest {
        seed()
        db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fail_audit BEFORE INSERT ON auto_audit_events
            BEGIN SELECT RAISE(ABORT, 'injected audit failure'); END
        """.trimIndent())
        assertNotNull(runCatching { commit(prepare()) }.exceptionOrNull())
        assertNull(db.releaseReceiptDao().byKey(key))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals("RELEASE_PENDING", repo.getAttempt(attemptId)!!.aplusState)
        assertTrue(db.auditEventDao().forAttempt(attemptId).isEmpty())

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_audit")
        commit(prepare(now = 30))
        assertEquals(1, executor.releaseEffectCount(attemptId))
        assertEquals(1, db.auditEventDao().forAttempt(attemptId).size)
    }

    @Test fun `under-quota release closes with no advance carrier`() = runTest {
        seed(quota = 2)
        assertEquals(AttemptState.CLOSED, commit(prepare(), route = ReleaseReceiptRoute.COMMITTED_UNDER_QUOTA))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertNotNull(db.releaseReceiptDao().byKey(key))
        assertEquals("RELEASE_PENDING->CLOSED[COMMITTED_UNDER_QUOTA]",
            db.auditEventDao().forAttempt(attemptId).single().payloadDigest)
    }

    @Test fun `negative release preserves negative carrier and closes with no advance carrier`() = runTest {
        seed(trusted = false)
        val negative = repo.getUnverifiedRecord(attemptId)
        assertEquals(AttemptState.CLOSED, commit(prepare(), route = ReleaseReceiptRoute.NOT_COMMITTED))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals(negative, repo.getUnverifiedRecord(attemptId))
        assertNotNull(db.releaseReceiptDao().byKey(key))
    }

    @Test fun `illegal initial phase cannot acquire a release commit`() = runTest {
        seed(phase = "CLOSED")
        assertNotNull(runCatching { commit(prepare()) }.exceptionOrNull())
        assertEquals("CLOSED", repo.getAttempt(attemptId)!!.aplusState)
        assertNull(db.releaseReceiptDao().byKey(key))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertTrue(db.auditEventDao().forAttempt(attemptId).isEmpty())
    }

    @Test fun `legacy release without exact carrier never manufactures a request using new clock`() = runTest {
        seed()
        db.releaseReceiptDao().insertIfAbsent(ReleaseReceiptRow(key, lease, digest, "RELEASED", 2))
        val failure = runCatching { commit(prepare(), clock = 999_999) }.exceptionOrNull()
        assertEquals("ADVANCE_REPLAY_CARRIER_MISSING:$attemptId", failure?.message)
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals("RELEASE_PENDING", repo.getAttempt(attemptId)!!.aplusState)
        assertEquals(0, executor.releaseInvocationCount(key))
    }

    @Test fun `release incomplete yields no handoff and leaves the owner recoverable`() = runTest {
        seed()
        val incomplete = RecoveryCoordinator(RecordingExternalApplyExecutor(outcome = "INCOMPLETE"),
            RoomDurableRecoveryLog(db.operationReceiptDao(), db.recoveryCheckpointRoomDao(), db.releaseReceiptDao()))
        assertNull(incomplete.prepareReleaseLease(attemptId, key, lease, digest, 3))
        assertNull(db.releaseReceiptDao().byKey(key))
        assertNull(repo.getAdvanceReplayRequest(attemptId))
        assertEquals("RELEASE_PENDING", repo.getAttempt(attemptId)!!.aplusState)
    }
}
