package com.example.cellrebelauto.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.PlanConfig
import com.example.cellrebelauto.model.plan.RowError

/**
 * Plan screen (F001 home, wireframe v2.1 §1.1): CSV import card with atomic
 * error panel, first-run-required global buffer field, collapsible Advanced
 * timing, execution-order task cards, and state-driven Start/Resume/Stop.
 * # 计划页（F001 首页）：导入卡片 + 原子错误面板、首次必填的全局缓冲、
 * # 折叠的高级参数、执行顺序任务卡片、按状态切换的 Start/Resume/Stop
 */
@Composable
fun PlanScreen(
    planState: PlanUiState,
    planConfig: PlanConfig,
    isRunning: Boolean,
    isServiceConnected: Boolean,
    importErrors: List<RowError>,
    importNotice: String?,
    onImport: (Uri) -> Unit,
    onSetGlobalBuffer: (Int) -> Unit,
    onSetTestTimeout: (Int) -> Unit,
    onSetGpsSettle: (Int) -> Unit,
    onSetLocationStage: (Boolean) -> Unit,
    onSetTestStage: (Boolean) -> Unit,
    onStartOrResume: () -> Unit,
    onStop: () -> Unit,
    onOpenRun: () -> Unit,
    onOpenHistory: () -> Unit
) {
    // # SAF 文件选择器（无需新增权限）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImport(uri)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // # 标题栏 + 服务指示灯
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Location Plan", fontSize = 24.sp, fontWeight = FontWeight.Bold)
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
        }

        // # 导入卡片：当前计划摘要 + Import 按钮
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val plan = planState.plan
                    if (plan != null) {
                        Text(
                            "${plan.sourceFileName} — ${plan.totalRows} rows, " +
                                "${plan.totalRequiredSuccesses} successes total",
                            fontWeight = FontWeight.Medium
                        )
                        if (planState.isUnfinished) {
                            Text(
                                "Progress: ${planState.completedSuccesses}/${plan.totalRequiredSuccesses} verified successes",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                    } else {
                        Text(
                            "No plan imported yet",
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            importLauncher.launch(
                                arrayOf("text/*", "text/comma-separated-values", "application/octet-stream")
                            )
                        },
                        enabled = !isRunning
                    ) {
                        Text("Import CSV")
                    }
                }
            }
        }

        // # 原子导入错误面板：列出全部行级错误（AC-A2）
        if (importErrors.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            importNotice ?: "Import rejected",
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        importErrors.forEach { err ->
                            Text(
                                "Row ${err.csvRow}: ${err.message}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        } else if (importNotice != null) {
            // # 其他导入提示（拒绝原因 / 成功摘要）
            item {
                Text(
                    text = importNotice,
                    fontSize = 13.sp,
                    color = if (importNotice.startsWith("Imported"))
                        Color(0xFF4CAF50)
                    else
                        MaterialTheme.colorScheme.error
                )
            }
        }

        // # 全局缓冲：唯一业务参数，首次必填并持久化（设计稿 v2.1）
        item {
            BufferField(
                planState = planState,
                planConfig = planConfig,
                onSetGlobalBuffer = onSetGlobalBuffer
            )
        }

        // # F003：阶段开关（位置 / CellRebel 测试），Advanced 之上
        item {
            StageTogglesSection(
                planConfig = planConfig,
                onSetLocationStage = onSetLocationStage,
                onSetTestStage = onSetTestStage
            )
        }

        // # 高级参数（折叠）：test timeout / GPS settle，有内部默认
        item {
            AdvancedSection(
                planConfig = planConfig,
                onSetTestTimeout = onSetTestTimeout,
                onSetGpsSettle = onSetGpsSettle
            )
        }

        // # 执行顺序卡片列表（INV-1 可见化）
        if (planState.tasks.isNotEmpty()) {
            item {
                Text(
                    "Execution order (priority ↑, then CSV row ↑):",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
            }
            itemsIndexed(planState.tasks, key = { _, t -> t.id }) { index, task ->
                TaskCard(
                    executionIndex = index + 1,
                    task = task,
                    attempts = planState.attemptCounts[task.id] ?: 0
                )
            }
        }

        // # 主操作按钮：按 session/进度状态切换（复审校正 2）
        item {
            when {
                // # 仅存在 active session 时显示 Stop + Run 入口
                isRunning -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("■ Stop", modifier = Modifier.padding(vertical = 8.dp))
                        }
                        OutlinedButton(
                            onClick = onOpenRun,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Run ▸", modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                }
                // # 计划未启动 → Start
                planState.plan != null && !planState.isStarted -> {
                    Button(
                        onClick = onStartOrResume,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isServiceConnected
                    ) {
                        Text("▶ Start Plan", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                // # 有未完成但已暂停的计划 → Resume（INV-9 恢复入口）
                planState.isUnfinished -> {
                    Button(
                        onClick = onStartOrResume,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = isServiceConnected
                    ) {
                        Text("⏸ Resume Plan", modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
                // # 计划已全部完成
                planState.isComplete -> {
                    Text(
                        "Plan completed ✔ — import a new CSV to continue",
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // # 底部导航
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onOpenRun, modifier = Modifier.weight(1f)) {
                    Text("Run")
                }
                OutlinedButton(onClick = onOpenHistory, modifier = Modifier.weight(1f)) {
                    Text("History")
                }
            }
        }
    }
}

/**
 * Global buffer field — the only business parameter, first-run required and
 * persisted. Displays the EFFECTIVE value (F6): once a plan exists, that is the
 * plan snapshot the engine executes; locked to next-plan-only once started.
 * # 全局缓冲输入框：唯一业务参数，首次必填并持久化。
 * # 展示有效值（F6）：计划存在即展示 engine 执行的 plan 快照；
 * # 计划启动后锁定（仅对下次导入生效）
 */
@Composable
private fun BufferField(
    planState: PlanUiState,
    planConfig: PlanConfig,
    onSetGlobalBuffer: (Int) -> Unit
) {
    // # 有效值 = engine 执行值（计划存在用 plan 快照，否则用 DataStore 默认）
    val effectiveBuffer = planState.plan?.globalBufferSeconds ?: planConfig.globalBufferSeconds
    // # 计划已启动 → 锁定，修改只对下次导入生效
    val locked = planState.isStarted

    var bufferText by remember { mutableStateOf("") }
    // # 有效值变化（DataStore 异步加载 / 快照同步完成）后回填
    LaunchedEffect(effectiveBuffer) {
        bufferText = effectiveBuffer?.toString() ?: ""
    }

    Column {
        OutlinedTextField(
            value = bufferText,
            onValueChange = { newValue ->
                bufferText = newValue
                newValue.toIntOrNull()?.let { if (it >= 0) onSetGlobalBuffer(it) }
            },
            label = { Text("Global buffer between attempts (s)") },
            supportingText = {
                when {
                    effectiveBuffer == null ->
                        Text("Required before import", color = MaterialTheme.colorScheme.error)
                    locked ->
                        Text("Locked for current plan — changes apply to next import")
                }
            },
            isError = effectiveBuffer == null,
            enabled = !locked,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * F003 stage toggles: two independent Switch rows above Advanced. OFF skips
 * that pipeline stage per attempt; both OFF blocks Start (KD-F3-3).
 * # F003 阶段开关：两个独立开关行。关闭即每次 attempt 跳过对应阶段；
 * # 双关时 Start 被拒绝
 */
@Composable
private fun StageTogglesSection(
    planConfig: PlanConfig,
    onSetLocationStage: (Boolean) -> Unit,
    onSetTestStage: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Pipeline stages", fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))

            // # 位置阶段：Fake GPS 落点 + 激活验证 + 稳定等待
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Location stage (Fake GPS)", fontSize = 14.sp)
                    Text(
                        "OFF: skip GPS setup & settle — go straight to CellRebel " +
                            "(location handled externally; marked gps_skipped)",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = planConfig.locationStageEnabled,
                    onCheckedChange = onSetLocationStage
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // # CellRebel 测试阶段
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CellRebel test stage", fontSize = 14.sp)
                    Text(
                        "OFF: GPS-verified only — no CellRebel launch " +
                            "(location-app walk; counts as ok_gps_only, marked test_skipped)",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(
                    checked = planConfig.testStageEnabled,
                    onCheckedChange = onSetTestStage
                )
            }

            // # 双关提示（Start 将被拒绝，KD-F3-3）
            if (!planConfig.locationStageEnabled && !planConfig.testStageEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Both stages are OFF — Start will be rejected (nothing would run)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * Collapsible Advanced section: test timeout + GPS settle (independent
 * internal-default fields, AC-B5).
 * # 可折叠高级区：测试超时 + GPS 稳定等待（独立字段、独立持久化）
 */
@Composable
private fun AdvancedSection(
    planConfig: PlanConfig,
    onSetTestTimeout: (Int) -> Unit,
    onSetGpsSettle: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (expanded) "Advanced ▾" else "Advanced ▸",
                    fontWeight = FontWeight.Medium
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                AdvancedIntField(
                    label = "Test timeout (s)",
                    value = planConfig.testTimeoutSeconds,
                    onSave = onSetTestTimeout
                )
                Spacer(modifier = Modifier.height(8.dp))
                AdvancedIntField(
                    label = "GPS settle wait (s)",
                    value = planConfig.gpsSettleSeconds,
                    onSave = onSetGpsSettle
                )
            }
        }
    }
}

// # 高级区整数输入框：本地编辑态 + 持久化值回填
@Composable
private fun AdvancedIntField(
    label: String,
    value: Int,
    onSave: (Int) -> Unit
) {
    var text by remember { mutableStateOf("") }
    LaunchedEffect(value) {
        text = value.toString()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { newValue ->
            text = newValue
            newValue.toIntOrNull()?.let { if (it >= 0) onSave(it) }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * One execution-order card: index, priority, coordinates with csv row
 * traceability, verified successes / quota and total attempts.
 * Badge is the persisted task status only (pending/active/completed) — the
 * cooldown projection is shown on the Run page, not here (wireframe v2.1).
 * # 执行顺序卡片：序号、优先级、坐标（含 csv row 追溯）、成功/配额与尝试数。
 * # 徽标仅取持久化的任务状态；cooldown 投影只在 Run 页显示
 */
@Composable
private fun TaskCard(
    executionIndex: Int,
    task: LocationTask,
    attempts: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (task.status) {
                "completed" -> MaterialTheme.colorScheme.primaryContainer
                "active" -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("#$executionIndex · Priority ${task.priority}", fontWeight = FontWeight.Bold)
                Text(
                    text = task.status,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = when (task.status) {
                        "completed" -> Color(0xFF4CAF50)
                        "active" -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray
                    }
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "%.4f, %.4f  (csv row %d)".format(task.longitude, task.latitude, task.csvRow),
                fontSize = 13.sp
            )
            Text(
                "Success ${task.completedSuccesses}/${task.requiredSuccesses} · Attempts $attempts",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
    }
}
