package com.example.cellrebelauto.automation.selfheal

/**
 * P1.3 #1 — attempt watchdog threshold policy (pure, JVM-testable).
 *
 * An attempt that stays running longer than `max(90s, 3× the task's historical median attempt
 * duration)` is a zombie (the measured incident: a service-instance rebuild left the engine holding
 * a dead runner callback and the attempt ran 8+ minutes with no timeout and no reaping). The
 * threshold deliberately sits at or above the runner's own `testTimeoutMs` default (90s): the
 * watchdog is a SAFETY NET for hung runners, not a second normal-path timeout.
 *
 * # attempt 看门狗阈值：max(90s, 3× 该任务历史中位时长)；无历史用 90s。是僵尸兜底，不是第二套常规超时。
 */
object AttemptWatchdogPolicy {

    /** Floor when there is no history (and the minimum regardless of history). */
    const val MIN_TIMEOUT_MS: Long = 90_000L

    /** History multiplier. */
    const val HISTORY_MULTIPLIER: Long = 3L

    /**
     * Watchdog budget for one attempt. `null` median (no terminal history) → the 90s floor.
     * # 阈值 = max(90s, 3×中位)；无历史（null）→ 90s
     */
    fun timeoutMs(medianAttemptDurationMs: Long?): Long =
        maxOf(MIN_TIMEOUT_MS, (medianAttemptDurationMs ?: 0L) * HISTORY_MULTIPLIER)

    /**
     * Median of terminal-attempt durations. `null` when the task has no terminal history.
     * Even counts average the two middle values.
     * # 中位数；空历史返回 null；偶数个取中间两值平均
     */
    fun median(durationsMs: List<Long>): Long? {
        if (durationsMs.isEmpty()) return null
        val sorted = durationsMs.sorted()
        return if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        }
    }
}
