package com.example.cellrebelauto.ui.dashboard.v2

import com.example.cellrebelauto.ui.dashboard.RunProgressProjection

/**
 * T7v2 §A1-v2 #4 — the configurable metric-pill row's pure half.
 *
 * Selection is 1..3 keys, DataStore-persisted (DashboardMetricsSettings).
 * Rendering is truth-only:
 *  - 吞吐: RunProgressProjection's trailing-1h window successes scaled to
 *    per-hour (windowSuccesses * 3_600_000 / WINDOW_MS). Assumption: the rate
 *    inside the window is representative; no window successes → "--/h".
 *  - ETA: remaining trusted quota / throughput (same projection); "--" when
 *    no measurable rate.
 *  - 坐标: the CURRENT PLAN ROW's coordinate (plan truth, not a GPS fix).
 *  - 小区: the device CI reading + its honest badge.
 *
 * # 指标 pill：选择清洗（1..3 去重回退）+ 渲染真值；口径注释就地写死
 */
enum class MetricKey(val label: String) {
    PROGRESS("进度"),
    THROUGHPUT("吞吐"),
    ETA("剩余"),
    COORDINATE("坐标"),
    CI_SOURCE("小区"),
}

/** Render inputs assembled once by the ViewModel; all nullable = "not known yet". */
object MetricPillFormatter {

    /** Max pills on one row (spec: 1..3). */
    const val MAX_PILLS = 3

    /**
     * Cleans a user selection for persistence/rendering: deduped preserving
     * choice order, capped at three, and NEVER empty — an emptied row falls
     * back to the trusted progress fraction (the spec default).
     */
    fun sanitize(selected: List<MetricKey>): List<MetricKey> {
        val deduped = selected.distinct().take(MAX_PILLS)
        return if (deduped.isEmpty()) listOf(MetricKey.PROGRESS) else deduped
    }

    fun render(
        key: MetricKey,
        progress: RunProgressProjection.ProgressSnapshot,
        currentPoint: CurrentPointView?,
        ci: CiHeroView?,
    ): String = when (key) {
        MetricKey.PROGRESS -> "${progress.trustedDone}/${progress.trustedTotal}"

        // 口径：近 1 小时窗口内成功数折算每小时（RunProgressProjection.WINDOW_MS）。
        // 假设：窗口内速率近似代表当前速率；窗口无成功则不给数（"--/h"）。
        MetricKey.THROUGHPUT -> progress.throughputPerHour
            ?.let { String.format(java.util.Locale.US, "%.1f/h", it) }
            ?: "--/h"

        // 口径：剩余可信配额 / 上述吞吐（RunProgressProjection.project 的 etaMs）。
        // 无可测速率 → "--"。紧凑格式把 v1 的"约 X 小时 Y 分"压进 pill 宽度。
        MetricKey.ETA -> progress.etaMs?.let { formatEtaCompact(it) } ?: "--"

        // 计划行坐标（plan 真值，非 GPS 读数），4 位小数与规格样例一致。
        MetricKey.COORDINATE -> currentPoint
            ?.let { String.format(java.util.Locale.US, "%.4f,%.4f", it.latitude, it.longitude) }
            ?: "--"

        // 设备 CI 真值 + 如实徽标；无读数只显示占位，绝不编造。
        MetricKey.CI_SOURCE -> ci?.reading?.ci?.let { value ->
            val badge = ci.badge?.let { "·${it.label}" } ?: ""
            "$value$badge"
        } ?: "--"
    }

    /** "89h0m" / "45m" — compact because a pill has one line to live on. */
    private fun formatEtaCompact(etaMs: Long): String {
        val totalMinutes = (etaMs + 59_999) / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours >= 1) "${hours}h${minutes}m" else "${minutes}m"
    }
}
