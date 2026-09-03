package name.caiyao.fakegps.ui.screen.verify

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.ui.observationScopePresentation
import name.caiyao.fakegps.verify.FieldReport
import name.caiyao.fakegps.verify.FieldVerdict
import name.caiyao.fakegps.verify.HookApplicability
import name.caiyao.fakegps.verify.ObservationScope
import name.caiyao.fakegps.verify.ProbeFailure
import name.caiyao.fakegps.verify.ProbeUiStatus
import name.caiyao.fakegps.verify.VerificationSummary
import name.caiyao.fakegps.verify.VerificationStatus

private enum class RowFilter(val label: String) {
    CONFIGURED("仅已配置"),
    ALL("全部字段"),
}

/**
 * Reconciliation view: for every field — what was configured, what the app actually reads back, and
 * whether those agree.
 *
 * Replaces a flat dump of device readings, which could not answer the only question that matters
 * ("did my configuration take effect?") because it never showed the configured value at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyScreen(
    onBack: () -> Unit,
    vm: VerifyViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    var filter by remember { mutableStateOf(RowFilter.CONFIGURED) }

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("伪装验证") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "重新验证")
                    }
                },
            )
        },
    ) { innerPadding ->
        val report = state.report
        if (state.loading && report == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "verdict") { VerdictCard(state) }
            if (state.scope != ObservationScope.SELF_HOOKED) {
                item(key = "probe") { ProbeCard(state.probeStatus) }
            }
            item(key = "scope") { ScopeCard(state) }
            item(key = "payload") { PayloadCard(state) }

            if (state.notes.isNotEmpty()) {
                item(key = "notes") { NotesCard(state.notes) }
            }

            item(key = "filter") {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (f in RowFilter.entries) {
                        FilterChip(
                            selected = filter == f,
                            onClick = { filter = f },
                            label = { Text(f.label) },
                        )
                    }
                }
            }

            for (group in report?.groups.orEmpty()) {
                val rows = group.fields.filter { it.visibleUnder(filter) }
                if (rows.isEmpty()) continue

                item(key = "h_${group.category}") {
                    Text(
                        text = group.category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                    )
                }
                item(key = "c_${group.category}") {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            rows.forEachIndexed { i, f ->
                                FieldRow(f, state.scope)
                                if (i < rows.lastIndex) HorizontalDivider()
                            }
                        }
                    }
                }
            }

            item(key = "tail") { Box(Modifier.padding(bottom = 24.dp)) {} }
        }
    }
}

private fun FieldReport.visibleUnder(filter: RowFilter): Boolean = when (filter) {
    RowFilter.ALL -> true
    // Group-derived and passthrough rows reflect no decision the user made, so they are noise while
    // checking only configured fields. The configured value is the single truth source here.
    RowFilter.CONFIGURED -> configured != null
}

// -- header cards -------------------------------------------------------------------------------

@Composable
private fun VerdictCard(state: VerifyUiState) {
    val summary = state.report?.summary
    // Verdicts are only believable while the hook is actually applying this payload. In every other
    // state passthrough is the DESIGNED behaviour, and scoring it naively would blame the module for
    // doing exactly what it was told.
    val notApplying = !state.applicability.verdictsMeaningful

    val headline: String
    val detail: String
    val tone: Color
    when {
        notApplying -> {
            tone = MaterialTheme.colorScheme.onSurfaceVariant
            when (state.applicability) {
                HookApplicability.MODE_OFF -> {
                    headline = "伪装已关闭"
                    detail = "当前模式为「关闭」，本模块原样透传系统 API 返回值。" +
                        "到「设置 → 伪装模式」开启后再验证。"
                }
                HookApplicability.OUTSIDE_ACTIVE_HOURS -> {
                    headline = "当前不在生效时段"
                    detail = "模式为「按时段」，现在不在配置的时间窗口内，本模块按设计透传系统 API 返回值。" +
                        "此时读到未被本模块替换的值是正常的，不代表伪装失败。"
                }
                HookApplicability.SCHEMA_REJECTED -> {
                    headline = "hook 拒绝了当前配置"
                    detail = "payload 的 schemaVersion 与 hook 期望的不一致，hook 会保留上一次可用配置。" +
                        "因此本页的对比结果不代表这份配置的实际效果 —— 请重新保存一次档案。"
                }
                HookApplicability.PAYLOAD_INCOMPLETE -> {
                    headline = "配置不完整，hook 仍在用旧配置"
                    detail = "已发布的 payload 没有 fields 内容，hook 按契约保留上一次可用配置继续伪装。" +
                        "也就是说当前生效的并不是这份配置，本页无法据此判断 —— 请重新保存一次档案。"
                }
                HookApplicability.PAYLOAD_MALFORMED -> {
                    headline = "配置无法解析，hook 仍在用旧配置"
                    detail = "磁盘上有 payload 但解析失败，hook 按契约保留上一次可用配置继续伪装。" +
                        "也就是说当前生效的配置本页读不出来，任何对比结论都不作数 —— 请重新保存一次档案。"
                }
                HookApplicability.PAYLOAD_UNREADABLE -> {
                    headline = "读不到已发布的配置"
                    detail = "磁盘上的 payload 读取失败（权限或文件异常）。hook 按契约保留上一次可用配置继续伪装，" +
                        "所以当前很可能仍在伪装，只是本页看不到用的是哪份配置 —— 不要据此判断已停止伪装。"
                }
                HookApplicability.NEVER_PUBLISHED -> {
                    headline = "尚未发布过配置"
                    detail = "还没有任何配置送达 hook，本模块会透传系统 API 返回值。到档案编辑页保存一次即可。"
                }
                HookApplicability.PUBLICATION_FAILED -> {
                    headline = "档案尚未发布"
                    detail = "数据库保存后发布失败；目标 App 仍可能使用上一份配置，不能据此判定生效。"
                }
                HookApplicability.APPLYING -> { headline = ""; detail = "" }
            }
        }
        state.probeStatus is ProbeUiStatus.Failed -> {
            headline = "无法完成运行时验证"
            detail = probeFailureMessage(state.probeStatus.failure)
            tone = MaterialTheme.colorScheme.error
        }
        summary == null -> {
            headline = "无法验证"
            detail = "未取得可用于字段判定的运行时观测。"
            tone = MaterialTheme.colorScheme.error
        }
        else -> when (summary.status) {
            VerificationStatus.EFFECTIVE -> {
                headline = "伪装生效"
                detail = "${summary.spoofed} 个字段读回值与配置一致，且没有读不到的字段。"
                tone = MaterialTheme.colorScheme.primary
            }
            VerificationStatus.PARTIALLY_EFFECTIVE -> {
                headline = "部分验证通过"
                detail = partialVerificationDetail(summary)
                tone = MaterialTheme.colorScheme.primary
            }
            VerificationStatus.CONFIGURED_UNVERIFIABLE -> {
                headline = "已配置，但本页无法验证"
                // Claims only what the payload proves. "已生效" would be unprovable here: the hook
                // may not have re-read yet, and in a target app it may not be running at all.
                detail = "当前档案只配置了 ${summary.notVerifiable} 个模块开关类字段（如信号波动）。" +
                    "这类字段没有对应的系统读取接口，任何 App 都读不回来，因此本页无法验证它们是否已应用 —— " +
                    "只能确认它们已写入发布给 hook 的配置。"
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            }
            VerificationStatus.PENDING_PROPAGATION -> {
                headline = "配置刚保存，尚未生效"
                detail = "hook 最长可能仍按之前的 " +
                    "${PublishPropagation.MAX_PROPAGATION_DELAY_MS / 1000} 秒周期等待刷新，" +
                    "刚保存的改动可能还没被读到。请稍后再点右上角重新验证 —— " +
                    "现在读到旧值是正常的，不代表失败。"
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            }
            VerificationStatus.FAILING -> {
                headline = "${summary.mismatch} 个字段未生效"
                detail = "这些字段配置了值，但应用读到的是别的内容。可能原因：" +
                    "配置没送达 hook、该字段的 hook 未覆盖" +
                    // A self-hooked build still needs its own package inside the framework's scope.
                    // Without this hint the most common innocent cause reads as a code defect.
                    if (state.scope == ObservationScope.SELF_HOOKED)
                        "，或本应用未加入 Vector/LSPosed 的作用域（自我 hook 未激活）。"
                    else "。"
                tone = MaterialTheme.colorScheme.error
            }
            VerificationStatus.INCONCLUSIVE -> {
                headline = "本页无法验证"
                detail = "配置了 ${summary.configuredCount} 个字段，但本进程一个都读不回来，" +
                    "因此既不能证明生效、也不能证明失败。"
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            }
            VerificationStatus.NOTHING_CONFIGURED -> {
                headline = "当前档案没有配置任何字段"
                detail = "本模块会透传系统 API 返回值。到档案编辑页填入至少一个字段再来验证。"
                tone = MaterialTheme.colorScheme.onSurfaceVariant
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(headline, style = MaterialTheme.typography.titleLarge, color = tone)
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary != null && !notApplying) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CountPill("已生效", summary.spoofed, verdictColor(FieldVerdict.SPOOFED))
                    if (summary.ambiguous > 0) {
                        CountPill("巧合", summary.ambiguous, verdictColor(FieldVerdict.AMBIGUOUS))
                    }
                    CountPill("未生效", summary.mismatch, verdictColor(FieldVerdict.MISMATCH))
                    CountPill("读不到", summary.unobservable, verdictColor(FieldVerdict.UNOBSERVABLE))
                    // Must be shown, or rows chipped 不可验证 have no counterpart in the summary —
                    // which is how "读不到 0" ended up sitting above a row that said 读不到.
                    if (summary.notVerifiable > 0) {
                        CountPill("不可验证", summary.notVerifiable, verdictColor(FieldVerdict.NOT_VERIFIABLE))
                    }
                    if (summary.groupDerived > 0) {
                        CountPill("联动值", summary.groupDerived, verdictColor(FieldVerdict.GROUP_DERIVED))
                    }
                    CountPill("透传", summary.passthrough, verdictColor(FieldVerdict.PASSTHROUGH))
                }
            }
        }
    }
}

internal fun partialVerificationDetail(summary: VerificationSummary): String {
    val unchecked = buildList {
        if (summary.ambiguous > 0) {
            add("${summary.ambiguous} 个值与读取基线相同，需改成明显不同的值")
        }
        if (summary.unobservable > 0) add("${summary.unobservable} 个本进程读不到")
        if (summary.notVerifiable > 0) add("${summary.notVerifiable} 个没有系统读取接口")
    }.joinToString("、")
    return "${summary.spoofed} 个字段确认生效，另有 $unchecked，无法验证。" +
        "没有发现矛盾，但也不能说全部生效。"
}

internal fun probeFailureMessage(failure: ProbeFailure): String = when (failure) {
    ProbeFailure.NOT_SCOPED ->
        "验证探针进程没有被 Vector/LSPosed 注入；这是作用域问题，不对任何字段作失败判定。" +
            "请确认 FakeGPS 模块已启用并重试。"
    ProbeFailure.TIMEOUT -> "验证探针超时，未取得本次请求的结果；旧结果已丢弃，可以重试。"
    ProbeFailure.PAYLOAD_MISMATCH -> "验证探针读到的配置指纹与本页不同，结果已拒绝；请重试。"
    ProbeFailure.MALFORMED_RESULT -> "验证探针返回了无法解析的结果，旧结果未保留。"
    ProbeFailure.START_FAILED -> "验证探针进程无法启动，请确认模块安装状态后重试。"
    ProbeFailure.INTERNAL_ERROR -> "验证探针读取系统 API 时发生错误，本次无法判断。"
}

@Composable
private fun ScopeCard(state: VerifyUiState) {
    val text = observationScopePresentation(state.scope).explanation
    InfoCard(title = "这一页能证明什么", body = text)
}

@Composable
private fun ProbeCard(status: ProbeUiStatus) {
    val (title, body) = probeStatusCopy(status)
    InfoCard(title = title, body = body)
}

internal fun probeStatusCopy(status: ProbeUiStatus): Pair<String, String> = when (status) {
    ProbeUiStatus.NotRequested ->
        "运行时探针" to "当前配置不具备运行条件，未启动探针；这里不会据此判定任何字段失败。"
    ProbeUiStatus.Starting ->
        "运行时探针连接中" to "正在启动独立 hook 进程并等待本次请求的匹配结果。"
    ProbeUiStatus.Verified ->
        "运行时探针已连接" to "请求 ID 与配置指纹匹配；下方字段来自 hook 进程的公共 API 读回值。"
    is ProbeUiStatus.Failed ->
        "运行时探针不可用" to probeFailureMessage(status.failure)
}

@Composable
private fun PayloadCard(state: VerifyUiState) {
    val lines = mutableListOf<Pair<String, String>>()
    lines += "伪装模式" to modeLabel(state.mode)

    when (val p = state.payload) {
        is PayloadStatus.NeverPublished ->
            lines += "配置通道" to "从未发布过配置 — hook 无配置可用"
        is PayloadStatus.Malformed ->
            lines += "配置通道" to "payload 解析失败 — hook 将保留上一次可用配置"
        is PayloadStatus.Unreadable ->
            lines += "配置通道" to "读取失败（${p.cause}）— hook 仍在用上一次可用配置"
        is PayloadStatus.Ok -> {
            lines += "已送达 hook 的字段数" to "${p.fieldCount}"
            lines += "schemaVersion" to
                if (p.compatible) "${p.schemaVersion}（兼容）"
                else "${p.schemaVersion} — hook 期望 ${ConfigPrefsSync.SCHEMA_VERSION}，会拒绝此配置"
        }
    }
    state.fingerprint?.let { lines += "配置指纹" to it }

    val unmapped = state.report?.unmappedPayloadColumns.orEmpty()
    if (unmapped.isNotEmpty()) {
        val preview = unmapped.take(3).joinToString() + if (unmapped.size > 3) "…" else ""
        lines += "界面未覆盖的字段" to "${unmapped.size} 个（$preview）"
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("配置通道", style = MaterialTheme.typography.titleSmall)
            for ((k, v) in lines) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        k,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(0.45f),
                    )
                    Text(
                        v,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NotesCard(notes: List<String>) {
    InfoCard(
        title = "有些字段读不回来，原因如下",
        body = notes.joinToString("\n") { "· $it" },
    )
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// -- field row ----------------------------------------------------------------------------------

@Composable
private fun FieldRow(f: FieldReport, scope: ObservationScope) {
    val observedLabel = observationScopePresentation(scope).observedLabel
    val observedValue = displayedObservation(scope, f.observed, f.baseline)

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.spec.displayName, style = MaterialTheme.typography.bodyMedium)
            VerdictChip(f.verdict)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ValueSlot("配置", f.configured, Modifier.weight(1f))
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            ValueSlot(observedLabel, observedValue, Modifier.weight(1f))
        }

        if (f.ambiguous) {
            Text(
                text = "此值与读取基线相同 — 即使 hook 生效也无法区分，建议改成明显不同的值",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

internal fun displayedObservation(
    scope: ObservationScope,
    observed: String?,
    baseline: String?,
): String? = if (scope == ObservationScope.REAL_BASELINE) baseline else observed

@Composable
private fun ValueSlot(label: String, value: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = if (value != null) FontWeight.Medium else FontWeight.Normal,
            color = if (value != null) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun VerdictChip(verdict: FieldVerdict) {
    val label = when (verdict) {
        FieldVerdict.SPOOFED -> "已生效"
        FieldVerdict.AMBIGUOUS -> "无法区分"
        FieldVerdict.MISMATCH -> "未生效"
        FieldVerdict.UNOBSERVABLE -> "读不到"
        FieldVerdict.PASSTHROUGH -> "透传"
        FieldVerdict.NOT_VERIFIABLE -> "不可验证"
        FieldVerdict.GROUP_DERIVED -> "联动值"
    }
    val color = verdictColor(verdict)
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun CountPill(label: String, count: Int, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text("$label $count", style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun verdictColor(v: FieldVerdict): Color = when (v) {
    FieldVerdict.SPOOFED -> MaterialTheme.colorScheme.primary
    FieldVerdict.AMBIGUOUS -> MaterialTheme.colorScheme.tertiary
    FieldVerdict.MISMATCH -> MaterialTheme.colorScheme.error
    FieldVerdict.UNOBSERVABLE -> MaterialTheme.colorScheme.tertiary
    FieldVerdict.PASSTHROUGH -> MaterialTheme.colorScheme.outline
    FieldVerdict.NOT_VERIFIABLE -> MaterialTheme.colorScheme.tertiary
    FieldVerdict.GROUP_DERIVED -> MaterialTheme.colorScheme.outline
}

private fun modeLabel(mode: String): String = when (mode) {
    SpoofSettings.MODE_ALWAYS_ON -> "始终开启"
    SpoofSettings.MODE_TIME_BASED -> "按时段"
    SpoofSettings.MODE_OFF -> "关闭"
    else -> mode
}
