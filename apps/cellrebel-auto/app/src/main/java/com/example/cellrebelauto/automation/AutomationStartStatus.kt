package com.example.cellrebelauto.automation

/** UI-visible #80 admission state. `Accepted` carries a Room-durable session identity. */
sealed interface AutomationStartStatus {
    data object IDLE : AutomationStartStatus
    data object STARTING : AutomationStartStatus
    data class Accepted(val sessionId: Long) : AutomationStartStatus
    data class Rejected(val reason: String) : AutomationStartStatus
}
