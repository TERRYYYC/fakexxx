package com.example.cellrebelauto.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val currentTask by vm.currentTask.collectAsState()
    val cooldown by vm.cooldown.collectAsState()
    val lastFailure by vm.lastFailure.collectAsState()

    // # targetSdk 35 强制 edge-to-edge：统一处理状态栏/导航栏 insets，
    // # 否则标题绘制在状态栏下、右上角服务指示被裁切
    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
        when (currentScreen) {
        Screen.PLAN -> {
            // # Issue #9：进入 Plan 页即重探无障碍启用态（从系统设置返回后也会重新进入本页）
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshDeviceReadiness() }
            PlanScreen(
                planState = planState,
                planConfig = planConfig,
                isRunning = isRunning,
                isServiceConnected = isServiceConnected,
                serviceStatusLine = serviceStatusLine,
                importErrors = importErrors,
                importNotice = importNotice,
                onImport = { vm.importCsv(it) },
                onSetGlobalBuffer = { vm.setGlobalBuffer(it) },
                onSetTestTimeout = { vm.setTestTimeout(it) },
                onSetGpsSettle = { vm.setGpsSettle(it) },
                onSetLocationStage = { vm.setLocationStageEnabled(it) },
                onSetTestStage = { vm.setTestStageEnabled(it) },
                onStartOrResume = { vm.startOrResumePlan() },
                onStop = { vm.stopAutomation() },
                onOpenProviders = { vm.navigateTo(Screen.PROVIDERS) },
                onOpenRun = { vm.navigateTo(Screen.RUN) },
                onOpenHistory = { vm.navigateTo(Screen.HISTORY) }
            )
        }

        Screen.RUN -> {
            ControlScreen(
                isRunning = isRunning,
                currentState = currentState,
                cycleCount = cycleCount,
                currentTask = currentTask,
                cooldown = cooldown,
                lastFailure = lastFailure,
                planCompletedSuccesses = planState.completedSuccesses,
                planTotalSuccesses = planState.plan?.totalRequiredSuccesses ?: 0,
                logs = logs,
                isServiceConnected = isServiceConnected,
                onStop = { vm.stopAutomation() },
                onOpenPlan = { vm.navigateTo(Screen.PLAN) },
                onOpenHistory = { vm.navigateTo(Screen.HISTORY) },
                // R44 F5: the seven-state incident card + provider-management entry.
                pairingUiState = vm.pairingUiState.collectAsState().value,
                onOpenProviders = { vm.navigateTo(Screen.PROVIDERS) },
                // # 调试功能
                onExportLogs = { vm.exportLogs() },
                onDumpA11yTree = { vm.dumpAccessibilityTree() }
            )
        }

        Screen.HISTORY -> {
            HistoryScreen(
                attempts = attempts,
                legacyResults = legacyResults,
                onExportCsv = { vm.exportCsv() },
                onBack = { vm.navigateTo(Screen.PLAN) }
            )
        }

        Screen.PROVIDERS -> {
            // R43 (spec Task 6): the §6.5.3 operator approval/revocation surface.
            androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshProviders() }
            val entries by vm.providerEntries.collectAsState()
            // # Issue #10：撤销走 暂存→确认对话框→执行；撤销后横幅说明引擎影响
            val revokeCandidate by vm.revokeCandidate.collectAsState()
            val revokeNotice by vm.revokeImpactNotice.collectAsState()
            ProviderApprovalScreen(
                pending = entries.filter { !it.isApproved },
                approved = entries.filter { it.isApproved },
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
