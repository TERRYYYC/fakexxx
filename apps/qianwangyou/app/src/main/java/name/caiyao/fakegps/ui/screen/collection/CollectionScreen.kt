package name.caiyao.fakegps.ui.screen.collection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import name.caiyao.fakegps.data.db.ProfileSummary
import name.caiyao.fakegps.data.importer.ProfileImportTemplate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    onEditProfile: (id: Long, lat: Double, lon: Double) -> Unit,
    onBack: () -> Unit,
    vm: CollectionViewModel = viewModel(),
) {
    val profiles by vm.profiles.collectAsState()
    val effectiveId by vm.effectiveProfileId.collectAsState()
    val importState by vm.importState.collectAsState()
    val templateSaveState by vm.templateSaveState.collectAsState()
    var showClearDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProfileSummary?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::previewImport)
    }
    val templateLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(ProfileImportTemplate.MIME_TYPE),
    ) { uri ->
        uri?.let(vm::saveImportTemplate)
    }

    LaunchedEffect(templateSaveState) {
        val message = when (val state = templateSaveState) {
            ProfileTemplateSaveState.Idle,
            ProfileTemplateSaveState.Saving,
            -> null
            ProfileTemplateSaveState.Success -> "导入模板已保存"
            is ProfileTemplateSaveState.Failure -> "模板保存失败：${state.message}"
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            vm.dismissTemplateSaveResult()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("收藏档案 (${profiles.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    val importBusy = importState is ProfileImportUiState.Parsing ||
                        importState is ProfileImportUiState.Importing
                    IconButton(
                        enabled = ProfileTemplateSaveReducer.canStart(templateSaveState),
                        onClick = { templateLauncher.launch(ProfileImportTemplate.DEFAULT_FILE_NAME) },
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "下载导入模板")
                    }
                    IconButton(
                        enabled = !importBusy,
                        onClick = {
                            importLauncher.launch(
                                arrayOf(
                                    "text/csv",
                                    "text/comma-separated-values",
                                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                    "application/octet-stream",
                                ),
                            )
                        },
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = "导入 CSV/Excel")
                    }
                    if (profiles.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "清空")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text("暂无档案", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "__effective_hint") {
                    Text(
                        text = if (effectiveId == null) {
                            "当前没有收藏档案与已发布 Hook 配置匹配；导入只新增收藏，不会自动生效。"
                        } else {
                            "只有标记「生效中」的档案与已发布 Hook 配置一致。" +
                                "编辑其它档案不会改变伪装结果。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isEffective = profile.id == effectiveId,
                        onClick = {
                            onEditProfile(
                                profile.id,
                                profile.latitude ?: 0.0,
                                profile.longitude ?: 0.0,
                            )
                        },
                        onDelete = { deleteTarget = profile },
                    )
                }
            }
        }
    }

    // Delete single
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除档案") },
            text = { Text("确定删除 \"${target.addname ?: "未命名"}\"？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.id)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }

    // Delete all
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空所有档案") },
            text = { Text("删除全部 ${profiles.size} 个档案？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    showClearDialog = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    ProfileImportDialogs(
        state = importState,
        onConfirm = vm::confirmImport,
        onDismiss = vm::dismissImport,
    )
}

@Composable
private fun ProfileImportDialogs(
    state: ProfileImportUiState,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        ProfileImportUiState.Idle -> Unit
        is ProfileImportUiState.Parsing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在校验") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("正在读取 ${state.fileName}…")
                }
            },
            confirmButton = {},
        )
        is ProfileImportUiState.Preview -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("确认导入") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.fileName, style = MaterialTheme.typography.titleSmall)
                    Text("数据行：${state.dataRows}")
                    Text("将新增：${state.records.size}")
                    Text("文件内重复：${state.fileDuplicates}")
                    Text(
                        "导入只新增收藏，不替换现有档案，也不会改变「生效中」档案或已发布 Hook 配置。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) { Text("确认导入") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            },
        )
        is ProfileImportUiState.Invalid -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("文件无法导入") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(state.fileName, style = MaterialTheme.typography.titleSmall)
                    state.issues.forEach { issue ->
                        val location = buildString {
                            issue.row?.let { append("第 ${it} 行") }
                            issue.column?.let {
                                if (isNotEmpty()) append(" · ")
                                append(it)
                            }
                        }
                        Text(
                            text = if (location.isEmpty()) issue.message else "$location：${issue.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text("未写入任何档案。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            },
        )
        is ProfileImportUiState.Importing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在导入") },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text("正在原子写入 ${state.fileName}…")
                }
            },
            confirmButton = {},
        )
        is ProfileImportUiState.Success -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导入完成") },
            text = {
                Text("新增 ${state.imported} 个档案，跳过重复 ${state.duplicates} 个。生效档案未改变。")
            },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("完成") }
            },
        )
        is ProfileImportUiState.Failure -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("导入失败") },
            text = { Text("${state.message}\n事务已回滚，未留下半批数据。") },
            confirmButton = {
                TextButton(onClick = onDismiss) { Text("知道了") }
            },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: ProfileSummary,
    isEffective: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isEffective) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = profile.addname ?: "未命名",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (isEffective) {
                            Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                Text("生效中")
                            }
                        }
                    }
                    val coords = buildString {
                        profile.latitude?.let { append("%.4f".format(it)) }
                        append(", ")
                        profile.longitude?.let { append("%.4f".format(it)) }
                    }
                    Text(
                        text = coords,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
