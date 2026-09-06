package com.example.cellrebelauto.automation

import com.example.cellrebelauto.repository.PlanRepository

/** A successful #80 start is a durable `run_sessions` receipt, not a launched coroutine. */
sealed interface RunStartReceipt {
    data class Accepted(val sessionId: Long) : RunStartReceipt
    data class Resumed(val sessionId: Long) : RunStartReceipt
    data class Rejected(val reason: String) : RunStartReceipt
}

class RunStartCoordinator(private val planRepository: PlanRepository) {
    suspend fun admit(planId: Long, startedAt: Long): RunStartReceipt = when (
        val admission = planRepository.admitRunSession(planId, startedAt)
    ) {
        is PlanRepository.RunSessionAdmission.Created -> RunStartReceipt.Accepted(admission.sessionId)
        is PlanRepository.RunSessionAdmission.Existing -> RunStartReceipt.Resumed(admission.sessionId)
        PlanRepository.RunSessionAdmission.MissingPlan -> RunStartReceipt.Rejected("PLAN_NOT_FOUND")
    }
}
