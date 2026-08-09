package com.example.cellrebelauto.automation.plan

import com.example.cellrebelauto.model.plan.LocationTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic plan scheduler tests: priority ASC / csvRow ASC order (INV-1),
 * quota gate before advancing (INV-2), plan completion projection.
 * # 确定性计划调度器测试：优先级/行号排序、配额门禁、计划完成投影
 */
class PlanSchedulerTest {

    private fun task(
        id: Long, csvRow: Int, priority: Int,
        required: Int = 2, completed: Int = 0, status: String = "pending"
    ) = LocationTask(
        id = id, planId = 1, csvRow = csvRow,
        longitude = 0.0, latitude = 0.0,
        priority = priority, requiredSuccesses = required,
        completedSuccesses = completed, status = status
    )

    @Test
    fun `orders by priority then csv row`() {
        val tasks = listOf(
            task(id = 1, csvRow = 1, priority = 5),
            task(id = 2, csvRow = 2, priority = 1),
            task(id = 3, csvRow = 3, priority = 3)
        )
        assertEquals(listOf(2L, 3L, 1L), PlanScheduler.executionOrder(tasks).map { it.id })
    }

    @Test
    fun `equal priority keeps csv row order`() {
        val tasks = listOf(
            task(id = 1, csvRow = 3, priority = 1),
            task(id = 2, csvRow = 1, priority = 1),
            task(id = 3, csvRow = 2, priority = 1)
        )
        assertEquals(listOf(2L, 3L, 1L), PlanScheduler.executionOrder(tasks).map { it.id })
    }

    @Test
    fun `completed tasks are skipped by selectNext`() {
        val tasks = listOf(
            task(id = 1, csvRow = 1, priority = 1, status = "completed", completed = 2),
            task(id = 2, csvRow = 2, priority = 2)
        )
        assertEquals(2L, PlanScheduler.selectNext(tasks)!!.id)
    }

    @Test
    fun `active unfinished task is reselected before any pending task`() {
        // # INV-2：活动任务配额未完成前不得推进到下一个 pending 任务
        val tasks = listOf(
            task(id = 1, csvRow = 1, priority = 1, status = "active", required = 3, completed = 1),
            task(id = 2, csvRow = 2, priority = 2)
        )
        assertEquals(1L, PlanScheduler.selectNext(tasks)!!.id)
    }

    @Test
    fun `quota-met active task is not reselected even if status not yet completed`() {
        // # 配额已满的活动任务视为完成，不应再被选中
        val tasks = listOf(
            task(id = 1, csvRow = 1, priority = 1, status = "active", required = 2, completed = 2),
            task(id = 2, csvRow = 2, priority = 2)
        )
        assertEquals(2L, PlanScheduler.selectNext(tasks)!!.id)
        assertTrue(PlanScheduler.isQuotaComplete(tasks[0]))
    }

    @Test
    fun `selectNext returns null when nothing remains`() {
        val tasks = listOf(
            task(id = 1, csvRow = 1, priority = 1, status = "completed", completed = 2),
            task(id = 2, csvRow = 2, priority = 2, status = "completed", completed = 2)
        )
        assertNull(PlanScheduler.selectNext(tasks))
    }

    @Test
    fun `plan is complete only when all tasks completed`() {
        val unfinished = listOf(
            task(id = 1, csvRow = 1, priority = 1, status = "completed", completed = 2),
            task(id = 2, csvRow = 2, priority = 2, status = "active", completed = 1)
        )
        assertFalse(PlanScheduler.isPlanComplete(unfinished))
        assertTrue(PlanScheduler.isPlanComplete(unfinished.map { it.copy(status = "completed") }))
    }
}
