package com.example.cellrebelauto.model.plan

/**
 * One attempt joined with its task's plan context (History rows + CSV export).
 * # 一次尝试与其任务计划上下文的联接结果（History 行与 CSV 导出用）
 */
data class AttemptWithTask(
    val attempt: TestAttempt,
    // # 任务在其计划执行顺序中的 1 起始序号（INV-1 投影，导出 plan_row 列）
    val planRow: Int,
    // # 源 CSV 数据行号（追溯）
    val csvRow: Int,
    val priority: Int,
    val requiredSuccesses: Int
)
