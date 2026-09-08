package name.caiyao.fakegps.ui.screen.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PublishedConfig
import name.caiyao.fakegps.config.PublishPropagation
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.bundle.ConfigBundleImportDecision
import name.caiyao.fakegps.data.bundle.ConfigBundleImportResult
import name.caiyao.fakegps.data.bundle.QwyBundleExport
import name.caiyao.fakegps.data.bundle.QwyBundleExporter
import name.caiyao.fakegps.data.bundle.QwyBundleImporter
import name.caiyao.fakegps.data.bundle.QwyBundleSections
import name.caiyao.fakegps.data.bundle.QwyProfileFingerprint
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.mockprovider.MockLocationAppOps
import name.caiyao.fakegps.mockprovider.MockProviderRuntime
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore
import name.caiyao.fakegps.integration.v1.OperatorScheduleRestartResult
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import name.caiyao.fakegps.integration.v1.ProviderRuntime
import name.caiyao.fakegps.ui.screen.collection.PublishedProfileMatcher

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SpoofSettings.getInstance(app)

    private val _pendingCallers = MutableStateFlow<List<PendingPairingCandidate>>(emptyList())
    val pendingCallers: StateFlow<List<PendingPairingCandidate>> = _pendingCallers

    private val _environmentControlMessage = MutableStateFlow<String?>(null)
    val environmentControlMessage: StateFlow<String?> = _environmentControlMessage

    init {
        refreshPendingCallers()
    }

    fun refreshPendingCallers() {
        viewModelScope.launch {
            _pendingCallers.value = withContext(Dispatchers.IO) {
                ProviderRuntime.pendingCallers(getApplication())
            }
        }
    }

    fun approveCaller(candidate: PendingPairingCandidate) {
        viewModelScope.launch {
            val approved = withContext(Dispatchers.IO) {
                ProviderRuntime.approveCaller(
                    getApplication(),
                    candidate.callerApplicationId,
                    candidate.currentSignerDigest,
                )
            }
            _environmentControlMessage.value = if (approved) {
                "已批准 ${candidate.callerApplicationId}；请返回 Auto 重新运行"
            } else {
                "批准失败：候选已变化，请刷新后核对完整身份"
            }
            refreshPendingCallers()
        }
    }

    fun restartCompletedSchedule() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ProviderRuntime.restartScheduleForOperator(getApplication())
            }
            _environmentControlMessage.value = when (result) {
                OperatorScheduleRestartResult.RESTARTED -> "日程已开启新一轮；可返回 Auto 重新运行"
                OperatorScheduleRestartResult.BLOCKED_BY_LEASE -> "无法重开：仍有未释放的运行环境，请先让当前运行完成或恢复"
                OperatorScheduleRestartResult.NO_SCHEDULE -> "无法重开：当前没有可用日程"
                OperatorScheduleRestartResult.NOT_EXHAUSTED -> "无需重开：当前日程尚未完成"
                OperatorScheduleRestartResult.WRITE_FAILED -> "重开失败：状态未写入，请重试"
            }
        }
    }

    fun dismissEnvironmentControlMessage() {
        _environmentControlMessage.value = null
    }

    val spoofMode: StateFlow<String> = settings.spoofMode
    val activeHourStart: StateFlow<Int> = settings.activeHourStart
    val activeHourEnd: StateFlow<Int> = settings.activeHourEnd
    val locationDeliveryMode: StateFlow<LocationDeliveryMode> = settings.locationDeliveryMode

    /**
     * Per-module hook switches (transport schema v5): wire name → enabled, exactly the canonical
     * [SpoofModules.ALL] vocabulary. Rendered by [ModulesSection].
     */
    val moduleSwitches: StateFlow<Map<String, Boolean>> = settings.modulesEnabled
    val mockProviderState = MockProviderStatusStore.state

    private val _publishedConfig = MutableStateFlow(readPublishedConfig())
    val publishedConfig: StateFlow<PublishedConfig?> = _publishedConfig

    /** Hook refresh cadence, in seconds. Always a value [PublishPropagation] sanctions. */
    val refreshIntervalSec: StateFlow<Int> = settings.refreshIntervalSec

    /** The choices the picker may offer — from the policy, never from the screen. */
    val refreshIntervalChoicesSec: List<Int> = PublishPropagation.REFRESH_INTERVAL_CHOICES_SEC

    /**
     * Every setting mutation must re-publish the transport payload (review FC-1).
     * Writing only to SpoofSettings leaves the hook side reading a stale snapshot until
     * the app restarts or a profile is edited — i.e. switching to `off` would NOT actually
     * stop spoofing in the target process.
     */
    fun setSpoofMode(mode: String) {
        settings.setSpoofMode(mode)
        publish()
    }

    fun setActiveHourStart(hour: Int) {
        settings.setActiveHourStart(hour)
        publish()
    }

    fun setActiveHourEnd(hour: Int) {
        settings.setActiveHourEnd(hour)
        publish()
    }

    /**
     * Toggle one module's hook registration switch. Like every setting mutation this MUST
     * re-publish: the switch only exists in the v5 payload the hook reads, so persisting alone
     * would change nothing in the target process. Registration happens once per target process,
     * so the toggle fully applies on the target app's next start.
     */
    fun setModuleEnabled(module: String, enabled: Boolean) {
        // Same seam the JVM test pins: persist first, then publish, never drop the outcome.
        val result = ModuleToggleUpdate.apply(
            module = module,
            enabled = enabled,
            persist = settings::setModuleEnabled,
            publish = { ConfigPrefsSync.sync(getApplication()) },
        )
        _publishFailure.value =
            if (result.published) null
            else "模块开关已保存，但未发布给 Hook —— 目标 App 仍在使用上一份配置"
    }

    /**
     * Non-null when the last settings change was persisted but NOT delivered to the hook.
     *
     * The preference is deliberately kept (the user's intent is not discarded), but the screen
     * must not present it as in effect: the hook is still running the previous payload, so a
     * silently-accepted change would read exactly like the "I changed it and nothing happened"
     * failure this feature exists to eliminate.
     */
    private val _publishFailure = MutableStateFlow<String?>(null)
    val publishFailure: StateFlow<String?> = _publishFailure

    fun dismissPublishFailure() {
        _publishFailure.value = null
    }

    fun reportSystemMockPermissionFailure(message: String) {
        _publishFailure.value = message
    }

    fun setSystemMockEnabled(enabled: Boolean) {
        if (mockProviderState.value is MockProviderState.Starting ||
            mockProviderState.value is MockProviderState.Stopping
        ) return

        if (enabled) {
            // The service resolves coordinates from these exact bytes. Never pass a parallel UI
            // coordinate through an Intent, and never start from a stale publication.
            when (
                val outcome = SystemMockEnableAction.run(
                    syncPublishedConfig = { ConfigPrefsSync.sync(getApplication()) },
                    readPublishedConfig = ::readPublishedConfig,
                    publishProviderState = MockProviderStatusStore::publish,
                    startService = { MockProviderRuntime.enableSystemMock(getApplication()) },
                    // Issue #8: ask AppOpsManager before any mutation (fail-open inside).
                    mockLocationAppOpAllowed = {
                        MockLocationAppOps.isMockLocationAllowed(getApplication())
                    },
                )
            ) {
                SystemMockEnableOutcome.PublicationFailed -> {
                    _publishFailure.value =
                        "无法发布生效中档案，System Mock 未启动；Hook 仍保持当前状态"
                }
                SystemMockEnableOutcome.AppOpDenied -> {
                    // The typed Failed state (with the dev-options guidance) is already published
                    // to MockProviderStatusStore — the System Mock card renders it; no banner.
                }
                is SystemMockEnableOutcome.Invalid -> {
                    _publishedConfig.value = outcome.published
                }
                is SystemMockEnableOutcome.Started -> {
                    _publishedConfig.value = outcome.published
                    _publishFailure.value = null
                }
            }
        } else {
            retryStopSystemMock()
        }
    }

    fun retryStopSystemMock() {
        if (mockProviderState.value is MockProviderState.Starting ||
            mockProviderState.value is MockProviderState.Stopping
        ) return
        MockProviderStatusStore.publish(MockProviderState.Stopping)
        MockProviderRuntime.useHookAndStopSystemMock(getApplication())
    }

    /**
     * Changing the cadence must re-publish like any other setting: the interval is part of the
     * payload the hook reads, so persisting it without publishing would leave the hook running the
     * OLD cadence — the setting would appear to apply while changing nothing.
     */
    fun setRefreshIntervalSec(seconds: Int) {
        // Goes through the same seam the JVM test pins, so the tested sequence IS the shipped one.
        val result = RefreshIntervalUpdate.apply(
            requestedSec = seconds,
            persist = settings::setRefreshIntervalSec,
            publish = { ConfigPrefsSync.sync(getApplication()) },
        )
        _publishFailure.value =
            if (result.published) null
            else "刷新间隔已保存为 ${result.storedSec} 秒，但未发布给 Hook —— " +
                "目标 App 仍在使用上一份配置"
    }

    /** Publishes and records the outcome; a `false` from [ConfigPrefsSync.sync] must never be dropped. */
    private fun publish() {
        val published = ConfigPrefsSync.sync(getApplication())
        _publishFailure.value =
            if (published) null
            else "设置已保存，但未发布给 Hook —— 目标 App 仍在使用上一份配置"
    }

    private fun readPublishedConfig(): PublishedConfig? = PublishedConfig.parse(
        ConfigPrefsSync.readPublished(getApplication()).textOrNull,
    )

    // ---- T8 (P0.3): configuration bundle export / import ----

    /** Human-readable bundle import outcome (success summary / rejection), null = silent. */
    data class BundleImportUi(
        val message: String,
        val warnings: List<String> = emptyList(),
        val callerFingerprints: List<QwyBundleSections.CallerFingerprint> = emptyList(),
        val isError: Boolean = false,
    )

    private val _bundleImportUi = MutableStateFlow<BundleImportUi?>(null)
    val bundleImportUi: StateFlow<BundleImportUi?> = _bundleImportUi

    /** True while a parsed bundle waits for the overwrite/skip decision (existing profiles). */
    private val _bundleConflictPending = MutableStateFlow(false)
    val bundleConflictPending: StateFlow<Boolean> = _bundleConflictPending

    private var pendingBundleBytes: ByteArray? = null

    fun dismissBundleImportUi() {
        _bundleImportUi.value = null
    }

    fun exportConfigBundle(uri: Uri) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val app = getApplication<Application>()
                    val db = AppDatabase.getInstance(app)
                    val entities = db.profileDao().getAll()
                    // The truthful pointer is the profile the hook is ACTUALLY running.
                    val activeId = PublishedProfileMatcher.effectiveProfileId(
                        entities,
                        ConfigPrefsSync.readPublished(app),
                    )
                    val active = activeId?.let { id -> entities.firstOrNull { it.id == id } }
                    val settings = SpoofSettings.getInstance(app)
                    val export = QwyBundleExport(
                        profiles = entities,
                        activeProfile = active?.let {
                            QwyBundleExport.ActiveProfileRef(
                                QwyProfileFingerprint.of(it),
                                it.addname,
                            )
                        },
                        settings = QwyBundleSections.SettingsSnapshot(
                            spoofMode = settings.getRawMode(),
                            activeHourStart = settings.getRawHourStart(),
                            activeHourEnd = settings.getRawHourEnd(),
                            refreshIntervalSec = settings.readRefreshIntervalSec(),
                            locationDeliveryMode = settings.readLocationDeliveryMode().wireValue,
                            modules = settings.readModulesEnabled(),
                        ),
                        callers = ProviderRuntime.pairingFingerprints(app)
                            .filter { it.revokedAtElapsedRealtimeMs == null }
                            .map {
                                QwyBundleSections.CallerFingerprint(
                                    applicationId = it.applicationId,
                                    signerDigest = it.signerDigest,
                                    observedVersionCode = it.observedVersionCode,
                                )
                            },
                        lane = QwyBundleSections.LaneMetadata(
                            qwyApplicationId = app.packageName,
                            qwyVersionName = name.caiyao.fakegps.BuildConfig.VERSION_NAME,
                            transportSchemaVersion = ConfigPrefsSync.SCHEMA_VERSION,
                            autoApplicationId = null,
                            autoVersionName = null,
                            providerPrincipal = null,
                        ),
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                    val bytes = QwyBundleExporter.export(export).zipBytes
                    app.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(bytes)
                    } ?: error("无法创建配置包文件")
                    entities.size
                }
            }
            _bundleImportUi.value = outcome.fold(
                onSuccess = { count ->
                    BundleImportUi("配置包已导出：$count 个档案、车道配置与调用方指纹（不含任何密钥）")
                },
                onFailure = { BundleImportUi("导出失败：${it.message}", isError = true) },
            )
        }
    }

    fun importConfigBundle(uri: Uri) {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)
                        ?.use { it.readBytes() }
                }.getOrNull()
            }
            if (bytes == null) {
                _bundleImportUi.value = BundleImportUi("无法读取所选配置包", isError = true)
                return@launch
            }
            // Conflict policy: existing profiles → the overwrite/skip dialog (default prompt).
            val existingCount = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(getApplication()).profileDao().getAll().size
            }
            if (existingCount > 0) {
                pendingBundleBytes = bytes
                _bundleConflictPending.value = true
            } else {
                runBundleImport(bytes, ConfigBundleImportDecision.Replace)
            }
        }
    }

    /** Overwrite decision: the bundle's profile set replaces the local one. */
    fun confirmBundleReplace() {
        val bytes = pendingBundleBytes ?: return
        clearBundleConflict()
        runBundleImport(bytes, ConfigBundleImportDecision.Replace)
    }

    /** Skip decision: keep local profiles entirely; the rest of the bundle still applies. */
    fun confirmBundleKeepExisting() {
        val bytes = pendingBundleBytes ?: return
        clearBundleConflict()
        runBundleImport(bytes, ConfigBundleImportDecision.KeepExisting)
    }

    fun dismissBundleConflict() {
        clearBundleConflict()
        _bundleImportUi.value = BundleImportUi("已取消导入：本机档案保持不变")
    }

    private fun clearBundleConflict() {
        pendingBundleBytes = null
        _bundleConflictPending.value = false
    }

    private fun runBundleImport(bytes: ByteArray, decision: ConfigBundleImportDecision) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                val db = AppDatabase.getInstance(app)
                QwyBundleImporter(
                    db = db,
                    repository = ProfileRepository(db, app),
                    settingsApplier = { snapshot ->
                        val settings = SpoofSettings.getInstance(app)
                        settings.setSpoofMode(snapshot.spoofMode)
                        settings.setActiveHourStart(snapshot.activeHourStart)
                        settings.setActiveHourEnd(snapshot.activeHourEnd)
                        settings.setRefreshIntervalSec(snapshot.refreshIntervalSec)
                        settings.setLocationDeliveryMode(
                            LocationDeliveryMode.fromWireValue(snapshot.locationDeliveryMode),
                        )
                        for ((module, enabled) in snapshot.modules) {
                            settings.setModuleEnabled(module, enabled)
                        }
                        // One publish carries mode/hours/refresh/delivery/modules to the hook.
                        ConfigPrefsSync.sync(app)
                    },
                ).import(bytes, decision)
            }
            _bundleImportUi.value = when (result) {
                is ConfigBundleImportResult.Rejected ->
                    BundleImportUi("导入已拒绝：${result.reason}", isError = true)
                is ConfigBundleImportResult.Done -> BundleImportUi(
                    message = buildString {
                        if (result.profilesSectionApplied) {
                            append("已导入档案 ${result.profilesImported} 个")
                            if (result.profilesDuplicate > 0) {
                                append("（重复跳过 ${result.profilesDuplicate}）")
                            }
                            append("；")
                        }
                        if (result.anchoredProfileId != null) {
                            append("已锚定生效档案：${result.anchoredProfileName ?: result.anchoredProfileId}；")
                        }
                        if (result.settingsApplied) append("车道配置已应用；")
                        if (result.callerFingerprints.isNotEmpty()) {
                            append("包内含 ${result.callerFingerprints.size} 个 Auto 指纹，仅用于核对——请在下方重新批准；")
                        }
                        append("完成。")
                    },
                    warnings = result.warnings,
                    callerFingerprints = result.callerFingerprints,
                )
            }
        }
    }
}
