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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.automation.CooldownInfo
import com.example.cellrebelauto.automation.EngineTaskSnapshot
import com.example.cellrebelauto.automation.LastFailureInfo
import com.example.cellrebelauto.model.AutomationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Attempt pipeline stages for the Run page stepper (wireframe v2.1 §1.2).
 * COOLDOWN is NOT a pipeline stage — it is a scheduler phase shown on its own
 * card after the attempt reaches a terminal state.
 * # attempt 流水线阶段：cooldown 不属于流水线，是 scheduler 独立阶段
 */
private val PIPELINE_STAGES = listOf(
    "Setting GPS", "GPS settling", "Testing", "Processing"
)

// # AutomationState → 流水线阶段下标；4=成功终态，5=失败终态，-1=不在流水线
private fun stageIndexOf(state: AutomationState): Int = when (state) {
    AutomationState.LAUNCHING_FAKE_GPS,
    AutomationState.STOPPING_OLD_GPS,
    AutomationState.SETTING_LOCATION,
    AutomationState.CONFIRMING_LOCATION,
    AutomationState.STARTING_FAKE_GPS -> 0
    AutomationState.WAITING_INTERVAL -> 1
    AutomationState.LAUNCHING_CELLREBEL,
    AutomationState.NAVIGATING_TO_TEST,
    AutomationState.STARTING_TEST,
    AutomationState.WAITING_FOR_RESULT -> 2
    AutomationState.COLLECTING_RESULT,
    AutomationState.PROCESSING -> 3
    AutomationState.SUCCEEDED,
    AutomationState.DONE -> 4
    AutomationState.FAILED,
    AutomationState.ERROR -> 5
    else -> -1 // # IDLE / COOLDOWN 不在 attempt 流水线内
}

/**
 * Issue #15: states that mean "the engine believes it is mid-run" — the Run surface must not
 * keep rendering these as LIVE progress once the accessibility service is disconnected (the
 * engine host is gone; anything mid-run is a stale illusion).
 * # 呈"运行中"语义的状态集合：断开后这些状态一律视为过期假象
 */
val RUN_LIKE_STATES: Set<AutomationState> = setOf(
    AutomationState.LAUNCHING_FAKE_GPS,
    AutomationState.STOPPING_OLD_GPS,
    AutomationState.SETTING_LOCATION,
    AutomationState.CONFIRMING_LOCATION,
    AutomationState.STARTING_FAKE_GPS,
    AutomationState.WAITING_INTERVAL,
    AutomationState.LAUNCHING_CELLREBEL,
    AutomationState.NAVIGATING_TO_TEST,
    AutomationState.STARTING_TEST,
    AutomationState.WAITING_FOR_RESULT,
    AutomationState.COLLECTING_RESULT,
    AutomationState.PROCESSING,
    AutomationState.COOLDOWN,
    AutomationState.RECOVERING,
)

/** The Run-surface disconnect warning line (issue #15b). */
const val SERVICE_DISCONNECTED_WARNING: String = "服务已断开 — 引擎已停止，请重新开始"

/**
 * Issue #15b: the Run-surface projection over (service connection, engine state, stage wait) —
 * PURE, the UI renders it, never decides it (same pattern as DeviceReadinessProjection).
 *
 * Semantics:
 *  - connected → no warning; the stage wait line ticks live from the anchor;
 *  - disconnected && (Run-like state || a stale stage anchor) → the prominent warning line and
 *    NO wait line (the local tick is frozen — the anchor belongs to a dead engine);
 *  - disconnected && terminal state && no anchor → nothing (a quiet terminal is honest).
 */
data class RunSurfaceProjection(
    /** Non-null = render the prominent "service disconnected" warning row. */
    val serviceWarning: String?,
    /** The "已等待 Xs（上限 Ys）" line; null = nothing to render / frozen. */
    val stageWaitLine: String?,
)

fun runSurfaceProjection(
    isServiceConnected: Boolean,
    currentState: AutomationState,
    stageProgress: StageProgress?,
    nowMs: Long,
): RunSurfaceProjection = RunSurfaceProjection(
    // Scaffold of TODAY's behavior (issue #15b RED): no disconnect awareness — the wait line
    // always ticks and no warning exists. The RED test pins the DESIRED projection.
    serviceWarning = null,
    stageWaitLine = StageProgress.waitLine(stageProgress, nowMs),
)

/**
 * Run dashboard (evolved from the old control panel, wireframe v2.1 §1.2):
 * status card (location / verified successes / attempts / plan total),
 * attempt stepper ending in a binary terminal, a SEPARATE scheduler cooldown
 * card with countdown + next action, last-failure line, and the unchanged
 * dark log terminal + debug buttons.
 * # Run 仪表盘（由旧控制面板演进）：状态卡、attempt 步进条（二值终态收尾）、
 * # 独立的 scheduler cooldown 卡（倒计时 + 下一步去向）、last failure 行，
 * # 以及保持不变的暗色日志终端与调试按钮
 */
@Composable
fun ControlScreen(
    isRunning: Boolean,
    currentState: AutomationState,
    cycleCount: Int,
    currentTask: EngineTaskSnapshot?,
    cooldown: CooldownInfo?,
    lastFailure: LastFailureInfo?,
    planCompletedSuccesses: Int,
    planTotalSuccesses: Int,
    logs: List<String>,
    isServiceConnected: Boolean,
    onStop: () -> Unit,
    onOpenPlan: () -> Unit,
    onOpenHistory: () -> Unit,
    // R44 (Sol GREEN-review-3 F5): the seven-state pairing/incident card on the RUN surface —
    // the operator sees WHAT is wrong and WHAT to do, with an entry into provider management.
    pairingUiState: com.example.cellrebelauto.ui.PairingUiState = com.example.cellrebelauto.ui.PairingUiState.Trusted,
    onOpenProviders: () -> Unit = {},
    // # 调试功能回调
    onExportLogs: () -> Unit = {},
    onDumpA11yTree: () -> Unit = {}
) {
    val context = LocalContext.current
    val logListState = rememberLazyListState()

    // # 新日志自动滚动到底部
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            logListState.animateScrollToItem(logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // # 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isRunning) "CellRebel Auto — Running" else "CellRebel Auto",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )
            // # 服务连接状态指示灯
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isServiceConnected) Color(0xFF4CAF50) else Color(0xFFFF5722))
                )
                Text(
                    text = if (isServiceConnected) " Service ON" else " Service OFF",
                    fontSize = 12.sp,
                    color = if (isServiceConnected) Color(0xFF4CAF50) else Color(0xFFFF5722)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // # 状态卡：位置 / 已验证成功 / 尝试数 / 计划总进度（INV-3/4 可见）
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    currentState == AutomationState.ERROR -> MaterialTheme.colorScheme.errorContainer
                    isRunning -> MaterialTheme.colorScheme.primaryContainer
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
                    Text("Status", fontWeight = FontWeight.Medium)
                    Text(
                        text = currentState.displayName,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            currentState == AutomationState.ERROR -> MaterialTheme.colorScheme.error
                            currentState == AutomationState.DONE -> Color(0xFF4CAF50)
                            isRunning -> MaterialTheme.colorScheme.primary
                            else -> Color.Gray
                        }
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                if (currentTask != null) {
                    Text(
                        "Location (pri ${currentTask.priority})  " +
                            "%.4f, %.4f  (csv row %d)".format(
                                currentTask.longitude, currentTask.latitude, currentTask.csvRow
                            )
                    )
                    Text(
                        "Verified successes: ${currentTask.completedSuccesses} / " +
                            "${currentTask.requiredSuccesses}   " +
                            "Attempts: ${currentTask.attemptOrdinal}"
                    )
                } else {
                    Text("No active attempt", color = Color.Gray, fontSize = 13.sp)
                }
                if (planTotalSuccesses > 0) {
                    Text("Plan total: $planCompletedSuccesses / $planTotalSuccesses")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // # attempt 步进条卡 + 独立的 scheduler cooldown 卡
        AttemptStepperCard(currentState = currentState, attemptOrdinal = currentTask?.attemptOrdinal)
        if (cooldown != null) {
            Spacer(modifier = Modifier.height(6.dp))
            CooldownCard(cooldown = cooldown)
        }

        // # 最近一次失败（INV-10：类型化原因可见、不计数、buffer 后重试）
        if (lastFailure != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Last failure: attempt #${lastFailure.attemptOrdinal} — ${lastFailure.reason} " +
                    "(not counted, retried after buffer)",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // R44 (Sol GREEN-review-3 F5): the pairing/incident status card — ALWAYS rendered on the
        // run surface; the Trusted state renders quietly, every incident state shows its concrete
        // recovery action. Not-approvable states offer the provider-management entry.
        if (pairingUiState !is com.example.cellrebelauto.ui.PairingUiState.Trusted) {
            PairingStatusCard(
                state = pairingUiState,
                onOpenApproval = onOpenProviders,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // # 主操作：仅 active session 显示 Stop（Start/Resume 在 Plan 页）
        if (isRunning) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("■ Stop Automation", modifier = Modifier.padding(vertical = 8.dp))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // # 功能按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenPlan,
                modifier = Modifier.weight(1f)
            ) {
                Text("Plan")
            }
            OutlinedButton(
                onClick = onOpenHistory,
                modifier = Modifier.weight(1f)
            ) {
                Text("History")
            }
            OutlinedButton(
                onClick = {
                    // # 打开系统无障碍设置页面
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("A11y")
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // # 调试工具按钮行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onExportLogs,
                modifier = Modifier.weight(1f),
                enabled = logs.isNotEmpty()
            ) {
                // # 导出日志到文件
                Text("Export Logs", fontSize = 12.sp)
            }
            OutlinedButton(
                onClick = onDumpA11yTree,
                modifier = Modifier.weight(1f),
                enabled = isServiceConnected
            ) {
                // # 抓取当前前台应用的无障碍节点树
                Text("Dump A11y Tree", fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // # 日志区域标题
        Text("Log", fontWeight = FontWeight.Medium, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))

        // # 日志内容（暗色背景终端风格）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A2E)
            )
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No logs yet. Start automation to see activity.",
                        color = Color(0xFF666688),
                        fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    items(logs) { logLine ->
                        Text(
                            text = logLine,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                logLine.contains("ERROR") || logLine.contains("FAILED") -> Color(0xFFFF6B6B)
                                logLine.contains("WARN") || logLine.contains("RETRY") -> Color(0xFFFFD93D)
                                logLine.contains("===") -> Color(0xFF6BCB77)
                                logLine.contains("---") -> Color(0xFF4D96FF)
                                else -> Color(0xFFCCCCDD)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Attempt stepper: Setting GPS → GPS settling → Testing → Processing →
 * ✔ Completed / ✘ Failed. The current stage is highlighted; the attempt
 * always ends in a binary terminal state.
 * # attempt 步进条：当前阶段高亮；attempt 以二值终态收尾
 */
@Composable
private fun AttemptStepperCard(
    currentState: AutomationState,
    attemptOrdinal: Int?
) {
    val stageIndex = stageIndexOf(currentState)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (attemptOrdinal != null) "Current attempt #$attemptOrdinal"
                else "Current attempt",
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(6.dp))

            // # 第一行：Setting GPS → GPS settling → Testing
            Row(verticalAlignment = Alignment.CenterVertically) {
                PIPELINE_STAGES.take(3).forEachIndexed { index, label ->
                    if (index > 0) StageArrow()
                    StageLabel(label, highlighted = stageIndex == index)
                }
            }
            // # 第二行：→ Processing → ✔ Completed / ✘ Failed
            Row(verticalAlignment = Alignment.CenterVertically) {
                StageArrow()
                StageLabel(PIPELINE_STAGES[3], highlighted = stageIndex == 3)
                StageArrow()
                Text(
                    "✔ Completed",
                    fontSize = 12.sp,
                    fontWeight = if (stageIndex == 4) FontWeight.Bold else FontWeight.Normal,
                    color = if (stageIndex == 4) Color(0xFF4CAF50) else Color.Gray
                )
                Text(" / ", fontSize = 12.sp, color = Color.Gray)
                Text(
                    "✘ Failed",
                    fontSize = 12.sp,
                    fontWeight = if (stageIndex == 5) FontWeight.Bold else FontWeight.Normal,
                    color = if (stageIndex == 5) MaterialTheme.colorScheme.error else Color.Gray
                )
            }

            // # 当前阶段名（cooldown 不在流水线内，由独立卡片呈现）
            if (stageIndex in 0..5) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "current: ${currentState.displayName}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// # 阶段标签：当前阶段加粗高亮
@Composable
private fun StageLabel(label: String, highlighted: Boolean) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
        color = if (highlighted) MaterialTheme.colorScheme.primary else Color.Gray
    )
}

@Composable
private fun StageArrow() {
    Text(" → ", fontSize = 12.sp, color = Color.Gray)
}

/**
 * Separate scheduler cooldown card (NOT part of the attempt pipeline):
 * local countdown derived from the one-shot engine emission, plus the next
 * action after the buffer expires (retry same / advance to next).
 * # 独立的 scheduler cooldown 卡（不属于 attempt 流水线）：
 * # 基于引擎一次性发射做本地倒计时，并显示 buffer 结束后的去向
 */
@Composable
private fun CooldownCard(cooldown: CooldownInfo) {
    // # 本地 1s 倒计时 tick
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldown) {
        while (isActive) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val remainingSec = maxOf(0L, cooldown.startedAtMs + cooldown.remainingMs - nowMs) / 1000
    val totalSec = cooldown.totalMs / 1000

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Scheduler: Cooldown ${remainingSec}s / ${totalSec}s",
                fontWeight = FontWeight.Medium
            )
            Text(
                "then ${cooldown.nextAction}",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}
