package name.caiyao.fakegps.integration.v1

import android.os.Handler

/**
 * #198 迭代二（mi14 三周期证据，issues/198#issuecomment-5666574320）：迭代一在
 * lease 收敛的瞬间 stopService，而实测 PowerKeeper 在 FGS 移除后 3.0–3.8s 即冻
 * 结无 FGS 的 QWY；引擎 attempt 间隙（BufferGate = globalBufferSeconds，默认
 * 10s、可配置）必然整个落在冻结窗内 → 下一次 attempt 的第一个 binder 调用
 * （discover）黑洞（"return black caller"，冻结进程不因入站 binder 自动 THAW）
 * → typed PAUSED，0 次干净边界跨越。TICK 收紧无效：冻结发生在 FGS 整体移除之后。
 *
 * 修复语义 = lease 压力收敛后保活【有界 linger】：
 *  - true（存在阻塞 lease）：原样下发 [downstream]（含每次重复 true —— 那是
 *    FGS 中途死亡的自愈重拉路径，迭代一语义不变）。
 *  - false（lease 全收敛）：【不】立即 disengage；下游保持当前状态，起一个
 *    [lingerMillis] 的有界定时器。期间任何新 true 取消定时器并继续（间隙内
 *    下一次 attempt 的 apply 落在仍被豁免的进程上 → 无保活窗口）。
 *  - 定时器到点：才真正下发 false（stopService）。plan 真结束时保活仍在
 *    linger 上限内退出 —— 不常驻。
 *
 * linger 取值依据（[DEFAULT_LINGER_MILLIS]）：冻结延迟 3.0–3.8s ≪ 默认间隙
 * 10s；linger 必须 ≥ 整个间隙才有效。30s = 默认 BufferGate（10s）的 3 倍余量，
 * 覆盖到 30s 的操作员配置；上限代价仅是 plan 真结束后通知多挂 ≤30s
 * （IMPORTANCE_LOW 静默）。不取 10–12s：对默认间隙零余量，且引擎在 buffer 到期
 * 与下一次 apply 之间还有 preflight/discover 等调度抖动；不取 60s+：从未观测到
 * >30s 的间隙，徒增 plan 结束后的前台残留。
 *
 * 红线（继承迭代一并加严）：
 *  - handler 侧同步路径的失败仍由 handler 的 signalLeasePressure 降级；本类
 *    新增的【延迟路径】跑在 handler 的 try/catch 之外（定时器线程），因此
 *    downstream 失败必须在这里就地降级为 diagnostics —— 绝不能炸进定时器线程
 *    （= 进程崩溃），失败后相位仍前移到 IDLE，不卡死在 LINGERING（不常驻）。
 *  - 本类对合同无任何权威：它是纯下游编排，读不到也不读合同状态；压力 bit
 *    仍由 handler 在每次落定后重算并逐次下发（幂等 replay 也逐次）。
 *  - 进程死亡语义不变：策略器与服务同生共死，下一个合同信号自愈重拉。
 *
 * 时钟：仅用 [MonotonicClock.elapsedRealtimeMs]（单调，§6.4.2）做 linger 窗口
 * 核算 —— 定时器提前/重复触发按剩余时间重挂，不精确定时器最终也会触发退出。
 */
class LingeringKeepAlive(
    private val downstream: LeaseKeepAliveSignal,
    private val clock: MonotonicClock,
    private val lingerTimer: LingerTimer,
    private val lingerMillis: Long = DEFAULT_LINGER_MILLIS,
    private val diagnostics: DiagnosticLog = DiagnosticLog.ANDROID,
) : LeaseKeepAliveSignal {

    /**
     * 有界延迟触发 seam：生产用 [HandlerLingerTimer]（主线程 Handler），JVM 用
     * 假件、Robolectric 用真 Handler + 虚拟 looper 时间。arm 语义 = 一次性定时，
     * 前 [delayMs] 毫秒后触发 [onExpiry] 恰好一次；新 arm 取换旧 arm。
     */
    interface LingerTimer {
        fun arm(delayMs: Long, onExpiry: () -> Unit)
        fun disarm()
    }

    private enum class Phase {
        /** 下游已停（或从未拉起）。 */
        IDLE,

        /** 存在阻塞 lease，下游保持拉起。 */
        ENGAGED,

        /** lease 已收敛但仍在 linger 窗口内，下游保持拉起，定时器挂起。 */
        LINGERING,
    }

    private var phase = Phase.IDLE
    private var lingerStartElapsedMs = 0L

    override fun onLeasePressure(hasBlockingLease: Boolean) {
        // handler 的 binder 线程与定时器线程（主 looper）并发到达。
        synchronized(this) {
            if (hasBlockingLease) engage() else absorbOrPassThrough()
        }
    }

    private fun engage() {
        if (phase == Phase.LINGERING) {
            lingerTimer.disarm()
            log("#198 keep-alive linger cancelled by a new lease (gap bridged)")
        }
        phase = Phase.ENGAGED
        // 每次都透传 true：重复 true 是 FGS 中途死亡的自愈重拉路径（迭代一语义）。
        deliver(true)
    }

    private fun absorbOrPassThrough() {
        when (phase) {
            // 收敛：这是迭代一黑洞的根因点 —— 此处不再立即 disengage。
            Phase.ENGAGED, Phase.LINGERING -> startLinger()
            // 本来就没保活：原样透传（stopService 对未运行的服务是无害 no-op，
            // 与迭代一行为对齐）。
            Phase.IDLE -> deliver(false)
        }
    }

    private fun startLinger() {
        lingerStartElapsedMs = clock.elapsedRealtimeMs()
        phase = Phase.LINGERING
        lingerTimer.arm(lingerMillis) { onLingerExpired() }
        log(
            "#198 keep-alive linger armed for ${lingerMillis}ms " +
                "(lease converged; bridging the engine attempt gap)",
        )
    }

    /** 定时器回调（主 looper）。与 [onLeasePressure] 并发，需锁。 */
    private fun onLingerExpired() {
        synchronized(this) {
            if (phase != Phase.LINGERING) return
            val remaining = lingerMillis - (clock.elapsedRealtimeMs() - lingerStartElapsedMs)
            if (remaining > 0) {
                // 提前触发（不精确定时器/时钟基线差）：按剩余时间重挂。
                lingerTimer.arm(remaining) { onLingerExpired() }
                return
            }
            // 相位先落定再下发：下游失败也不得把策略器卡死在 LINGERING（不常驻）。
            phase = Phase.IDLE
            log("#198 keep-alive linger expired — disengaging (plan ended or gap exceeded)")
            deliver(false)
        }
    }

    /**
     * 延迟路径的失败兜底：这里不在 handler 的 signalLeasePressure try/catch 之
     * 内，任何下游异常都必须就地降级为 diagnostics，绝不上抛进定时器线程。
     */
    private fun deliver(pressure: Boolean) {
        try {
            downstream.onLeasePressure(pressure)
        } catch (failure: RuntimeException) {
            log(
                "#198 keep-alive linger downstream failed (contract semantics unaffected) — " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    private fun log(message: String) {
        runCatching { diagnostics.warn(TAG, message) }
    }

    companion object {
        private const val TAG = "HookKeepAlive"

        /**
         * 30s：默认 BufferGate 10s 的 3 倍余量（引擎间隙 ≥9s、实测冻结延迟
         * 3.0–3.8s）；覆盖操作员配到 30s 的间隙；plan 真结束后最多多挂 30s
         * 静默低优通知。
         */
        const val DEFAULT_LINGER_MILLIS = 30_000L
    }
}

/**
 * 生产 [LingeringKeepAlive.LingerTimer]：主线程 [Handler] 一次性定时。arm 先
 * 解除旧挂起（幂等）；触发后自清 current，使随后的 disarm 成为无害 no-op。
 */
class HandlerLingerTimer(
    private val handler: Handler,
) : LingeringKeepAlive.LingerTimer {

    private var current: Runnable? = null

    override fun arm(delayMs: Long, onExpiry: () -> Unit) {
        synchronized(this) {
            disarmLocked()
            val wrapper = Runnable {
                synchronized(this@HandlerLingerTimer) { current = null }
                onExpiry()
            }
            current = wrapper
            handler.postDelayed(wrapper, delayMs)
        }
    }

    override fun disarm() {
        synchronized(this) { disarmLocked() }
    }

    private fun disarmLocked() {
        current?.let(handler::removeCallbacks)
        current = null
    }
}
