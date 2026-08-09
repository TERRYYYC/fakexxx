package name.caiyao.fakegps.ui.screen.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.data.SpoofSettings
import kotlin.math.roundToInt

@SuppressLint("InlinedApi")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    vm: SettingsViewModel = viewModel(),
) {
    val spoofMode by vm.spoofMode.collectAsState()
    val hourStart by vm.activeHourStart.collectAsState()
    val hourEnd by vm.activeHourEnd.collectAsState()

    val refreshIntervalSec by vm.refreshIntervalSec.collectAsState()
    val publishFailure by vm.publishFailure.collectAsState()
    val locationDeliveryMode by vm.locationDeliveryMode.collectAsState()
    val mockProviderState by vm.mockProviderState.collectAsState()
    val publishedConfig by vm.publishedConfig.collectAsState()
    val locationModel = LocationDeliveryUiContract.model(
        locationDeliveryMode,
        mockProviderState,
        publishedConfig,
    )
    val context = LocalContext.current
    fun missingSystemMockPermissions(): Set<SystemMockPermission> =
        SystemMockPermissionPolicy.missing(
            sdkInt = Build.VERSION.SDK_INT,
            fineLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
            coarseLocationGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
            notificationsGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
        )

    val systemMockPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = missingSystemMockPermissions()
        if (missing.isEmpty()) {
            vm.setSystemMockEnabled(true)
        } else {
            vm.reportSystemMockPermissionFailure(
                "System Mock 未启动：请授予定位权限和通知权限，确保运行状态与停止入口可见",
            )
        }
    }

    fun requestSystemMock(enabled: Boolean) {
        if (!enabled) {
            vm.setSystemMockEnabled(false)
            return
        }
        val missing = missingSystemMockPermissions()
        if (missing.isEmpty()) {
            vm.setSystemMockEnabled(true)
        } else {
            systemMockPermissionLauncher.launch(
                missing.map { permission ->
                    when (permission) {
                        SystemMockPermission.FineLocation -> Manifest.permission.ACCESS_FINE_LOCATION
                        SystemMockPermission.CoarseLocation -> Manifest.permission.ACCESS_COARSE_LOCATION
                        SystemMockPermission.Notifications -> Manifest.permission.POST_NOTIFICATIONS
                    }
                }.toTypedArray(),
            )
        }
    }

    var showModeDialog by remember { mutableStateOf(false) }
    var showRefreshDialog by remember { mutableStateOf(false) }
    var showHourStartDialog by remember { mutableStateOf(false) }
    var showHourEndDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            // A setting that was persisted but never reached the hook must say so: the preference
            // is kept, but presenting it as in effect would reproduce the exact "changed it and
            // nothing happened" confusion this screen is meant to remove.
            publishFailure?.let { message ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingContent = {
                        TextButton(onClick = { vm.dismissPublishFailure() }) { Text("知道了") }
                    },
                )
                HorizontalDivider()
            }

            // --- 位置注入 ---
            SectionHeader("位置注入")
            ListItem(
                headlineContent = { Text("系统 Mock 位置") },
                supportingContent = {
                    Text(
                        "${locationModel.status}\n" +
                            "生效中档案：${locationModel.effectiveCoordinate}\n" +
                            locationModel.detail,
                    )
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Switch(
                            checked = locationModel.systemMockEnabled,
                            enabled = locationModel.switchEnabled,
                            onCheckedChange = ::requestSystemMock,
                        )
                        if (locationModel.retryStopVisible) {
                            TextButton(onClick = vm::retryStopSystemMock) {
                                Text("重试停止")
                            }
                        }
                    }
                },
            )
            ListItem(
                headlineContent = {
                    Text(
                        if (locationModel.mockAppSelectionRequired &&
                            locationModel.retryStopVisible
                        ) {
                            "重新选择当前千网游"
                        } else if (locationModel.mockAppSelectionRequired) {
                            "选择当前千网游"
                        }
                        else "选择模拟位置 App",
                        color = if (locationModel.mockAppSelectionRequired) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                },
                supportingContent = {
                    Text(
                        if (locationModel.mockAppSelectionRequired) {
                            if (locationModel.retryStopVisible) {
                                "1. 打开开发者选项；2. 将模拟位置 App 重新选为当前千网游；" +
                                    "3. 返回后点“重试停止”"
                            } else {
                                "1. 打开开发者选项；2. 将模拟位置 App 选为当前千网游；" +
                                    "3. 返回后重新打开 System Mock 开关"
                            }
                        } else {
                            "System Mock 需要在开发者选项中选择当前安装的千网游版本"
                        },
                    )
                },
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                },
            )
            HorizontalDivider()

            // --- Hook 配置 ---
            SectionHeader("Hook 配置")
            ListItem(
                headlineContent = { Text("伪装模式") },
                supportingContent = { Text(modeDisplayName(spoofMode)) },
                modifier = Modifier.clickable { showModeDialog = true },
            )
            AnimatedVisibility(visible = spoofMode == SpoofSettings.MODE_TIME_BASED) {
                Column {
                    ListItem(
                        headlineContent = { Text("开始时间") },
                        supportingContent = { Text("%02d:00".format(hourStart)) },
                        modifier = Modifier.clickable { showHourStartDialog = true },
                    )
                    ListItem(
                        headlineContent = { Text("结束时间") },
                        supportingContent = { Text("%02d:00".format(hourEnd)) },
                        modifier = Modifier.clickable { showHourEndDialog = true },
                    )
                }
            }
            // The value shown here comes from the persisted flow, never from a literal: this row
            // used to render a hardcoded "60 秒" while the hook re-read every 30 s, and had no
            // clickable modifier — a number that was both wrong and unchangeable.
            ListItem(
                headlineContent = { Text("刷新间隔") },
                supportingContent = {
                    Text("$refreshIntervalSec 秒 · 改配置后最多等这么久生效")
                },
                modifier = Modifier.clickable { showRefreshDialog = true },
            )
            HorizontalDivider()

            // --- 外观 ---
            SectionHeader("外观")
            ListItem(
                headlineContent = { Text("主题") },
                supportingContent = { Text("跟随系统") },
            )
            HorizontalDivider()

            // --- 数据 ---
            SectionHeader("数据管理")
            ListItem(
                headlineContent = { Text("导出档案") },
                supportingContent = { Text("导出所有档案为 JSON") },
            )
            ListItem(
                headlineContent = { Text("导入档案") },
                supportingContent = { Text("从 JSON 文件导入") },
            )
            ListItem(
                headlineContent = { Text("清空所有档案") },
                supportingContent = { Text("删除所有已保存的档案") },
            )
            HorizontalDivider()

            // --- 关于 ---
            SectionHeader("关于")
            ListItem(
                headlineContent = { Text("版本") },
                supportingContent = { Text("3.0.0") },
            )
            ListItem(
                headlineContent = { Text("GitHub") },
                supportingContent = { Text("TERRYYYC/FakeGps-test") },
            )
        }
    }

    // Mode selection dialog
    if (showRefreshDialog) {
        AlertDialog(
            onDismissRequest = { showRefreshDialog = false },
            title = { Text("刷新间隔") },
            text = {
                Column {
                    // Choices come from the policy so this screen cannot offer a cadence the
                    // hook is not allowed to run at.
                    for (sec in vm.refreshIntervalChoicesSec) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setRefreshIntervalSec(sec)
                                    showRefreshDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = refreshIntervalSec == sec,
                                onClick = {
                                    vm.setRefreshIntervalSec(sec)
                                    showRefreshDialog = false
                                },
                            )
                            Text(
                                text = "$sec 秒",
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Text(
                        text = "Hook 按这个周期重新读取配置。\n" +
                                "改完档案后，最长要等一个周期才会在目标 App 上生效——" +
                                "间隔越短生效越快，代价是更频繁的读取。\n" +
                                "注意：刚改完周期时，第一次刷新可能仍按上一个周期等待。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showRefreshDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showModeDialog) {
        val options = listOf(
            SpoofSettings.MODE_ALWAYS_ON to "始终开启",
            SpoofSettings.MODE_TIME_BASED to "按时段",
            SpoofSettings.MODE_OFF to "关闭",
        )
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("伪装模式") },
            text = {
                Column {
                    for ((value, label) in options) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.setSpoofMode(value)
                                    showModeDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = spoofMode == value,
                                onClick = {
                                    vm.setSpoofMode(value)
                                    showModeDialog = false
                                },
                            )
                            Text(
                                text = label,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    Text(
                        text = "始终开启：忽略时段，始终使用第一条档案\n" +
                                "按时段：仅在配置的时段内伪装\n" +
                                "关闭：透传真实设备信息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showModeDialog = false }) { Text("取消") }
            },
        )
    }

    // Hour picker dialogs
    if (showHourStartDialog) {
        HourPickerDialog(
            title = "开始时间",
            currentHour = hourStart,
            onConfirm = { vm.setActiveHourStart(it); showHourStartDialog = false },
            onDismiss = { showHourStartDialog = false },
        )
    }

    if (showHourEndDialog) {
        HourPickerDialog(
            title = "结束时间",
            currentHour = hourEnd,
            onConfirm = { vm.setActiveHourEnd(it); showHourEndDialog = false },
            onDismiss = { showHourEndDialog = false },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HourPickerDialog(
    title: String,
    currentHour: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(currentHour.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "%02d:00".format(selected.roundToInt()),
                    style = MaterialTheme.typography.displaySmall,
                )
                Slider(
                    value = selected,
                    onValueChange = { selected = it },
                    valueRange = 0f..23f,
                    steps = 22,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("00:00", style = MaterialTheme.typography.bodySmall)
                    Text("23:00", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.roundToInt()) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun modeDisplayName(mode: String): String = when (mode) {
    SpoofSettings.MODE_ALWAYS_ON -> "始终开启"
    SpoofSettings.MODE_TIME_BASED -> "按时段"
    SpoofSettings.MODE_OFF -> "关闭"
    else -> mode
}
