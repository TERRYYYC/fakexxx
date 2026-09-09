package com.example.cellrebelauto.ui.dashboard.v2

import android.app.Activity
import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.MapTileRequestState
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.MapTileIndex
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机首启瓦片不渲染（mi14, 2026-09-09）的生命周期钉死测试。
 *
 * 根因（osmdroid 6.1.20 字节码核实）：
 * 1. `MapView(ctx)` 单参构造**总是**先建一个内部 MapTileProviderBasic（默认
 *    Mapnik 源，自带 SqlTileWriter + 每模块线程池，与真 provider 写同一个
 *    osmdroid.db），真机日志 "Using tile source: Mapnik" 即此孤儿 provider。
 *    随后 setTileProvider 把它 detach——首启 = 双 provider 栈并发初始化互踩。
 * 2. MapView 默认 `mDestroyModeOnDetach=true`：**任何**窗口 detach（Compose
 *    可以在不弃用组合的情况下 detach/reattach 同一 AndroidView；全屏 Dialog、
 *    兄弟节点移动等）都会 onDetach → provider 各模块线程池 shutdown，之后
 *    的瓦片请求被 MapTileModuleProviderBase.loadMapTileAsync 的 isShutdown
 *    检查**静默丢弃**（无日志、永不成功）——首启"无任何成功加载行"的寂静；
 *    而 detach 与在飞下载赛跑时正是真机 "mWriter being null (map shutdown?)"
 *    洪水的来源。
 *
 * 修复契约（buildTileMapView）：
 * - provider 由构造期注入，孤儿 provider 不再存在（无 Mapnik 日志）；
 * - setDestroyMode(false)：窗口级 detach 不销毁瓦片管线（reattach 后仍出结果）；
 * - 释放只由 AndroidView onRelease 的显式 `MapView.onDetach()` 一处负责
 *   （detach 后请求静默死亡是**预期**的终局语义，也在此钉死）。
 *
 * 注：瓦片请求经 `MapTileProviderArray.getMapTile` 的 cache-miss 路径触发
 * （与 TilesOverlay 绘制时的入队路径完全一致）；Robolectric 无网络 → 全模块
 * 失败 → `mapTileRequestFailed` 必达，正好作为"管线还活着"的确定性信号。
 */
@RunWith(RobolectricTestRunner::class)
class TileMapViewLifecycleTest {

    private class RecordingProvider(context: Context) : MapTileProviderBasic(context, testSource()) {
        val failed = CountDownLatch(1)
        val completed = CountDownLatch(1)

        fun awaitAnyOutcome(timeoutSeconds: Long): Boolean =
            failed.await(timeoutSeconds, TimeUnit.SECONDS) ||
                completed.await(0, TimeUnit.SECONDS)

        override fun mapTileRequestCompleted(state: MapTileRequestState?, result: Drawable?) {
            super.mapTileRequestCompleted(state, result)
            completed.countDown()
        }

        override fun mapTileRequestFailed(state: MapTileRequestState?) {
            super.mapTileRequestFailed(state)
            failed.countDown()
        }

        override fun mapTileRequestFailedExceedsMaxQueueSize(state: MapTileRequestState?) {
            super.mapTileRequestFailedExceedsMaxQueueSize(state)
            failed.countDown()
        }
    }

    private fun attachToWindow(mapView:android.view.View): Activity =
        Robolectric.setupActivity(Activity::class.java).apply { setContentView(mapView) }

    /** 与 TilesOverlay 绘制时一致的请求触发：cache-miss → 异步入队。 */
    private fun requestOneTile(provider: MapTileProviderBasic) {
        provider.getMapTile(MapTileIndex.getTileIndex(10, 536, 358))
    }

    private fun drainMainThread() {
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun `factory map view carries our tile source and never constructs the Mapnik orphan`() {
        OsmdroidBootstrap.ensure(ApplicationProvider.getApplicationContext())
        ShadowLog.reset()
        ShadowLog.setupLogging()

        val mapView = buildTileMapView(
            ApplicationProvider.getApplicationContext(),
            RecordingProvider(ApplicationProvider.getApplicationContext()),
        )
        drainMainThread()

        assertTrue(
            "tile provider must be our single injected stack",
            mapView.tileProvider.tileSource.name().startsWith("fakexxx-auto-osm"),
        )
        val logged = ShadowLog.getLogs().mapNotNull { it.msg }.joinToString("\n")
        assertFalse(
            "MapView must not silently build (and then detach) an internal Mapnik provider",
            logged.contains("Mapnik"),
        )
    }

    @Test
    fun `window detach and reattach keeps the tile pipeline answering`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OsmdroidBootstrap.ensure(context)
        val provider = RecordingProvider(context)
        val mapView = buildTileMapView(context, provider)
        val activity = attachToWindow(mapView)
        drainMainThread()

        // Compose 可以不弃用组合地 detach/reattach 同一 AndroidView
        activity.setContentView(FrameLayout(context))
        drainMainThread()
        val root = FrameLayout(context)
        activity.setContentView(root)
        root.addView(mapView)
        drainMainThread()

        requestOneTile(mapView.tileProvider as MapTileProviderBasic)

        assertTrue(
            "after window detach+reattach the provider must still deliver an outcome " +
                "(destroyMode=false); a silent drop means the pipeline was killed",
            provider.awaitAnyOutcome(timeoutSeconds = 5),
        )
    }

    @Test
    fun `explicit release detach is the single teardown and silences the pipeline`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        OsmdroidBootstrap.ensure(context)
        val provider = RecordingProvider(context)
        val mapView = buildTileMapView(context, provider)
        attachToWindow(mapView)
        drainMainThread()

        // = AndroidView onRelease 的显式终局清理
        mapView.onDetach()
        drainMainThread()

        requestOneTile(mapView.tileProvider as MapTileProviderBasic)

        assertFalse(
            "after explicit release onDetach the pipeline is torn down: requests are " +
                "silently dropped and no outcome may ever fire",
            provider.awaitAnyOutcome(timeoutSeconds = 2),
        )
    }

    private companion object {
        fun testSource() = XYTileSource(
            "fakexxx-auto-osm-test",
            OsmTileSource.MIN_ZOOM,
            OsmTileSource.MAX_ZOOM,
            256,
            ".png",
            arrayOf(OsmTileSource.TILE_SOURCE_URL),
            OsmTileSource.ATTRIBUTION,
        )
    }
}
