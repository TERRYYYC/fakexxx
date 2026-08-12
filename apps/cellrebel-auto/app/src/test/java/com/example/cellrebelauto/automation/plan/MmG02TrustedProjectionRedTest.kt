package com.example.cellrebelauto.automation.plan

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-MG-02 (Issue #5 Task 4, area 6): the trusted-ledger projection — not the legacy v4
 * `completedSuccesses` counter — must drive quota completion AND address selection. The legacy
 * counter has NO A+ evidence chain (no observation, no intent hash, no continuity proof), so a
 * counter-complete task with zero trusted entries is NOT actually complete; routing on the counter
 * would mark it done and skip its address — exactly the bug M-MG-02 forbids
 * ("恢复流程读到 legacy 计数 → 不得当作已完成而跳过地址", spec line 24/15).
 *
 * TRUSTWORTHY RED (defeats Sol's round-3 isolated-helper counterexample): every assertion goes
 * through the REAL production path backed by a REAL Room database —
 *   • [com.example.cellrebelauto.db.LocationTaskDao.normalizeQuotaCompletedTasks] — the recovery
 *     sweep SQL that M-MG-02 literally names,
 *   • [com.example.cellrebelauto.db.LocationTaskDao.completeTaskIfQuotaReached] — the success-path
 *     completion SQL,
 *   • [PlanRepository.selectNextTrustedTask] — DB-aware selection.
 * The trusted counts are seeded as `trusted_quota_entries` rows and read by production via the real
 * `TrustedQuotaDao.trustedCountForTask` projection. A bad impl CANNOT green these by painting an
 * isolated `PlanScheduler` helper (the trusted count is not a test-supplied map); it must rewire the
 * real completion SQL and the real selection to consult the trusted ledger.
 *
 * Both polarities are covered for each seam: counter-complete/trusted-incomplete must NOT complete
 * (and MUST be re-selected); counter-incomplete/trusted-complete MUST complete (and be skipped).
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md (Finding 3, Option a).
 *
 * # M-MG-02（RED）：真实 Room 投影驱动完成/选址；双极性；无法用孤立 helper 绿化
 */
@RunWith(RobolectricTestRunner::class)
class MmG02TrustedProjectionRedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = PlanRepository(db)
        // FK enforcement is OFF by default in Room, but a dummy plan row keeps the fixture
        // self-consistent regardless.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO location_plans (id, sourceFileName, importedAt, globalBufferSeconds, " +
                "totalRows, totalRequiredSuccesses) VALUES (1, 'mmg02', 0, 0, 0, 0)"
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Seeds a location_task (planId = 1) with explicit counter + status. */
    private fun seedTask(
        id: Long,
        csvRow: Int,
        required: Int,
        completed: Int,
        status: String,
        priority: Int = 1
    ) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO location_tasks (id, planId, csvRow, longitude, latitude, priority, " +
                "requiredSuccesses, completedSuccesses, status) " +
                "VALUES ($id, 1, $csvRow, -74.0, 40.0, $priority, $required, $completed, '$status')"
        )
    }

    /** Seeds `count` trusted_quota_entries for a task with distinct attemptIds (UNIQUE constraint). */
    private fun seedTrusted(taskId: Long, count: Int) {
        val sqlite = db.openHelper.writableDatabase
        repeat(count) { i ->
            sqlite.execSQL(
                "INSERT INTO trusted_quota_entries (attemptId, taskId, evidenceDigest, committedAt) " +
                    "VALUES (${taskId * 1000 + i}, $taskId, 'dig-$taskId-$i', ${1000L + i})"
            )
        }
    }

    // ---- Recovery normalization (the literal M-MG-02 seam) ----

    @Test
    fun `recovery normalization does NOT complete a counter-complete trusted-incomplete task`() = runTest {
        // Legacy counter is full (3/3) but the trusted ledger is empty. M-MG-02: the recovery sweep
        // must NOT treat this as completed (doing so would skip the address).
        seedTask(id = 1, csvRow = 1, required = 3, completed = 3, status = "active")
        seedTrusted(taskId = 1, count = 0)
        db.locationTaskDao().normalizeQuotaCompletedTasks()
        val task = db.locationTaskDao().getTaskById(1)
        assertEquals(
            "counter-complete with zero trusted entries must NOT be normalized to completed (M-MG-02)",
            "active",
            task?.status
        )
    }

    @Test
    fun `recovery normalization DOES complete a trusted-complete task despite a zero counter`() = runTest {
        // Inverse polarity: the trusted ledger reached the quota (3) even though the legacy counter
        // is 0. M-MG-02: the trusted projection must rule — this task IS complete.
        seedTask(id = 2, csvRow = 2, required = 3, completed = 0, status = "active")
        seedTrusted(taskId = 2, count = 3)
        db.locationTaskDao().normalizeQuotaCompletedTasks()
        val task = db.locationTaskDao().getTaskById(2)
        assertEquals(
            "trusted-complete task must be normalized to completed even with a zero counter (M-MG-02)",
            "completed",
            task?.status
        )
    }

    // ---- Success-path completion (finalizeAttemptSuccess → completeTaskIfQuotaReached) ----

    @Test
    fun `success-path completion does NOT complete a counter-complete trusted-incomplete task`() = runTest {
        seedTask(id = 1, csvRow = 1, required = 3, completed = 3, status = "active")
        seedTrusted(taskId = 1, count = 0)
        val updatedRows = db.locationTaskDao().completeTaskIfQuotaReached(1)
        assertEquals(
            "completeTaskIfQuotaReached must not complete a trusted-incomplete task (M-MG-02)",
            0,
            updatedRows
        )
        assertEquals("active", db.locationTaskDao().getTaskById(1)?.status)
    }

    @Test
    fun `success-path completion DOES complete a trusted-complete task despite a zero counter`() = runTest {
        seedTask(id = 2, csvRow = 2, required = 3, completed = 0, status = "active")
        seedTrusted(taskId = 2, count = 3)
        val updatedRows = db.locationTaskDao().completeTaskIfQuotaReached(2)
        assertEquals(
            "completeTaskIfQuotaReached must complete a trusted-complete task (M-MG-02)",
            1,
            updatedRows
        )
        assertEquals("completed", db.locationTaskDao().getTaskById(2)?.status)
    }

    // ---- Trusted address selection (PlanRepository.selectNextTrustedTask) ----

    @Test
    fun `trusted selection re-runs a counter-complete trusted-incomplete task`() = runTest {
        // Both tasks are counter-complete (3/3) and active. A is trusted-incomplete (0), B is
        // trusted-complete (3). Legacy selectNext skips both (counter-complete) → null. M-MG-02:
        // selection must re-run A (trusted-incomplete) and skip B (trusted-complete).
        seedTask(id = 1, csvRow = 1, required = 3, completed = 3, status = "active")
        seedTask(id = 2, csvRow = 2, required = 3, completed = 3, status = "active")
        seedTrusted(taskId = 1, count = 0)
        seedTrusted(taskId = 2, count = 3)
        val selected = repo.selectNextTrustedTask(planId = 1)
        assertEquals(
            "must select trusted-incomplete task A, not skip it (M-MG-02)",
            1L,
            selected?.id
        )
    }

    @Test
    fun `trusted selection skips a trusted-complete task and picks the trusted-incomplete one`() = runTest {
        // Inverse polarity: A (csvRow 1) is trusted-complete, B (csvRow 2) is trusted-incomplete.
        // Selection must skip A and pick B — even though A sorts first by execution order.
        seedTask(id = 1, csvRow = 1, required = 3, completed = 3, status = "active")
        seedTask(id = 2, csvRow = 2, required = 3, completed = 3, status = "active")
        seedTrusted(taskId = 1, count = 3)
        seedTrusted(taskId = 2, count = 0)
        val selected = repo.selectNextTrustedTask(planId = 1)
        assertEquals(
            "must skip trusted-complete A and select trusted-incomplete B (M-MG-02)",
            2L,
            selected?.id
        )
    }

    @Test
    fun `trusted selection returns null when every task is trusted-complete`() = runTest {
        // Guard: when every task is trusted-complete there is nothing to select. (Passes under the
        // skeleton too — it is a valid negative, not RED signal, like the F1/F2 negatives.)
        seedTask(id = 1, csvRow = 1, required = 3, completed = 3, status = "active")
        seedTask(id = 2, csvRow = 2, required = 3, completed = 3, status = "active")
        seedTrusted(taskId = 1, count = 3)
        seedTrusted(taskId = 2, count = 3)
        assertNull(
            "no selectable task when every task is trusted-complete",
            repo.selectNextTrustedTask(planId = 1)
        )
    }
}
