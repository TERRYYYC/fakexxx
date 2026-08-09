package com.example.cellrebelauto.automation.plan

import com.example.cellrebelauto.model.plan.LocationTask

/**
 * Pure, deterministic scheduler over a plan's task list.
 * Order: priority ASC, csvRow ASC (INV-1). An active task whose quota is not
 * met is always re-selected before any pending task (INV-2).
 * # 纯函数式确定性调度器。顺序：priority ASC, csvRow ASC（INV-1）；
 * # 配额未满的活动任务永远先于任何 pending 任务被选中（INV-2）
 */
object PlanScheduler {

    // # 执行顺序投影
    fun executionOrder(tasks: List<LocationTask>): List<LocationTask> =
        tasks.sortedWith(compareBy({ it.priority }, { it.csvRow }))

    // # 下一个要执行的任务：先找配额未满的活动任务，再找 pending 任务
    fun selectNext(tasks: List<LocationTask>): LocationTask? =
        executionOrder(tasks).firstOrNull { it.status == "active" && !isQuotaComplete(it) }
            ?: executionOrder(tasks).firstOrNull { it.status == "pending" }

    fun isQuotaComplete(task: LocationTask): Boolean =
        task.completedSuccesses >= task.requiredSuccesses

    fun isPlanComplete(tasks: List<LocationTask>): Boolean =
        tasks.all { it.status == "completed" }
}
