package name.caiyao.fakegps.integration.v1

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import name.caiyao.fakegps.R
import name.caiyao.fakegps.ui.ComposeActivity

/**
 * #198: hook 投递模式下的轻量保活前台服务。
 *
 * ROOT CAUSE (#198, mi14 e2e): engine-apply 驱动的 hook 模式运行期间 QWY 没有
 * 任何前台服务 → 小米 PowerKeeper 冻结进程 → 引擎随后的 lease release binder
 * 事务落入黑洞（release 永不完成）。e2e 实证缓解 = System Mock ON 时
 * [name.caiyao.fakegps.mockprovider.MockProviderService] 的前台 1Hz 注入可抗冻
 * —— 即"有 FGS 在前台"本身就足以免冻。本服务把同一豁免带给 hook 模式。
 *
 * 生命周期红线：本服务只是抗冻手段，与服务死亡相关的任何路径都不得影响合同
 * 语义 —— 启动失败被 [HookKeepAlive] 吞掉（降级为无保活），服务被系统杀死后
 * 下一次 lease 信号自愈重拉；release 的正确性从不依赖本服务存活。
 *
 * 语义（由 [HookKeepAlive] 驱动，服务自身无状态机）：
 *  - ACTION_ENGAGE（或首次创建）：立即 startForeground（onCreate 里做，掐灭
 *    startForegroundService 的 fg-required 竞态）+ 极轻心跳（仅打日志，不持
 *    wakelock —— FGS 前台态本身即 PowerKeeper 豁免）。
 *  - release：lease 全部收敛（无阻塞 lease）时控制器 stopService（→ onDestroy），
 *    不常驻耗电。停止是 stopService 的职责，服务没有"命令型停止"分支。
 *  - START_NOT_STICKY：死即止，重启由下一个合同信号负责。
 *
 * FGS 类型（targetSdk 35 / API 34+ 强制声明）：specialUse —— 本服务不投递
 * 位置（hook 模式由引擎 apply 发布载荷），借用 location 类型是谎报；specialUse
 * 语义就是"合同保活"这类无标准类型的前台用途。
 */
class HookKeepAliveService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val tick = object : Runnable {
        override fun run() {
            android.util.Log.d(TAG, "keep-alive tick (lease guard) pid=${android.os.Process.myPid()}")
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // onCreate 内 startForeground：不依赖 intent 内容——只要经
        // startForegroundService 拉起，fg-required 即已满足，不会触发
        // "did not then call startForeground"崩溃。
        beginForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ENGAGE、重复 start、无 action start 一律幂等：重置心跳即可。
        // release 走控制器的 stopService（→ onDestroy），无命令 intent；
        // stopService 对未运行的服务是无害 no-op，也不受后台 start 限制。
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, TICK_MILLIS)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun beginForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "环境合同保活",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_lan)
        .setContentTitle("千网游 · 环境合同保活")
        .setContentText("Hook 投递合同生效期间保持前台，防止系统冻结")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, ComposeActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    companion object {
        private const val TAG = "HookKeepAlive"
        const val ACTION_ENGAGE = "name.caiyao.fakegps.action.HOOK_KEEP_ALIVE_ENGAGE"
        const val CHANNEL_ID = "hook_keepalive"
        const val NOTIFICATION_ID = 2402

        /**
         * 极轻心跳：只打一行日志维持 CPU 上的活动痕迹，不持 wakelock。
         * 5s 取 1–5s 区间的省电端；若真机验证发现 5s 不足以抗冻，主线再收紧。
         */
        const val TICK_MILLIS = 5_000L
    }
}
