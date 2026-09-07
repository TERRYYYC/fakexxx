package com.example.cellrebelauto.automation

import com.example.cellrebelauto.repository.PlanRepository

sealed interface SupersessionStopStatus {
    data object Idle : SupersessionStopStatus
    data class Stopping(val requestId: String, val planId: Long, val sessionId: Long) : SupersessionStopStatus
    data class Verified(
        val requestId: String,
        val proof: PlanRepository.SupersessionStopProof
    ) : SupersessionStopStatus
    data class Blocked(val requestId: String, val reason: String) : SupersessionStopStatus
}

/**
 * Serial stop boundary for #97. Job retirement is ordered before durable verification, and the
 * only optional engine action is convergence of an existing owner. There is intentionally no
 * normal-run or admission callback on this type.
 */
internal class SupersessionStopCoordinator(
    private val cancelAndJoinRun: suspend () -> Unit,
    private val verifyDurableStop: suspend () -> PlanRepository.SupersessionStopVerification,
    private val convergeExistingOwner: suspend () -> Boolean
) {
    suspend fun execute(): PlanRepository.SupersessionStopVerification {
        cancelAndJoinRun()
        val first = verifyDurableStop()
        if (first !is PlanRepository.SupersessionStopVerification.NeedsConvergence) return first
        if (!convergeExistingOwner()) {
            return PlanRepository.SupersessionStopVerification.Blocked(
                "STOP_ONLY_CONVERGENCE_FAILED:${first.reason}"
            )
        }
        return when (val second = verifyDurableStop()) {
            is PlanRepository.SupersessionStopVerification.NeedsConvergence ->
                PlanRepository.SupersessionStopVerification.Blocked(
                    "STOP_ONLY_CONVERGENCE_INCOMPLETE:${second.reason}"
                )
            else -> second
        }
    }
}
