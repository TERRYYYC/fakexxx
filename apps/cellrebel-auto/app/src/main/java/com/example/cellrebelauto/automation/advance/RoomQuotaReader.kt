package com.example.cellrebelauto.automation.advance

import com.example.cellrebelauto.repository.PlanRepository

/**
 * Production [QuotaReader] backed by Room's trusted quota ledger.
 *
 * Reads `count(TrustedQuotaEntry where taskId)` — the insert-only ledger
 * that [PlanRepository.finalizeAttemptSuccess] populates inside its Room
 * transaction. UNIQUE(attemptId) guarantees at-most-once semantics.
 *
 * This replaces the previous approach of reading the denormalized
 * `LocationTask.completedSuccesses` counter. The counter is still maintained
 * for UI convenience, but the ledger is the source of truth for advance
 * decisions (§7.3, M-AD-14, M-AD-15).
 *
 * # 生产 QuotaReader：从可信配额台账 COUNT 读取。
 * # 台账是推进决策的真相源；completedSuccesses 计数器仅供 UI 展示。
 */
class RoomQuotaReader(
    private val planRepository: PlanRepository,
) : QuotaReader {

    override suspend fun countTrustedEntries(taskId: String): Int {
        return planRepository.countTrustedQuotaEntries(taskId.toLong())
    }
}
