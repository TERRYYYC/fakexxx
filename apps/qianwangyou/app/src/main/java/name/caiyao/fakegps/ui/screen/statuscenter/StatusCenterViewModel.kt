package name.caiyao.fakegps.ui.screen.statuscenter

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.config.PayloadRead
import name.caiyao.fakegps.config.SpoofModules
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.repository.ProfileRepository
import name.caiyao.fakegps.integration.v1.PendingPairingCandidate
import name.caiyao.fakegps.integration.v1.ProviderRuntime
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore
import name.caiyao.fakegps.ui.screen.settings.ModuleToggleUpdate
import name.caiyao.fakegps.ui.screen.settings.SpoofModuleUiCatalog
import name.caiyao.fakegps.verify.ObservationScope

/**
 * M1 状态中心（T11b）ViewModel——只做数据装配，一切显示决策在 [StatusCenterProjection]。
 *
 * 三灯数据源（§M1 元素表）：发布 = ConfigPrefsSync publish_state 回读；Vector = 自 hook 判定
 * （BuildConfig.DEBUG 镜像 MainHook 注入条件）或 publish 新鲜度；Mock = MockProviderStatusStore。
 * 待办条 = ProviderRuntime.pendingCallers + 锚定指针；模块开关行复用 T5 抽出的
 * [ModuleToggleUpdate] 持久化→发布序列（与 SettingsViewModel 同一条链）。
 */
class StatusCenterViewModel @JvmOverloads constructor(
    app: Application,
    // 与 CollectionViewModel 相同的 oracle seam：测试注入带 recording publisher 的仓库，
    // 生产保持单例 DB + 真 ConfigPrefsSync 发布链。
    repoOverride: ProfileRepository? = null,
    /** 模块开关/重新发布的发布链 seam；生产 = ConfigPrefsSync.sync。 */
    private val publishOverride: (() -> Boolean)? = null,
    /** publish_state 回读 seam；生产 = [defaultSnapshotReader]。 */
    private val snapshotReader: PublishSnapshotReader =
        PublishSnapshotReader { context -> defaultSnapshotReader(context) },
    /** pending callers 读取 seam；生产 = [defaultPendingCallersLoader]。 */
    private val pendingCallersLoader: suspend (Context) -> List<PendingPairingCandidate> =
        { context -> defaultPendingCallersLoader(context) },
    /** Vector 灯的「模块自检」输入；镜像 ObservationScope.current() 的 BuildConfig 条件。 */
    private val selfHooked: Boolean = ObservationScope.current() == ObservationScope.SELF_HOOKED,
    private val clock: () -> Long = System::currentTimeMillis,
) : AndroidViewModel(app) {

    /** publish_state 回读投影（§状态字典：发布回执 + 锚定指针）。 */
    data class PublishSnapshot(
        /** 世界可读 prefs 里的 payload 原文；null = 从未发布（或读失败，同 Absent 投影）。 */
        val payloadText: String?,
        val publishedAtMs: Long?,
        val publishFailed: Boolean,
        /** sync() 在已验证发布时持久化的 activeProfileId；null = 未锚定。 */
        val activeProfileId: Long?,
    )

    fun interface PublishSnapshotReader {
        fun read(context: Context): PublishSnapshot
    }

    /** 状态中心整屏的单一状态——Compose 一次 collect，测试一次断言。 */
    data class Ui(
        val profile: ProfileCardUi = StatusCenterProjection.profileCard(
            hasActiveProfile = false,
            name = null,
            latitude = null,
            longitude = null,
            delivery = LocationDeliveryMode.HOOK,
            publishFailed = false,
        ),
        val publish: StatusLamp = StatusLamp(LampState.GREY, "未发布"),
        val vector: StatusLamp = StatusLamp(LampState.GREY, "未发布"),
        val mock: StatusLamp = StatusLamp(LampState.GREY, "未运行"),
        val todos: List<TodoItemUi> = emptyList(),
        val motion: MotionEntryUi? = null,
        /** 七模块开关行，顺序 = SpoofModules.ALL（与设置页/发布词表一致）。 */
        val modules: List<ModuleRow> = emptyList(),
        val pendingCallerCount: Int = 0,
    ) {
        data class ModuleRow(val module: String, val label: String, val enabled: Boolean)
    }

    private val settings = SpoofSettings.getInstance(app)
    private val repo = repoOverride ?: ProfileRepository(AppDatabase.getInstance(app), app)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    /** 当前等待批准的调用方（待办条详情/后续批准流共用）。 */
    private val _pendingCallers = MutableStateFlow<List<PendingPairingCandidate>>(emptyList())
    val pendingCallers: StateFlow<List<PendingPairingCandidate>> = _pendingCallers

    /** 非空 = 最近一次设置变更加已持久化但未送达 Hook（绝不把失败说成生效）。 */
    private val _publishFailure = MutableStateFlow<String?>(null)
    val publishFailure: StateFlow<String?> = _publishFailure

    /** 锚定结果提示；成功确认「activeProfileId 已写 + publish 链已走」，失败绝不谎报。 */
    private val _anchorNotice = MutableStateFlow<String?>(null)
    val anchorNotice: StateFlow<String?> = _anchorNotice

    /** 档案表最新快照（锚定卡按 durable 指针查行）。 */
    private var entities: List<ProfileEntity> = emptyList()

    private var pending: List<PendingPairingCandidate> = emptyList()

    init {
        viewModelScope.launch {
            repo.observeEntities().collect { rows ->
                entities = rows
                recompute()
            }
        }
        refresh()
    }

    /** 重新回读 publish_state + pending callers（屏幕每次可见、以及每个动作之后调用）。 */
    fun refresh() {
        viewModelScope.launch {
            // IO 跳移由 loader 自己负责（defaultPendingCallersLoader 内 withContext(IO)），
            // 注入的同步 loader 在测试主调度器下确定性地完成——不在这里再包一层 withContext。
            pending = runCatching { pendingCallersLoader(getApplication()) }
                .getOrDefault(emptyList())
            _pendingCallers.value = pending
            recompute()
        }
    }

    /**
     * 一键锚定入口（生效档案卡 → 档案页长按/完成对话框共用既有链）：显式 profileId
     * 走 [ProfileRepository.setActiveProfile]，已验证发布时指针被持久化。
     */
    fun anchorActiveProfile(id: Long) {
        viewModelScope.launch {
            val published = repo.setActiveProfile(id)
            _anchorNotice.value =
                if (published) "已锚定生效档案并发布给 Hook"
                else "已请求锚定，但发布失败：目标 App 仍使用上一份配置"
            refresh()
        }
    }

    fun dismissAnchorNotice() {
        _anchorNotice.value = null
    }

    /**
     * 模块开关行（七模块）。持久化→发布序列与 SettingsViewModel 完全同源
     * （[ModuleToggleUpdate.apply]）：开关只存在于 v5 payload 里，只持久化不发布 = 假生效。
     */
    fun setModuleEnabled(module: String, enabled: Boolean) {
        val result = ModuleToggleUpdate.apply(
            module = module,
            enabled = enabled,
            persist = settings::setModuleEnabled,
            publish = ::publishSeam,
        )
        _publishFailure.value =
            if (result.published) null
            else "模块开关已保存，但未发布给 Hook —— 目标 App 仍在使用上一份配置"
        recompute()
    }

    fun dismissPublishFailure() {
        _publishFailure.value = null
    }

    /** 发布黄灯的恢复动作（W4：重新发布）。 */
    fun republish() {
        val published = publishSeam()
        _publishFailure.value =
            if (published) null
            else "重新发布未送达 Hook —— 目标 App 仍在使用上一份配置"
        refresh()
    }

    /** 唯一的发布链入口：测试注入 [publishOverride]，生产走 ConfigPrefsSync.sync。 */
    private fun publishSeam(): Boolean {
        val publish = publishOverride ?: { ConfigPrefsSync.sync(getApplication()) }
        return publish()
    }

    private fun recompute() {
        val app = getApplication<Application>()
        val snapshot = runCatching { snapshotReader.read(app) }.getOrElse {
            PublishSnapshot(payloadText = null, publishedAtMs = null, publishFailed = true, activeProfileId = null)
        }
        val publishReadback = StatusCenterProjection.PublishReadback(
            payloadPresent = snapshot.payloadText != null,
            publishFailed = snapshot.publishFailed,
        )
        val mockState = MockProviderStatusStore.state.value
        val active = snapshot.activeProfileId?.let { id -> entities.firstOrNull { it.id == id } }
        val delivery = settings.locationDeliveryMode.value

        _ui.value = Ui(
            profile = StatusCenterProjection.profileCard(
                hasActiveProfile = active != null,
                name = active?.addname,
                latitude = active?.latitude,
                longitude = active?.longitude,
                delivery = delivery,
                publishFailed = snapshot.publishFailed,
            ),
            publish = StatusCenterProjection.publishLamp(publishReadback),
            vector = StatusCenterProjection.vectorLamp(
                StatusCenterProjection.VectorReadback(
                    selfHooked = selfHooked,
                    payloadPresent = snapshot.payloadText != null,
                    publishFailed = snapshot.publishFailed,
                    publishedAtMs = snapshot.publishedAtMs,
                ),
                nowMs = clock(),
            ),
            mock = StatusCenterProjection.mockLamp(mockState),
            todos = StatusCenterProjection.todoItems(
                pendingCallerCount = pending.size,
                anchoredProfileMissing = active == null,
            ),
            motion = StatusCenterProjection.motionEntry(mockState),
            modules = moduleRows(),
            pendingCallerCount = pending.size,
        )
    }

    /** 七模块行；出厂默认（运动链关）如实投影，标签与设置页共用同一 catalog。 */
    private fun moduleRows(): List<Ui.ModuleRow> {
        val switches = settings.modulesEnabled.value
        return SpoofModuleUiCatalog.entries.map { entry ->
            Ui.ModuleRow(
                module = entry.module,
                label = entry.label,
                enabled = switches[entry.module] ?: SpoofModules.defaultEnabled(entry.module),
            )
        }
    }

    companion object {
        /** 生产回读：payload 原文 + 发布回执 + 锚定指针，全部来自 ConfigPrefsSync。 */
        fun defaultSnapshotReader(context: Context): PublishSnapshot = PublishSnapshot(
            payloadText = (ConfigPrefsSync.readPublished(context) as? PayloadRead.Raw)?.text,
            publishedAtMs = ConfigPrefsSync.readPublishedAt(context),
            publishFailed = ConfigPrefsSync.hasPublicationFailure(context),
            activeProfileId = ConfigPrefsSync.readActiveProfileId(context),
        )

        /** 生产读取：ProviderRuntime 的 pending candidates（首次触达会自举 provider）。 */
        suspend fun defaultPendingCallersLoader(
            context: Context,
        ): List<PendingPairingCandidate> = withContext(Dispatchers.IO) {
            ProviderRuntime.pendingCallers(context)
        }
    }
}
