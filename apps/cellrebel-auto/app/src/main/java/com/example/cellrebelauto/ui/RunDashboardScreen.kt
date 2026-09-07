package com.example.cellrebelauto.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.example.cellrebelauto.data.SelfHealConfig
import com.example.cellrebelauto.ui.dashboard.DashboardAction
import com.example.cellrebelauto.ui.dashboard.HealthLamp
import com.example.cellrebelauto.ui.dashboard.LampState
import com.example.cellrebelauto.ui.dashboard.v2.CiBadge
import com.example.cellrebelauto.ui.dashboard.v2.CurrentPointView
import com.example.cellrebelauto.ui.dashboard.v2.MapPointState
import com.example.cellrebelauto.ui.dashboard.v2.MapTransform
import com.example.cellrebelauto.ui.dashboard.v2.MetricKey
import com.example.cellrebelauto.ui.dashboard.v2.MetricPillFormatter
import com.example.cellrebelauto.ui.dashboard.v2.PlanMapPoints
import com.example.cellrebelauto.ui.dashboard.v2.PlanMapProjector
import com.example.cellrebelauto.ui.dashboard.v2.RunStatusBarProjection
import com.example.cellrebelauto.ui.theme.LocalShadcnSemantic
import com.example.cellrebelauto.ui.theme.ShadcnCard

/**
 * T7v2 §A1-v2 — the run dashboard, MINIMAL surface (the operator's verdict:
 * the user reads ONE line and presses ONE button; everything else collapses).
 *
 * Top-down: ① status bar (word + primary button) → ② CI hero (device-true
 * reading + honest badge) → ③ embedded zoomable plan map → ④ configurable
 * metric pills → ⑤ 4px progress strip → ⑥ log drawer (v1's lamps, failure
 * classes, raw errors, self-heal switches, diagnostics export, terminal —
 * NOTHING was deleted, only demoted behind the drawer).
 *
 * STILL THIN on purpose: every number/word is pre-projected in
 * [MainViewModel.RunDashboardUiState] or the v2 pure projections; every button
 * forwards to an EXISTING entry (startOrResumePlan / stopAutomation / #12
 * reset / navigation / diagnostic bundle). No state, no second engine.
 *
 * # 运行台 v2：一行状态+主按钮；CI hero（值=设备真实读数）；内嵌可缩放地图；
 * # 指标 pill；log 抽屉收纳 v1 全部详情。按钮只复用既有入口。
 */
@Composable
fun RunDashboardScreen(
    state: MainViewModel.RunDashboardUiState,
    metricSelection: List<MetricKey>,
    logs: List<String>,
    selfHealConfig: SelfHealConfig,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenProviders: () -> Unit,
    onResetPlan: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onSetAttemptWatchdog: (Boolean) -> Unit,
    onSetCoordinateGuard: (Boolean) -> Unit,
    onSetServiceAutoResume: (Boolean) -> Unit,
    onSetMetricSelection: (List<MetricKey>) -> Unit,
    resumeOutcome: MainViewModel.ResumeOutcome?,
    onConsumeResumeOutcome: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var drawerExpanded by remember { mutableStateOf(false) }
    var metricsSheetOpen by remember { mutableStateOf(false) }

    // # 一键恢复的成败走 snackbar；失败时主按钮由投影切为「查看日志」
    LaunchedEffect(resumeOutcome) {
        val outcome = resumeOutcome ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(
            if (outcome.succeeded) "已恢复运行——进度从可信账本继续"
            else "恢复失败：${outcome.reason ?: "未知原因"}（详情见运行日志）"
        )
        onConsumeResumeOutcome()
    }

    val bar = RunStatusBarProjection.project(
        engineState = state.engineState,
        isRunning = state.isRunning,
        trustedDone = state.progress.trustedDone,
        trustedTotal = state.progress.trustedTotal,
        resumeFailure = state.resumeFailure,
        hasPlan = state.hasPlan,
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

            // ---- ① 状态条：一行状态词 + 单主按钮（极简铁律） -------------------
            StatusBarRow(
                bar = bar,
                onResume = onResume,
                onStop = onStop,
                onExport = onExportDiagnostics,
                onOpenLog = { drawerExpanded = true },
            )

            Spacer(modifier = Modifier.height(10.dp))

            // ---- ② CI hero：当前小区 · 设备真实读数 · 来源徽标 -----------------
            ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                CiHeroContent(state.ciHero)
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ---- ③ 内嵌地图卡（可缩放，占屏约 1/3） ----------------------------
            ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                PlanMapCard(
                    points = state.mapPoints,
                    currentPoint = state.currentPoint,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ---- ④ 关注指标行（1..3 个 pill + ⚙ 配置） -------------------------
            MetricPillsRow(
                progress = state.progress,
                currentPoint = state.currentPoint,
                ciHero = state.ciHero,
                selection = metricSelection,
                onOpenConfig = { metricsSheetOpen = true },
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- ⑤ 进度细条（4px，绿/总灰） ------------------------------------
            ThinProgressStrip(state.progress.trustedDone, state.progress.trustedTotal)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- ⑥ log 抽屉（默认收起；v1 的详情全部在这里） -------------------
            LogDrawer(
                expanded = drawerExpanded,
                onToggle = { drawerExpanded = !drawerExpanded },
                state = state,
                logs = logs,
                selfHealConfig = selfHealConfig,
                onOpenPlan = onOpenPlan,
                onOpenProviders = onOpenProviders,
                onResetPlan = onResetPlan,
                onExportDiagnostics = onExportDiagnostics,
                onSetAttemptWatchdog = onSetAttemptWatchdog,
                onSetCoordinateGuard = onSetCoordinateGuard,
                onSetServiceAutoResume = onSetServiceAutoResume,
                onOpenA11ySettings = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
            )

            Spacer(modifier = Modifier.weight(1f))

            // ---- 底栏导航（不变） ----------------------------------------------
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenPlan, modifier = Modifier.weight(1f)) { Text("Plan") }
                OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) { Text("History") }
                OutlinedButton(onClick = onOpenProviders, modifier = Modifier.weight(1f)) { Text("Provider") }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
        )
    }

    if (metricsSheetOpen) {
        MetricsConfigSheet(
            selection = metricSelection,
            onToggle = { key ->
                val next = if (key in metricSelection) {
                    metricSelection - key
                } else {
                    metricSelection + key
                }
                onSetMetricSelection(MetricPillFormatter.sanitize(next))
            },
            onDismiss = { metricsSheetOpen = false },
        )
    }
}

// ---- ① 状态条 ------------------------------------------------------------------

@Composable
private fun StatusBarRow(
    bar: RunStatusBarProjection.Model,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    onOpenLog: () -> Unit,
) {
    val semantic = LocalShadcnSemantic.current
    val dotColor = when (bar.tone) {
        RunStatusBarProjection.Tone.RUNNING -> semantic.blue
        RunStatusBarProjection.Tone.HELD -> semantic.amber
        RunStatusBarProjection.Tone.DONE -> semantic.green
        RunStatusBarProjection.Tone.IDLE -> semantic.grayDot
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(bar.statusWord, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            bar.subLine?.let {
                Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        when (bar.primary) {
            RunStatusBarProjection.Primary.RESUME ->
                Button(onClick = onResume) { Text(bar.primaryLabel) }
            RunStatusBarProjection.Primary.STOP ->
                OutlinedButton(onClick = onStop) { Text(bar.primaryLabel) }
            RunStatusBarProjection.Primary.EXPORT ->
                OutlinedButton(onClick = onExport) { Text(bar.primaryLabel) }
            RunStatusBarProjection.Primary.OPEN_LOG ->
                OutlinedButton(onClick = onOpenLog) { Text(bar.primaryLabel) }
            RunStatusBarProjection.Primary.NONE -> Unit
        }
    }
}

// ---- ② CI hero -----------------------------------------------------------------

@Composable
private fun CiHeroContent(ciHero: com.example.cellrebelauto.ui.dashboard.v2.CiHeroView) {
    val semantic = LocalShadcnSemantic.current
    val (badgeLabel, badgeColor) = when (ciHero.badge) {
        CiBadge.INJECTED -> "注入" to semantic.green
        CiBadge.PASSTHROUGH_REAL -> "透传·真实" to semantic.blue
        CiBadge.DEVICE_READING -> "设备读数" to semantic.grayDot
        null -> "无读数" to semantic.grayDot
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前小区 · 来源",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(badgeColor.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(badgeLabel, fontSize = 11.sp, color = badgeColor, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        // 大号等宽数字：值永远是设备真实读数（无读数显示 "--"，绝不编造）
        Text(
            ciHero.ciText,
            fontSize = 30.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
        )
        val r = ciHero.reading
        val sub = if (r == null) {
            "等待蜂窝读数…"
        } else {
            listOfNotNull(
                r.mcc?.let { "mcc $it" },
                r.mnc?.let { "mnc $it" },
                r.tac?.let { "tac $it" },
                r.pci?.let { "pci $it" },
                r.rsrpDbm?.let { "rsrp $it dBm" },
            ).joinToString(" · ").ifEmpty { "（框架未给出明细字段）" } + " · ${r.rat}"
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            sub,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// ---- ③ 内嵌地图卡（可缩放） ------------------------------------------------------

@Composable
private fun PlanMapCard(
    points: List<PlanMapPoints.MapPoint>,
    currentPoint: CurrentPointView?,
) {
    val semantic = LocalShadcnSemantic.current
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var transform by remember { mutableStateOf<MapTransform.Transform?>(null) }

    val geo = remember(points) {
        PlanMapProjector.boundsOf(points.map { PlanMapProjector.GeoPoint(it.latitude, it.longitude) })
    }
    val pad = 24.0
    val fit = remember(geo, viewSize) {
        geo?.let {
            PlanMapProjector.fit(it, viewSize.width.toDouble(), viewSize.height.toDouble(), pad)
        }
    }
    // 世界坐标 = 内容包围盒左上角为原点的像素坐标；内容尺寸用于平移钳制。
    val projected = remember(points, geo, fit) {
        if (geo == null || fit == null || points.isEmpty()) {
            emptyList()
        } else {
            points.map {
                val p = PlanMapProjector.project(it.latitude, it.longitude, geo, fit)
                p.first.toFloat() to p.second.toFloat()
            }
        }
    }
    val minX = projected.minOfOrNull { it.first } ?: 0f
    val minY = projected.minOfOrNull { it.second } ?: 0f
    val contentW = ((projected.maxOfOrNull { it.first } ?: 0f) - minX).coerceAtLeast(1f)
    val contentH = ((projected.maxOfOrNull { it.second } ?: 0f) - minY).coerceAtLeast(1f)
    val world = remember(projected, minX, minY) {
        projected.map { (it.first - minX) to (it.second - minY) }
    }

    fun initial(): MapTransform.Transform =
        MapTransform.pan(
            MapTransform.Transform(),
            dx = 0f, dy = 0f,
            contentW = contentW, contentH = contentH,
            viewW = viewSize.width.toFloat(), viewH = viewSize.height.toFloat(),
        )

    val outlineColor = MaterialTheme.colorScheme.outline
    val legendBg = MaterialTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .onSizeChanged { viewSize = it }
    ) {
        if (points.isEmpty()) {
            Text(
                "导入计划后显示点位",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val breathing = rememberInfiniteTransition(label = "current-point")
            val breath by breathing.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
                label = "breath",
            )
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(contentW, contentH, viewSize) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            if (viewSize == IntSize.Zero) return@detectTransformGestures
                            val vw = viewSize.width.toFloat()
                            val vh = viewSize.height.toFloat()
                            val start = transform ?: initial()
                            // 双指缩放（围绕质心）+ 单指/双指平移，全部经纯函数钳制
                            val zoomed = MapTransform.pinch(start, centroid.x, centroid.y, zoom)
                            transform = MapTransform.pan(
                                zoomed, pan.x, pan.y,
                                contentW = contentW, contentH = contentH, viewW = vw, viewH = vh,
                            )
                        }
                    }
            ) {
                val t = transform ?: initial()
                val vw = size.width
                val vh = size.height
                // 极简底图：muted 底 + 网格
                                val step = 24.dp.toPx()
                var gx = step
                while (gx < vw) {
                    drawLine(outlineColor.copy(alpha = 0.5f), Offset(gx, 0f), Offset(gx, vh), 1f)
                    gx += step
                }
                var gy = step
                while (gy < vh) {
                    drawLine(outlineColor.copy(alpha = 0.5f), Offset(0f, gy), Offset(vw, gy), 1f)
                    gy += step
                }
                // 顺序细线连接
                if (world.size >= 2) {
                    for (i in 0 until world.size - 1) {
                        val a = MapTransform.toScreen(t, world[i].first, world[i].second)
                        val b = MapTransform.toScreen(t, world[i + 1].first, world[i + 1].second)
                        drawLine(
                            outlineColor, Offset(a.first, a.second), Offset(b.first, b.second),
                            strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round,
                        )
                    }
                }
                // 点位三色：完成=绿实心、进行中=蓝实心+呼吸环、待完成=灰空心
                world.forEachIndexed { index, (wx, wy) ->
                    val (sx, sy) = MapTransform.toScreen(t, wx, wy)
                    if (sx < -40f || sy < -40f || sx > vw + 40f || sy > vh + 40f) return@forEachIndexed
                    val center = Offset(sx, sy)
                    val radius = 5.dp.toPx()
                    when (points[index].state) {
                        MapPointState.DONE -> drawCircle(semantic.green, radius, center)
                        MapPointState.ACTIVE -> {
                            drawCircle(
                                semantic.blue.copy(alpha = 0.35f * (1 - breath)),
                                radius + 8.dp.toPx() * breath,
                                center,
                            )
                            drawCircle(semantic.blue, radius, center)
                        }
                        MapPointState.PENDING ->
                            drawCircle(
                                semantic.grayDot, radius, center,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                    }
                }
            }
        }
        // 右下角微型图例
        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(legendBg.copy(alpha = 0.85f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot(semantic.green, filled = true); Text("完成", fontSize = 10.sp)
            LegendDot(semantic.blue, filled = true); Text("进行中", fontSize = 10.sp)
            LegendDot(semantic.grayDot, filled = false); Text("待完成", fontSize = 10.sp)
        }
        // 当前点坐标小字（左下）
        currentPoint?.let {
            Text(
                String.format(java.util.Locale.US, "%.6f, %.6f", it.latitude, it.longitude),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, filled: Boolean) {
    if (filled) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
    } else {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(1.5.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ---- ④ 指标 pill 行 + 配置 sheet ------------------------------------------------

@Composable
private fun MetricPillsRow(
    progress: com.example.cellrebelauto.ui.dashboard.RunProgressProjection.ProgressSnapshot,
    currentPoint: CurrentPointView?,
    ciHero: com.example.cellrebelauto.ui.dashboard.v2.CiHeroView,
    selection: List<MetricKey>,
    onOpenConfig: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MetricPillFormatter.sanitize(selection).forEach { key ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    text = pillTitle(key) + " " + MetricPillFormatter.render(
                        key, progress, currentPoint, ciHero,
                    ),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onOpenConfig,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) { Text("⚙", fontSize = 13.sp) }
    }
}

@Composable
private fun pillTitle(key: MetricKey): String = key.label

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetricsConfigSheet(
    selection: List<MetricKey>,
    onToggle: (MetricKey) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("关注指标（1–3 个）", fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "全部为真值：进度=可信配额；吞吐=近 1 小时窗口折算；ETA=剩余/吞吐；坐标=当前计划行；小区=设备读数+如实徽标",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            MetricKey.values().forEach { key ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = key in selection, onCheckedChange = { onToggle(key) })
                    Text(key.label)
                }
            }
        }
    }
}

// ---- ⑤ 进度细条 ----------------------------------------------------------------

@Composable
private fun ThinProgressStrip(done: Int, total: Int) {
    val semantic = LocalShadcnSemantic.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(semantic.grayDot)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(semantic.green)
        )
    }
}

// ---- ⑥ log 抽屉（v1 详情的收纳处） ----------------------------------------------

@Composable
private fun LogDrawer(
    expanded: Boolean,
    onToggle: () -> Unit,
    state: MainViewModel.RunDashboardUiState,
    logs: List<String>,
    selfHealConfig: SelfHealConfig,
    onOpenPlan: () -> Unit,
    onOpenProviders: () -> Unit,
    onResetPlan: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onSetAttemptWatchdog: (Boolean) -> Unit,
    onSetCoordinateGuard: (Boolean) -> Unit,
    onSetServiceAutoResume: (Boolean) -> Unit,
    onOpenA11ySettings: () -> Unit,
) {
    val logListState = rememberLazyListState()
    // # 新日志自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (expanded) "运行日志 ▾" else "运行日志 ▸",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                "${logs.size} 行",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            LazyColumn(
                state = logListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(430.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // —— 人话原因（v1 状态卡） ——
                item {
                    ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("原因", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(state.explanation.headline, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                state.explanation.detail,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            // # 具体原因对应的既有动作按钮（重置/去 Provider/去 Plan），只复用既有入口；
                            // # RESUME 不重复出现——它就是状态条上的主按钮「重启恢复」
                            when (state.explanation.action) {
                                DashboardAction.RESUME -> Unit
                                DashboardAction.OPEN_PROVIDERS ->
                                    OutlinedButton(onClick = onOpenProviders) {
                                        Text(state.explanation.actionLabel ?: "去 Provider 管理")
                                    }
                                DashboardAction.OPEN_PLAN ->
                                    OutlinedButton(onClick = onOpenPlan) {
                                        Text(state.explanation.actionLabel ?: "去 Plan 核对")
                                    }
                                DashboardAction.RESET_PLAN ->
                                    OutlinedButton(onClick = onResetPlan) {
                                        Text(state.explanation.actionLabel ?: "重置计划")
                                    }
                                DashboardAction.NONE -> Unit
                            }
                        }
                    }
                }
                // —— 原始报错（最近 ERROR 行） ——
                val latestError = logs.lastOrNull {
                    it.contains("ERROR") || it.contains("EXHAUSTED") ||
                        it.contains("ANCHOR_MISMATCH") || it.contains("trust gate rejected")
                }
                if (latestError != null) {
                    item {
                        ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("原始报错", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    latestError,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                // —— 失败分类（v1 进度卡） ——
                if (state.progress.failureClasses.isNotEmpty()) {
                    item {
                        ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text("失败分类", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    state.progress.failureClasses.take(4).forEach { (label, count) ->
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.errorContainer)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text("$label ×$count", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // —— 健康三灯（v1） ——
                item {
                    ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("健康三灯", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LampRow("无障碍", state.lampAccessibility, onOpen = onOpenA11ySettings)
                            LampRow("QWY", state.lampProvider)
                            LampRow("Vector", state.lampVector)
                        }
                    }
                }
                // —— 自愈三开关（v1；同一 DataStore，引擎实时读取） ——
                item {
                    ShadcnCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            SwitchRow("Attempt 看门狗（僵死尝试自动收尾）", selfHealConfig.attemptWatchdogEnabled, onSetAttemptWatchdog)
                            SwitchRow("坐标校验（配额入账前核对档案/计划）", selfHealConfig.coordinateGuardEnabled, onSetCoordinateGuard)
                            SwitchRow("服务重连自动恢复（服务被回收后自动 Resume）", selfHealConfig.serviceReconnectAutoResumeEnabled, onSetServiceAutoResume)
                        }
                    }
                }
                // —— 诊断导出（v1 完整保留） ——
                item {
                    OutlinedButton(onClick = onExportDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Text("导出诊断包（日志+状态+契约回读+DB 快照）")
                    }
                }
                // —— 日志终端（v1 原样） ——
                item { Text("Log", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (logs.isEmpty()) {
                    item {
                        Text(
                            "暂无日志。启动计划后这里会滚动显示。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                } else {
                    items(logs) { logLine ->
                        Text(
                            text = logLine,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                logLine.contains("ERROR") || logLine.contains("FAILED") -> MaterialTheme.colorScheme.error
                                logLine.contains("WARN") || logLine.contains("RETRY") -> LocalShadcnSemantic.current.amber
                                logLine.contains("===") -> LocalShadcnSemantic.current.green
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---- v1 组件原样保留（只被抽屉引用） --------------------------------------------

@Composable
private fun LampRow(label: String, lamp: HealthLamp?, onOpen: (() -> Unit)? = null) {
    val semantic = LocalShadcnSemantic.current
    val resolved = lamp ?: HealthLamp(LampState.GREY, "未探测")
    val color = when (resolved.state) {
        LampState.GREEN -> semantic.green
        LampState.YELLOW -> semantic.amber
        LampState.GREY -> semantic.grayDot
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(64.dp))
        Text(resolved.detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (onOpen != null) {
            OutlinedButton(onClick = onOpen, enabled = resolved.state != LampState.GREEN) {
                Text("设置", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---- 小工具 --------------------------------------------------------------------
