package com.example.cellrebelauto.automation.selfheal

import com.example.cellrebelauto.data.SelfHealSettings
import kotlinx.coroutines.flow.first

/**
 * P1.3 #2 — executes the service-reconnect auto-resume decision (extracted from
 * [com.example.cellrebelauto.automation.AutomationService] so the decision SIDE EFFECTS are
 * directly unit-testable without reflection, static companions, or the shared app singleton).
 *
 * Every action lands in the durable audit stream AND the service log; the recycled plan resumes
 * through the EXACT entry a manual Resume uses.
 *
 * # 重连自动恢复执行器：Resume/Hold 的副作用（消费标记、记账预算、审计行、日志、启动）集中在这一处
 */
class ServiceReconnectAutoResumeCoordinator(
    private val settings: SelfHealSettings,
    private val markerStore: ServiceRecycleMarkerStore,
    private val policy: ServiceReconnectAutoResumePolicy,
    private val audit: suspend (eventType: String, correlationRef: String?, detail: String, recordedAt: Long) -> Unit,
    private val log: (String) -> Unit,
    private val resume: (planId: Long) -> Unit,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /** Called from the EXISTING connect callback (onServiceConnected) of every service instance. */
    suspend fun afterServiceConnected() {
        val marker = markerStore.pendingRecycle() ?: return
        val config = settings.config.first()
        when (
            val decision = policy.decide(
                enabled = config.serviceReconnectAutoResumeEnabled,
                marker = marker,
                autoResumeTimestampsMs = markerStore.autoResumeTimestamps()
            )
        ) {
            is ServiceReconnectAutoResumePolicy.Decision.NothingToDo -> return
            is ServiceReconnectAutoResumePolicy.Decision.Resume -> {
                val now = nowMs()
                markerStore.clearPendingRecycle()
                markerStore.recordAutoResume(now)
                audit(
                    "SERVICE_AUTO_RESUME",
                    "plan:${decision.planId}",
                    "auto-resumed plan ${decision.planId} after SERVICE_RECYCLED " +
                        "(recycled at ${marker.recycledAtMs})",
                    now
                )
                log("Self-heal: auto-resuming plan #${decision.planId} after SERVICE_RECYCLED")
                resume(decision.planId)
            }
            is ServiceReconnectAutoResumePolicy.Decision.Hold -> {
                val now = nowMs()
                audit(
                    "SERVICE_AUTO_RESUME_HELD",
                    "plan:${marker.planId}",
                    decision.reason,
                    now
                )
                log("Self-heal: auto-resume held — ${decision.reason} (plan #${marker.planId} stays stopped)")
            }
        }
    }
}
