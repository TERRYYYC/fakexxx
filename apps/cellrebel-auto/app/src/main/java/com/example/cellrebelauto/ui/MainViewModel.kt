package com.example.cellrebelauto.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.cellrebelauto.CellRebelAutoApp
import com.example.cellrebelauto.automation.AutomationService
import com.example.cellrebelauto.automation.CooldownInfo
import com.example.cellrebelauto.automation.EngineTaskSnapshot
import com.example.cellrebelauto.automation.LastFailureInfo
import com.example.cellrebelauto.automation.SupersessionStopStatus
import com.example.cellrebelauto.automation.plan.PlanScheduler
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverAccessResult
import com.example.cellrebelauto.cutover.CutoverDataState
import com.example.cellrebelauto.data.SelfHealConfig
import com.example.cellrebelauto.data.SelfHealSettings
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
import com.example.cellrebelauto.util.DiagnosticBundleBuilder
import com.example.cellrebelauto.util.DiagnosticFiles
import com.example.cellrebelauto.util.RollingLogFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
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
    val trustedCounts: Map<Long, Int> = emptyMap(),
    // # #12：本计划是否存在终态卡死的 RECOVERY_REQUIRED 尝试（死车道标志）
    val hasRecoveryRequired: Boolean = false
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

    /**
     * #12：重置入口可见性 —— 计划已全部完成，或存在 RECOVERY_REQUIRED 终态死尝试
     * （此时车道无前进路径、无人工兜底，只剩重置）。仅是投影；真正的守卫在
     * PlanRepository.resetPlanAsFreshGeneration 的事务内再判一次（UI 隐藏不是安全边界）。
     */
    val canResetPlan: Boolean
        get() = plan != null && (isComplete || hasRecoveryRequired)
}

/** A validated but not-yet-durable #97 replacement proposal. */
data class ImportProposal(
    val expectedOldPlanId: Long,
    val oldSourceFileName: String,
    val sourceFileName: String,
    val globalBufferSeconds: Int,
    val rows: List<com.example.cellrebelauto.model.plan.WorklistRow>
)

/** Testable UI boundary over the accessibility service's request-id-bound stop proof flow. */
interface SupersessionStopClient {
    val status: StateFlow<SupersessionStopStatus>
    fun request(planId: Long, sessionId: Long, requestId: String)
}

/**
 * P0.1-5: seam over the EXISTING discover channel (EnvironmentControlClient →
 * CapabilitySnapshotV1.profileRefs) that yields the QWY provider's profile count.
 * Null = the count is unobtainable right now — the consistency check silently skips.
 * JVM tests inject a fake; production builds the real Binder client lazily.
 */
fun interface ProfileCountProbe {
    fun providerProfileCount(): Int?
}

/** Production probe: one synchronous handshake on the caller's (IO) thread. */
private class DiscoverProfileCountProbe(private val context: android.content.Context) :
    ProfileCountProbe {
    override fun providerProfileCount(): Int? = try {
        when (val result =
            com.example.cellrebelauto.integration.v1.EnvironmentControlClient(context).handshake()) {
            is com.example.cellrebelauto.integration.v1.EnvironmentControlClient.HandshakeResult.Connected ->
                result.snapshot.profileRefs.size
            else -> null
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * T7 P1.1: seam over the EXISTING discover channel for the provider health
 * lamp. Null = the channel yields nothing (unbound/unreachable) — the lamp
 * greys WITH an explanation, never silently.
 */
fun interface ProviderHealthProbe {
    fun probe(): com.example.cellrebelauto.ui.dashboard.HealthLampsProjection.ProviderHandshake?
}

/** Production probe: one synchronous handshake on the caller's (IO) thread. */
private class DiscoverProviderHealthProbe(private val context: android.content.Context) :
    ProviderHealthProbe {
    override fun probe(): com.example.cellrebelauto.ui.dashboard.HealthLampsProjection.ProviderHandshake? =
        try {
            when (val result =
                com.example.cellrebelauto.integration.v1.EnvironmentControlClient(context)
                    .handshake()) {
                is com.example.cellrebelauto.integration.v1.EnvironmentControlClient.HandshakeResult.Connected ->
                    com.example.cellrebelauto.ui.dashboard.HealthLampsProjection.ProviderHandshake(
                        exhausted = result.snapshot.exhausted,
                        profileCount = result.snapshot.profileRefs.size,
                    )
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
}

/**
 * T7 P1.1: seam for the Vector-chain freshness lamp — the QWY-side publish
 * timestamp (epoch ms) or null when unreadable. The production binding stays
 * null on purpose: QWY's publish_state prefs are app-private and this app
 * cannot read them on-device, so the lamp renders its EXPLAINED grey until a
 * channel exposes the timestamp (T10/#90 surface). The projection and the
 * lane-tooling probes are fully oracle-driven.
 */
fun interface PublishTimestampProbe {
    fun publishedAtMs(): Long?
}

private object AutomationServiceSupersessionStopClient : SupersessionStopClient {
    override val status: StateFlow<SupersessionStopStatus> = AutomationService.supersessionStopStatus
    override fun request(planId: Long, sessionId: Long, requestId: String) {
        AutomationService.stopAndVerifyForSupersession(planId, sessionId, requestId)
    }
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
    private val injectedDb: AppDatabase? = null,
    private val supersessionStopClient: SupersessionStopClient = AutomationServiceSupersessionStopClient,
    private val injectedAccessGate: CutoverAccessGate? = null,
    // P0.1-5: the plan↔profile consistency probe (discover channel); tests inject a fake.
    private val profileCountProbe: ProfileCountProbe? = null,
    // T7 P1.1: the provider health lamp probe (discover channel); tests inject a fake.
    private val providerHealthProbe: ProviderHealthProbe? = null,
    // T7 P1.1: the Vector publish timestamp probe (null = unreadable → explained grey).
    private val publishTimestampProbe: PublishTimestampProbe? = null,
    // T7: the P1.3 self-heal trio surface; tests inject a file-backed instance.
    injectedSelfHealSettings: SelfHealSettings? = null
) : AndroidViewModel(application) {

    private val accessGate = injectedAccessGate ?: CellRebelAutoApp.accessGateFor(application)
    private val db = injectedDb ?: CellRebelAutoApp.databaseFor(application, accessGate)
    private val planRepository = PlanRepository(db, accessGate)
    private val planConfigStore = CellRebelAutoApp.planConfigStoreFor(application, accessGate)

    // R43 (spec Task 6 / Sol GREEN-review-2 F5): the ProviderTrustStore PRODUCTION callers —
    // the operator approval/revocation surface (§6.5.3). No silent TOFU: approval is explicit.
    private val trustStore = com.example.cellrebelauto.environment.ProviderTrustStore(
        db.providerPairingDao(),
        accessGate
    )
    private val _providerRefreshVersion = MutableStateFlow(0L)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val providerEntriesSource = _providerRefreshVersion.transformLatest {
        when (val access = accessGate.withNormalAccess { loadProviderEntriesUnderLease() }) {
            is CutoverAccessResult.Granted -> emit(access.value)
            is CutoverAccessResult.Unavailable -> Unit
        }
    }
    val providerEntries: StateFlow<CutoverDataState<List<ProviderEntry>>> =
        accessGate.dataFlow(providerEntriesSource)
            .stateIn(viewModelScope, SharingStarted.Eagerly, accessGate.initialDataState())

    // R44 (Sol GREEN-review-3 F5): the SEVEN-state production projection — derived from DURABLE
    // owner state only (pairing records + the crashed attempt's §8.1 phase + unverified records),
    // combined with the attempts flow the History projection already observes. This is the state
    // the run surface's PairingStatusCard renders; the UI never decides it.
    @OptIn(ExperimentalCoroutinesApi::class)
    val pairingUiState: StateFlow<CutoverDataState<PairingUiState>> =
        accessGate.dataFlow(combine(
            planRepository.observeAttemptsWithTasks(),
            providerEntriesSource
        ) { attempts, entries -> attempts to entries }.transformLatest { (attempts, entries) ->
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
            when (val access = accessGate.withNormalAccess {
                planRepository.getUnverifiedRecord(
                    attempts.firstOrNull()?.attempt?.id ?: -1L
                ) != null
            }) {
                is CutoverAccessResult.Granted -> emit(
                    PairingUiState.project(
                        hasProviderRecord = entries.isNotEmpty(),
                        providerActive = hasActiveProvider,
                        crashedAplusState = crashed?.aplusState,
                        hasUnverifiedRecord = access.value
                    )
                )
                is CutoverAccessResult.Unavailable -> Unit
            }
        }).stateIn(viewModelScope, SharingStarted.Eagerly, accessGate.initialDataState())

    fun refreshProviders() {
        _providerRefreshVersion.value += 1L
    }

    private suspend fun loadProviderEntriesUnderLease(): List<ProviderEntry> {
        val app = getApplication<Application>()
        val rows = trustStore.all()
        return computeProviderEntries(rows) { appId ->
            com.example.cellrebelauto.environment.ProviderTrustGate
                .packageManagerSignerDigest(app.packageManager, appId)
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
        launchNormalAccess {
            trustStore.approve(
                entry.applicationId, entry.signerDigest,
                entry.approvedVersionCode ?: 0, System.currentTimeMillis()
            )
            refreshProviders()
        }
    }

    fun revokeProvider(entry: ProviderEntry) {
        launchNormalAccess {
            trustStore.revoke(entry.applicationId, entry.signerDigest, System.currentTimeMillis())
            refreshProviders()
        }
    }

    // ---- Issue #10: revoke is irreversible and bricks every provider call — confirm first. ----

    /** The staged revoke awaiting operator confirmation; null = no dialog shown. */
    private val _revokeCandidate = MutableStateFlow<ProviderEntry?>(null)
    val revokeCandidate: StateFlow<ProviderEntry?> = _revokeCandidate

    /** Post-revoke impact banner (the engine will refuse ALL calls from this provider). */
    private val _revokeImpactNotice = MutableStateFlow<String?>(null)
    val revokeImpactNotice: StateFlow<String?> = _revokeImpactNotice

    /** Stage ONLY: the principal stays active until confirmRevoke (no silent one-touch revoke). */
    fun requestRevoke(entry: ProviderEntry) {
        _revokeCandidate.value = entry
    }

    /** Perform the staged revoke and post the impact notice. */
    fun confirmRevoke() {
        val entry = _revokeCandidate.value ?: return
        launchNormalAccess {
            if (_revokeCandidate.value == entry) {
                _revokeCandidate.value = null
            }
            try {
                trustStore.revoke(entry.applicationId, entry.signerDigest, System.currentTimeMillis())
                refreshProviders()
                _revokeImpactNotice.value =
                    "已撤销 ${entry.applicationId}（signer ${entry.signerDigest}）：" +
                        "引擎的信任门将拒绝该 provider 的一切契约调用（discover/preflight/apply/observe/" +
                        "completeAndAdvance），进行中的 attempt 只走 release/恢复。如需恢复请重新批准。"
            } catch (failure: Throwable) {
                if (_revokeCandidate.value == null) {
                    _revokeCandidate.value = entry
                }
                throw failure
            }
        }
    }

    /** Dismiss the dialog WITHOUT revoking. */
    fun dismissRevokeDialog() {
        _revokeCandidate.value = null
    }

    fun dismissRevokeNotice() {
        _revokeImpactNotice.value = null
    }

    // ---- Navigation ----

    // # T7 P1.1：运行台是新首页（新入口）；Plan/History/Provider 仍在底栏可达
    private val _currentScreen = MutableStateFlow(Screen.RUN)
    val currentScreen: StateFlow<Screen> = _currentScreen

    // ---- Device readiness (issue #9) ----

    // # OEM 无障碍开关脆弱（settings put 被无条件回滚；force-stop/install -r 会清掉启用），
    // # Plan 页的 Start 变灰必须给出可读原因。启用态由 AccessibilityManager 实测，进入
    // # Plan 页时刷新（refreshDeviceReadiness）；连接态来自 AutomationService 的实时流。
    private val _accessibilityEnabled = MutableStateFlow<Boolean?>(null)

    /** The readable service status line for the Plan surface; null = healthy. */
    val serviceStatusLine: StateFlow<String?> =
        combine(AutomationService.isServiceConnected, _accessibilityEnabled) { connected, enabled ->
            DeviceReadinessProjection.statusLine(
                serviceConnected = connected,
                accessibilityEnabled = enabled,
                appDisplayName = DeviceReadinessProbe.appDisplayName(getApplication()),
            )
        }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    /** Re-probe the system's enabled accessibility services (call on Plan page entry). */
    fun refreshDeviceReadiness() {
        _accessibilityEnabled.value = DeviceReadinessProbe.accessibilityEnabled(getApplication())
    }

    // ---- Data from service (delegated flows) ----

    // # 来自 AutomationService 的状态流
    val isRunning: StateFlow<Boolean> = AutomationService.isRunning
    val currentState: StateFlow<AutomationState> = AutomationService.currentState
    val cycleCount: StateFlow<Int> = AutomationService.cycleCount
    val logs: StateFlow<List<String>> = AutomationService.logs
    val isServiceConnected: StateFlow<Boolean> = AutomationService.isServiceConnected
    val startStatus: StateFlow<com.example.cellrebelauto.automation.AutomationStartStatus> =
        AutomationService.startStatus

    private val _startRequested = MutableStateFlow(false)

    // # Run 页投影流（Task 11）
    val currentTask: StateFlow<EngineTaskSnapshot?> = AutomationService.currentTask
    val cooldown: StateFlow<CooldownInfo?> = AutomationService.cooldown
    val lastFailure: StateFlow<LastFailureInfo?> = AutomationService.lastFailure


    // ---- Plan config (O6, DataStore-persisted) ----

    // # 计划配置：buffer 缺省即默认 10（P0.1-4，默认值落在 PlanConfigStore 层）；timeout/settle 有内部默认
    val planConfig: StateFlow<CutoverDataState<PlanConfig>> =
        accessGate.dataFlow(planConfigStore.config)
            .stateIn(viewModelScope, SharingStarted.Eagerly, accessGate.initialDataState())

    // ---- Plan screen state ----

    // # 最近计划 + 任务 + 尝试数的组合流（Room 实时刷新）
    @OptIn(ExperimentalCoroutinesApi::class)
    private val planUiStateSource = planRepository.observeLatestPlan()
        .flatMapLatest { plan ->
            if (plan == null) {
                flowOf(PlanUiState())
            } else {
                combine(
                    planRepository.observeTasksWithTrustedCounts(plan.id),
                    planRepository.observeAttemptCounts(plan.id),
                    // #12：RECOVERY_REQUIRED 死尝试投影（重置入口可见性的第二支）
                    planRepository.observeRecoveryRequiredCount(plan.id)
                ) { tasksWithTrusted, counts, recoveryRequired ->
                    PlanUiState(
                        plan = plan,
                        tasks = PlanScheduler.executionOrder(tasksWithTrusted.map { it.task }),
                        attemptCounts = counts.associate { it.taskId to it.count },
                        trustedCounts = tasksWithTrusted.associate { it.task.id to it.trustedSuccesses },
                        hasRecoveryRequired = recoveryRequired > 0
                    )
                }
            }
        }
    val planUiState: StateFlow<CutoverDataState<PlanUiState>> =
        accessGate.dataFlow(planUiStateSource)
            .stateIn(viewModelScope, SharingStarted.Eagerly, accessGate.initialDataState())

    // # 原子导入的行级错误（面板一次列出全部，AC-A2）
    private val _importErrors = MutableStateFlow<List<RowError>>(emptyList())
    val importErrors: StateFlow<List<RowError>> = _importErrors

    // # P0.1-5 计划-档案一致性警告：51 行计划配 52 档案会整体错位一档空转烧配额。
    // # null = 无警告（数字相等，或通道取不到档案数 → 静默跳过）。
    private val _planProfileMismatch =
        MutableStateFlow<com.example.cellrebelauto.model.plan.PlanProfileMismatch?>(null)
    val planProfileMismatch: StateFlow<com.example.cellrebelauto.model.plan.PlanProfileMismatch?> =
        _planProfileMismatch

    // # 导入提示（拒绝原因或成功摘要）
    private val _importNotice = MutableStateFlow<String?>(null)
    val importNotice: StateFlow<String?> = _importNotice

    // The parsed rows remain memory-only until the operator explicitly confirms replacement.
    private val _importProposal = MutableStateFlow<ImportProposal?>(null)
    val importProposal: StateFlow<ImportProposal?> = _importProposal
    private val _isImportReplacementStopping = MutableStateFlow(false)
    val isImportReplacementStopping: StateFlow<Boolean> = _isImportReplacementStopping
    private var activeReplacementConfirmationId: String? = null
    private var activeReplacementStopRequestId: String? = null

    init {
        viewModelScope.launch {
            startStatus.collect { status ->
                if (!_startRequested.value) return@collect
                when (status) {
                    is com.example.cellrebelauto.automation.AutomationStartStatus.Accepted -> {
                        _startRequested.value = false
                        _currentScreen.value = Screen.RUN
                    }
                    is com.example.cellrebelauto.automation.AutomationStartStatus.Rejected -> {
                        _startRequested.value = false
                        _importNotice.value = "Start rejected: ${status.reason}"
                    }
                    else -> Unit
                }
            }
        }
        viewModelScope.launch {
            supersessionStopClient.status.collect { status ->
                val activeRequestId = activeReplacementStopRequestId ?: return@collect
                when (status) {
                    is SupersessionStopStatus.Stopping -> {
                        if (status.requestId == activeRequestId) {
                            _isImportReplacementStopping.value = true
                        }
                    }
                    is SupersessionStopStatus.Verified -> {
                        if (status.requestId != activeRequestId) return@collect
                        commitVerifiedReplacement(status.proof)
                    }
                    is SupersessionStopStatus.Blocked -> {
                        if (status.requestId != activeRequestId) return@collect
                        activeReplacementStopRequestId = null
                        _isImportReplacementStopping.value = false
                        _importNotice.value =
                            "Current plan could not be safely stopped (${status.reason}); review and retry"
                    }
                    SupersessionStopStatus.Idle -> Unit
                }
            }
        }
    }

    // ---- Data from repository ----

    // # History 页：尝试行联接任务上下文（最新在前，AC-C3）
    val attempts: StateFlow<CutoverDataState<List<AttemptWithTask>>> =
        accessGate.dataFlow(planRepository.observeAttemptsWithTasks())
            .stateIn(viewModelScope, SharingStarted.Eagerly, accessGate.initialDataState())

    // # History 页 Legacy 分区：v2 遗留结果（C1，迁移故意保留的数据不静默消失）
    val legacyResults: StateFlow<CutoverDataState<List<com.example.cellrebelauto.model.TestResult>>> =
        accessGate.dataFlow(planRepository.observeLegacyResults())
            .stateIn(viewModelScope, SharingStarted.Eagerly, accessGate.initialDataState())

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
        val cfg = planConfig.value.readyValueOrNull() ?: run {
            _importNotice.value = "Plan data is unavailable or still loading"
            return
        }
        if (!cfg.locationStageEnabled && !cfg.testStageEnabled) {
            _importNotice.value =
                "Both stages are OFF — nothing would run. Enable Location and/or CellRebel test stage first."
            return
        }
        val plan = planUiState.value.readyValueOrNull()?.plan ?: return
        _startRequested.value = true
        AutomationService.startAutomation(plan.id)
    }

    fun stopAutomation() {
        AutomationService.stopAutomation()
    }

    // ---- Plan config setters (independent fields, AC-B5) ----

    fun setGlobalBuffer(seconds: Int) {
        launchNormalAccess {
            val planState = planUiState.value.readyValueOrNull()
            if (planState == null) {
                _importNotice.value = "Plan data is unavailable or still loading"
                return@launchNormalAccess
            }
            // # DataStore 始终写：作为下次导入的默认值
            planConfigStore.setGlobalBufferSeconds(seconds)
            // # F6：计划未启动时同步 engine 执行的 plan 快照，UI 展示值 == 执行值；
            // # 计划已启动则快照不动（UI 标注 next-plan-only）
            planState.plan?.let { plan ->
                withContext(Dispatchers.IO) {
                    planRepository.syncBufferIfPlanNotStarted(plan.id, seconds)
                }
            }
        }
    }

    fun setTestTimeout(seconds: Int) {
        launchNormalAccess { planConfigStore.setTestTimeoutSeconds(seconds) }
    }

    fun setGpsSettle(seconds: Int) {
        launchNormalAccess { planConfigStore.setGpsSettleSeconds(seconds) }
    }

    // # F003：位置阶段开关（运行时偏好，下个 attempt 生效）
    fun setLocationStageEnabled(enabled: Boolean) {
        launchNormalAccess { planConfigStore.setLocationStageEnabled(enabled) }
    }

    // # F003：CellRebel 测试阶段开关（运行时偏好，下个 attempt 生效）
    fun setTestStageEnabled(enabled: Boolean) {
        launchNormalAccess { planConfigStore.setTestStageEnabled(enabled) }
    }

    // ---- CSV import (atomic, AC-A2) ----

    /**
     * P0.1-5: after a plan import, compare the imported row count against the provider's
     * profile count over the EXISTING discover channel. A mismatch is a prominent warning;
     * an unobtainable count silently skips (never an error, never a crash).
     * # 导入后经既有 AIDL discover 通道取 QWY 档案数做一致性校验；取不到即静默跳过。
     */
    private fun checkPlanProfileConsistency(planRows: Int) {
        if (planRows <= 0) {
            _planProfileMismatch.value = null
            return
        }
        viewModelScope.launch {
            val providerCount = withContext(Dispatchers.IO) {
                runCatching {
                    (profileCountProbe ?: DiscoverProfileCountProbe(getApplication()))
                        .providerProfileCount()
                }.getOrNull()
            }
            _planProfileMismatch.value =
                com.example.cellrebelauto.model.plan.PlanProfileConsistency.evaluate(
                    planRows,
                    providerCount,
                )
        }
    }

    /**
     * Imports a worklist CSV chosen via SAF. Atomic: any invalid row rejects
     * the whole file and lists ALL row errors; nothing is persisted. The global
     * buffer seeds from the DataStore default (10) so a fresh device is never
     * blocked; an unfinished current plan produces an in-memory replacement
     * proposal that requires explicit confirmation.
     * # 导入 SAF 选择的 CSV 清单：任一行无效整份拒绝并列出全部错误；
     * # buffer 缺省即默认 10 不再卡死导入；当前计划未完成时仅生成待确认的内存提案
     */
    fun importCsv(uri: Uri) {
        launchNormalAccess {
            _importErrors.value = emptyList()
            _importNotice.value = null
            _planProfileMismatch.value = null

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
                return@launchNormalAccess
            }

            when (val result = WorklistParser.parse(text)) {
                is ParseResult.Failure -> {
                    _importErrors.value = result.errors
                    _importNotice.value =
                        "Import rejected — ${result.errors.size} invalid row(s). Fix the file and re-import."
                }
                is ParseResult.Success -> {
                    // # buffer 缺省即默认 10（P0.1-4），导入永不被 buffer 卡死：
                    // # store 层保持 main 契约（null=未设置），配置流不可用（cutover 门）时同样兜底。
                    val buffer = planConfig.value.readyValueOrNull()?.globalBufferSeconds
                        ?: PlanConfig.DEFAULT_GLOBAL_BUFFER_SECONDS
                    val state = planUiState.value.readyValueOrNull()
                    if (state == null) {
                        _importNotice.value = "Plan data is unavailable or still loading"
                        return@launchNormalAccess
                    }
                    val plan = state.plan
                    val fileName = withContext(Dispatchers.IO) { queryDisplayName(uri) }
                        ?: "worklist.csv"
                    if (plan != null && state.isUnfinished) {
                        _importProposal.value = ImportProposal(
                            expectedOldPlanId = plan.id,
                            oldSourceFileName = plan.sourceFileName,
                            sourceFileName = fileName,
                            globalBufferSeconds = buffer,
                            rows = result.rows
                        )
                        _importNotice.value = "Review replacement before importing ${fileName}"
                        return@launchNormalAccess
                    }
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
                    checkPlanProfileConsistency(result.rows.size)
                }
            }
        }
    }

    /** Commits a validated replacement only after the Plan screen's explicit confirmation. */
    fun confirmImportReplacement() {
        if (activeReplacementConfirmationId != null || activeReplacementStopRequestId != null) return
        val proposal = _importProposal.value ?: return
        val confirmationId = java.util.UUID.randomUUID().toString()
        activeReplacementConfirmationId = confirmationId
        _isImportReplacementStopping.value = true
        viewModelScope.launch {
            try {
                val access = accessGate.withNormalAccess {
                    val result = withContext(Dispatchers.IO) {
                        planRepository.confirmSupersedingImport(
                            expectedOldPlanId = proposal.expectedOldPlanId,
                            sourceFileName = proposal.sourceFileName,
                            globalBufferSeconds = proposal.globalBufferSeconds,
                            rows = proposal.rows,
                            importedAt = System.currentTimeMillis(),
                            supersededAt = System.currentTimeMillis()
                        )
                    }
                    if (activeReplacementConfirmationId != confirmationId) return@withNormalAccess
                    when (result) {
                        is PlanRepository.SupersedingImportResult.Imported -> {
                            _importProposal.value = null
                            _importNotice.value =
                                "Archived ${proposal.oldSourceFileName}; imported ${proposal.sourceFileName}"
                            // P0.1-5: re-check plan↔profile consistency after replacement import (T2)
                            checkPlanProfileConsistency(proposal.rows.size)
                        }
                        is PlanRepository.SupersedingImportResult.ActiveSession -> {
                            requestSupersessionStop(proposal, result.sessionId)
                        }
                        is PlanRepository.SupersedingImportResult.StopVerificationRequired -> {
                            requestSupersessionStop(proposal, result.sessionId)
                        }
                        PlanRepository.SupersedingImportResult.StaleStopProof -> {
                            _importNotice.value =
                                "The stopped plan changed; verify it again before replacing it"
                        }
                        PlanRepository.SupersedingImportResult.StalePlan -> {
                            _importProposal.value = null
                            _importNotice.value = "Current plan changed; review the CSV again before replacing it"
                        }
                    }
                }
                if (access is CutoverAccessResult.Unavailable) {
                    _importNotice.value =
                        "Replacement paused while data is unavailable (${access.reason})"
                }
            } finally {
                if (activeReplacementConfirmationId == confirmationId) {
                    activeReplacementConfirmationId = null
                    if (activeReplacementStopRequestId == null) {
                        _isImportReplacementStopping.value = false
                    }
                }
            }
        }
    }

    private fun requestSupersessionStop(proposal: ImportProposal, sessionId: Long) {
        val requestId = java.util.UUID.randomUUID().toString()
        activeReplacementStopRequestId = requestId
        _isImportReplacementStopping.value = true
        _importNotice.value = "Safely stopping ${proposal.oldSourceFileName} before import"
        supersessionStopClient.request(proposal.expectedOldPlanId, sessionId, requestId)
    }

    private suspend fun commitVerifiedReplacement(proof: PlanRepository.SupersessionStopProof) {
        try {
            val access = accessGate.withNormalAccess {
                commitVerifiedReplacementUnderLease(proof)
            }
            if (access is CutoverAccessResult.Unavailable) {
                _importNotice.value = "Replacement paused while data is unavailable (${access.reason})"
            }
        } finally {
            if (activeReplacementStopRequestId == proof.requestId) {
                activeReplacementStopRequestId = null
                _isImportReplacementStopping.value = false
            }
        }
    }

    private suspend fun commitVerifiedReplacementUnderLease(proof: PlanRepository.SupersessionStopProof) {
        val proposal = _importProposal.value
        if (proposal == null || proof.requestId != activeReplacementStopRequestId ||
            proof.planId != proposal.expectedOldPlanId
        ) {
            activeReplacementStopRequestId = null
            _isImportReplacementStopping.value = false
            return
        }
        when (val result = withContext(Dispatchers.IO) {
            planRepository.confirmSupersedingImport(
                expectedOldPlanId = proposal.expectedOldPlanId,
                sourceFileName = proposal.sourceFileName,
                globalBufferSeconds = proposal.globalBufferSeconds,
                rows = proposal.rows,
                importedAt = System.currentTimeMillis(),
                supersededAt = System.currentTimeMillis(),
                stopProof = proof
            )
        }) {
            is PlanRepository.SupersedingImportResult.Imported -> {
                _importProposal.value = null
                _importNotice.value =
                    "Archived ${proposal.oldSourceFileName}; imported ${proposal.sourceFileName}"
                checkPlanProfileConsistency(proposal.rows.size)
            }
            PlanRepository.SupersedingImportResult.StaleStopProof,
            is PlanRepository.SupersedingImportResult.ActiveSession,
            is PlanRepository.SupersedingImportResult.StopVerificationRequired -> {
                _importNotice.value =
                    "The stopped plan changed; review and retry the replacement"
            }
            PlanRepository.SupersedingImportResult.StalePlan -> {
                _importProposal.value = null
                _importNotice.value = "Current plan changed; review the CSV again before replacing it"
            }
        }
        activeReplacementStopRequestId = null
        _isImportReplacementStopping.value = false
    }

    /** Dismissing confirmation deliberately preserves the latest plan and all of its history. */
    fun cancelImportReplacement() {
        activeReplacementConfirmationId = null
        activeReplacementStopRequestId = null
        _isImportReplacementStopping.value = false
        _importProposal.value = null
        _importNotice.value = "Kept the current plan; no CSV was imported"
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

    // ---- #12 plan-reset（计划重置 / 重跑入口） ----

    /**
     * The provider-side half of the #12 reset sequence, rendered into the
     * confirm dialog for the operator to copy. Built from THIS build's
     * selected provider principal so lane builds (glmbench) print their own
     * package, not the production one.
     */
    val providerScheduleResetCommand: String
        get() = "adb shell am start -n " +
            "${com.example.cellrebelauto.automation.ProviderPrincipal.selected}" +
            "/name.caiyao.fakegps.integration.v1.FaultCollectorActivity --es cmd schedule_reset"

    /** Re-approval entry after the reset (pairing is wiped with the durable layer). */
    val providerPairingApprovalCommand: String
        get() = "adb shell am start -n " +
            "${com.example.cellrebelauto.automation.ProviderPrincipal.selected}" +
            "/name.caiyao.fakegps.integration.v1.PairingApprovalActivity"

    /**
     * Resets the current plan as a fresh generation (#12): copies the same
     * worklist rows into a NEW plan (all tasks pending, zero attempts/quota —
     * the old attempts stay in History as the audit trail) and appends a
     * PLAN_RESET audit event. The provider side (schedule + contract durable
     * layer) is reset separately by the operator via the adb command shown in
     * the confirm dialog; this method does NOT touch the provider.
     *
     * # 重置计划为新一代：同清单新计划行 + PLAN_RESET 审计；
     * # provider 侧 schedule_reset 由操作者按确认框中的命令执行
     */
    fun resetPlan() {
        viewModelScope.launch {
            _importErrors.value = emptyList()
            when (val outcome = withContext(Dispatchers.IO) {
                planRepository.resetPlanAsFreshGeneration()
            }) {
                is PlanRepository.PlanResetOutcome.Reset -> {
                    _importNotice.value =
                        "Plan reset — ${outcome.rows} rows re-imported as a new plan. " +
                            "Next: run the provider schedule_reset command, re-approve pairing, then Start."
                }
                is PlanRepository.PlanResetOutcome.Refused ->
                    _importNotice.value = "Reset refused — ${outcome.reason}"
                PlanRepository.PlanResetOutcome.NoPlan ->
                    _importNotice.value = "No plan to reset"
            }
        }
    }

    /**
     * Exports all attempts to the 16-column audit CSV (AC-C3 + F003 stage_notes), chronological.
     * # 导出全部尝试为 16 列审计 CSV（时间升序）
     */
    fun exportCsv() {
        launchNormalAccess {
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
                    return@launchNormalAccess
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

    // ---- Run dashboard (T7 P1.1 运行台) --------------------------------------

    private val selfHealSettings = injectedSelfHealSettings
        ?: SelfHealSettings(application)

    /** P1.3 self-heal trio — the dashboard section edits the SAME DataStore the engine reads. */
    val selfHealConfig: StateFlow<SelfHealConfig> = selfHealSettings.config
        .stateIn(viewModelScope, SharingStarted.Lazily, SelfHealConfig())

    fun setAttemptWatchdogEnabled(enabled: Boolean) {
        viewModelScope.launch { selfHealSettings.setAttemptWatchdogEnabled(enabled) }
    }

    fun setCoordinateGuardEnabled(enabled: Boolean) {
        viewModelScope.launch { selfHealSettings.setCoordinateGuardEnabled(enabled) }
    }

    fun setServiceReconnectAutoResumeEnabled(enabled: Boolean) {
        viewModelScope.launch { selfHealSettings.setServiceReconnectAutoResumeEnabled(enabled) }
    }

    /** The three lamps; null = not probed yet (the screen renders an unprobed grey). */
    data class LampTriple(
        val accessibility: com.example.cellrebelauto.ui.dashboard.HealthLamp? = null,
        val provider: com.example.cellrebelauto.ui.dashboard.HealthLamp? = null,
        val vector: com.example.cellrebelauto.ui.dashboard.HealthLamp? = null,
    )

    private val _lamps = MutableStateFlow(LampTriple())
    private val _lastContractReadback = MutableStateFlow("no readback yet — press refresh")

    /**
     * Refreshes the health lamps. A11y comes from the cached enablement probe +
     * the live connection flow; QWY runs one synchronous discover handshake on
     * IO; the Vector publish timestamp comes from the (currently grey-by-design)
     * probe seam. Never throws — a failed probe is a LAMP VALUE, not a crash.
     */
    fun refreshDashboardHealth() {
        val a11y = com.example.cellrebelauto.ui.dashboard.HealthLampsProjection.accessibility(
            serviceConnected = AutomationService.isServiceConnected.value,
            enabled = _accessibilityEnabled.value,
        )
        viewModelScope.launch {
            val handshake = withContext(Dispatchers.IO) {
                runCatching {
                    (providerHealthProbe ?: DiscoverProviderHealthProbe(getApplication())).probe()
                }.getOrNull()
            }
            val publishedAt = runCatching { publishTimestampProbe?.publishedAtMs() }.getOrNull()
            _lastContractReadback.value = handshake?.let {
                "handshake=Connected exhausted=${it.exhausted} profileRefs=${it.profileCount}"
            } ?: "handshake=UNREACHABLE (discover 通道不可达或探针失败)"
            _lamps.value = LampTriple(
                accessibility = a11y,
                provider = com.example.cellrebelauto.ui.dashboard.HealthLampsProjection
                    .provider(handshake),
                vector = com.example.cellrebelauto.ui.dashboard.HealthLampsProjection
                    .vector(publishedAt, System.currentTimeMillis()),
            )
        }
    }

    /**
     * The dashboard's aggregated UI state — every number/word here is a pure
     * projection over trusted data (PlanUiState.trustedCounts, the attempt
     * rows, the service flows). The Compose screen stays thin.
     */
    data class RunDashboardUiState(
        val engineState: com.example.cellrebelauto.model.AutomationState =
            com.example.cellrebelauto.model.AutomationState.IDLE,
        val isRunning: Boolean = false,
        val serviceConnected: Boolean = false,
        val explanation: com.example.cellrebelauto.ui.dashboard.PauseExplanation =
            com.example.cellrebelauto.ui.dashboard.PauseReasonExplainer.explain(
                com.example.cellrebelauto.model.AutomationState.IDLE
            ),
        val progress: com.example.cellrebelauto.ui.dashboard.RunProgressProjection.ProgressSnapshot =
            com.example.cellrebelauto.ui.dashboard.RunProgressProjection.ProgressSnapshot(),
        val lampAccessibility: com.example.cellrebelauto.ui.dashboard.HealthLamp? = null,
        val lampProvider: com.example.cellrebelauto.ui.dashboard.HealthLamp? = null,
        val lampVector: com.example.cellrebelauto.ui.dashboard.HealthLamp? = null,
    )

    private data class DashboardEngineView(
        val state: com.example.cellrebelauto.model.AutomationState,
        val isRunning: Boolean,
        val lastFailure: LastFailureInfo?,
        val latestErrorLog: String?,
        val planState: PlanUiState,
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dashboardState: StateFlow<RunDashboardUiState> =
        combine(
            currentState,
            isRunning,
            lastFailure,
            logs,
            planUiState,
        ) { state, running, failure, logLines, plan ->
            DashboardEngineView(
                state = state,
                isRunning = running,
                lastFailure = failure,
                latestErrorLog = logLines.lastOrNull {
                    it.contains("ERROR") || it.contains("EXHAUSTED") ||
                        it.contains("ANCHOR_MISMATCH") || it.contains("trust gate rejected")
                },
                planState = plan,
            )
        }
            .combine(attempts) { view, attemptRows ->
                val plan = view.planState.plan
                val planId = plan?.id
                val planAttempts = if (planId == null) {
                    emptyList()
                } else {
                    withContext(Dispatchers.IO) {
                        runCatching {
                            db.testAttemptDao().getAttemptsForPlan(planId)
                        }.getOrDefault(emptyList())
                    }
                }
                val facts = planAttempts.map {
                    com.example.cellrebelauto.ui.dashboard.RunProgressProjection.AttemptFact(
                        succeeded = it.status == "succeeded" || it.status == "ok_gps_only",
                        failureReason = it.failureReason,
                        endedAt = it.endedAt,
                    )
                }
                val progress = com.example.cellrebelauto.ui.dashboard.RunProgressProjection.project(
                    trustedDone = view.planState.completedSuccesses, // trusted 求和，绝不读 legacy 列
                    trustedTotal = plan?.totalRequiredSuccesses ?: 0,
                    attempts = facts,
                    nowMs = System.currentTimeMillis(),
                )
                val explanation = com.example.cellrebelauto.ui.dashboard.PauseReasonExplainer.explain(
                    state = view.state,
                    lastFailureReason = view.lastFailure?.reason,
                    latestErrorLog = view.latestErrorLog,
                )
                RunDashboardUiState(
                    engineState = view.state,
                    isRunning = view.isRunning,
                    explanation = explanation,
                    progress = progress,
                )
            }
            // # 三灯与服务连接是独立来源：refreshDashboardHealth 写 _lamps 后必须触发重投影
            .combine(_lamps) { partial, lamps ->
                partial.copy(
                    lampAccessibility = lamps.accessibility,
                    lampProvider = lamps.provider,
                    lampVector = lamps.vector,
                )
            }
            .combine(isServiceConnected) { partial, connected ->
                partial.copy(serviceConnected = connected)
            }
            .stateIn(
                viewModelScope,
                kotlinx.coroutines.flow.SharingStarted.Lazily,
                RunDashboardUiState()
            )

    /**
     * One-tap diagnostic bundle: rolling log + engine-state JSON + contract
     * readback + DB snapshot, zipped into Downloads. Completeness is enforced
     * by the builder (a missing section aborts the export with a toast).
     */
    fun exportDiagnosticBundle() {
        viewModelScope.launch {
            try {
                val sections = withContext(Dispatchers.IO) { buildDiagnosticSections() }
                val zip = DiagnosticBundleBuilder.build(sections)
                val fileName = withContext(Dispatchers.IO) {
                    DebugExporter(getApplication()).saveBundle(zip)
                }
                showToast("诊断包已导出: $fileName")
            } catch (e: Exception) {
                showToast("诊断包导出失败: ${e.message}")
            }
        }
    }

    private suspend fun buildDiagnosticSections(): Map<String, String> {
        val app = getApplication<Application>()
        val manifest = DiagnosticBundleBuilder.REQUIRED_ENTRIES.joinToString("\n")
        val persistedLogs = RollingLogFile(DiagnosticFiles.rollingLogFile(app)).readText()
        val logSection = if (persistedLogs.isBlank()) {
            // Fresh install with no persisted ring yet — the in-memory lines
            // (or a placeholder header) keep the bundle complete.
            (logs.value.takeIf { it.isNotEmpty() }?.joinToString("\n")
                ?: "(no log lines yet)")
        } else {
            persistedLogs
        }
        val view = dashboardState.value
        val engineJson = DiagnosticBundleBuilder.engineStateJson(
            DiagnosticBundleBuilder.EngineStateDump(
                stateName = view.engineState.name,
                isRunning = view.isRunning,
                cycleCount = AutomationService.cycleCount.value,
                currentTaskCsvRow = AutomationService.currentTask.value?.csvRow,
                lastFailureOrdinal = AutomationService.lastFailure.value?.attemptOrdinal,
                lastFailureReason = AutomationService.lastFailure.value?.reason,
                serviceConnected = isServiceConnected.value,
                trustedDone = view.progress.trustedDone,
                trustedTotal = view.progress.trustedTotal,
                startStatus = AutomationService.startStatus.value.toString(),
                attemptWatchdogEnabled = selfHealConfig.value.attemptWatchdogEnabled,
                coordinateGuardEnabled = selfHealConfig.value.coordinateGuardEnabled,
                serviceReconnectAutoResumeEnabled =
                    selfHealConfig.value.serviceReconnectAutoResumeEnabled,
                generatedAtMs = System.currentTimeMillis(),
            )
        )
        val dbSection = withContext(Dispatchers.IO) {
            runCatching { renderDbSnapshot() }.getOrDefault("db snapshot failed")
        }
        return mapOf(
            DiagnosticBundleBuilder.ENTRY_MANIFEST to manifest,
            DiagnosticBundleBuilder.ENTRY_LOGS to logSection,
            DiagnosticBundleBuilder.ENTRY_ENGINE to engineJson,
            DiagnosticBundleBuilder.ENTRY_CONTRACT to
                (_lastContractReadback.value + "\nstartStatus=" +
                    AutomationService.startStatus.value + "\n"),
            DiagnosticBundleBuilder.ENTRY_DB to dbSection,
        )
    }

    /** Human-readable CSV-ish snapshot of the plan/task/attempt/session tables. */
    private suspend fun renderDbSnapshot(): String {
        val sb = StringBuilder()
        val plan = db.planDao().getLatestPlan()
        if (plan == null) {
            sb.appendLine("plan,(none)")
            return sb.toString()
        }
        sb.appendLine("plan,id=${plan.id},file=${plan.sourceFileName},rows=${plan.totalRows}," +
            "totalRequired=${plan.totalRequiredSuccesses},buffer=${plan.globalBufferSeconds}")
        db.locationTaskDao().getTasksForPlan(plan.id).forEach {
            sb.appendLine("task,id=${it.id},csvRow=${it.csvRow},status=${it.status}," +
                "legacy=${it.completedSuccesses},required=${it.requiredSuccesses}," +
                "trusted=${db.trustedQuotaDao().trustedCountForTask(it.id)}," +
                "lat=${it.latitude},lng=${it.longitude}")
        }
        db.testAttemptDao().getAttemptsForPlan(plan.id).forEach {
            sb.appendLine("attempt,id=${it.id},taskId=${it.taskId},ordinal=${it.attemptOrdinal}," +
                "status=${it.status},reason=${it.failureReason ?: "-"}," +
                "startedAt=${it.startedAt},endedAt=${it.endedAt ?: "-"},aplus=${it.aplusState ?: "-"}")
        }
        db.runSessionDao().getLatest()?.let {
            sb.appendLine("session,id=${it.id},planId=${it.planId},status=${it.status}," +
                "startedAt=${it.startedAt},endedAt=${it.endedAt ?: "-"},cycles=${it.totalCycles}")
        }
        return sb.toString()
    }

    private fun showToast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_LONG).show()
    }

    private fun launchNormalAccess(block: suspend () -> Unit) {
        viewModelScope.launch {
            val access = accessGate.withNormalAccess(block)
            if (access is CutoverAccessResult.Unavailable) {
                _importNotice.value = "Data temporarily unavailable (${access.reason})"
            }
        }
    }

    private fun <T> CutoverDataState<T>.readyValueOrNull(): T? = when (this) {
        is CutoverDataState.Ready -> value
        CutoverDataState.Loading,
        is CutoverDataState.Unavailable -> null
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
