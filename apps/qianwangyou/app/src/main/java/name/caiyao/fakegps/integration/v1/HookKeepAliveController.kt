package name.caiyao.fakegps.integration.v1

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore

/**
 * #198: transport-free seam between the contract handler and the anti-freeze
 * foreground service. The handler knows exactly when a blocking lease exists;
 * this seam carries that single bit out WITHOUT letting anything Android-shaped
 * leak into the JVM-testable handler, and WITHOUT giving the keep-alive any
 * contract authority — a failed or dead keep-alive is a degraded anti-freeze
 * posture, never a contract outcome.
 */
fun interface LeaseKeepAliveSignal {
    /** [hasBlockingLease] = 设备上存在任何非 RELEASED 的阻塞 lease。 */
    fun onLeasePressure(hasBlockingLease: Boolean)
}

/**
 * #198 production impl: turn lease pressure into [HookKeepAliveService] FGS
 * lifecycle.
 *
 * 启停语义（迭代二起生产 wiring 由 [LingeringKeepAlive] 驱动 —— 收敛不再立即
 * 停，先过有界 linger；本类仍是唯一的 Android 执行器，语义不变）：
 *  - engage（存在阻塞 lease）：System Mock 前台已在跑时【不重复拉起】
 *    （MockProviderService 的 FGS 已提供同样的 PowerKeeper 豁免）；否则
 *    startForegroundService(ACTION_ENGAGE)。
 *  - disengage（无阻塞 lease）：stopService。服务不在即无操作。迭代一在这里
 *    立即停，实测 FGS 移除后 3.0–3.8s 即被 PowerKeeper 冻结、引擎间隙内下一
 *    次 attempt 黑洞 —— 迭代二起该调用只发生在 linger 到期后（见
 *    [LingeringKeepAlive]）。
 *  - 全部 Android 失败路径 runCatching + WARN：保活只是抗冻手段，服务死亡
 *    不得影响合同语义（红线）。linger 延迟路径经策略器调用时同样被
 *    [LingeringKeepAlive.deliver] 兜底降级。
 */
class HookKeepAlive(private val context: Context) : LeaseKeepAliveSignal {

    override fun onLeasePressure(hasBlockingLease: Boolean) {
        try {
            if (hasBlockingLease) engage() else disengage()
        } catch (failure: Exception) {
            android.util.Log.w(
                TAG,
                "#198 lease keep-alive sync failed (contract semantics unaffected): " +
                    "${failure.javaClass.simpleName}: ${failure.message}",
            )
        }
    }

    private fun engage() {
        // System Mock 模式不重复拉起：MockProviderService 前台运行中（同进程，
        // 状态在进程内 StateFlow）即已有 FGS 豁免。若它中途被停而 lease 仍
        // 活跃，下一个合同信号（observe 轮询/apply/release）会重新触发本方法
        // 完成自愈 —— 该窗口内的暴露是已知局限，真机验证归主线（#198 Next）。
        if (MockProviderStatusStore.state.value is MockProviderState.Running) {
            android.util.Log.d(TAG, "engage skipped — System Mock foreground already up")
            return
        }
        val intent = Intent(context, HookKeepAliveService::class.java)
            .setAction(HookKeepAliveService.ACTION_ENGAGE)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun disengage() {
        // stopService 无后台启动限制；服务未运行时是无害 no-op。
        context.stopService(Intent(context, HookKeepAliveService::class.java))
    }

    private companion object {
        const val TAG = "HookKeepAlive"
    }
}
