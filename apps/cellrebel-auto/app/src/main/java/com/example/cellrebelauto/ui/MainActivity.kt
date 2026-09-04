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
        setContent {
            CellRebelAutoTheme {
                MainApp()
            }
        }
    }
}

/**
 * Root composable that manages screen navigation via ViewModel.
 * # 根 Composable，通过 ViewModel 管理页面导航
 */
@Composable
fun MainApp(vm: MainViewModel = viewModel()) {
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
    val isImportReplacementStopping by vm.isImportReplacementStopping.collectAsState()
    val currentTask by vm.currentTask.collectAsState()
    val cooldown by vm.cooldown.collectAsState()
    val lastFailure by vm.lastFailure.collectAsState()
    val pairingState by vm.pairingUiState.collectAsState()

    // # targetSdk 35 强制 edge-to-edge：统一处理状态栏/导航栏 insets，
    // # 否则标题绘制在状态栏下、右上角服务指示被裁切
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
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
                                    onOpenProviders = { vm.navigateTo(Screen.PROVIDERS) },
                                    onOpenRun = { vm.navigateTo(Screen.RUN) },
                                    onOpenHistory = { vm.navigateTo(Screen.HISTORY) },
                                    // #12：计划重置入口（provider 侧命令文本供确认框复制）
                                    // Rebase note: T3's plan-reset entry rides inside main's
                                    // CutoverSafSurface + double CutoverDataBoundary wrapper.
                                    onResetPlan = { vm.resetPlan() },
                                    providerScheduleResetCommand = vm.providerScheduleResetCommand,
                                    providerPairingApprovalCommand = vm.providerPairingApprovalCommand
                                )
                            }
                        }
                    }
                }
            }

            Screen.RUN -> {
                CutoverDataBoundary(planState) { readyPlanState ->
                    CutoverDataBoundary(pairingState) { readyPairingState ->
                        ControlScreen(
                            isRunning = isRunning,
                            currentState = currentState,
                            cycleCount = cycleCount,
                            currentTask = currentTask,
                            cooldown = cooldown,
                            lastFailure = lastFailure,
                            planCompletedSuccesses = readyPlanState.completedSuccesses,
                            planTotalSuccesses = readyPlanState.plan?.totalRequiredSuccesses ?: 0,
                            logs = logs,
                            isServiceConnected = isServiceConnected,
                            onStop = { vm.stopAutomation() },
                            onOpenPlan = { vm.navigateTo(Screen.PLAN) },
                            onOpenHistory = { vm.navigateTo(Screen.HISTORY) },
                            pairingUiState = readyPairingState,
                            onOpenProviders = { vm.navigateTo(Screen.PROVIDERS) },
                            onExportLogs = { vm.exportLogs() },
                            onDumpA11yTree = { vm.dumpAccessibilityTree() }
                        )
                    }
                }
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
                androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshProviders() }
                val entries by vm.providerEntries.collectAsState()
                // # Issue #10：撤销走 暂存→确认对话框→执行；撤销后横幅说明引擎影响
                val revokeCandidate by vm.revokeCandidate.collectAsState()
                val revokeNotice by vm.revokeImpactNotice.collectAsState()
                CutoverDataBoundary(entries) { readyEntries ->
                    ProviderApprovalScreen(
                        pending = readyEntries.filter { !it.isApproved },
                        approved = readyEntries.filter { it.isApproved },
                        onApprove = { vm.approveProvider(it) },
                        onRevoke = { vm.requestRevoke(it) },
                        onBack = { vm.navigateTo(Screen.PLAN) },
                        revokeDialog = revokeCandidate?.let {
                            ProviderRevokeDialogState(candidate = it)
                        },
                        onRevokeConfirmed = { vm.confirmRevoke() },
                        onRevokeDismissed = { vm.dismissRevokeDialog() },
                        revokeImpactNotice = revokeNotice,
                        onRevokeNoticeDismissed = { vm.dismissRevokeNotice() },
                        modifier = Modifier
                    )
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
