package name.caiyao.fakegps.integration.v1

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import java.time.Duration
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * #198: the ANDROID side of the lease keep-alive binding, on Robolectric.
 *
 *  - [HookKeepAlive] engage → startForegroundService(HookKeepAliveService, ENGAGE)
 *  - System Mock 前台运行中（MockProviderService 已有 FGS）→ 不重复拉起
 *  - [HookKeepAlive] disengage → stopService（生产 release 路径，服务侧无命令 intent）
 *  - 服务本体：创建即前台（onCreate startForeground，掐灭 fg-required 竞态），
 *    ENGAGE 幂等重置心跳，任何命令都不停服务（停止归 stopService/onDestroy）
 */
@RunWith(RobolectricTestRunner::class)
class HookKeepAliveServiceTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val shadowApp get() = shadowOf(context)

    @Before
    fun resetSystemMockStatus() {
        MockProviderStatusStore.publish(MockProviderState.Idle)
    }

    @After
    fun tearDown() {
        MockProviderStatusStore.publish(MockProviderState.Idle)
    }

    @Test
    fun `engage signal starts the keep-alive foreground service`() {
        HookKeepAlive(context).onLeasePressure(true)

        val intent = shadowApp.nextStartedService
        assertNotNull("lease pressure must start the keep-alive service", intent)
        assertEquals(
            HookKeepAliveService::class.java.name,
            intent.component?.className,
        )
        assertEquals(HookKeepAliveService.ACTION_ENGAGE, intent.action)
    }

    @Test
    fun `no duplicate engage while System Mock foreground is already up`() {
        MockProviderStatusStore.publish(
            MockProviderState.Running(
                config = MockLocationConfig(latitude = 30.0, longitude = 120.0),
                emittedCount = 1L,
            ),
        )

        HookKeepAlive(context).onLeasePressure(true)

        assertNull(
            "MockProviderService 前台已提供同样的抗冻豁免 — 不得重复拉起第二个 FGS",
            shadowApp.peekNextStartedService(),
        )
    }

    @Test
    fun `disengage signal stops the service`() {
        HookKeepAlive(context).onLeasePressure(false)

        val stopped = shadowApp.nextStoppedService
        assertNotNull("a fully converged lease must stop the keep-alive service", stopped)
        assertEquals(HookKeepAliveService::class.java.name, stopped.component?.className)
    }

    @Test
    fun `controller failures never propagate to the contract handler`() {
        // A wiring where the OS refuses the FGS start and the stop (the real
        // background-start restriction shape): the red line is that the caller
        // — the contract handler's signal — sees NOTHING.
        val failing = object : android.content.ContextWrapper(context) {
            override fun startForegroundService(service: Intent?): android.content.ComponentName? {
                throw IllegalStateException("ForegroundServiceStartNotAllowedException (simulated)")
            }

            override fun stopService(service: Intent?): Boolean =
                throw IllegalStateException("background restriction (simulated)")
        }

        HookKeepAlive(failing).onLeasePressure(true) // must not throw
        HookKeepAlive(failing).onLeasePressure(false) // must not throw
    }

    @Test
    fun `service enters foreground on create without waiting for a command`() {
        val controller = Robolectric.buildService(
            HookKeepAliveService::class.java,
            Intent(context, HookKeepAliveService::class.java)
                .setAction(HookKeepAliveService.ACTION_ENGAGE),
        ).create()

        val service = controller.get()
        val shadow = shadowOf(service)
        assertEquals(
            "onCreate must startForeground immediately — the fg-required window is closed",
            HookKeepAliveService.NOTIFICATION_ID,
            shadow.getLastForegroundNotificationId(),
        )
        assertNotNull(shadow.getLastForegroundNotification())
        assertFalse(shadow.isForegroundStopped)
        assertFalse(shadow.isStoppedBySelf)
    }

    @Test
    fun `release has no command intent - production disengage is stopService`() {
        // 生产 disengage = controller.stopService（已由 `disengage signal stops the
        // service` 钉住）→ 系统直接走 onDestroy：没有任何"命令型停止"分支。到达
        // 服务的每个命令（含无 action）都只是 engage/重置心跳。
        val controller = Robolectric.buildService(
            HookKeepAliveService::class.java,
            Intent(context, HookKeepAliveService::class.java), // no action at all
        ).create().startCommand(0, 1)

        val shadow = shadowOf(controller.get())
        assertFalse("no command intent may stop the service — stopping is stopService's job", shadow.isStoppedBySelf)
        assertFalse("no command intent may drop the foreground state", shadow.isForegroundStopped)

        // stopService 的落地形状：onDestroy 清理心跳，teardown 无需命令 intent。
        controller.destroy()
    }

    @Test
    fun `engage command keeps the service alive and foregrounded`() {
        val controller = Robolectric.buildService(
            HookKeepAliveService::class.java,
            Intent(context, HookKeepAliveService::class.java)
                .setAction(HookKeepAliveService.ACTION_ENGAGE),
        ).create().startCommand(0, 1)

        val shadow = shadowOf(controller.get())
        assertFalse("an engaged lease guard must stay foreground", shadow.isForegroundStopped)
        assertFalse("an engaged lease guard must not stop itself", shadow.isStoppedBySelf)
    }

    // ---- #198 迭代二：linger 跨 attempt 间隙（服务停启时序，真实 Handler 定时器） ----

    /** 策略器自己的单调时钟（测试手动推进，与 looper 虚拟时间同步走）。 */
    private class SteadyClock(var now: Long = 0L) : MonotonicClock {
        override fun elapsedRealtimeMs(): Long = now
        override fun epochMs(): Long = 0L
    }

    /** 生产 wiring 原样：LingeringKeepAlive → HookKeepAlive → FGS，Handler 定时器。 */
    private fun productionChain(): Pair<LingeringKeepAlive, SteadyClock> {
        val clock = SteadyClock()
        val policy = LingeringKeepAlive(
            downstream = HookKeepAlive(context),
            clock = clock,
            lingerTimer = HandlerLingerTimer(Handler(Looper.getMainLooper())),
        )
        return policy to clock
    }

    private fun idleMainLooperFor(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    @Test
    fun `linger keeps the service across the gap and stops it only when the linger expires`() {
        val (policy, clock) = productionChain()

        policy.onLeasePressure(true)
        assertEquals(
            HookKeepAliveService::class.java.name,
            shadowApp.nextStartedService?.component?.className,
        )

        policy.onLeasePressure(false)
        assertNull(
            "release 收敛瞬间服务必须继续运行（迭代一在此立即 stopService → 3-4s 后被冻结）",
            shadowApp.nextStoppedService,
        )

        clock.now += LingeringKeepAlive.DEFAULT_LINGER_MILLIS
        idleMainLooperFor(LingeringKeepAlive.DEFAULT_LINGER_MILLIS)

        assertEquals(
            "linger 到期才真正 stopService（plan 真结束时保活仍退出，不常驻）",
            HookKeepAliveService::class.java.name,
            shadowApp.nextStoppedService?.component?.className,
        )
    }

    @Test
    fun `an engage inside the linger window cancels the pending stop`() {
        val (policy, clock) = productionChain()

        policy.onLeasePressure(true)
        shadowApp.nextStartedService // dequeue
        policy.onLeasePressure(false)

        clock.now += LingeringKeepAlive.DEFAULT_LINGER_MILLIS / 2
        idleMainLooperFor(LingeringKeepAlive.DEFAULT_LINGER_MILLIS / 2)
        assertNull("半个 linger 后不得停服务", shadowApp.nextStoppedService)

        // 间隙内新 lease：disarm + 继续。此后即使跑过原窗口的截止点也不得停。
        policy.onLeasePressure(true)
        clock.now += LingeringKeepAlive.DEFAULT_LINGER_MILLIS * 2
        idleMainLooperFor(LingeringKeepAlive.DEFAULT_LINGER_MILLIS * 2)

        assertNull(
            "linger 内新 engage 已取消挂起的停止 —— 跑过原截止点服务仍在",
            shadowApp.nextStoppedService,
        )
    }

    @Test
    fun `a fully disengaged keep-alive re-engages on the next lease pressure`() {
        val (policy, clock) = productionChain()

        policy.onLeasePressure(true)
        shadowApp.nextStartedService // dequeue attempt 1 engage
        policy.onLeasePressure(false)
        clock.now += LingeringKeepAlive.DEFAULT_LINGER_MILLIS
        idleMainLooperFor(LingeringKeepAlive.DEFAULT_LINGER_MILLIS)
        shadowApp.nextStoppedService // dequeue the lingered stop

        policy.onLeasePressure(true)

        assertEquals(
            "下一轮 attempt 的 apply 必须重新拉起服务（stop→start 完整循环）",
            HookKeepAliveService::class.java.name,
            shadowApp.nextStartedService?.component?.className,
        )
    }
}
