package name.caiyao.fakegps.ui.screen.statuscenter

import java.util.Locale
import name.caiyao.fakegps.data.LocationDeliveryMode
import name.caiyao.fakegps.mockprovider.MockProviderState

/**
 * M1 状态中心（T11b）的 UI 投影真值表——全部纯函数，无 Android 类型。
 *
 * 状态字典来源：UI-WORKFLOWS-AND-SCREEN-SPECS.md §M1 元素表 + §状态字典；灯色语义与
 * Auto 侧 RunDashboardScreen 一致：绿=就绪/完成、黄=待处理、灰=未知。ViewModel 负责喂数据，
 * 一切「显示成什么」的决策都在这里钉死（由 StatusCenterProjectionTest 穷尽）。
 */

/** 三灯/徽标共用的状态词表：绿=就绪、黄=待处理、灰=未知。 */
enum class LampState { GREEN, YELLOW, GREY }

data class StatusLamp(val state: LampState, val detail: String)

/** 生效档案卡三态（§M1：无档案/已锚定/发布失败）。 */
enum class ProfileCardState { NO_PROFILE, ANCHORED, PUBLISH_FAILED }

/** 生效档案卡的投影结果。chipText 是三态的人话徽标文案。 */
data class ProfileCardUi(
    val state: ProfileCardState,
    val name: String?,
    val coordinate: String?,
    val deliveryLabel: String,
    val chipText: String,
)

/** 待办条条目。id 供 Compose key / 跳转分流用。 */
data class TodoItemUi(val id: String, val title: String, val detail: String)

/** 运动链入口卡片；[playing]=播放中，false=已完成。null = 隐藏（无播放会话）。 */
data class MotionEntryUi(val playing: Boolean, val label: String, val detail: String)

object StatusCenterProjection {

    /**
     * Vector 灯的「publish 新鲜度」默认窗口：一次成功发布后 5 分钟内视为镜像新鲜。
     * 纯 UI 判定词——hook 侧真正的刷新节奏由 PublishPropagation 管，这里只回答
     * 「我有没有理由相信镜像还活着」。
     */
    const val DEFAULT_VECTOR_FRESH_WINDOW_MS: Long = 5 * 60 * 1000L

    // ---- 发布灯：publish_state 回读（published=true 绿 / false 黄 / 未知 灰）-----------

    /** ConfigPrefsSync 回读结果中与发布灯相关的投影输入。 */
    data class PublishReadback(
        /** 世界可读 prefs 里有已发布的 payload（Absent/ReadError = false）。 */
        val payloadPresent: Boolean,
        /** 私有 publish_state store 的失败标记。 */
        val publishFailed: Boolean,
    )

    /**
     * 发布灯。2^2 输入组合的决策表：
     * (present,ok)→绿 published=true；(present,failed)/(absent,failed)→黄 发布失败；
     * (absent,ok)→灰 未知（从未发布）。
     */
    fun publishLamp(read: PublishReadback): StatusLamp = when {
        read.publishFailed -> StatusLamp(LampState.YELLOW, "published=false · 发布失败")
        read.payloadPresent -> StatusLamp(LampState.GREEN, "published=true")
        else -> StatusLamp(LampState.GREY, "未发布")
    }

    // ---- Vector 灯：模块自检（BuildConfig 自 hook 判定）或 publish 新鲜度 --------------

    data class VectorReadback(
        /** 观测域 = SELF_HOOKED（debug 构建自 hook；镜像 MainHook 的注入条件）。 */
        val selfHooked: Boolean,
        val payloadPresent: Boolean,
        val publishFailed: Boolean,
        /** 最近一次 VERIFIED 发布的墙钟时间；null = 从未记录。 */
        val publishedAtMs: Long?,
    )

    /**
     * Vector 灯的判定顺序（不可调换）：
     * 1. publish 失败 → 黄（自 hook 也救不了没送达的配置）；
     * 2. 从未发布 → 灰（未知，不是失败）；
     * 3. 自 hook 激活 → 绿（模块注入的最强证据）；
     * 4. 无时间戳 → 灰；
     * 5. 回读新鲜（now-publishedAt ≤ window，含边界）→ 绿，否则黄。
     */
    fun vectorLamp(
        read: VectorReadback,
        nowMs: Long,
        freshWindowMs: Long = DEFAULT_VECTOR_FRESH_WINDOW_MS,
    ): StatusLamp = when {
        read.publishFailed -> StatusLamp(LampState.YELLOW, "发布失败")
        !read.payloadPresent -> StatusLamp(LampState.GREY, "未发布")
        read.selfHooked -> StatusLamp(LampState.GREEN, "self-hook · enabled")
        read.publishedAtMs == null -> StatusLamp(LampState.GREY, "无回读时间")
        nowMs - read.publishedAtMs <= freshWindowMs ->
            StatusLamp(LampState.GREEN, "enabled · ${formatClock(read.publishedAtMs)}")
        else -> StatusLamp(LampState.YELLOW, "回读过期 · ${formatClock(read.publishedAtMs)}")
    }

    // ---- Mock 灯：MockProvider 状态 + 注入坐标 -----------------------------------------

    /**
     * Mock 灯。Running → 绿并携带注入坐标（真值探针）；Starting/Stopping/Failed → 黄
     * （待处理/进行中）；Idle → 灰（未运行，不是错误）。
     */
    fun mockLamp(state: MockProviderState): StatusLamp = when (state) {
        is MockProviderState.Running -> StatusLamp(
            LampState.GREEN,
            "Running · ${coordinate(state.config.latitude, state.config.longitude)}",
        )
        is MockProviderState.Starting -> StatusLamp(LampState.YELLOW, "启动中")
        is MockProviderState.Stopping -> StatusLamp(LampState.YELLOW, "正在停止")
        is MockProviderState.Failed -> StatusLamp(LampState.YELLOW, "失败 · ${state.message}")
        MockProviderState.Idle -> StatusLamp(LampState.GREY, "未运行")
    }

    // ---- 生效档案卡三态 -----------------------------------------------------------------

    /**
     * 档案卡。publishFailed 优先于「已锚定」：pointer 存在但最近一次发布未验证时，
     * 用户要看到「发布失败」而不是虚假的安心。
     */
    fun profileCard(
        hasActiveProfile: Boolean,
        name: String?,
        latitude: Double?,
        longitude: Double?,
        delivery: LocationDeliveryMode,
        publishFailed: Boolean,
    ): ProfileCardUi {
        val state = when {
            !hasActiveProfile -> ProfileCardState.NO_PROFILE
            publishFailed -> ProfileCardState.PUBLISH_FAILED
            else -> ProfileCardState.ANCHORED
        }
        return ProfileCardUi(
            state = state,
            name = if (hasActiveProfile) name else null,
            coordinate = if (hasActiveProfile && latitude != null && longitude != null) {
                coordinate(latitude, longitude)
            } else {
                null
            },
            deliveryLabel = when (delivery) {
                LocationDeliveryMode.SYSTEM_MOCK -> "系统 Mock"
                LocationDeliveryMode.HOOK -> "Hook"
            },
            chipText = when (state) {
                ProfileCardState.NO_PROFILE -> "无档案"
                ProfileCardState.ANCHORED -> "已锚定"
                ProfileCardState.PUBLISH_FAILED -> "发布失败"
            },
        )
    }

    // ---- 待办条（契约通道 + DB）---------------------------------------------------------

    /**
     * 待办条条目，按处理优先序排列：等待批准 Auto（pending callers 非空时出现）→
     * 档案未锚定。都无需处理 → 空列表（条消失）。CSV 待导入由档案页的 Download 发现条负责，
     * 不在此重复。
     */
    fun todoItems(pendingCallerCount: Int, anchoredProfileMissing: Boolean): List<TodoItemUi> =
        buildList {
            if (pendingCallerCount > 0) {
                add(
                    TodoItemUi(
                        id = "pending_callers",
                        title = "等待批准 Auto",
                        detail = "有 $pendingCallerCount 个调用方等待批准",
                    ),
                )
            }
            if (anchoredProfileMissing) {
                add(
                    TodoItemUi(
                        id = "anchor_missing",
                        title = "档案未锚定",
                        detail = "点生效档案卡选择档案",
                    ),
                )
            }
        }

    // ---- 运动链入口（T9 播放状态）-------------------------------------------------------

    /**
     * 运动链入口卡；null = 隐藏。只有「正在播放路线的 Running 会话」或「已完成的路线会话」
     * 出现——静态点位会话/未运行不出现（§M1：关/播放/完成）。
     */
    fun motionEntry(state: MockProviderState): MotionEntryUi? {
        val running = state as? MockProviderState.Running ?: return null
        val player = running.routePlayer ?: return null
        val waypoints = player.spec.waypoints.size
        return if (running.routeCompleted) {
            MotionEntryUi(
                playing = false,
                label = "运动链已完成",
                detail = "$waypoints 路标播放完毕",
            )
        } else {
            MotionEntryUi(
                playing = true,
                label = "运动链播放中",
                detail = "已发 ${running.emittedCount} 点 / $waypoints 路标",
            )
        }
    }

    // ---- 小工具 -------------------------------------------------------------------------

    /** 与 §5 数据样例一致的坐标格式：小数点后 6 位，逗号+空格分隔。 */
    private fun coordinate(latitude: Double, longitude: Double): String =
        String.format(Locale.US, "%.6f, %.6f", latitude, longitude)

    private fun formatClock(epochMs: Long): String {
        val format = java.text.SimpleDateFormat("HH:mm", Locale.US)
        return format.format(java.util.Date(epochMs))
    }
}
