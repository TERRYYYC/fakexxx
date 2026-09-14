package name.caiyao.fakegps.integration.v1

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import name.caiyao.fakegps.mockprovider.MockLocationConfig
import name.caiyao.fakegps.mockprovider.MockProviderState
import name.caiyao.fakegps.mockprovider.MockProviderStatusStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
 *  - [HookKeepAlive] disengage → stopService
 *  - 服务本体：创建即前台（onCreate startForeground，掐灭 fg-required 竞态），
 *    ENGAGE 幂等重置心跳，RELEASE → stopForeground + stopSelf
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
    fun `release action stops foreground and self`() {
        val controller = Robolectric.buildService(
            HookKeepAliveService::class.java,
            Intent(context, HookKeepAliveService::class.java)
                .setAction(HookKeepAliveService.ACTION_RELEASE),
        ).create().startCommand(0, 1)

        val shadow = shadowOf(controller.get())
        assertTrue("release must drop the foreground state", shadow.isForegroundStopped)
        assertTrue("release must stop the service (不常驻耗电)", shadow.isStoppedBySelf)
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
}
