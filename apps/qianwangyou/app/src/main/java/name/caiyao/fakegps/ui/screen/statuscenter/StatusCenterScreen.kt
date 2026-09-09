package name.caiyao.fakegps.ui.screen.statuscenter

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import name.caiyao.fakegps.ui.theme.LocalShadcnSemantic
import name.caiyao.fakegps.ui.theme.ShadcnCard

/**
 * M1 状态中心（T11b）——QWY app 的默认落地页。
 *
 * 布局遵循高保真 v3 M1 三态稿 + 极简纪律（正文 ≤3 行/卡，详情进次级页/抽屉）：
 * 待办条 → 生效档案卡（点进档案页换档案/一键锚定）→ 三灯 → 模块七开关行 → 运动链入口
 * （无播放会话时隐藏）。一切绿/黄/灰与出现/消失的决策来自 [StatusCenterProjection]，
 * 本屏只做薄投影；语义色/卡片体系与 Auto 侧 RunDashboardScreen 同一套（shadcn 中性）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatusCenterScreen(
    onOpenMap: () -> Unit,
    onOpenCollection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenVerify: () -> Unit,
    vm: StatusCenterViewModel = viewModel(),
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val ui by vm.ui.collectAsState()
    val pendingCallers by vm.pendingCallers.collectAsState()
    val anchorNotice by vm.anchorNotice.collectAsState()
    val publishFailure by vm.publishFailure.collectAsState()

    // 每次进入屏幕（或从其他页返回）都回读 publish_state / pending callers。
    LaunchedEffect(Unit) { vm.refresh() }

    LaunchedEffect(anchorNotice) {
        anchorNotice?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissAnchorNotice()
        }
    }
    LaunchedEffect(publishFailure) {
        publishFailure?.let {
            snackbarHostState.showSnackbar(it)
            vm.dismissPublishFailure()
        }
    }

    var approveTarget by remember { mutableStateOf<String?>(null) }
    approveTarget?.let { pkg ->
        ApprovalHintDialog(
            packageName = pkg,
            onDismiss = { approveTarget = null },
            onOpenSettings = {
                approveTarget = null
                onOpenSettings()
            },
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    text = "fakexxx-map",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 16.dp),
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    label = { Text("状态中心") },
                    selected = true,
                    onClick = { scope.launch { drawerState.close() } },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Bookmarks, contentDescription = null) },
                    label = { Text("收藏档案") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenCollection()
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Map, contentDescription = null) },
                    label = { Text("地图") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenMap()
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("设置") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenSettings()
                    },
                )
                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.VerifiedUser, contentDescription = null) },
                    label = { Text("验证") },
                    selected = false,
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenVerify()
                    },
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text("状态中心") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "菜单")
                        }
                    },
                )
            },
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---- 待办条（契约通道 + DB）：等待批准 Auto / 档案未锚定 ----
                for (todo in ui.todos) {
                    item(key = "todo_${todo.id}") {
                        TodoBanner(
                            title = todo.title,
                            detail = todo.detail,
                            actionLabel = if (todo.id == "pending_callers") "去批准" else null,
                            onAction = {
                                if (todo.id == "pending_callers") {
                                    approveTarget = pendingCallers
                                        .firstOrNull()?.callerApplicationId
                                } else {
                                    onOpenCollection()
                                }
                            },
                        )
                    }
                }

                // ---- 生效档案卡（无档案/已锚定/发布失败三态；点击=换档案锚定入口）----
                item(key = "profile") {
                    ProfileCard(ui.profile, onOpenCollection)
                }

                // ---- 三灯 ----
                item(key = "lamps") {
                    LampsCard(
                        publish = ui.publish,
                        vector = ui.vector,
                        mock = ui.mock,
                        onRepublish = vm::republish,
                    )
                }

                // ---- 模块开关行（七模块）----
                item(key = "modules") {
                    ModulesCard(
                        rows = ui.modules,
                        onToggle = vm::setModuleEnabled,
                    )
                }

                // ---- 运动链入口（T9 播放状态；无会话则隐藏）----
                ui.motion?.let { motion ->
                    item(key = "motion") {
                        MotionEntryCard(motion)
                    }
                }
            }
        }
    }
}

/** T11c 前的过渡：批准动作仍在设置页，这里只指路（deep link 归 T11c）。 */
@Composable
private fun ApprovalHintDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("等待批准 Auto") },
        text = {
            Text(
                "调用方：$packageName\n批准入口在「设置 → 待批准的 Auto」，" +
                    "请核对包名与完整签名摘要。",
            )
        },
        confirmButton = { TextButton(onClick = onOpenSettings) { Text("去批准") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍后") } },
    )
}

@Composable
private fun TodoBanner(
    title: String,
    detail: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            if (actionLabel != null) {
                TextButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: ProfileCardUi, onOpenCollection: () -> Unit) {
    val (container, content) = when (profile.state) {
        ProfileCardState.PUBLISH_FAILED ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        ProfileCardState.ANCHORED ->
            MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        ProfileCardState.NO_PROFILE ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenCollection),
        shape = MaterialTheme.shapes.large,
        color = container,
        contentColor = content,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "生效档案",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                )
                StateChip(profile.chipText, content)
            }
            Spacer(Modifier.height(4.dp))
            // 正文 ≤3 行：名 / 坐标 / 交付模式；字段详情在档案页与编辑器。
            Text(
                profile.name ?: "还没有档案",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                profile.coordinate ?: "点击选择档案并锚定",
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.deliveryLabel,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = "换档案",
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StateChip(text: String, accent: Color) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = accent.copy(alpha = 0.12f),
        contentColor = accent,
        tonalElevation = 0.dp,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun LampsCard(
    publish: StatusLamp,
    vector: StatusLamp,
    mock: StatusLamp,
    onRepublish: () -> Unit,
) {
    ShadcnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "三灯",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (publish.state == LampState.YELLOW) {
                    TextButton(onClick = onRepublish) { Text("重新发布") }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Lamp("发布", publish, Modifier.weight(1f))
                Lamp("Vector", vector, Modifier.weight(1f))
                Lamp("Mock", mock, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Lamp(label: String, lamp: StatusLamp, modifier: Modifier = Modifier) {
    val semantic = LocalShadcnSemantic.current
    val color = when (lamp.state) {
        LampState.GREEN -> semantic.green
        LampState.YELLOW -> semantic.amber
        LampState.GREY -> semantic.grayDot
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(color, CircleShape),
            )
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            lamp.detail,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
        )
    }
}

@Composable
private fun ModulesCard(
    rows: List<StatusCenterViewModel.Ui.ModuleRow>,
    onToggle: (String, Boolean) -> Unit,
) {
    ShadcnCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("模块", style = MaterialTheme.typography.titleSmall)
            Text(
                "开关立即发布；目标 App 下次重启读到原生数据。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            // 两列紧凑网格（七模块），行内只有标签 + 开关——每模块的详细说明在设置页。
            for (chunk in rows.chunked(2)) {
                Row(Modifier.fillMaxWidth()) {
                    for (row in chunk) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                row.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = row.enabled,
                                onCheckedChange = { checked -> onToggle(row.module, checked) },
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                    if (chunk.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MotionEntryCard(motion: MotionEntryUi) {
    val semantic = LocalShadcnSemantic.current
    val accent = if (motion.playing) semantic.blue else semantic.green
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 0.dp,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(accent.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Route,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(motion.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    motion.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StateChip(if (motion.playing) "播放中" else "已完成", accent)
        }
    }
}
