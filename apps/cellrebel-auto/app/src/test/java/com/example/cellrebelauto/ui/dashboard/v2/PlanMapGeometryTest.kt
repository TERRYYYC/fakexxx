package com.example.cellrebelauto.ui.dashboard.v2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T7v2 §A1-v2 #3 — the embedded map's math oracle (pure, no Android classes).
 *
 * Two pure layers are pinned here:
 *  1. [PlanMapProjector] — lat/lng → canvas, equal-ratio (aspect-corrected)
 *     projection: the lng axis is scaled by cos(mid-lat) so a plan that spans
 *     equal degrees of lat and lng draws as a SQUARE, not a stretched
 *     rectangle. Contains degenerate-input edges (single point, zero span).
 *  2. [MapTransform] — the pinch/pan transform: scale clamped to 0.5x..20x,
 *     zoom keeps the world point under the fingers stationary, translation is
 *     clamped so the content can never be flung out of view.
 *
 * # 地图数学 oracle：等比投影（经纬比不变形）+ 双指缩放/平移钳制，全部纯函数
 */
class PlanMapGeometryTest {

    // ---- bounds + projection ---------------------------------------------------

    private val kyivToLviv = listOf(
        PlanMapProjector.GeoPoint(50.4501, 30.5234), // Kyiv
        PlanMapProjector.GeoPoint(49.8397, 24.0297), // Lviv
    )

    @Test
    fun emptyPoints_noBounds() {
        assertNull(PlanMapProjector.boundsOf(emptyList()))
    }

    @Test
    fun bounds_encloseAllPoints() {
        val b = PlanMapProjector.boundsOf(kyivToLviv)!!
        assertEquals(49.8397, b.minLat, 1e-9)
        assertEquals(50.4501, b.maxLat, 1e-9)
        assertEquals(24.0297, b.minLng, 1e-9)
        assertEquals(30.5234, b.maxLng, 1e-9)
    }

    @Test
    fun projection_keepsLatLngAspectRatio_squareStaysSquare() {
        // A plan whose corners are exactly 1° apart in lat and lng must project
        // to a square (lng compressed by cos(mid-lat)), NOT a 1:1 degree box.
        val pts = listOf(
            PlanMapProjector.GeoPoint(50.0, 30.0),
            PlanMapProjector.GeoPoint(51.0, 30.0),
            PlanMapProjector.GeoPoint(50.0, 31.0),
            PlanMapProjector.GeoPoint(51.0, 31.0),
        )
        val b = PlanMapProjector.boundsOf(pts)!!
        val fit = PlanMapProjector.fit(b, viewW = 1000.0, viewH = 1000.0, paddingPx = 50.0)!!
        val tl = PlanMapProjector.project(51.0, 30.0, b, fit)
        val tr = PlanMapProjector.project(51.0, 31.0, b, fit)
        val bl = PlanMapProjector.project(50.0, 30.0, b, fit)
        val widthPx = tr.first - tl.first
        val heightPx = bl.second - tl.second
        val expectedRatio = Math.cos(Math.toRadians(50.5)) // mid-lat lng shrink
        assertEquals(expectedRatio, widthPx / heightPx, 1e-6)
    }

    @Test
    fun projection_fitsInsideViewWithPadding_andCenters() {
        val b = PlanMapProjector.boundsOf(kyivToLviv)!!
        val fit = PlanMapProjector.fit(b, viewW = 600.0, viewH = 400.0, paddingPx = 40.0)!!
        for (p in kyivToLviv) {
            val (x, y) = PlanMapProjector.project(p.lat, p.lng, b, fit)
            assertTrue("x=$x within padded view", x in 40.0..560.0)
            assertTrue("y=$y within padded view", y in 40.0..360.0)
        }
        // center of the content lands at the center of the view
        val mid = PlanMapProjector.project(
            (b.minLat + b.maxLat) / 2, (b.minLng + b.maxLng) / 2, b, fit,
        )
        assertEquals(300.0, mid.first, 1e-6)
        assertEquals(200.0, mid.second, 1e-6)
    }

    @Test
    fun projection_singlePoint_degenerateSpanDoesNotDivideByZero() {
        val single = listOf(PlanMapProjector.GeoPoint(50.4501, 30.5234))
        val b = PlanMapProjector.boundsOf(single)!!
        val fit = PlanMapProjector.fit(b, viewW = 600.0, viewH = 400.0, paddingPx = 40.0)
        assertNotNull(fit)
        val (x, y) = PlanMapProjector.project(50.4501, 30.5234, b, fit!!)
        assertEquals(300.0, x, 1e-6)
        assertEquals(200.0, y, 1e-6)
    }

    @Test
    fun projection_northIsUp_eastIsRight() {
        val b = PlanMapProjector.boundsOf(kyivToLviv)!!
        val fit = PlanMapProjector.fit(b, 600.0, 400.0, 40.0)!!
        val kyiv = PlanMapProjector.project(50.4501, 30.5234, b, fit) // NE corner
        val lviv = PlanMapProjector.project(49.8397, 24.0297, b, fit) // SW corner
        assertTrue(kyiv.first > lviv.first)  // east → right
        assertTrue(kyiv.second < lviv.second) // north → up
    }

    // ---- transform: scale clamp ---------------------------------------------------

    @Test
    fun scaleClamp_boundedToHalfTimesTwenty() {
        assertEquals(0.5f, MapTransform.clampScale(0.01f))
        assertEquals(20f, MapTransform.clampScale(99f))
        assertEquals(1.7f, MapTransform.clampScale(1.7f))
    }

    // ---- transform: pinch ----------------------------------------------------------

    @Test
    fun pinch_zoomsAroundCentroid_worldPointUnderFingersStaysPut() {
        val t0 = MapTransform.Transform(scale = 1f, offsetX = 100f, offsetY = 50f)
        val cx = 200f; val cy = 150f
        val worldBefore = MapTransform.toWorld(t0, cx, cy)
        val t1 = MapTransform.pinch(t0, centroidX = cx, centroidY = cy, zoom = 2f)
        val worldAfter = MapTransform.toWorld(t1, cx, cy)
        assertEquals(worldBefore.first, worldAfter.first, 1e-3f)
        assertEquals(worldBefore.second, worldAfter.second, 1e-3f)
        assertEquals(2f, t1.scale)
    }

    @Test
    fun pinch_scaleClamped_zoomInBeyondLimitStopsAt20() {
        val t0 = MapTransform.Transform(scale = 18f)
        val t1 = MapTransform.pinch(t0, 0f, 0f, zoom = 4f)
        assertEquals(20f, t1.scale)
    }

    @Test
    fun pinch_zoomOutBelowLimitStopsAtHalf() {
        val t0 = MapTransform.Transform(scale = 0.6f)
        val t1 = MapTransform.pinch(t0, 50f, 50f, zoom = 0.1f)
        assertEquals(0.5f, t1.scale)
    }

    // ---- transform: pan + clamp ------------------------------------------------------

    @Test
    fun pan_translationClamped_contentCannotLeaveTheView() {
        // content 2000px wide in a 400px view at scale 1: offsetX ∈ [-1600, 0]
        val t0 = MapTransform.Transform(scale = 1f, offsetX = 0f, offsetY = 0f)
        val panned = MapTransform.pan(t0, dx = 500f, dy = 0f, contentW = 2000f, contentH = 400f, viewW = 400f, viewH = 400f)
        assertEquals(0f, panned.offsetX) // dragging right past the left edge clamps
        val pannedLeft = MapTransform.pan(t0, dx = -5000f, dy = 0f, contentW = 2000f, contentH = 400f, viewW = 400f, viewH = 400f)
        assertEquals(-1600f, pannedLeft.offsetX)
    }

    @Test
    fun pan_contentSmallerThanView_staysCentered() {
        val t = MapTransform.pan(
            MapTransform.Transform(scale = 1f, offsetX = 10f, offsetY = 10f),
            dx = 100f, dy = -100f, contentW = 100f, contentH = 50f, viewW = 400f, viewH = 400f,
        )
        assertEquals(150f, t.offsetX) // (400-100)/2
        assertEquals(175f, t.offsetY) // (400-50)/2
    }

    @Test
    fun transformRoundTrip_worldToScreenToWorldIsIdentity() {
        val t = MapTransform.Transform(scale = 3.5f, offsetX = -120f, offsetY = 40f)
        val (sx, sy) = MapTransform.toScreen(t, 33.3f, 44.4f)
        val (wx, wy) = MapTransform.toWorld(t, sx, sy)
        assertEquals(33.3f, wx, 1e-2f)
        assertEquals(44.4f, wy, 1e-2f)
    }

    // ---- plan-point state resolution ----------------------------------------------

    @Test
    fun pointState_completedTaskIsDone() {
        assertEquals(MapPointState.DONE, PlanMapPoints.resolvePointState(status = "completed", trusted = 0, required = 5, isCurrent = false))
    }

    @Test
    fun pointState_quotaMetIsDone_evenBeforeStatusFlips() {
        assertEquals(MapPointState.DONE, PlanMapPoints.resolvePointState(status = "active", trusted = 5, required = 5, isCurrent = false))
    }

    @Test
    fun pointState_currentTaskIsActive() {
        assertEquals(MapPointState.ACTIVE, PlanMapPoints.resolvePointState(status = "active", trusted = 2, required = 5, isCurrent = true))
    }

    @Test
    fun pointState_everythingElseIsPending() {
        assertEquals(MapPointState.PENDING, PlanMapPoints.resolvePointState(status = "active", trusted = 2, required = 5, isCurrent = false))
        assertEquals(MapPointState.PENDING, PlanMapPoints.resolvePointState(status = "pending", trusted = 0, required = 0, isCurrent = false))
    }

    @Test
    fun projectPlan_ordersByExecutionOrder_andSkipsBadCoordinates() {
        val points = PlanMapPoints.project(
            tasks = listOf(
                PlanMapPoints.Row(id = 1, csvRow = 2, latitude = 50.0, longitude = 30.0, status = "active", requiredSuccesses = 3),
                PlanMapPoints.Row(id = 2, csvRow = 1, latitude = 49.9, longitude = 29.9, status = "completed", requiredSuccesses = 3),
                // (0,0) is never a real worklist row — a parse artifact must not
                // drag the bounds to the Gulf of Guinea.
                PlanMapPoints.Row(id = 3, csvRow = 3, latitude = 0.0, longitude = 0.0, status = "pending", requiredSuccesses = 3),
            ),
            trustedCounts = mapOf(2L to 3),
            currentCsvRow = 2,
        )
        assertEquals(2, points.size)
        // Execution order is csvRow ASC: csvRow 1 (task id 2, completed) first.
        assertEquals(2L, points[0].taskId)
        assertEquals(MapPointState.DONE, points[0].state)
        // csvRow 2 (task id 1) is the current row → ACTIVE.
        assertEquals(1L, points[1].taskId)
        assertEquals(MapPointState.ACTIVE, points[1].state)
    }

    // ---- 实测层世界坐标（#185 F1 回归钉：实测与计划共用同一内容原点） -------------

    private fun twoPointPlan() = listOf(
        PlanMapPoints.Row(id = 1, csvRow = 1, latitude = 50.0, longitude = 30.0, status = "active", requiredSuccesses = 1),
        PlanMapPoints.Row(id = 2, csvRow = 2, latitude = 50.002, longitude = 30.004, status = "pending", requiredSuccesses = 1),
    )

    private fun worldFor(points: List<PlanMapPoints.MapPoint>): List<PlanMeasuredWorld> {
        val geo = PlanMapProjector.boundsOf(
            points.map { PlanMapProjector.GeoPoint(it.latitude, it.longitude) }
        )!!
        val fit = PlanMapProjector.fit(geo, viewW = 600.0, viewH = 400.0, paddingPx = 24.0)!!
        return PlanMapWorld.project(points, geo, fit)
    }

    @Test
    fun measuredWorld_zeroDeviation_landsExactlyOnPlanWorldPosition() {
        // F1 曾是：实测层直接用含 fit.tx/ty 的视口像素坐标（未减内容原点），零偏差
        // 也画出一根原点长的偏差线 + 游离菱形。钉死：零偏差实测的世界坐标必须与
        // 计划点完全重合（该数学现在内聚在纯函数里，绘制层无法再各算各的）。
        val points = PlanMapPoints.project(
            twoPointPlan(), emptyMap(), null,
            measured = mapOf(1L to PlanMapPoints.MeasuredFix(50.0, 30.0)), // 零偏差
        )
        // 前置：视口投影确实含非零原点（padding/居中），否则本测试恒真、钉不住。
        val raw = PlanMapProjector.project(
            50.0, 30.0,
            PlanMapProjector.boundsOf(points.map { PlanMapProjector.GeoPoint(it.latitude, it.longitude) })!!,
            PlanMapProjector.fit(
                PlanMapProjector.boundsOf(points.map { PlanMapProjector.GeoPoint(it.latitude, it.longitude) })!!,
                600.0, 400.0, 24.0,
            )!!,
        )
        assertTrue("precondition: viewport coords carry a nonzero origin", raw.first > 0.0 && raw.second > 0.0)

        val world = worldFor(points)
        val zeroDev = world[0]
        assertEquals(zeroDev.planX.toDouble(), zeroDev.measuredX!!.toDouble(), 0.0)
        assertEquals(zeroDev.planY.toDouble(), zeroDev.measuredY!!.toDouble(), 0.0)
        // 整层被 re-based 到内容原点：最小计划世界坐标即 (0,0)。
        assertEquals(0.0, world.minOf { it.planX }.toDouble(), 1e-4)
        assertEquals(0.0, world.minOf { it.planY }.toDouble(), 1e-4)
    }

    @Test
    fun measuredWorld_deviatedFix_keepsTheRawViewportDelta() {
        // 有偏差时：同原点平移不改变相对几何——实测-计划的世界差 == 视口投影差。
        val fixLat = 50.0 + 0.001
        val fixLng = 30.0 + 0.002
        val points = PlanMapPoints.project(
            twoPointPlan(), emptyMap(), null,
            measured = mapOf(1L to PlanMapPoints.MeasuredFix(fixLat, fixLng)),
        )
        val geo = PlanMapProjector.boundsOf(
            points.map { PlanMapProjector.GeoPoint(it.latitude, it.longitude) }
        )!!
        val fit = PlanMapProjector.fit(geo, viewW = 600.0, viewH = 400.0, paddingPx = 24.0)!!

        val w = PlanMapWorld.project(points, geo, fit)[0]
        val (rawPlanX, rawPlanY) = PlanMapProjector.project(50.0, 30.0, geo, fit)
        val (rawMeasX, rawMeasY) = PlanMapProjector.project(fixLat, fixLng, geo, fit)
        assertEquals(rawMeasX - rawPlanX, (w.measuredX!! - w.planX).toDouble(), 1e-3)
        assertEquals(rawMeasY - rawPlanY, (w.measuredY!! - w.planY).toDouble(), 1e-3)
    }

    @Test
    fun measuredWorld_pointWithoutFix_hasNoMeasuredWorld() {
        val points = PlanMapPoints.project(twoPointPlan(), emptyMap(), null)
        val world = worldFor(points)
        assertNull(world[0].measuredX)
        assertNull(world[0].measuredY)
        assertNull(world[1].measuredX)
        assertNull(world[1].measuredY)
    }
}
