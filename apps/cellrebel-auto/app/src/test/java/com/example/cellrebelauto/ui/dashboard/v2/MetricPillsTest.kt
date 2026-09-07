package com.example.cellrebelauto.ui.dashboard.v2

import com.example.cellrebelauto.ui.dashboard.RunProgressProjection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T7v2 §A1-v2 #4 — the configurable metric-pill oracle.
 *
 * Pills are 1..3 user-chosen metrics; every value is TRUTH computed by the
 * existing trusted projection. 口径（写死在这，别处不得再算）:
 *  - 吞吐 = RunProgressProjection 的近 1 小时窗口成功数折算每小时
 *    （windowSuccesses * 3_600_000 / WINDOW_MS；假设：窗口内速率恒定）；
 *  - ETA = 剩余配额 / 吞吐（RunProgressProjection.project，无速率则 "--"）；
 *  - 坐标 = 当前执行任务的计划坐标（plan 行真值，非 GPS 读数）。
 *
 * # 指标 pill oracle：选择集合清洗（1..3、去重、空回退默认）+ 各键渲染真值
 */
class MetricPillsTest {

    private val progress = RunProgressProjection.project(
        trustedDone = 107,
        trustedTotal = 285,
        attempts = listOf(
            RunProgressProjection.AttemptFact(succeeded = true, failureReason = null, endedAt = 3_600_000L),
            RunProgressProjection.AttemptFact(succeeded = true, failureReason = null, endedAt = 3_500_000L),
        ),
        nowMs = 3_600_000L,
    )

    @Test
    fun sanitize_emptyFallsBackToProgress() {
        assertEquals(listOf(MetricKey.PROGRESS), MetricPillFormatter.sanitize(emptyList()))
    }

    @Test
    fun sanitize_deduplicatesPreservingOrder() {
        assertEquals(
            listOf(MetricKey.ETA, MetricKey.PROGRESS),
            MetricPillFormatter.sanitize(listOf(MetricKey.ETA, MetricKey.PROGRESS, MetricKey.ETA)),
        )
    }

    @Test
    fun sanitize_capsAtThree() {
        assertEquals(
            listOf(MetricKey.PROGRESS, MetricKey.THROUGHPUT, MetricKey.ETA),
            MetricPillFormatter.sanitize(
                listOf(MetricKey.PROGRESS, MetricKey.THROUGHPUT, MetricKey.ETA, MetricKey.COORDINATE),
            ),
        )
    }

    @Test
    fun progressPill_rendersTrustedFraction() {
        assertEquals(
            "107/285",
            MetricPillFormatter.render(MetricKey.PROGRESS, progress, currentPoint = null, ci = null),
        )
    }

    @Test
    fun throughputPill_rendersPerHour() {
        assertEquals(
            "2.0/h",
            MetricPillFormatter.render(MetricKey.THROUGHPUT, progress, currentPoint = null, ci = null),
        )
    }

    @Test
    fun throughputPill_noWindowSuccesses_rendersPlaceholder() {
        val empty = RunProgressProjection.project(0, 10, attempts = emptyList(), nowMs = 0)
        assertEquals(
            "--/h",
            MetricPillFormatter.render(MetricKey.THROUGHPUT, empty, currentPoint = null, ci = null),
        )
    }

    @Test
    fun etaPill_rendersCompactHours() {
        val text = MetricPillFormatter.render(MetricKey.ETA, progress, currentPoint = null, ci = null)
        // remaining 178 at 2.0/h → 89h; the exact compact string is format-stable
        assertEquals("89h0m", text)
    }

    @Test
    fun coordinatePill_rendersCurrentPlanPoint() {
        val point = CurrentPointView(csvRow = 3, latitude = 50.450864, longitude = 30.523367)
        assertEquals(
            "50.4509,30.5234",
            MetricPillFormatter.render(MetricKey.COORDINATE, progress, currentPoint = point, ci = null),
        )
    }

    @Test
    fun coordinatePill_withoutCurrentPoint_rendersPlaceholder() {
        assertEquals(
            "--",
            MetricPillFormatter.render(MetricKey.COORDINATE, progress, currentPoint = null, ci = null),
        )
    }

    @Test
    fun ciPill_rendersValueWithHonestBadge() {
        val ci = CiHeroView(reading = servingCell(289001L), badge = CiBadge.INJECTED)
        assertEquals(
            "289001·注入",
            MetricPillFormatter.render(MetricKey.CI_SOURCE, progress, currentPoint = null, ci = ci),
        )
    }

    @Test
    fun ciPill_noReading_rendersPlaceholder() {
        assertEquals(
            "--",
            MetricPillFormatter.render(MetricKey.CI_SOURCE, progress, currentPoint = null, ci = null),
        )
    }

    private fun servingCell(ci: Long?) = ServingCellReading(
        rat = "LTE", ci = ci, tac = 31461, pci = 210, mcc = "460", mnc = "0",
        rsrpDbm = -95, registered = true, readAtMs = 0L,
    )
}
