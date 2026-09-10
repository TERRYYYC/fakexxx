package name.caiyao.fakegps.ui.screen.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * T11d 简单模式编辑器（v3 原型 M2/M3 拍板：简单模式是默认）：
 * 只有 名称 + 坐标（纬度/经度——地图选点带出的坐标在这里保留、可改）+（若挂路线）路线卡，
 * 底部一个「高级字段 ▸」入口。字段清单由 [SimpleProfileEditorSpec] 定义并被测试锁定——
 * 极简铁律：简单模式零蜂窝/WiFi 字段可见。
 *
 * 保存链与专家模式共用同一个 [ProfileEditorViewModel]（含 #129 的 SaveFailureNotice 与
 * 「仅保存」出路），因此「保存并验证/仅保存」的语义、文案与失败路径逐字一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleProfileEditorScreen(
    profileId: Long,
    lat: Double,
    lon: Double,
    onBack: () -> Unit,
    onVerify: () -> Unit = {},
    vm: ProfileEditorViewModel = viewModel(),
) {
    val isNew = profileId == -1L || profileId == 0L
    val fieldValues by vm.fieldValues.collectAsState()
    val saved by vm.saved.collectAsState()
    val saving by vm.saving.collectAsState()
    val fieldErrors by vm.fieldErrors.collectAsState()
    val notice by vm.notice.collectAsState()
    val routeSummary by vm.routeSummary.collectAsState()
    val profileName by vm.profileName.collectAsState()
    val verifyRequested by vm.verifyRequested.collectAsState()

    // 与专家编辑器同一触发；同键重复 load（模式切换重入）在 VM 内去重，草稿不丢。
    LaunchedEffect(profileId) {
        vm.load(if (isNew) -1L else profileId, lat, lon)
    }

    LaunchedEffect(saved) {
        if (saved) onBack()
    }

    // 与专家编辑器相同：只在发布成功后进验证页（见 ProfileEditorScreen 的同名注释）。
    LaunchedEffect(verifyRequested) {
        if (verifyRequested) onVerify()
    }

    val context = LocalContext.current
    val modePrefs = remember { EditorModePrefs.getInstance(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "新建档案" else "编辑档案") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        floatingActionButton = {
            // 与专家模式逐字相同的双 FAB：#129 的失败提示指向「仅保存」，这条出路必须可见。
            Column(horizontalAlignment = Alignment.End) {
                ExtendedFloatingActionButton(
                    onClick = { if (!saving) vm.saveAndVerify() },
                    icon = { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null) },
                    text = { Text("保存并验证") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .alpha(if (saving) 0.5f else 1f),
                )
                ExtendedFloatingActionButton(
                    onClick = { if (!saving) vm.save() },
                    text = { Text("仅保存") },
                    icon = { Icon(Icons.Default.Save, contentDescription = null) },
                    modifier = Modifier.alpha(if (saving) 0.5f else 1f),
                )
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            notice?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            routeSummary?.let { summary -> ProfileRouteCard(summary) }

            OutlinedTextField(
                value = profileName,
                onValueChange = vm::updateName,
                label = { Text("名称") },
                placeholder = { Text("留空 = 按坐标自动命名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            for (column in SimpleProfileEditorSpec.COLUMNS.toList().sorted()) {
                CoordinateField(
                    column = column,
                    value = fieldValues[column] ?: "",
                    validationError = fieldErrors[column],
                    onValueChange = { vm.updateField(column, it) },
                )
            }

            ExpertModeEntry(onClick = { modePrefs.setSimpleMode(false) })

            Spacer(modifier = Modifier.padding(bottom = 72.dp))
        }
    }
}

@Composable
private fun CoordinateField(
    column: String,
    value: String,
    validationError: String?,
    onValueChange: (String) -> Unit,
) {
    val label = if (column == "latitude") "纬度" else "经度"
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { Text("地图选点或手动输入") },
        isError = validationError != null,
        supportingText = {
            Text(
                validationError
                    ?: if (column == "latitude") "范围 -90 ~ 90（°）" else "范围 -180 ~ 180（°）",
            )
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** v3 原型 M2 底部「专家模式 ▸」在编辑器内的对应物：进入完整 14 组 90 字段编辑器。 */
@Composable
private fun ExpertModeEntry(onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("高级字段 ▸", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "蜂窝 / WiFi / 运营商等 14 组 90 字段（专家模式）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
