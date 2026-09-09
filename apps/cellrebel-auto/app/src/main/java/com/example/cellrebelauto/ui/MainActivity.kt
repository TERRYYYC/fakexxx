package com.example.cellrebelauto.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cellrebelauto.cutover.CutoverDataState
import com.example.cellrebelauto.cutover.CutoverUnavailableReason
import com.example.cellrebelauto.ui.theme.CellRebelAutoTheme

/**
 * Main activity — hosts the Compose UI.
 * # 主 Activity，承载 Compose 界面
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // T11c: `fakexxx-auto://providers` lands directly on the Provider page.
        // Navigation only — the URI becomes a Screen, nothing else is read from it.
        val deepLinkScreen = CrossAppDeepLinks.routeToScreen(intent?.data)
        setContent {
            CellRebelAutoTheme {
                MainApp(initialScreen = deepLinkScreen)
            }
        }
    }
}

/**
 * Root composable that manages screen navigation via ViewModel.
 * # 根 Composable，通过 ViewModel 管理页面导航
 */
@Composable
fun MainApp(vm: MainViewModel = viewModel(), initialScreen: Screen? = null) {
    val currentScreen by vm.currentScreen.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val currentState by vm.currentState.collectAsState()
    val cycleCount by vm.cycleCount.collectAsState()
    val logs by vm.logs.collectAsState()
    val attempts by vm.attempts.collectAsState()
    val legacyResults by vm.legacyResults.collectAsState()
    val isServiceConnected by vm.isServiceConnected.collectAsState()
    // Issue #9: readable device-readiness line for the Plan surface (null = healthy).
    val serviceStatusLine by vm.serviceStatusLine.collectAsState()
    val planState by vm.planUiState.collectAsState()
    val planConfig by vm.planConfig.collectAsState()
    val importErrors by vm.importErrors.collectAsState()
    val importNotice by vm.importNotice.collectAsState()
    val importProposal by vm.importProposal.collectAsState()
    // P0.1-5: plan↔profile count mismatch warning after a CSV import.
    val planProfileMismatch by vm.planProfileMismatch.collectAsState()
    val isImportReplacementStopping by vm.isImportReplacementStopping.collectAsState()
    // T8 (P0.3): configuration bundle state (conflict prompt, fingerprints, warnings).
    val bundleConflict by vm.bundleConflict.collectAsState()
    val bundlePairingFingerprints by vm.bundlePairingFingerprints.collectAsState()
    val currentTask by vm.currentTask.collectAsState()
    val cooldown by vm.cooldown.collectAsState()
    val lastFailure by vm.lastFailure.collectAsState()
    val pairingState by vm.pairingUiState.collectAsState()

    // T11c: a deep link arriving with the launch intent overrides the landing page once.
    androidx.compose.runtime.LaunchedEffect(initialScreen) {
        initialScreen?.let { vm.navigateTo(it) }
    }

    // #139 返回栈矩阵（AutoBottomNav.kt）：历史→计划、计划/Provider→运行台；
    // 运行台没有 backTarget → BackHandler 关闭 → 系统默认行为 = 退出 app。
    val backTarget = AutoBottomNav.backTarget(currentScreen)
    androidx.activity.compose.BackHandler(enabled = backTarget != null) {
        backTarget?.let { vm.navigateTo(it) }
    }

    // # targetSdk 35 强制 edge-to-edge：统一处理状态栏/导航栏 insets，
    // # 否则标题绘制在状态栏下、右上角服务指示被裁切
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                when (currentScreen) {
            Screen.PLAN -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    // The product importer must stay reachable while normal data is recovery-closed.
                    CutoverSafSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        // # Issue #9：进入 Plan 页即重探无障碍启用态（从系统设置返回后也会重新进入本页）
                        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshDeviceReadiness() }
                        CutoverDataBoundary(planState) { readyPlanState ->
                            CutoverDataBoundary(planConfig) { readyPlanConfig ->
                                PlanScreen(
                                    planState = readyPlanState,
                                    planConfig = readyPlanConfig,
                                    isRunning = isRunning,
                                    isServiceConnected = isServiceConnected,
                                    serviceStatusLine = serviceStatusLine,
                                    importErrors = importErrors,
                                    importNotice = importNotice,
                                    importProposal = importProposal,
                                    // P0.1: plan↔profile count mismatch warning (T2)
                                    planProfileMismatch = planProfileMismatch,
                                    isImportReplacementStopping = isImportReplacementStopping,
                                    onImport = { vm.importCsv(it) },
                                    onConfirmImportReplacement = { vm.confirmImportReplacement() },
                                    onCancelImportReplacement = { vm.cancelImportReplacement() },
                                    onSetGlobalBuffer = { vm.setGlobalBuffer(it) },
                                    onSetTestTimeout = { vm.setTestTimeout(it) },
                                    onSetGpsSettle = { vm.setGpsSettle(it) },
                                    onSetLocationStage = { vm.setLocationStageEnabled(it) },
                                    onSetTestStage = { vm.setTestStageEnabled(it) },
                                    onStartOrResume = { vm.startOrResumePlan() },
                                    onStop = { vm.stopAutomation() },
                                    onOpenRun = { vm.navigateTo(Screen.RUN) },
                                    onOpenHistory = { vm.navigateTo(Screen.HISTORY) },
                                    // #12：计划重置入口（provider 侧命令文本供确认框复制）
                                    // CutoverSafSurface + double CutoverDataBoundary wrapper.
                                    // (onAbandonPlan) — re-add it mechanically on merge.
                                    onResetPlan = { vm.resetPlan() },
                                    // #135：放弃当前计划入口（确认框在 PlanScreen 内）
                                    onAbandonPlan = { vm.abandonPlan() },
                                    // #140：快速重置入口（两步确认框在 PlanScreen 内；一次操作双 app 生效）
                                    onQuickReset = { vm.quickResetAll() },
                                    providerScheduleResetCommand = vm.providerScheduleResetCommand,
                                    providerPairingApprovalCommand = vm.providerPairingApprovalCommand,
                                    // T8 (P0.3): configuration bundle export/import + conflict surfaces.
                                    onExportBundle = { vm.exportConfigBundle(it) },
                                    onImportBundle = { vm.importConfigBundle(it) },
                                    bundleConflict = bundleConflict,
                                    onBundleOverwrite = { vm.confirmBundleOverwrite() },
                                    onBundleSkip = { vm.skipBundlePlanApply() },
                                    onBundleConflictDismiss = { vm.dismissBundleConflict() },
                                    bundlePairingFingerprints = bundlePairingFingerprints,
                                    onDismissBundleFingerprints = { vm.dismissBundleFingerprints() }
                                )
                            }
                        }
                    }
                }
            }
            Screen.RUN -> {
                // T7 P1.1: the RUN surface IS the run dashboard — the app's landing
                // page. Entry refreshes the lamps + the a11y enablement probe.
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    vm.refreshDeviceReadiness()
                    vm.refreshDashboardHealth()
                }
                RunDashboardScreen(
                    state = vm.dashboardState.collectAsState().value,
                    metricSelection = vm.metricSelection.collectAsState().value,
                    logs = logs,
                    selfHealConfig = vm.selfHealConfig.collectAsState().value,
                    // T-tilemap: 瓦片底图开关（DataStore 持久化）+ sticky 失败位
                    mapTilesEnabled = vm.mapTilesEnabled.collectAsState().value,
                    mapTileFailure = vm.mapTileFailure.collectAsState().value,
                    onSetMapTilesEnabled = { vm.setMapTilesEnabled(it) },
                    onReportTileFailure = { vm.reportTileLoadFailure() },
                    onClearTileFailure = { vm.clearTileLoadFailure() },
                    // T7v2: 重启恢复/启动 = 同一 startOrResumePlan 入口；停止/导出/导航同 v1
                    onResume = { vm.resumeRun() },
                    onStop = { vm.stopAutomation() },
                    // #139：History 不再从运行台可达（收进计划页）；底栏替代页内导航行
                    onOpenPlan = { vm.navigateTo(Screen.PLAN) },
                    onOpenProviders = { vm.navigateTo(Screen.PROVIDERS) },
                    onResetPlan = { vm.resetPlan() },
                    onExportDiagnostics = { vm.exportDiagnosticBundle() },
                    onSetAttemptWatchdog = { vm.setAttemptWatchdogEnabled(it) },
                    onSetCoordinateGuard = { vm.setCoordinateGuardEnabled(it) },
                    onSetServiceAutoResume = { vm.setServiceReconnectAutoResumeEnabled(it) },
                    onSetMetricSelection = { vm.setMetricSelection(it) },
                    resumeOutcome = vm.resumeOutcome.collectAsState().value,
                    onConsumeResumeOutcome = { vm.consumeResumeOutcome() },
                )
            }

            Screen.HISTORY -> {
                CutoverDataBoundary(attempts) { readyAttempts ->
                    CutoverDataBoundary(legacyResults) { readyLegacyResults ->
                        HistoryScreen(
                            attempts = readyAttempts,
                            legacyResults = readyLegacyResults,
                            onExportCsv = { vm.exportCsv() },
                            onBack = { vm.navigateTo(Screen.PLAN) }
                        )
                    }
                }
            }

            Screen.PROVIDERS -> {
                // R43 (spec Task 6): the §6.5.3 operator approval/revocation surface.
                // T11c: entry also probes 对方（QWY）是否已批准我方（既有 discover 通道）。
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    vm.refreshProviders()
                    vm.refreshPeerApproval()
                }
                val entries by vm.providerEntries.collectAsState()
                // # Issue #10：撤销走 暂存→确认对话框→执行；撤销后横幅说明引擎影响
                val revokeCandidate by vm.revokeCandidate.collectAsState()
                val revokeNotice by vm.revokeImpactNotice.collectAsState()
                val peerApprovalTodo = PeerApprovalTodoBar.project(
                    vm.peerPairingStatus.collectAsState().value
                )
                val context = androidx.compose.ui.platform.LocalContext.current
                CutoverDataBoundary(entries) { readyEntries ->
                    ProviderApprovalScreen(
                        pending = readyEntries.filter { !it.isApproved },
                        approved = readyEntries.filter { it.isApproved },
                        onApprove = { vm.approveProvider(it) },
                        onRevoke = { vm.requestRevoke(it) },
                        // #139：Provider 升为底栏顶层 tab——不再有页内返回按钮
                        revokeDialog = revokeCandidate?.let {
                            ProviderRevokeDialogState(candidate = it)
                        },
                        onRevokeConfirmed = { vm.confirmRevoke() },
                        onRevokeDismissed = { vm.dismissRevokeDialog() },
                        revokeImpactNotice = revokeNotice,
                        onRevokeNoticeDismissed = { vm.dismissRevokeNotice() },
                        peerApprovalTodo = peerApprovalTodo,
                        onOpenPeerApproval = {
                            // 导航 only：跳不出去（对方未安装）时如实说明，绝不静默。
                            if (!CrossAppDeepLinks.launchPeerPending(context)) {
                                android.widget.Toast.makeText(
                                    context,
                                    "未找到千网游（fakexxx-map）— 请先安装并批准配对",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        },
                        modifier = Modifier
                    )
                }
            }
        }
        }

            // ---- #139 底栏：运行台/计划/Provider 三个顶层 tab（v3 原型）----
            // 只在 tab 页显示；History 是子页，从计划页进。
            val currentTab = AutoBottomNav.tabOf(currentScreen)
            if (currentTab != null) {
                NavigationBar {
                    AutoBottomNav.tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = tab.screen == currentScreen,
                            onClick = { vm.navigateTo(tab.screen) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> CutoverDataBoundary(
    state: CutoverDataState<T>,
    content: @Composable (T) -> Unit
) {
    when (state) {
        CutoverDataState.Loading -> ProtectedDataStatus("Loading protected data…")
        is CutoverDataState.Ready -> content(state.value)
        is CutoverDataState.Unavailable -> ProtectedDataStatus(
            when (state.reason) {
                CutoverUnavailableReason.CUTOVER_IN_PROGRESS ->
                    "Protected data is unavailable while migration is in progress"
                CutoverUnavailableReason.RECOVERY_REQUIRED ->
                    "Protected data requires migration recovery"
            }
        )
    }
}

@Composable
private fun ProtectedDataStatus(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(message)
    }
}
