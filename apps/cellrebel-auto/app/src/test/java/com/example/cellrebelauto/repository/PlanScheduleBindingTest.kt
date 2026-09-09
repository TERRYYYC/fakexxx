package com.example.cellrebelauto.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.WorklistRow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #79: bound import is atomic and provider item identity owns task selection. */
@RunWith(RobolectricTestRunner::class)
class PlanScheduleBindingTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: PlanRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        repo = PlanRepository(db, com.example.cellrebelauto.cutover.CutoverAccessGate.open())
    }

    @After fun tearDown() { db.close() }

    @Test fun `bound import persists one plan schedule and a unique item per task`() = runTest {
        val planId = repo.importPlan(
            "bound.csv",
            5,
            listOf(
                WorklistRow(30.5, 50.4, 9, 2, 1, "schedule-a", "item-2"),
                WorklistRow(31.5, 51.4, 1, 1, 2, "schedule-a", "item-1")
            ),
            100L
        )

        assertEquals("schedule-a", repo.getPlan(planId)!!.boundScheduleId)
        assertEquals(setOf("item-1", "item-2"), repo.getTasks(planId).map { it.scheduleItemId }.toSet())
    }

    @Test fun `repository rejects parser bypass that mixes legacy and bound rows without a partial insert`() = runTest {
        var rejected = false
        try {
            repo.importPlan(
                "mixed.csv",
                5,
                listOf(
                    WorklistRow(30.5, 50.4, 1, 1, 1, "schedule-a", "item-1"),
                    WorklistRow(31.5, 51.4, 2, 1, 2)
                ),
                100L
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }

        assertTrue("partial binding must be rejected", rejected)
        assertNull("validation must happen before the plan insert", db.planDao().getLatestPlan())
    }

    @Test fun `bound selection follows provider current item not local priority or csv order`() = runTest {
        val planId = repo.importPlan(
            "reversed.csv",
            0,
            listOf(
                WorklistRow(30.5, 50.4, 9, 2, 1, "schedule-a", "provider-current"),
                WorklistRow(31.5, 51.4, 1, 1, 2, "schedule-a", "local-first")
            ),
            100L
        )

        val selected = repo.selectNextTrustedTask(
            planId,
            attemptedTaskIds = emptySet(),
            activeScheduleItemId = "provider-current"
        )

        assertEquals("provider-current", selected!!.scheduleItemId)
        assertEquals(9, selected.priority)
        assertNull(
            repo.selectNextTrustedTask(
                planId,
                attemptedTaskIds = emptySet(),
                activeScheduleItemId = "unknown-item"
            )
        )
    }

    /**
     * #135 review RED: the bound lane must NEVER re-select an abandoned task. The
     * Run console's Resume suggestion is engine-state-driven and still offers Resume
     * after a Plan-page abandon, so the engine can be started on an abandoned plan;
     * a bound 'cancelled' task passing this takeIf would be marked 'active' again by
     * markTaskActive — silently resurrecting work the operator explicitly abandoned.
     */
    @Test fun `bound selection never re-drives an abandoned cancelled task`() = runTest {
        val planId = repo.importPlan(
            "abandoned.csv",
            0,
            listOf(WorklistRow(30.5, 50.4, 1, 1, 1, "schedule-a", "provider-current")),
            100L
        )
        db.locationTaskDao().cancelUnfinishedForPlan(planId)

        assertNull(
            "a cancelled bound task must not be re-selected for driving",
            repo.selectNextTrustedTask(
                planId,
                attemptedTaskIds = emptySet(),
                activeScheduleItemId = "provider-current"
            )
        )
    }
}
