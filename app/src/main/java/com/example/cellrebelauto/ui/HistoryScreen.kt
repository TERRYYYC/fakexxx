package com.example.cellrebelauto.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cellrebelauto.model.plan.AttemptWithTask
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * History screen (F001, wireframe v2.1 §1.3): one card per attempt with
 * plan row / csv row traceability, success vs attempt ordinal split
 * (INV-3/4 visible), typed failure reason (INV-10), start→end timestamps,
 * running-observed audit evidence (AC-B2), and scores on success rows.
 * # 历史页：每次尝试一张卡片，含行号追溯、成功/尝试序号分列、
 * # 类型化失败原因、起止时间戳、running 观察证据与分数
 */
@Composable
fun HistoryScreen(
    attempts: List<AttemptWithTask>,
    legacyResults: List<com.example.cellrebelauto.model.TestResult>,
    onExportCsv: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // # 标题和记录数
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Test History", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("${attempts.size} records", fontSize = 14.sp, color = Color.Gray)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // # 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(
                onClick = onExportCsv,
                enabled = attempts.isNotEmpty() || legacyResults.isNotEmpty()
            ) { Text("Export CSV") }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // # 尝试列表（最新在前）
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(attempts, key = { it.attempt.id }) { item ->
                AttemptCard(item)
            }

            // # C1：v2 遗留结果分区（迁移故意保留的数据，与 attempt 卡片视觉区分）
            if (legacyResults.isNotEmpty()) {
                item(key = "legacy-header") {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Legacy results (pre-plan)",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                }
                items(legacyResults, key = { "legacy-${it.id}" }) { result ->
                    LegacyResultCard(result)
                }
            }
        }
    }
}

/**
 * One legacy v2 result row: timestamp, coords, scores, status — plain style,
 * visually distinct from attempt cards.
 * # v2 遗留结果卡片：时间、坐标、分数、状态；样式从简，与尝试卡片区分
 */
@Composable
private fun LegacyResultCard(result: com.example.cellrebelauto.model.TestResult) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "%.4f, %.4f · ${result.status}".format(result.longitude, result.latitude),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                dateFormat.format(Date(result.timestamp)) + " · session ${result.runSessionId}",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Text(
                "Web %.2f · Video %.2f".format(result.webBrowsingScore, result.videoStreamingScore),
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun AttemptCard(item: AttemptWithTask) {
    val a = item.attempt
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val succeeded = a.status == "succeeded" || a.status == "ok_gps_only"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (succeeded)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // # 记录号 · 计划行 · 优先级 · 坐标（INV-1/8 追溯）
            Text(
                "#${a.id} · row ${item.csvRow} (pri ${item.priority}) · " +
                    "%.4f, %.4f".format(a.longitude, a.latitude),
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            // # 成功序号 / 尝试序号分列（INV-3/4）；失败行带类型化原因（INV-10）
            if (a.successOrdinal != null) {
                Text(
                    "success ${a.successOrdinal}/${item.requiredSuccesses} · " +
                        "attempt ${a.attemptOrdinal} · ${a.status}",
                    fontSize = 13.sp
                )
            } else {
                Text(
                    "attempt ${a.attemptOrdinal} · ${a.status}" +
                        (a.failureReason?.let { ": $it" } ?: ""),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }

            // # F003 INV-F3-1：阶段跳过标记（gps_skipped / test_skipped）
            if (a.stageNotes != null) {
                Text(
                    "stage: ${a.stageNotes}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // # 起止时间戳与时长
            val startText = dateFormat.format(Date(a.startedAt))
            val endText = a.endedAt?.let { timeFormat.format(Date(it)) } ?: "—"
            val durationSec = a.endedAt?.let { (it - a.startedAt) / 1000 }
            Text(
                "$startText → $endText" + (durationSec?.let { "  (${it}s)" } ?: ""),
                fontSize = 12.sp,
                color = Color.Gray
            )

            // # running 迁移观察时间戳（AC-B2 审计证据）
            if (a.runningObservedAt != null) {
                Text(
                    "running observed ${timeFormat.format(Date(a.runningObservedAt))}",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }

            // # 分数（仅成功行）
            if (a.webBrowsingScore != null || a.videoStreamingScore != null) {
                Text(
                    "Web %.2f · Video %.2f".format(
                        a.webBrowsingScore ?: 0.0,
                        a.videoStreamingScore ?: 0.0
                    ),
                    fontSize = 13.sp
                )
            }
        }
    }
}
