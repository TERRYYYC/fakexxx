package com.example.cellrebelauto.ui.dashboard.v2

import com.example.cellrebelauto.automation.selfheal.CoordinateGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Plan-map 实测层（探针观察投影）的纯投影 oracle：
 *  1. 配对语义 — 实测按 taskId 配到计划点，跨 task 绝不串点；无观察的点保持纯计划位。
 *  2. 匹配判定 — 逐字复用 [CoordinateGuard.violation]（逐轴 |Δ| ≤ TOLERANCE_DEG），
 *     边界（恰好在容差上 = 匹配，violation 用严格大于）与轴独立性在此钉死。
 *  3. 偏差距离 — haversine 展示用 sanity（零距离/已知城市间距量级）。
 *
 * # 实测层投影 oracle：配对/匹配边界/偏差距离，全纯函数
 */
class PlanMapPointsMeasuredTest {

    private fun row(
        id: Long,
        csvRow: Int = id.toInt(),
        lat: Double = 50.0,
        lng: Double = 30.0,
        status: String = "active",
    ) = PlanMapPoints.Row(
        id = id, csvRow = csvRow, latitude = lat, longitude = lng,
        status = status, requiredSuccesses = 1,
    )

    // ---- 配对 -------------------------------------------------------------------

    @Test
    fun project_withMeasured_attachesFixToSameTaskOnly() {
        val points = PlanMapPoints.project(
            tasks = listOf(row(id = 1), row(id = 2)),
            trustedCounts = emptyMap(),
            currentCsvRow = null,
            measured = mapOf(
                // Task 1 的实测：微小偏差（容差内）；Task 2 无观察数据。
                1L to PlanMapPoints.MeasuredFix(50.0 + CoordinateGuard.TOLERANCE_DEG / 2, 30.0),
            ),
        )
        assertEquals(2, points.size)
        val withFix = points.first { it.taskId == 1L }
        assertEquals(50.0 + CoordinateGuard.TOLERANCE_DEG / 2, withFix.measuredLat!!, 1e-12)
        assertEquals(30.0, withFix.measuredLng!!, 1e-12)
        assertTrue(withFix.measuredMatched)
        val planOnly = points.first { it.taskId == 2L }
        assertNull(planOnly.measuredLat)
        assertNull(planOnly.measuredLng)
        assertFalse(planOnly.measuredMatched)
    }

    @Test
    fun project_withoutMeasured_isPurePlanProjection() {
        val points = PlanMapPoints.project(
            tasks = listOf(row(id = 1)),
            trustedCounts = emptyMap(),
            currentCsvRow = null,
        )
        assertNull(points.single().measuredLat)
        assertNull(points.single().measuredLng)
        assertFalse(points.single().measuredMatched)
    }

    @Test
    fun project_measuredForUnknownTask_isIgnored() {
        // 观察流的 task 不在本计划点位里（计划已换代等）——绝不能串到别的点上。
        val points = PlanMapPoints.project(
            tasks = listOf(row(id = 1)),
            trustedCounts = emptyMap(),
            currentCsvRow = null,
            measured = mapOf(99L to PlanMapPoints.MeasuredFix(51.0, 31.0)),
        )
        val p = points.single()
        assertNull(p.measuredLat)
        assertFalse(p.measuredMatched)
    }

    @Test
    fun project_lateArrival_emptyMapMatchesBaseline() {
        val tasks = listOf(row(id = 1), row(id = 2, status = "pending"))
        val baseline = PlanMapPoints.project(tasks, emptyMap(), null)
        val withEmpty = PlanMapPoints.project(tasks, emptyMap(), null, measured = emptyMap())
        assertEquals(baseline, withEmpty)
        // 观察晚到：只变实测三字段，计划位/状态原样。
        val late = PlanMapPoints.project(
            tasks, emptyMap(), null,
            measured = mapOf(2L to PlanMapPoints.MeasuredFix(50.5, 30.5)),
        )
        assertEquals(baseline.map { it.taskId }, late.map { it.taskId })
        assertEquals(baseline.map { it.state }, late.map { it.state })
        assertEquals(baseline.map { it.latitude }, late.map { it.latitude })
    }

    // ---- 匹配判定（CoordinateGuard 语义复用） -----------------------------------

    @Test
    fun measuredMatched_exactlyAtTolerance_isMatch() {
        // violation 用严格大于：|Δ| == TOLERANCE_DEG 恰好不越界 → 匹配。
        val fix = PlanMapPoints.MeasuredFix(
            latitude = 50.0 + CoordinateGuard.TOLERANCE_DEG,
            longitude = 30.0,
        )
        assertTrue(PlanMapPoints.measuredMatched(50.0, 30.0, fix))
    }

    @Test
    fun measuredMatched_justPastTolerance_isMismatch() {
        val fix = PlanMapPoints.MeasuredFix(
            latitude = 50.0 + CoordinateGuard.TOLERANCE_DEG + 1e-9,
            longitude = 30.0,
        )
        assertFalse(PlanMapPoints.measuredMatched(50.0, 30.0, fix))
    }

    @Test
    fun measuredMatched_axesAreIndependent() {
        // lat 在容差内、lng 越界 → 不匹配（CoordinateGuard 逐轴判定语义）。
        val lngOut = PlanMapPoints.MeasuredFix(
            latitude = 50.0,
            longitude = 30.0 + CoordinateGuard.TOLERANCE_DEG * 3,
        )
        assertFalse(PlanMapPoints.measuredMatched(50.0, 30.0, lngOut))
        val latOut = PlanMapPoints.MeasuredFix(
            latitude = 50.0 - CoordinateGuard.TOLERANCE_DEG * 3,
            longitude = 30.0,
        )
        assertFalse(PlanMapPoints.measuredMatched(50.0, 30.0, latOut))
    }

    @Test
    fun project_farOffFix_flagsMismatch() {
        // 一个真实"档案错位"量级的偏差（~0.01° ≈ 1.1km）必须亮红。
        val points = PlanMapPoints.project(
            tasks = listOf(row(id = 1)),
            trustedCounts = emptyMap(),
            currentCsvRow = null,
            measured = mapOf(1L to PlanMapPoints.MeasuredFix(50.01, 30.01)),
        )
        assertFalse(points.single().measuredMatched)
    }

    // ---- 偏差距离（展示用 haversine） --------------------------------------------

    @Test
    fun haversine_zeroDistance() {
        assertEquals(0.0, PlanMapPoints.haversineMeters(50.0, 30.0, 50.0, 30.0), 1e-6)
    }

    @Test
    fun haversine_toleranceIsOrderOfTensOfMeters() {
        // CoordinateGuard 容差角在纬线方向的地面距离 ~22m：偏差统计的量级 sanity。
        val meters = PlanMapPoints.haversineMeters(
            50.0, 30.0,
            50.0 + CoordinateGuard.TOLERANCE_DEG, 30.0,
        )
        assertTrue("got $meters", meters > 15.0 && meters < 30.0)
    }

    @Test
    fun haversine_knownCityDistance_isKilometerScale() {
        // Kyiv → Lviv ~470km（球面近似，1% 量级容差）。
        val meters = PlanMapPoints.haversineMeters(50.4501, 30.5234, 49.8397, 24.0297)
        assertTrue("got $meters", abs(meters - 470_000.0) < 470_000.0 * 0.02)
    }

    // ---- 最大偏差角标（haversineMeters 的图例消费点，#185 F3） --------------------

    @Test
    fun maxDeviationMeters_noMeasuredFix_isNull() {
        val points = PlanMapPoints.project(
            tasks = listOf(row(id = 1)),
            trustedCounts = emptyMap(),
            currentCsvRow = null,
        )
        assertNull(PlanMapPoints.maxDeviationMeters(points))
    }

    @Test
    fun maxDeviationMeters_takesMaxAcrossMeasuredPoints() {
        val points = PlanMapPoints.project(
            tasks = listOf(row(id = 1), row(id = 2, lat = 51.0, lng = 31.0)),
            trustedCounts = emptyMap(),
            currentCsvRow = null,
            measured = mapOf(
                // 点 1：容差内小偏差；点 2：~111m 级大偏差——角标必须取后者。
                1L to PlanMapPoints.MeasuredFix(50.0 + CoordinateGuard.TOLERANCE_DEG / 2, 30.0),
                2L to PlanMapPoints.MeasuredFix(51.0 + 0.001, 31.0),
            ),
        )
        val max = PlanMapPoints.maxDeviationMeters(points)!!
        val big = PlanMapPoints.haversineMeters(51.0, 31.0, 51.0 + 0.001, 31.0)
        assertEquals(big, max, 1e-9)
        assertTrue("got $max", max > 100.0)
    }
}
