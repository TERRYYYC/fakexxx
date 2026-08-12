package com.example.cellrebelauto.automation

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * M-MG-02 completion predicate — TRUSTED-ONLY, legacy counter never consulted (Issue #5 R5-F3).
 *
 * Sol's round-4 combined attack (§11.7) rewrote [LocationTaskDao.completeTaskIfQuotaReached] +
 * [LocationTaskDao.normalizeQuotaCompletedTasks] to consult the trusted count via a SQL subquery but
 * KEPT `(completedSuccesses >= requiredSuccesses)` as an **OR-branch alternate-truth**. The round-4
 * M-MG-02 tests did NOT catch this: test 1 covers the SELECTION path, test 2 covers the inverse
 * polarity (trusted-complete despite a ZERO counter) — but neither covers the direction that exposes
 * the OR-branch: a task whose LEGACY counter is full but whose trusted ledger is EMPTY must NOT be
 * completed. The OR-branch wrongly completes such a task; trusted-only SQL correctly leaves it active.
 *
 * These REDs pin the trusted-only predicate from BOTH directions and BOTH quota sizes:
 *  • the **counter-full / trusted-empty** direction is the discriminator — it MUST stay active under a
 *    trusted-only GREEN, but is completed by both the skeleton (pure counter SQL) AND the OR-branch
 *    attack. Asserting it stays `active` is RED under both, GREEN only under trusted-only.
 *  • the **counter-empty / trusted-full** direction is the positive control — it MUST become `completed`
 *    under trusted-only (and under the OR-branch), and stays active under the skeleton (counter-empty).
 *    Asserting it completes is RED under the skeleton, GREEN under trusted-only.
 *  • quota=1 AND quota>1 are both exercised. A single quota size leaves an OR-variant greenable by
 *    special-casing (e.g. `required==1`); covering BOTH sizes kills every OR-branch shape.
 *  • the legacy `completedSuccesses` column is asserted UNCHANGED — completion must be a pure status
 *    projection of the trusted count, never a counter mutation.
 *
 * Why both the production recovery entry ([PlanRepository.normalizeQuotaCompletedTasks]) AND the DAO
 * ([LocationTaskDao.completeTaskIfQuotaReached]) directly: `normalize` is reachable through the repo
 * (the recovery sweep); `completeTaskIfQuotaReached`'s only production caller is inside the
 * `finalizeAttemptSuccess` transaction, which under GREEN also MINTS a trusted entry — and that mint
 * changes the trusted count, confounding a counter=full/trusted=empty fixture (esp. at quota=1, where
 * one mint reaches quota). So `completeTaskIfQuotaReached` is pinned at its own SQL seam, while the
 * engine/finalize call sites remain covered by [MmG02EngineSelectionRedTest].
 *
 * Grounding: docs/features/2026-08-12-issue5-red-rewrite-round3-grounding.md §11.7 (R5-F3).
 *
 * # M-MG-02 完成谓词 = 仅可信投影：计数器满但可信空 ⇒ 必须留 active（quota=1 与 quota>1 双杀 OR-分支）
 */
@RunWith(RobolectricTestRunner::class)
class TrustedOnlyCompletionRedTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---- seeding (exact state via post-insert SQL, independent of insert defaults) ----

    /** Seeds two tasks in one plan — X (csvRow 1) and Y (csvRow 2) — each with exact counter/trusted/status. */
    private suspend fun seedTwoTaskPlan(
        reqX: Int, completedX: Int, trustedX: Int,
        reqY: Int, completedY: Int, trustedY: Int
    ): Pair<Long, Long> {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "f3.csv", importedAt = 0L, globalBufferSeconds = 0,
                totalRows = 2, totalRequiredSuccesses = reqX + reqY
            ),
            listOf(
                LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = reqX),
                LocationTask(planId = 0, csvRow = 2, longitude = 116.4, latitude = 39.9, priority = 2, requiredSuccesses = reqY)
            )
        )
        val tasks = db.locationTaskDao().getTasksForPlan(planId)
        val xId = tasks[0].id
        val yId = tasks[1].id
        setState(xId, completedX, reqX, trustedX)
        setState(yId, completedY, reqY, trustedY)
        return xId to yId
    }

    /** Seeds one single-task plan with exact counter/trusted/status; returns its id. */
    private suspend fun seedSingleTask(required: Int, completed: Int, trusted: Int): Long {
        val planId = db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "f3c.csv", importedAt = 0L, globalBufferSeconds = 0,
                totalRows = 1, totalRequiredSuccesses = required
            ),
            listOf(
                LocationTask(planId = 0, csvRow = 1, longitude = 116.4, latitude = 39.9, priority = 1, requiredSuccesses = required)
            )
        )
        val taskId = db.locationTaskDao().getTasksForPlan(planId).first().id
        setState(taskId, completed, required, trusted)
        return taskId
    }

    private fun setState(taskId: Long, completed: Int, required: Int, trusted: Int) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE location_tasks SET completedSuccesses = $completed, requiredSuccesses = $required, " +
                "status = 'active' WHERE id = $taskId"
        )
        repeat(trusted) { i ->
            db.openHelper.writableDatabase.execSQL(
                "INSERT INTO trusted_quota_entries (attemptId, taskId, evidenceDigest, committedAt) " +
                    "VALUES (${taskId * 1000 + i}, $taskId, 'dig-$taskId-$i', ${1000L + i})"
            )
        }
    }

    // ---- normalize (production recovery entry: repo.normalizeQuotaCompletedTasks) ----

    @Test
    fun `normalize never completes a counter-full trusted-empty task and does complete a trusted-complete one (quota 3)`() = runTest {
        // X: legacy counter full (3/3) but trusted ledger EMPTY ⇒ must stay active.
        // Y: legacy counter empty (0/3) but trusted ledger full (3) ⇒ must become completed.
        val (xId, yId) = seedTwoTaskPlan(
            reqX = 3, completedX = 3, trustedX = 0,
            reqY = 3, completedY = 0, trustedY = 3
        )
        repo.normalizeQuotaCompletedTasks()
        val x = db.locationTaskDao().getTaskById(xId)!!
        val y = db.locationTaskDao().getTaskById(yId)!!
        // RED under skeleton (counter SQL: X 3>=3 → completed) and under the OR-branch attack
        // ((trusted 0>=3) OR (counter 3>=3) → completed). GREEN (trusted-only): 0>=3 false → stays active.
        assertNotEquals(
            "M-MG-02: a counter-full/trusted-EMPTY task must NOT be normalized to completed on the legacy " +
                "counter (quota 3); got status=${x.status}, completedSuccesses=${x.completedSuccesses}",
            "completed",
            x.status
        )
        // Positive control — RED under skeleton (Y counter 0>=3 false → stays active), GREEN under trusted-only.
        assertEquals(
            "M-MG-02: a trusted-complete task (3 trusted, counter 0) MUST be normalized to completed",
            "completed",
            y.status
        )
        // The legacy counter is never mutated by normalization.
        assertEquals("normalize must not mutate X's legacy counter", 3, x.completedSuccesses)
        assertEquals("normalize must not mutate Y's legacy counter", 0, y.completedSuccesses)
    }

    @Test
    fun `normalize never completes a counter-full trusted-empty task (quota 1)`() = runTest {
        // quota=1 kills an OR-variant that special-cases required!=1 (e.g. `(trusted>=req) OR (counter>=req AND req>1)`).
        val (xId, yId) = seedTwoTaskPlan(
            reqX = 1, completedX = 1, trustedX = 0,
            reqY = 1, completedY = 0, trustedY = 1
        )
        repo.normalizeQuotaCompletedTasks()
        val x = db.locationTaskDao().getTaskById(xId)!!
        val y = db.locationTaskDao().getTaskById(yId)!!
        assertNotEquals(
            "M-MG-02: a counter-full/trusted-EMPTY task must NOT be normalized to completed (quota 1); " +
                "got status=${x.status}",
            "completed",
            x.status
        )
        assertEquals(
            "M-MG-02: a trusted-complete task (1 trusted, counter 0) MUST be normalized to completed (quota 1)",
            "completed",
            y.status
        )
    }

    // ---- completeTaskIfQuotaReached (the SQL seam; pinned directly — see class KDoc) ----

    @Test
    fun `completeTaskIfQuotaReached ignores a full legacy counter when the trusted ledger is empty (quota 1)`() = runTest {
        val taskId = seedSingleTask(required = 1, completed = 1, trusted = 0)
        db.locationTaskDao().completeTaskIfQuotaReached(taskId)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        // RED under skeleton (counter 1>=1 → completed) and the OR-branch. GREEN (trusted-only): 0>=1 false → active.
        assertNotEquals(
            "M-MG-02: a counter-full/trusted-EMPTY task must NOT complete on the legacy counter (quota 1); " +
                "got status=${task.status}",
            "completed",
            task.status
        )
        assertEquals("the legacy counter must be unchanged (no counter mutation)", 1, task.completedSuccesses)
    }

    @Test
    fun `completeTaskIfQuotaReached ignores a full legacy counter when the trusted ledger is empty (quota 3)`() = runTest {
        val taskId = seedSingleTask(required = 3, completed = 3, trusted = 0)
        db.locationTaskDao().completeTaskIfQuotaReached(taskId)
        val task = db.locationTaskDao().getTaskById(taskId)!!
        assertNotEquals(
            "M-MG-02: a counter-full/trusted-EMPTY task must NOT complete on the legacy counter (quota 3); " +
                "got status=${task.status}",
            "completed",
            task.status
        )
        assertEquals("the legacy counter must be unchanged (no counter mutation)", 3, task.completedSuccesses)
    }
}
