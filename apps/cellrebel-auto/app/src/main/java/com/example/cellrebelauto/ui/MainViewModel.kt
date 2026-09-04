package com.example.cellrebelauto.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.automation.CooldownInfo
import com.example.cellrebelauto.automation.EngineTaskSnapshot
import com.example.cellrebelauto.automation.LastFailureInfo
import com.example.cellrebelauto.automation.plan.PlanScheduler
import com.example.cellrebelauto.data.PlanConfigStore
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.plan.AttemptWithTask
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.ParseResult
import com.example.cellrebelauto.model.plan.PlanConfig
import com.example.cellrebelauto.model.plan.RowError
import com.example.cellrebelauto.model.plan.WorklistParser
import com.example.cellrebelauto.repository.PlanRepository
import com.example.cellrebelauto.util.CsvExporter
import com.example.cellrebelauto.util.DebugExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Immutable view of the Plan screen: latest plan + tasks in execution order
 * + per-task attempt counts. Plan/task status is a pure projection (O1/O2) —
 * nothing here is persisted beyond the Room rows themselves.
 * # Plan 页的不可变视图：最近计划 + 执行顺序任务 + 每任务尝试数。
 * # 计划/任务状态是纯投影，不落第二份状态
 */
data class PlanUiState(
    val plan: LocationPlan? = null,
    // # 已按执行顺序（priority ASC, csvRow ASC）排序
    val tasks: List<LocationTask> = emptyList(),
    // # taskId -> 尝试总数
    val attemptCounts: Map<Long, Int> = emptyMap(),
    // # taskId -> 可信成功数（§7.3 进度唯一投影；legacy completedSuccesses 列在可信路径下冻结不写）
    val trustedCounts: Map<Long, Int> = emptyMap()
) {
    // # 已验证成功总数（计划级进度）——可信计数求和，不读 legacy 列
    val completedSuccesses: Int get() = trustedCounts.values.sum()

    // # 计划未完成：存在未 completed 的任务
    val isUnfinished: Boolean
        get() = plan != null && tasks.isNotEmpty() && tasks.any { it.status != "completed" }

    // # 计划已启动过：有非 pending 任务或已有尝试记录
    val isStarted: Boolean
        get() = tasks.any { it.status != "pending" } || attemptCounts.isNotEmpty()

    // # 计划全部完成
    val isComplete: Boolean
        get() = plan != null && tasks.isNotEmpty() && tasks.all { it.status == "completed" }
}

/**
 * ViewModel for the main UI. Bridges AutomationService state
 * and provides actions for the Compose screens.
 *
 * # 主界面 ViewModel：桥接 AutomationService 的状态
 * # 并为 Compose 界面提供操作接口
 */
class MainViewModel @JvmOverloads constructor(
    application: Application,
    // R44 (DSF review P2-1): test-injectable DB — production keeps the singleton; oracles seed an
    // in-memory instance. The discovery/approval/revoke chain is thereby drivable end-to-end.
    private val injectedDb: AppDatabase? = null
) : AndroidViewModel(application) {

    private val db = injectedDb ?: AppDatabase.getInstance(application)
    private val planRepository = PlanRepository(db)
    private val planConfigStore = PlanConfigStore(application)

    // R43 (spec Task 6 / Sol GREEN-review-2 F5): the ProviderTrustStore PRODUCTION callers —
    // the operator approval/revocation surface (§6.5.3). No silent TOFU: approval is explicit.
    private val trustStore = com.example.cellrebelauto.environment.ProviderTrustStore(
        db.providerPairingDao()
    )
    private val _providerEntries = MutableStateFlow<List<ProviderEntry>>(emptyList())
    val providerEntries: StateFlow<List<ProviderEntry>> = _providerEntries

    // R44 (Sol GREEN-review-3 F5): the SEVEN-state production projection — derived from DURABLE
    // owner state only (pairing records + the crashed attempt's §8.1 phase + unverified records),
    // combined with the attempts flow the History projection already observes. This is the state
    // the run surface's PairingStatusCard renders; the UI never decides it.
    private val dbInstance = db
    val pairingUiState: kotlinx.coroutines.flow.StateFlow<PairingUiState> =
        kotlinx.coroutines.flow.combine(
            planRepository.observeAttemptsWithTasks(),
            _providerEntries
        ) { attempts, entries ->
            // R46 (Sol R46 P2): the Run surface's providerActive binds the CURRENT measured
            // principal — an approved DB row PLUS a discovered pending candidate for the same
            // appId means the signer rotated and the current signer is NOT approved (§6.5.4:
            // signer 变化即新 provider). The old `entries.any { it.isApproved }` kept showing
            // Trusted on a rotated-away principal.
            val hasActiveProvider = currentPrincipalActive(
                entries,
                com.example.cellrebelauto.automation.ProviderPrincipal.selected
            )
            val crashed = attempts.firstOrNull {
                it.attempt.status in setOf("starting", "running") && it.attempt.aplusState != null
            }?.attempt
            val hasUnverified = kotlinx.coroutines.runBlocking {
                dbInstance.unverifiedAttemptRecordDao().getByAttempt(
                    attempts.firstOrNull()?.attempt?.id ?: -1L
                ) != null
            }
            PairingUiState.project(
                hasProviderRecord = entries.isNotEmpty(),
                providerActive = hasActiveProvider,
                crashedAplusState = crashed?.aplusState,
                hasUnverifiedRecord = hasUnverified
            )
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Lazily, PairingUiState.Trusted)

    fun refreshProviders() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val rows = db.providerPairingDao().all()
            _providerEntries.value = computeProviderEntries(rows) { appId ->
                com.example.cellrebelauto.environment.ProviderTrustGate
                    .packageManagerSignerDigest(app.packageManager, appId)
            }
        }
    }

    companion object {
        /**
         * R46 (Sol R46 P2): True iff the provider's CURRENT principal is approved — an approved
         * row exists AND discovery surfaced no pending candidate for the same appId (a pending
         * candidate for an approved appId = the current signer rotated away from the approved
         * principal). Pure so the projection is oracle-drivable.
         * # 当前 principal 是否已批准：有 approved 行且同 appId 无 pending 候选（有 = signer 已轮转）
         */
        internal fun currentPrincipalActive(entries: List<ProviderEntry>, applicationId: String): Boolean =
            entries.any { it.applicationId == applicationId && it.isApproved } &&
                entries.none { it.applicationId == applicationId && !it.isApproved }

        /**
         * Pure projection (R45, Sol R45 P2): approved principals are the ACTIVE pairing rows; a
         * pending candidate is a KNOWN provider appId whose CURRENT resolved signer is NOT an
         * approved active principal. The current signer is resolved for EVERY known appId on EVERY
         * refresh — the previous `appId in approved` skip meant a signer ROTATION on an already-
         * approved appId was invisible: the UI kept showing the old principal as approved and no
         * new pending principal ever appeared, violating §6.5.4 ("signer 变化即视为新 provider，
         * 重新走批准"). Revoked rows are never pending candidates (M-PA-10: re-approval walks the
         * operator approval UI again, via this same discovery path).
         */
        internal fun computeProviderEntries(
            rows: List<com.example.cellrebelauto.model.plan.ProviderPairingRecord>,
            resolveCurrentSigner: (applicationId: String) -> String?
        ): List<ProviderEntry> {
            val approved = rows.filter { it.revokedAt == null }.map {
                ProviderEntry(
                    applicationId = it.applicationId,
                    signerDigest = it.currentSignerDigest,
                    approvedVersionCode = it.approvedVersionCode,
                    isApproved = true
                )
            }
            val activePrincipals = rows.filter { it.revokedAt == null }
                .map { it.applicationId to it.currentSignerDigest }.toSet()
            val pending = mutableListOf<ProviderEntry>()
            for (appId in com.example.cellrebelauto.automation.ProviderPrincipal.knownApplicationIds) {
                val signer = resolveCurrentSigner(appId) ?: continue // not installed / unresolvable
                if (appId to signer in activePrincipals) continue // the CURRENT signer IS approved
                pending += ProviderEntry(
                    applicationId = appId,
                    signerDigest = signer,
                    approvedVersionCode = null,
                    isApproved = false
                )
            }
            return approved + pending
        }
    }

    fun approveProvider(entry: ProviderEntry) {
        viewModelScope.launch {
            trustStore.approve(
                entry.applicationId, entry.signerDigest,
                entry.approvedVersionCode ?: 0, System.currentTimeMillis()
            )
            refreshProviders()
        }
    }

    fun revokeProvider(entry: ProviderEntry) {
        viewModelScope.launch {
            trustStore.revoke(entry.applicationId, entry.signerDigest, System.currentTimeMillis())
            refreshProviders()
        }
    }

    // ---- Navigation ----

    // # F001：Plan 页为首页（设计稿 v2.1 §1.1）
    private val _currentScreen = MutableStateFlow(Screen.PLAN)
    val currentScreen: StateFlow<Screen> = _currentScreen

    // ---- Data from service (delegated flows) ----

    // # 来自 AutomationService 的状态流
    val isRunning: StateFlow<Boolean> = AutomationService.isRunning
    val currentState: StateFlow<AutomationState> = AutomationService.currentState
    val cycleCount: StateFlow<Int> = AutomationService.cycleCount
    val logs: StateFlow<List<String>> = AutomationService.logs
    val isServiceConnected: StateFlow<Boolean> = AutomationService.isServiceConnected

    // # Run 页投影流（Task 11）
    val currentTask: StateFlow<EngineTaskSnapshot?> = AutomationService.currentTask
    val cooldown: StateFlow<CooldownInfo?> = AutomationService.cooldown
    val lastFailure: StateFlow<LastFailureInfo?> = AutomationService.lastFailure

    // ---- Plan config (O6, DataStore-persisted) ----

    // # 计划配置：buffer 首次必填，timeout/settle 有内部默认
    val planConfig: StateFlow<PlanConfig> = planConfigStore.config
        .stateIn(viewModelScope, SharingStarted.Lazily, PlanConfig(globalBufferSeconds = null))

    // ---- Plan screen state ----

    // # 最近计划 + 任务 + 尝试数的组合流（Room 实时刷新）
    @OptIn(ExperimentalCoroutinesApi::class)
    val planUiState: StateFlow<PlanUiState> = planRepository.observeLatestPlan()
        .flatMapLatest { plan ->
            if (plan == null) {
                flowOf(PlanUiState())
            } else {
                combine(
                    planRepository.observeTasksWithTrustedCounts(plan.id),
                    planRepository.observeAttemptCounts(plan.id)
                ) { tasksWithTrusted, counts ->
                    PlanUiState(
                        plan = plan,
                        tasks = PlanScheduler.executionOrder(tasksWithTrusted.map { it.task }),
                        attemptCounts = counts.associate { it.taskId to it.count },
                        trustedCounts = tasksWithTrusted.associate { it.task.id to it.trustedSuccesses }
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Lazily, PlanUiState())

    // # 原子导入的行级错误（面板一次列出全部，AC-A2）
    private val _importErrors = MutableStateFlow<List<RowError>>(emptyList())
    val importErrors: StateFlow<List<RowError>> = _importErrors

    // # 导入提示（拒绝原因或成功摘要）
    private val _importNotice = MutableStateFlow<String?>(null)
    val importNotice: StateFlow<String?> = _importNotice

    // ---- Data from repository ----

    // # History 页：尝试行联接任务上下文（最新在前，AC-C3）
    val attempts: StateFlow<List<AttemptWithTask>> = planRepository.observeAttemptsWithTasks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // # History 页 Legacy 分区：v2 遗留结果（C1，迁移故意保留的数据不静默消失）
    val legacyResults: StateFlow<List<com.example.cellrebelauto.model.TestResult>> =
        planRepository.observeLegacyResults()
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // ---- Actions ----

    fun navigateTo(screen: Screen) {
        _currentScreen.value = screen
    }

    /**
     * Starts (or resumes) the latest plan. Resume is the same call — the
     * engine's recovery sweep + persisted progress make it idempotent (INV-9).
     * # 启动/恢复最近计划：引擎启动即做恢复清扫，两者同一条路径
     */
    fun startOrResumePlan() {
        // # F003 AC-F3-4：双关 = 无操作流水线（KD-F3-3 配置错误），明确拒绝
        val cfg = planConfig.value
        if (!cfg.locationStageEnabled && !cfg.testStageEnabled) {
            _importNotice.value =
                "Both stages are OFF — nothing would run. Enable Location and/or CellRebel test stage first."
            return
        }
        val plan = planUiState.value.plan ?: return
        AutomationService.startAutomation(plan.id)
        _currentScreen.value = Screen.RUN
    }

    fun stopAutomation() {
        AutomationService.stopAutomation()
    }

    // ---- Plan config setters (independent fields, AC-B5) ----

    fun setGlobalBuffer(seconds: Int) {
        viewModelScope.launch {
            // # DataStore 始终写：作为下次导入的默认值
            planConfigStore.setGlobalBufferSeconds(seconds)
            // # F6：计划未启动时同步 engine 执行的 plan 快照，UI 展示值 == 执行值；
            // # 计划已启动则快照不动（UI 标注 next-plan-only）
            planUiState.value.plan?.let { plan ->
                withContext(Dispatchers.IO) {
                    planRepository.syncBufferIfPlanNotStarted(plan.id, seconds)
                }
            }
        }
    }

    fun setTestTimeout(seconds: Int) {
        viewModelScope.launch { planConfigStore.setTestTimeoutSeconds(seconds) }
    }

    fun setGpsSettle(seconds: Int) {
        viewModelScope.launch { planConfigStore.setGpsSettleSeconds(seconds) }
    }

    // # F003：位置阶段开关（运行时偏好，下个 attempt 生效）
    fun setLocationStageEnabled(enabled: Boolean) {
        viewModelScope.launch { planConfigStore.setLocationStageEnabled(enabled) }
    }

    // # F003：CellRebel 测试阶段开关（运行时偏好，下个 attempt 生效）
    fun setTestStageEnabled(enabled: Boolean) {
        viewModelScope.launch { planConfigStore.setTestStageEnabled(enabled) }
    }

    // ---- CSV import (atomic, AC-A2) ----

    /**
     * Imports a worklist CSV chosen via SAF. Atomic: any invalid row rejects
     * the whole file and lists ALL row errors; nothing is persisted. Rejects
     * when the global buffer is unset (first-run required) or when the current
     * plan is unfinished (progress hint).
     * # 导入 SAF 选择的 CSV 清单：任一行无效整份拒绝并列出全部错误；
     * # buffer 未设置或当前计划未完成时拒绝
     */
    fun importCsv(uri: Uri) {
        viewModelScope.launch {
            _importErrors.value = emptyList()
            _importNotice.value = null

            val text = withContext(Dispatchers.IO) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                _importNotice.value = "Cannot read the selected file"
                return@launch
            }

            when (val result = WorklistParser.parse(text)) {
                is ParseResult.Failure -> {
                    _importErrors.value = result.errors
                    _importNotice.value =
                        "Import rejected — ${result.errors.size} invalid row(s). Fix the file and re-import."
                }
                is ParseResult.Success -> {
                    // # buffer 取自当前 PlanConfig；首次必填（设计稿 v2.1 §1.1）
                    val buffer = planConfig.value.globalBufferSeconds
                    if (buffer == null) {
                        _importNotice.value = "Set global buffer first"
                        return@launch
                    }
                    val state = planUiState.value
                    val plan = state.plan
                    if (plan != null && state.isUnfinished) {
                        _importNotice.value =
                            "Current plan unfinished (${state.completedSuccesses}/${plan.totalRequiredSuccesses})"
                        return@launch
                    }
                    val fileName = withContext(Dispatchers.IO) { queryDisplayName(uri) }
                        ?: "worklist.csv"
                    withContext(Dispatchers.IO) {
                        planRepository.importPlan(
                            sourceFileName = fileName,
                            globalBufferSeconds = buffer,
                            rows = result.rows,
                            importedAt = System.currentTimeMillis()
                        )
                    }
                    _importNotice.value =
                        "Imported ${result.rows.size} rows, ${result.rows.sumOf { it.requiredSuccesses }} successes total"
                }
            }
        }
    }

    // # 从 SAF Uri 查询显示文件名
    private fun queryDisplayName(uri: Uri): String? {
        val cursor = getApplication<Application>().contentResolver
            .query(uri, null, null, null, null)
        cursor?.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) return it.getString(idx)
        }
        return null
    }

    /**
     * Exports all attempts to the 16-column audit CSV (AC-C3 + F003 stage_notes), chronological.
     * # 导出全部尝试为 16 列审计 CSV（时间升序）
     */
    fun exportCsv() {
        viewModelScope.launch {
            try {
                val allAttempts = withContext(Dispatchers.IO) {
                    planRepository.getAllAttemptsWithTasks()
                }
                // # C1：v2 遗留行并入同一份导出（排在 attempt 行之后）
                val legacy = withContext(Dispatchers.IO) {
                    planRepository.getLegacyResultsForExport()
                }
                if (allAttempts.isEmpty() && legacy.isEmpty()) {
                    showToast("No attempts to export")
                    return@launch
                }
                val exporter = CsvExporter(getApplication())
                val fileName = withContext(Dispatchers.IO) {
                    exporter.exportAttempts(allAttempts, legacy)
                }
                showToast("Exported: $fileName")
            } catch (e: Exception) {
                showToast("Export failed: ${e.message}")
            }
        }
    }

    // ---- Debug actions ----

    /**
     * Exports current logs to a .txt file in Downloads.
     * # 将当前日志导出到 Downloads 目录的 .txt 文件
     */
    fun exportLogs() {
        viewModelScope.launch {
            try {
                val currentLogs = logs.value
                if (currentLogs.isEmpty()) {
                    showToast("No logs to export")
                    return@launch
                }
                val exporter = DebugExporter(getApplication())
                val fileName = withContext(Dispatchers.IO) {
                    exporter.exportLogs(currentLogs)
                }
                showToast("Logs exported: $fileName")
            } catch (e: Exception) {
                showToast("Log export failed: ${e.message}")
            }
        }
    }

    /**
     * Dumps the current foreground app's accessibility tree to a .txt file.
     * # 将当前前台应用的无障碍节点树导出到 .txt 文件
     */
    fun dumpAccessibilityTree() {
        viewModelScope.launch {
            try {
                if (!isServiceConnected.value) {
                    showToast("Accessibility service not connected")
                    return@launch
                }
                val root = AutomationService.getRootNodeForDump()
                if (root == null) {
                    showToast("No active window to dump")
                    return@launch
                }
                val pkg = AutomationService.getCurrentForegroundPackage() ?: "unknown"
                val exporter = DebugExporter(getApplication())
                val fileName = withContext(Dispatchers.IO) {
                    exporter.dumpAccessibilityTree(root, pkg)
                }
                showToast("A11y tree dumped: $fileName ($pkg)")
            } catch (e: Exception) {
                showToast("Dump failed: ${e.message}")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }
}

/**
 * Navigation screens.
 * # 导航页面枚举
 */
enum class Screen {
    PLAN,     // # 位置计划页（F001 首页）
    RUN,      // # 运行仪表盘（由旧 CONTROL 演进）
    HISTORY,  // # 历史记录页面
    PROVIDERS // # R43（spec Task 6）：Provider 批准/撤销管理页
}
