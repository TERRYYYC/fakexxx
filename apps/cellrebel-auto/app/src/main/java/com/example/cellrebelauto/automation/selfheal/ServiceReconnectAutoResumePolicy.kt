package com.example.cellrebelauto.automation.selfheal

/**
 * P1.3 #2 (service reconnect auto-resume) — the reconnect decision (pure, JVM-testable).
 *
 * After SERVICE_RECYCLED the accessibility service instance is typically rebuilt by the system
 * within seconds (the measured incident: reconnect ~1s, then the run sat idle until a human pressed
 * Resume — the highest-frequency manual intervention in unattended runs). When the service's
 * EXISTING connect callback ([android.accessibilityservice.AccessibilityService.onServiceConnected])
 * fires on the new instance and a recycle marker is pending, the policy decides whether to
 * auto-resume the recycled plan.
 *
 * Budget: at most [maxAutoResumesPerWindow] auto-resumes inside a rolling [windowMs] window
 * (30 min / 3). Over budget the engine STAYS at its typed terminal with the reason — unattended
 * crash-loops must converge to a stopped state, never to a restart storm.
 *
 * Default is OFF (conservative): the caller must not even consult this policy's Resume branch
 * unless the persisted toggle is on.
 *
 * # 服务重连自动恢复决策：默认关；30 分钟滚动窗口 ≤3 次，超限维持停机终态+原因
 */
class ServiceReconnectAutoResumePolicy(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val windowMs: Long = 30 * 60 * 1000L,
    private val maxAutoResumesPerWindow: Int = 3
) {

    /** What [android.content.Context] persisted when the previous instance was recycled. */
    data class RecycleMarker(val planId: Long, val recycledAtMs: Long)

    sealed interface Decision {
        /** Marker pending + toggle on + budget available → resume this plan. */
        data class Resume(val planId: Long) : Decision

        /**
         * A marker was pending but the engine stays stopped; [reason] is the human-readable why
         * (persisted to the audit trail next to the SERVICE_RECYCLED terminal).
         */
        data class Hold(val reason: String) : Decision

        /** Nothing pending (ordinary first connect) — no action, no audit line. */
        data object NothingToDo : Decision
    }

    fun decide(
        enabled: Boolean,
        marker: RecycleMarker?,
        autoResumeTimestampsMs: List<Long>
    ): Decision {
        if (marker == null) return Decision.NothingToDo
        if (!enabled) return Decision.Hold("AUTO_RESUME_DISABLED")
        val inWindow = autoResumesInWindow(autoResumeTimestampsMs)
        if (inWindow.size >= maxAutoResumesPerWindow) {
            return Decision.Hold(
                "AUTO_RESUME_BUDGET_EXHAUSTED:${inWindow.size}_in_${windowMs / 60_000}min " +
                    "(limit $maxAutoResumesPerWindow) — staying stopped"
            )
        }
        return Decision.Resume(marker.planId)
    }

    /** The rolling-window projection of past auto-resume actions (older entries fall out). */
    fun autoResumesInWindow(timestampsMs: List<Long>): List<Long> {
        val cutoff = nowMs() - windowMs
        return timestampsMs.filter { it >= cutoff }
    }
}
