package com.example.cellrebelauto.automation.plan

import com.example.cellrebelauto.model.plan.LocationTask

/**
 * Pure, deterministic scheduler over a plan's task list.
 * Order: priority ASC, csvRow ASC (INV-1). An active task whose quota is not
 * met is always re-selected before any pending task (INV-2).
 *
 * Quota is determined by trusted quota counts (§7.3): the number of
 * TrustedQuotaEntry rows for a task, NOT the denormalized
 * LocationTask.completedSuccesses counter.
 *
 * # 纯函数式确定性调度器。顺序：priority ASC, csvRow ASC（INV-1）；
 * # 配额未满的活动任务永远先于任何 pending 任务被选中（INV-2）。
 * # §7.3：配额由 TrustedQuotaEntry 计数决定，不是 completedSuccesses
 */
object PlanScheduler {

    // # 执行顺序投影
    fun executionOrder(tasks: List<LocationTask>): List<LocationTask> =
        tasks.sortedWith(compareBy({ it.priority }, { it.csvRow }))

    /**
     * Select the next task to execute. Quota is determined by [trustedQuotaCounts]
     * (taskId → trusted entry count). An active task whose trusted count has not
     * reached requiredSuccesses is re-selected before any pending task (INV-2).
     *
     * # 选择下一个任务。配额由 trustedQuotaCounts 决定（§7.3）。
     */
    fun selectNext(
        tasks: List<LocationTask>,
        trustedQuotaCounts: Map<Long, Int> = emptyMap()
    ): LocationTask? =
        executionOrder(tasks).firstOrNull {
            it.status == "active" && !isQuotaComplete(it, trustedQuotaCounts)
        } ?: executionOrder(tasks).firstOrNull { it.status == "pending" }

    /**
     * Whether a task's trusted quota has been met (§7.3).
     * [trustedQuotaCounts] maps taskId → count of TrustedQuotaEntry rows.
     * Missing key → count = 0 (no entries = quota not met).
     *
     * # 任务的可信配额是否已满。缺失 key = 计数 0 = 未满。
     */
    fun isQuotaComplete(
        task: LocationTask,
        trustedQuotaCounts: Map<Long, Int> = emptyMap()
    ): Boolean {
        val count = trustedQuotaCounts[task.id] ?: 0
        return count >= task.requiredSuccesses
    }

    fun isPlanComplete(tasks: List<LocationTask>): Boolean =
        tasks.all { it.status == "completed" }
}
