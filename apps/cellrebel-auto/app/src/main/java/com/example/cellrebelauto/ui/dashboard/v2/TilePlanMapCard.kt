package com.example.cellrebelauto.ui.dashboard.v2

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.cellrebelauto.ui.theme.LocalShadcnSemantic
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBase
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.MapTileRequestState
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.ScaleBarOverlay
import java.io.File

/**
 * Tile map (T-tilemap, 2026-09-08) — the REAL OSM basemap card, osmdroid-backed.
 *
 * ## 选型与生产注意（承 ui-hifi/v3 README 的"生产环境注意"，operator 拍板）
 * 瓦片引擎 = osmdroid（栅格、原生双指/平移手势、瓦片源可换）。选它而不选
 * MapLibre：本卡只需要栅格底图 + 覆盖物，体积/复杂度小一个量级；未来若要
 * 矢量/离线包，迁移路径 = 替换本文件的 AndroidView 实现，选卡决策
 * （[TileMapCardPolicy]）与降级策略原样复用。默认瓦片源 = OSM 官方标准栅格，
 * **仅低量/内部测试合规**（OSMF 使用政策）；量产换源只动 [OsmTileSource]。
 *
 * ## 权限（按需最小化）
 * osmdroid 6.1.20 的 AAR manifest 不声明任何权限，权限全部由宿主 app 负责。
 * 本 app 只补 INTERNET + ACCESS_NETWORK_STATE（用途注释见 AndroidManifest.xml）。
 * **刻意不申请 WRITE_EXTERNAL_STORAGE**：osmdroid 自 6.0 起缓存可完全走应用
 * 内部存储——[OsmdroidBootstrap.ensure] 把 basePath/瓦片缓存显式指到
 * context.cacheDir（无外部存储访问、卸载即清、系统可回收），依据 osmdroid
 * 官方迁移说明（6.0 默认内部存储后写存储权限不再是必需项）。
 *
 * ## 无闪烁降级
 * 组合序：先画 fallback 槽（= 现有抽象 Canvas 地图，尺寸随全屏态走），MapView
 * 透明叠在其上（TilesOverlay 的 loading 底色也设透明）；首次瓦片成功回调后移除
 * fallback。首次瓦片失败回调 → 上抛 sticky 失败位 → 选卡策略翻回 CANVAS 卡。
 * sticky 位生命周期：每位（每次进入运行台组合）只在「首次瓦片失败」时置位、
 * 离线→在线跳变或重进页面时清除——因此 provider 的死亡必须只发生在终局
 * onRelease（见 [buildTileMapView]），否则任何中途窗口 detach 都会以
 * "mWriter being null (map shutdown?)" 的形式把首启变成永远不出图的寂静卡
 * （2026-09-09 真机 mi14 首启修复）。
 *
 * # 瓦片地图卡：osmdroid 原生手势 + 三色标记 + 虚线顺序连线 + 比例尺 + ODbL 归属
 */
object OsmdroidBootstrap {

    @Volatile private var configured = false

    /**
     * Process-wide osmdroid Configuration. MUST run before any MapView. Cache
     * lives in cacheDir (internal storage — no storage permission, system-
     * clearable); the UA carries the real application id (OSMF policy).
     */
    fun ensure(context: Context) {
        if (configured) return
        synchronized(this) {
            if (configured) return
            val cfg = Configuration.getInstance()
            cfg.userAgentValue = OsmTileSource.userAgent(context.packageName)
            val base = File(context.cacheDir, "osmdroid")
            cfg.osmdroidBasePath = base
            cfg.osmdroidTileCache = File(base, "tiles")
            // 低量内测的合理上限（osmdroid 默认 600MB 对本 app 太重）
            cfg.tileFileSystemCacheMaxBytes = 128L * 1024 * 1024
            cfg.tileFileSystemCacheTrimBytes = 64L * 1024 * 1024
            configured = true
        }
    }
}

/** CONNECTIVITY gate — 无网即回退抽象地图（选卡策略的输入之一）。 */
object TileNetworkGate {
    fun isOnline(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.activeNetwork != null
    }.getOrDefault(false)
}

/**
 * The single tile-source construction point. Swapping to a self-hosted/
 * commercial raster = changing [OsmTileSource.TILE_SOURCE_URL] (+ UA) only.
 */
private fun buildTileSource() = XYTileSource(
    "fakexxx-auto-osm",
    OsmTileSource.MIN_ZOOM,
    OsmTileSource.MAX_ZOOM,
    256,
    ".png",
    arrayOf(OsmTileSource.TILE_SOURCE_URL),
    OsmTileSource.ATTRIBUTION,
)

/**
 * First-tile-callback health: reports the FIRST provider outcome (success or
 * failure) exactly once. Fires on osmdroid worker threads — both sinks are
 * thread-safe (a Compose snapshot write / a StateFlow write).
 *
 * Note: osmdroid's offline downgraded mode can also serve approximated tiles
 * through the completed callback; airplane mode is independently gated by
 * [TileNetworkGate], so this stays a conservative "first evidence" signal.
 */
private class ReportingTileProvider(
    context: Context,
    private val onFirstSuccess: () -> Unit,
    private val onFirstFailure: () -> Unit,
) : MapTileProviderBasic(context, buildTileSource()) {

    @Volatile private var reported = false

    private fun report(success: Boolean) {
        if (reported) return
        reported = true
        if (success) onFirstSuccess() else onFirstFailure()
    }

    override fun mapTileRequestCompleted(state: MapTileRequestState?, result: Drawable?) {
        super.mapTileRequestCompleted(state, result)
        report(success = true)
    }

    override fun mapTileRequestFailed(state: MapTileRequestState?) {
        super.mapTileRequestFailed(state)
        report(success = false)
    }

    override fun mapTileRequestFailedExceedsMaxQueueSize(state: MapTileRequestState?) {
        super.mapTileRequestFailedExceedsMaxQueueSize(state)
        report(success = false)
    }
}

/** Marker + polyline style resolved from the Compose theme into ARGB ints. */
private data class PlanOverlayStyle(
    val doneFill: Int,
    val activeFill: Int,
    val activeDot: Int,
    val pendingFill: Int,
    val pendingStroke: Int,
    val lineHalo: Int,
    val lineDash: Int,
    val density: Float,
)

/**
 * The plan overlay: execution-order dashed polyline (surface halo + primary
 * dash, the v3 two-pass) and the three-state markers — done=green with a
 * surface check, active=blue + pulsing ring + center dot, pending=grey hollow.
 * ONE osmdroid Overlay so everything pans/zooms with the native gestures;
 * no custom gesture handling anywhere (osmdroid 原生手势，禁自定义).
 */
private class PlanPointsOverlay : Overlay() {

    data class Entry(val latitude: Double, val longitude: Double, val state: MapPointState)

    var entries: List<Entry> = emptyList()
    var style: PlanOverlayStyle = PlanOverlayStyle(0, 0, 0, 0, 0, 0, 0, 1f)
    /** 0..1 breathing phase, driven by the Compose-side loop while an ACTIVE point exists. */
    @Volatile var pulsePhase: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val point = android.graphics.Point()

    override fun draw(canvas: Canvas, projection: Projection) {
        if (entries.isEmpty()) return
        val d = style.density
        val pts = entries.map {
            projection.toPixels(GeoPoint(it.latitude, it.longitude), point)
            Pair(point.x.toFloat(), point.y.toFloat())
        }
        // ---- 顺序连线：表面色晕 + 主题色虚线（v3 双 pass） ----
        if (pts.size >= 2) {
            path.reset()
            path.moveTo(pts[0].first, pts[0].second)
            for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
            paint.style = Paint.Style.STROKE
            paint.pathEffect = null
            paint.color = style.lineHalo
            paint.strokeWidth = 4f * d
            canvas.drawPath(path, paint)
            paint.color = style.lineDash
            paint.strokeWidth = 2f * d
            paint.pathEffect = DashPathEffect(floatArrayOf(5f * d, 4f * d), 0f)
            canvas.drawPath(path, paint)
            paint.pathEffect = null
        }
        // ---- 三色标记（完成绿✓ / 进行中蓝+脉冲 / 待完成灰空心） ----
        entries.forEachIndexed { i, e ->
            val (x, y) = pts[i]
            when (e.state) {
                MapPointState.DONE -> {
                    val r = 5f * d
                    paint.style = Paint.Style.FILL
                    paint.color = style.doneFill
                    canvas.drawCircle(x, y, r, paint)
                    // surface 描边 + ✓ 勾，v3 同构
                    paint.style = Paint.Style.STROKE
                    paint.color = style.pendingFill
                    paint.strokeWidth = 1.5f * d
                    canvas.drawCircle(x, y, r, paint)
                    paint.strokeWidth = 1.6f * d
                    val check = Path().apply {
                        moveTo(x - 0.42f * r, y + 0.02f * r)
                        lineTo(x - 0.12f * r, y + 0.32f * r)
                        lineTo(x + 0.45f * r, y - 0.38f * r)
                    }
                    canvas.drawPath(check, paint)
                }
                MapPointState.ACTIVE -> {
                    val r = 5.5f * d
                    paint.style = Paint.Style.FILL
                    paint.color = style.activeFill
                    paint.alpha = (255 * 0.35f * (1f - pulsePhase)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(x, y, r + 8f * d * pulsePhase, paint)
                    paint.alpha = 255
                    canvas.drawCircle(x, y, r, paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = style.pendingFill
                    paint.strokeWidth = 1.5f * d
                    canvas.drawCircle(x, y, r, paint)
                    paint.style = Paint.Style.FILL
                    paint.color = style.activeDot
                    canvas.drawCircle(x, y, 1.8f * d, paint)
                }
                MapPointState.PENDING -> {
                    paint.style = Paint.Style.FILL
                    paint.color = style.pendingFill
                    canvas.drawCircle(x, y, 4.5f * d, paint)
                    paint.style = Paint.Style.STROKE
                    paint.color = style.pendingStroke
                    paint.strokeWidth = 2f * d
                    canvas.drawCircle(x, y, 4.5f * d, paint)
                }
            }
        }
    }
}

private fun currentEntries(points: List<PlanMapPoints.MapPoint>): List<PlanPointsOverlay.Entry> =
    points.map { PlanPointsOverlay.Entry(it.latitude, it.longitude, it.state) }

/**
 * Single construction point of the card's MapView + its tile provider wiring.
 * Isolated from the @Composable so the lifecycle is Robolectric-testable
 * (TileMapViewLifecycleTest drives attach/detach/request against THIS code).
 *
 * ## 生命周期根因（真机 mi14 首启瓦片不渲染，2026-09-09；osmdroid 6.1.20 字节码核实）
 * 1. **孤儿 provider**：`MapView(ctx)` 单参构造总是先自建一个内部
 *    `MapTileProviderBasic`（默认 Mapnik 源，自带 SqlTileWriter + 每模块线程池，
 *    与真 provider 指向同一个 osmdroid.db），真机日志 "Using tile source: Mapnik"
 *    即是它。随后 setTileProvider 再把它 detach——首启即双 provider 栈并发
 *    初始化 + 互踩。现改为构造期注入唯一 provider（走
 *    `MapView(Context, MapTileProviderBase, Handler, AttributeSet)`），孤儿与
 *    setTileProvider 的 detach-clear-swap 全部消失。
 * 2. **窗口级 detach 即永久死亡**：MapView 默认 `mDestroyModeOnDetach=true`，
 *    任何 onDetachedFromWindow 都会级联 `onDetach()` → tile provider 各模块
 *    线程池 shutdown；之后的请求被 `MapTileModuleProviderBase.loadMapTileAsync`
 *    的 isShutdown 检查**静默丢弃**（无日志、永不成功）。而 Compose 完全可能
 *    在不弃用组合的情况下 detach/reattach 同一 AndroidView（兄弟节点移动、
 *    Dialog 窗口等）。`setDestroyMode(false)` 把销毁责任收归一处：只有
 *    AndroidView onRelease 里的显式 `MapView.onDetach()`（终局清理）。
 *    detach 与在飞下载赛跑时，正是真机 "mWriter being null (map shutdown?)"
 *    洪水的来源——非终局的 provider 死亡由此禁绝。
 */
internal fun buildTileMapView(context: Context, provider: MapTileProviderBase): MapView =
    MapView(context, provider, null, null).apply {
        setDestroyMode(false)
        setMultiTouchControls(true)
        setBuiltInZoomControls(false)
        setMinZoomLevel(OsmTileSource.MIN_ZOOM.toDouble())
        setMaxZoomLevel(OsmTileSource.MAX_ZOOM.toDouble())
        // 透明底：瓦片未到前透出 fallback（先 canvas 后瓦片，无闪烁）
        setBackgroundColor(Color.TRANSPARENT)
        overlayManager.tilesOverlay?.let {
            it.loadingBackgroundColor = Color.TRANSPARENT
            it.loadingLineColor = Color.TRANSPARENT
        }
    }

/** Prototype fitZoom / mapFSReset semantics applied to the live osmdroid view. */
private fun fitInsideBounds(
    mv: MapView,
    points: List<WebMercator.GeoPoint>,
    viewW: Double, viewH: Double,
    padX: Double, padY: Double,
    fullscreen: Boolean,
) {
    if (points.isEmpty() || viewW <= 0.0 || viewH <= 0.0) return
    val z = if (fullscreen) {
        // v3 mapFSReset：z = min(cap, fitZoom(60,80)+1)
        WebMercator.fitZoomRaised(points, viewW, viewH, padX, padY, 16)
    } else {
        // v3 planMap：fitZoom(40,24,16)
        WebMercator.fitZoom(points, viewW, viewH, padX, padY, 16)
    }
    mv.controller.setZoom(z.toDouble())
    WebMercator.centerOf(points)?.let {
        mv.controller.setCenter(GeoPoint(it.latitude, it.longitude))
    }
}

/**
 * The osmdroid-backed tile map. [fallback] renders BENEATH the MapView until
 * the first tile success (无闪烁: 先出 canvas 后叠瓦片成功再换); a first tile
 * FAILURE is reported via [onTileFailure] so the caller's policy flips back to
 * the canvas card. [fullscreen] uses the prototype's raised fit (pads 60/80dp,
 * +1 level) and shows ＋/－/⌂ zoom buttons (buttons, NOT custom gestures).
 */
@Composable
fun TilePlanMapCard(
    points: List<PlanMapPoints.MapPoint>,
    modifier: Modifier = Modifier,
    fullscreen: Boolean = false,
    fallback: (@Composable () -> Unit)? = null,
    onTileFailure: () -> Unit = {},
) {
    val context = LocalContext.current
    OsmdroidBootstrap.ensure(context)

    var tilesReady by remember { mutableStateOf(false) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    val semantic = LocalShadcnSemantic.current
    val outlineColor = MaterialTheme.colorScheme.outline
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val style = remember(semantic, outlineColor, surfaceColor) {
        PlanOverlayStyle(
            doneFill = semantic.green.toArgb(),
            activeFill = semantic.blue.toArgb(),
            activeDot = Color.WHITE,
            pendingFill = surfaceColor.toArgb(),
            pendingStroke = outlineColor.toArgb(),
            lineHalo = surfaceColor.toArgb(),
            lineDash = semantic.blue.toArgb(),
            density = context.resources.displayMetrics.density,
        )
    }

    val geoPoints = remember(points) {
        points.map { WebMercator.GeoPoint(it.latitude, it.longitude) }
    }
    val boundsKey = remember(geoPoints) {
        WebMercator.centerOf(geoPoints)?.let {
            String.format(java.util.Locale.US, "%.6f,%.6f", it.latitude, it.longitude)
        }
    }
    val hasActive = remember(points) { points.any { it.state == MapPointState.ACTIVE } }

    val overlay = remember { PlanPointsOverlay() }
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }

    // 脉冲驱动：仅当存在进行中点位时以 ~30fps invalidate（与 canvas 卡呼吸同节奏）
    LaunchedEffect(hasActive) {
        while (hasActive) {
            overlay.pulsePhase = (overlay.pulsePhase + 0.05f) % 1f
            mapViewRef.value?.postInvalidate()
            delay(33)
        }
    }

    Box(modifier = modifier.onSizeChanged { viewSize = it }) {
        // ---- fallback underlay until the first tiles prove themselves -------
        if (!tilesReady && fallback != null) {
            Box(modifier = Modifier.fillMaxSize()) { fallback() }
        }

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                OsmdroidBootstrap.ensure(ctx)
                buildTileMapView(
                    context = ctx,
                    provider = ReportingTileProvider(
                        ctx,
                        onFirstSuccess = { tilesReady = true },
                        onFirstFailure = onTileFailure,
                    ),
                ).apply {
                    // osmdroid 原生双指缩放/平移/双击放大；本卡不写任何自定义手势
                    // 比例尺（osmdroid 内置，metric，左下）
                    overlays.add(
                        ScaleBarOverlay(this).apply {
                            setUnitsOfMeasure(ScaleBarOverlay.UnitsOfMeasure.metric)
                            setAlignBottom(true)
                            setAlignRight(false)
                        }
                    )
                    overlays.add(overlay)
                    // 初始视野 = 计划包围盒 fit（原型 fitZoom 语义；全屏 = +1 级）
                    val padX = if (fullscreen) 60f else 40f
                    val padY = if (fullscreen) 80f else 24f
                    addOnFirstLayoutListener { v, _, _, _, _ ->
                        overlay.entries = currentEntries(points)
                        overlay.style = style
                        fitInsideBounds(
                            this, geoPoints,
                            viewW = v.width.toDouble(), viewH = v.height.toDouble(),
                            padX = padX.toDouble() * style.density,
                            padY = padY.toDouble() * style.density,
                            fullscreen = fullscreen,
                        )
                    }
                }.also { mapViewRef.value = it }
            },
            update = { mv ->
                overlay.entries = currentEntries(points)
                overlay.style = style
                // 计划内容（中心）变化才重新 fit；否则手势视口说了算
                if (boundsKey != mv.tag) {
                    mv.tag = boundsKey
                    val w = viewSize
                    if (w.width > 0 && w.height > 0) {
                        val padX = if (fullscreen) 60f else 40f
                        val padY = if (fullscreen) 80f else 24f
                        fitInsideBounds(
                            mv, geoPoints,
                            viewW = w.width.toDouble(), viewH = w.height.toDouble(),
                            padX = padX.toDouble() * style.density,
                            padY = padY.toDouble() * style.density,
                            fullscreen = fullscreen,
                        )
                    }
                }
            },
            onRelease = { mv ->
                mapViewRef.value = null
                mv.onDetach()
            },
        )

        // ---- overlays over the map ------------------------------------------
        // 右下常驻归属（ODbL 强制，常量由 OsmTileSourceTest 钉死）
        Text(
            OsmTileSource.ATTRIBUTION,
            fontSize = 8.sp,
            color = onSurfaceColor,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .clip(RoundedCornerShape(topStart = 6.dp))
                .background(surfaceColor.copy(alpha = 0.78f))
                .padding(horizontal = 5.dp, vertical = 1.dp),
        )
        // 左上图例（与 canvas 卡同语义）
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(surfaceColor.copy(alpha = 0.78f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDotSmall(semantic.green, filled = true)
            Text("完成 ${points.count { it.state == MapPointState.DONE }}", fontSize = 9.sp, color = onSurfaceColor)
            LegendDotSmall(semantic.blue, filled = true)
            Text("进行中 ${points.count { it.state == MapPointState.ACTIVE }}", fontSize = 9.sp, color = onSurfaceColor)
            LegendDotSmall(semantic.grayDot, filled = false)
            Text("待完成 ${points.count { it.state == MapPointState.PENDING }}", fontSize = 9.sp, color = onSurfaceColor)
        }
        // 全屏态 ＋/－/⌂（按钮而非手势；⌂ = fit 还原，v3 的 zc 列）
        if (fullscreen) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(surfaceColor.copy(alpha = 0.85f)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ZoomControlButton("＋") { mapViewRef.value?.controller?.zoomIn() }
                ZoomControlButton("－") { mapViewRef.value?.controller?.zoomOut() }
                ZoomControlButton("⌂") {
                    mapViewRef.value?.let { mv ->
                        val padX = if (fullscreen) 60f else 40f
                        val padY = if (fullscreen) 80f else 24f
                        fitInsideBounds(
                            mv, geoPoints,
                            viewW = mv.width.toDouble(), viewH = mv.height.toDouble(),
                            padX = padX.toDouble() * style.density,
                            padY = padY.toDouble() * style.density,
                            fullscreen = fullscreen,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDotSmall(color: androidx.compose.ui.graphics.Color, filled: Boolean) {
    if (filled) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(color))
    } else {
        Box(
            modifier = Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = 0.25f))
        )
    }
}

@Composable
private fun ZoomControlButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(4.dp)
            .size(width = 34.dp, height = 30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 14.sp)
    }
}
