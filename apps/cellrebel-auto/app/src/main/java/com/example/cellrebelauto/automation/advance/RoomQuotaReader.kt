package com.example.cellrebelauto.automation.advance

import com.example.cellrebelauto.repository.PlanRepository

/**
 * Production [QuotaReader] backed by Room via [PlanRepository].
 *
 * Reads `LocationTask.completedSuccesses` — the same field that
 * [PlanRepository.finalizeAttemptSuccess] atomically increments inside
 * its CAS-guarded Room transaction (INV-3).
 *
 * This is the durable source the coordinator reads from after a crash:
 * Room persists the count, so a process restart sees the committed value.
 */
class RoomQuotaReader(
    private val planRepository: PlanRepository,
) : QuotaReader {

    override suspend fun countTrustedEntries(taskId: String): Int {
        val task = planRepository.getTask(taskId.toLong()) ?: return 0
        return task.completedSuccesses
    }
}
