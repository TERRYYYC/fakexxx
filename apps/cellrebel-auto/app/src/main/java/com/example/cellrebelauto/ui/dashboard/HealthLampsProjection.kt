package com.example.cellrebelauto.ui.dashboard

/**
 * The three-state lamp verdict. GREY is legal for every lamp but must always
 * carry an explanation.
 * # 灯三态：绿/黄/灰；灰灯必须带说明
 */
enum class LampState { GREEN, YELLOW, GREY }

/**
 * One lamp: the verdict + what the operator should know / do (never blank for GREY).
 */
data class HealthLamp(
    val state: LampState,
    val detail: String,
)

/**
 * T7 P1.1 — the three health lamps' states (pure projection).
 *
 * ① 无障碍 Bound — the engine HOST lives or dies with the accessibility
 *    service; ② QWY provider reachable + publish state — via the EXISTING
 *    discover channel (handshake), greyed with an explanation when the
 *    channel yields nothing; ③ Vector 链路新鲜度 — QWY-side publish_at vs
 *    now, YELLOW beyond the freshness window, GREY (explained) when the
 *    timestamp is unreadable from this app.
 *
 * A GREY lamp ALWAYS carries a reason — "取不到" must be visible and named,
 * never blank.
 *
 * # 健康三灯投影：绿/黄/灰三态；灰灯必须自带说明
 */
object HealthLampsProjection {

    /** A publish older than this is stale (YELLOW). */
    const val FRESH_MS: Long = 24L * 3_600_000L

    /**
     * The part of the discover handshake the provider lamp consumes. Kept as a
     * local struct so the projection has no dependency on the Binder contract.
     */
    data class ProviderHandshake(
        val exhausted: Boolean?,
        val profileCount: Int?,
    )

    // ---- ① 无障碍 Bound -------------------------------------------------------

    fun accessibility(serviceConnected: Boolean, enabled: Boolean?): HealthLamp = when {
        serviceConnected -> HealthLamp(
            LampState.GREEN,
            "无障碍服务已连接（引擎宿主在线）"
        )
        enabled == false -> HealthLamp(
            LampState.YELLOW,
            "无障碍未启用 — 设置 → 无障碍 → 本应用 → 打开开关（OEM 可能在重装后自动关闭）"
        )
        enabled == true -> HealthLamp(
            LampState.YELLOW,
            "无障碍已启用但未连接 — 在设置中关闭再重新打开本应用的无障碍开关"
        )
        else -> HealthLamp(
            LampState.GREY,
            "无法探测无障碍启用状态（引擎宿主未连接，Resume 前请先检查开关）"
        )
    }

    // ---- ② QWY provider ----------------------------------------------------------

    fun provider(handshake: ProviderHandshake?): HealthLamp = when {
        handshake == null -> HealthLamp(
            LampState.GREY,
            "discover 通道不可达（QWY 未安装/未运行或绑定失败）— 检查千网游与 Vector 模块"
        )
        handshake.exhausted == true -> HealthLamp(
            LampState.YELLOW,
            "QWY 可达但日程已耗尽 — 去千网游重开日程后继续"
        )
        else -> HealthLamp(
            LampState.GREEN,
            "QWY 可达，契约通道正常" +
                (handshake.profileCount?.let { "（档案 $it 份）" } ?: "")
        )
    }

    // ---- ③ vector chain freshness --------------------------------------------------

    fun vector(publishedAtMs: Long?, nowMs: Long): HealthLamp = when {
        publishedAtMs == null -> HealthLamp(
            LampState.GREY,
            "publish 时间不可读（QWY 侧 publish_state 未暴露给本应用）— " +
                "需在 QWY 侧确认最近一次发布；本灯在拿到时间戳前保持灰色"
        )
        nowMs - publishedAtMs >= FRESH_MS -> HealthLamp(
            LampState.YELLOW,
            "发布已陈旧（距今 ${((nowMs - publishedAtMs) / 3_600_000L)} 小时）— " +
                "到千网游重新发布配置"
        )
        else -> HealthLamp(
            LampState.GREEN,
            "发布新鲜（距今 ${((nowMs - publishedAtMs) / 60_000L).coerceAtLeast(0)} 分钟内）"
        )
    }
}
