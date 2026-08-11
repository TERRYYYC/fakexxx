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

    // ---------------------------------------------------------------------------------------------
    // M-MG-02 trusted-aware selection (RED skeleton). The legacy methods above route on the v4
    // `completedSuccesses` counter — which has NO A+ evidence chain (no observation, no intent hash,
    // no continuity proof) and must NOT decide quota completion / address selection (INV-05/06,
    // M-MG-02). GREEN rewires AutomationEngine to the trusted methods below (driven by the
    // trusted-ledger projection `count(trusted_quota_entries where taskId=…)`) and removes the
    // direct `completedSuccesses` counter path.
    //
    // PRE-FREEZE SKELETON (RED): both trusted methods currently ALIAS the legacy counter path —
    // exactly the forbidden behaviour — so tests asserting the trusted projection FAIL until GREEN.
    // A GREEN that forgets to rewire (keeps delegating to the legacy counter) also fails them.
    // ---------------------------------------------------------------------------------------------

    /**
     * Trusted quota completion (M-MG-02). RED: aliases legacy [isQuotaComplete] (counter-based) —
     * GREEN must compare [trustedCount] against [LocationTask.requiredSuccesses] and IGNORE
     * `completedSuccesses`.
     */
    fun isTrustedQuotaComplete(task: LocationTask, trustedCount: Int): Boolean = isQuotaComplete(task)

    /**
     * Trusted address selection (M-MG-02). RED: aliases legacy [selectNext] (counter-based) —
     * GREEN must skip a task only when its trusted count has reached `requiredSuccesses`.
     */
    fun selectNextTrusted(tasks: List<LocationTask>, trustedCounts: Map<Long, Int>): LocationTask? =
        selectNext(tasks)
}
