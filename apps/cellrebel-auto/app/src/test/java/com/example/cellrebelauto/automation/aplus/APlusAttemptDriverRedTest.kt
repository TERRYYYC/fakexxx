package com.example.cellrebelauto.automation.aplus

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R5-F4 — the §8.1 state machine must be driven through a REAL production entry that persists a durable
 * audit effect, NOT exercised as a standalone data-table oracle (Issue #5 / §11.7 F4).
 *
 * Sol's round-4 combined attack implemented the full §8.1 table inside [AttemptTransitions.next] with NO
 * production caller. Every [AttemptTransitionsRedTest] case and the AREA-5c canonical-path `fold` greened
 * — but the table was never consulted by any persisting code, so the engine could keep driving the legacy
 * counter path while the §8.1 machine sat dead. The fix is [APlusAttemptDriver.driveTransition]: the
 * production call site that (GREEN) consults [AttemptTransitions.next] AND appends a durable
 * [com.example.cellrebelauto.model.audit.AutoAuditEvent] bound to the real attempt.
 *
 * These REDs drive the driver — NEVER [AttemptTransitions.next] directly — so a table-only attack
 * (table complete, no caller) cannot green them:
 *  - SINGLE transition: BEGIN_APPLY must advance CREATED → APPLY_PENDING (table consulted) AND append
 *    exactly one audit row bound to the real attempt id (durable effect). A table-only attack leaves the
 *    state at CREATED; a table-wired-but-no-audit attack leaves the audit empty. Either ⇒ RED.
 *  - FULL canonical happy path: the frozen §8.1 event sequence driven THROUGH the driver must reach CLOSED
 *    AND append one audit row per transition (the durable end-to-end audit trail). Under the skeleton the
 *    walk never leaves CREATED and zero rows are appended.
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.7 (R5-F4).
 *
 * # R5-F4：§8.1 状态机必须经"会持久化审计的生产入口"驱动，而非裸数据表 oracle（杀无调用点 oracle）
 */
@RunWith(RobolectricTestRunner::class)
class APlusAttemptDriverRedTest {

    private lateinit var db: AppDatabase
    private lateinit var driver: APlusAttemptDriver

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        driver = APlusAttemptDriver(db.auditEventDao()) { 1_000_000L }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `a single transition driven through the production driver advances the state and appends a durable audit row bound to the attempt`() = runTest {
        val attemptId = 77L
        // RED under the skeleton: driveTransition returns `current` (CREATED) and appends NO audit row.
        // A table-only attack (full §8.1 table in AttemptTransitions, but driveTransition still a no-op)
        // ALSO fails here — the table has no persisting caller, so the state stays CREATED and the audit
        // stays empty. GREEN must consult AttemptTransitions.next AND append the audit event.
        val next = driver.driveTransition(attemptId, AttemptState.CREATED, AttemptEvent.BEGIN_APPLY)
        assertEquals(
            "BEGIN_APPLY must advance CREATED → APPLY_PENDING via the §8.1 table consulted INSIDE the driver",
            AttemptState.APPLY_PENDING,
            next
        )
        val events = db.auditEventDao().forAttempt(attemptId)
        assertEquals(
            "the transition must append exactly one durable audit event (the durable effect)",
            1,
            events.size
        )
        assertEquals(
            "the audit event must bind to the REAL attempt identity (not null / not a wrong id)",
            77L,
            events[0].attemptId
        )
    }

    @Test
    fun `all conditional canonical paths driven through the production driver reach CLOSED with complete audit`() = runTest {
        // THE no-call-site kill at scale: drive each route-aware §8.1 path THROUGH the production
        // driver. A table-only implementation or a generic RELEASE_RECEIPT call cannot satisfy this.
        APlusRunTemplate.TRUSTED_SYSTEM_MOCK_BATCH_V1.canonicalAttemptPaths
            .forEachIndexed { index, path ->
                val attemptId = 991L + index
                var state = AttemptState.CREATED
                for (event in path.eventSequence) {
                    state = if (event == AttemptEvent.RELEASE_RECEIPT) {
                        driver.driveReleaseReceipt(attemptId, state, path.releaseRoute)
                    } else {
                        driver.driveTransition(attemptId, state, event)
                    }
                }
                assertEquals(
                    "conditional path ${path.releaseRoute}/${path.eventSequence.last()} must reach CLOSED",
                    AttemptState.CLOSED,
                    state
                )
                val events = db.auditEventDao().forAttempt(attemptId)
                assertEquals(
                    "each declared transition must append one durable audit event",
                    path.eventSequence.size,
                    events.size
                )
                assertTrue(
                    "every audit row must bind to the real attempt identity",
                    events.all { it.attemptId == attemptId }
                )
            }
    }
}
