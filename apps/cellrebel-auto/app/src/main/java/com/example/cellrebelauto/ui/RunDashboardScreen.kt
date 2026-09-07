package com.example.cellrebelauto.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.data.SelfHealConfig
import com.example.cellrebelauto.ui.dashboard.DashboardAction
import com.example.cellrebelauto.ui.dashboard.HealthLamp
import com.example.cellrebelauto.ui.dashboard.LampState
import com.example.cellrebelauto.ui.dashboard.PauseExplanation
import com.example.cellrebelauto.ui.dashboard.RunProgressProjection

/**
 * T7 P1.1 — the run dashboard (the app's landing surface). THIN on purpose:
 * every number, word, and lamp state arrives pre-projected in
 * [MainViewModel.RunDashboardUiState]; this composable only renders it and
 * forwards button taps to the EXISTING entries (startOrResumePlan / navigation
 * / #12 reset). No state, no business logic, no second engine.
 *
 * # 运行台首页（薄 UI）：状态卡+进度卡+健康三灯+诊断导出+自愈开关+日志终端；
 * # 全部渲染 ViewModel 投影，按钮只复用既有入口
 */
@Composable
fun RunDashboardScreen(
    state: MainViewModel.RunDashboardUiState,
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
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val logListState = rememberLazyListState()

    // # 新日志自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) logListState.animateScrollToItem(logs.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ---- 标题 + 服务指示 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("运行台", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (state.serviceConnected) Color(0xFF4CAF50) else Color(0xFFFF5722)
                        )
                )
                Text(
                    if (state.serviceConnected) " Service ON" else " Service OFF",
                    fontSize = 12.sp,
                    color = if (state.serviceConnected) Color(0xFF4CAF50) else Color(0xFFFF5722)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            state = logListState,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ---- 状态卡 ----
            item {
                StatusCard(
                    state = state,
                    onResume = onResume,
                    onOpenProviders = onOpenProviders,
                    onOpenPlan = onOpenPlan,
                    onResetPlan = onResetPlan,
                    onStop = onStop,
                )
            }

            // ---- 进度卡 ----
            item { ProgressCard(state.progress) }

            // ---- 健康三灯 ----
            item {
                HealthLampsCard(
                    lampAccessibility = state.lampAccessibility,
                    lampProvider = state.lampProvider,
                    lampVector = state.lampVector,
                    onOpenA11ySettings = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }

            // ---- 诊断导出 + 自愈开关 ----
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedButton(
                            onClick = onExportDiagnostics,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("导出诊断包（日志+状态+契约回读+DB 快照）")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        SwitchRow(
                            title = "Attempt 看门狗（僵死尝试自动收尾）",
                            checked = selfHealConfig.attemptWatchdogEnabled,
                            onCheckedChange = onSetAttemptWatchdog,
                        )
                        SwitchRow(
                            title = "坐标校验（配额入账前核对档案/计划）",
                            checked = selfHealConfig.coordinateGuardEnabled,
                            onCheckedChange = onSetCoordinateGuard,
                        )
                        SwitchRow(
                            title = "服务重连自动恢复（服务被回收后自动 Resume）",
                            checked = selfHealConfig.serviceReconnectAutoResumeEnabled,
                            onCheckedChange = onSetServiceAutoResume,
                        )
                    }
                }
            }

            // ---- 日志终端 ----
            item {
                Text("Log", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            }
            if (logs.isEmpty()) {
                item {
                    Text(
                        "暂无日志。启动计划后这里会滚动显示。",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                items(logs) { logLine ->
                    Text(
                        text = logLine,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            logLine.contains("ERROR") || logLine.contains("FAILED") -> Color(0xFFFF6B6B)
                            logLine.contains("WARN") || logLine.contains("RETRY") -> Color(0xFFFFD93D)
                            logLine.contains("===") -> Color(0xFF6BCB77)
                            else -> Color(0xFFCCCCDD)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ---- 底栏导航 ----
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenPlan, modifier = Modifier.weight(1f)) { Text("Plan") }
            OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) { Text("History") }
            OutlinedButton(onClick = onOpenProviders, modifier = Modifier.weight(1f)) { Text("Provider") }
        }
    }
}

@Composable
private fun StatusCard(
    state: MainViewModel.RunDashboardUiState,
    onResume: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPlan: () -> Unit,
    onResetPlan: () -> Unit,
    onStop: () -> Unit,
) {
    val held = state.explanation.action != DashboardAction.NONE && !state.isRunning
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                held -> MaterialTheme.colorScheme.errorContainer
                state.isRunning -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("状态", fontWeight = FontWeight.Medium)
                Text(
                    text = state.engineState.displayName,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        held -> MaterialTheme.colorScheme.error
                        state.isRunning -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(state.explanation.headline, fontWeight = FontWeight.SemiBold)
            Text(state.explanation.detail, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            // # 建议动作只复用既有入口；运行中只给 Stop，不给旁路按钮
            if (state.isRunning) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("■ Stop") }
            } else {
                ActionButton(state.explanation, onResume, onOpenProviders, onOpenPlan, onResetPlan)
            }
        }
    }
}

@Composable
private fun ActionButton(
    explanation: PauseExplanation,
    onResume: () -> Unit,
    onOpenProviders: () -> Unit,
    onOpenPlan: () -> Unit,
    onResetPlan: () -> Unit,
) {
    when (explanation.action) {
        DashboardAction.RESUME -> FilledAction(explanation.actionLabel ?: "Resume", onResume)
        DashboardAction.OPEN_PROVIDERS ->
            FilledAction(explanation.actionLabel ?: "去 Provider 管理", onOpenProviders)
        DashboardAction.OPEN_PLAN ->
            FilledAction(explanation.actionLabel ?: "去 Plan 核对", onOpenPlan)
        DashboardAction.RESET_PLAN ->
            FilledAction(explanation.actionLabel ?: "重置计划", onResetPlan)
        DashboardAction.NONE -> Unit
    }
}

@Composable
private fun FilledAction(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}

@Composable
private fun ProgressCard(progress: RunProgressProjection.ProgressSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("进度（可信口径）", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${progress.trustedDone} / ${progress.trustedTotal}",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            val throughputText = progress.throughputPerHour?.let { String.format("吞吐 ≈ %.1f 成功/小时（近 1 小时）", it) }
                ?: "吞吐 --（近 1 小时无成功）"
            Text(throughputText, fontSize = 13.sp)
            Text("剩余完成 ${progress.etaText}", fontSize = 13.sp)
            if (progress.failureClasses.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text("失败分类", fontSize = 12.sp, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    progress.failureClasses.take(4).forEach { (label, count) ->
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

@Composable
private fun HealthLampsCard(
    lampAccessibility: HealthLamp?,
    lampProvider: HealthLamp?,
    lampVector: HealthLamp?,
    onOpenA11ySettings: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("健康三灯", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(6.dp))
            LampRow("无障碍", lampAccessibility) { onOpenA11ySettings() }
            LampRow("QWY", lampProvider)
            LampRow("Vector", lampVector)
        }
    }
}

@Composable
private fun LampRow(label: String, lamp: HealthLamp?, onOpen: (() -> Unit)? = null) {
    val resolved = lamp ?: HealthLamp(LampState.GREY, "未探测")
    val color = when (resolved.state) {
        LampState.GREEN -> Color(0xFF4CAF50)
        LampState.YELLOW -> Color(0xFFFFC107)
        LampState.GREY -> Color(0xFF9E9E9E)
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
        Text(resolved.detail, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.weight(1f))
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
