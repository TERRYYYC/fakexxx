package com.example.cellrebelauto.automation

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import com.example.cellrebelauto.recovery.ProviderExecutorRegistry
import com.example.cellrebelauto.recovery.testApplyIntent
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowApplication
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R43 (Sol GREEN-review-2 F1): the provider-bind LIFECYCLE oracle.
 *
 * Sol's surviving mutation: discarding the `bind()` result in `onServiceConnected` (never
 * attempting the bind) kept the whole suite green. This oracle observes the OBSERVABLE bind
 * effect: `BinderExternalApplyExecutor.bind()` issues a bindService intent targeting the FROZEN
 * contract component (applicationId + SERVICE_CLASS_NAME from ContractV1 — never a hand-typed
 * copy). The shadow's nextStartedService/bound intents record the attempt; a mutation that skips
 * bind(), or binds a wrong component, leaves no matching intent.
 *
 * # Binder 生命周期 oracle：bind() 必须对冻结契约组件真实发出 bind intent
 */
@RunWith(RobolectricTestRunner::class)
class ProviderBindLifecycleTest {

    private val registrySigner =
        "sha256:ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"

    private open class NoopProviderService :
        io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1.Stub() {
        override fun discover() =
            io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)

        override fun preflight(request: io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1) =
            io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)

        override fun apply(request: io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1) =
            io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)

        override fun observe(request: io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1) =
            io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)

        override fun release(request: io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1) =
            io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)

        override fun completeAndAdvance(
            request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
        ) = io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
    }

    @Test
    fun `bind issues a bindService intent against the frozen contract component`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var boundIntent: Intent? = null
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                boundIntent = service
                return true
            }
        }
        val executor = BinderExternalApplyExecutor(recordingContext)
        val returned = executor.bind()
        assertTrue("bind() against the frozen provider component must dispatch (returned false)", returned)
        assertEquals(
            "Auto must bind its build-selected provider, never a sibling installation",
            ProviderPrincipal.selected,
            boundIntent?.component?.packageName,
        )
        assertEquals(
            ContractV1.SERVICE_CLASS_NAME,
            boundIntent?.component?.className,
        )
    }

    @Test
    fun `provider build pairing selects bench for debug and production for release`() {
        assertEquals(
            ContractV1.PROVIDER_APPLICATION_ID_BENCH,
            ProviderPrincipal.resolve(isDebugBuild = true),
        )
        assertEquals(
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            ProviderPrincipal.resolve(isDebugBuild = false),
        )
    }

    @Test
    fun `an unknown provider identity is rejected before bind dispatch`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            BinderExternalApplyExecutor(app, providerApplicationId = "no.such.provider")
        }
    }

    @Test
    fun `the PRODUCTION onServiceConnected does not bind before a durable plan is read`() {
        // A service connection has no plan identity. Binding here samples BuildConfig and creates a
        // reusable process-global principal that can later misroute a restored plan's recovery.
        AutomationService.resetProviderBindObservability()
        val context: Context = ApplicationProvider.getApplicationContext()
        val service = AutomationService()
        // Protected framework/callback methods — invoked via reflection (the standard test seam
        // for lifecycle callbacks Robolectric does not drive for accessibility services).
        // attachBaseContext is declared on ContextWrapper, not Service.
        val attach = android.content.ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
        attach.isAccessible = true
        attach.invoke(service, context)
        val connect = AutomationService::class.java.getDeclaredMethod("onServiceConnected")
        connect.isAccessible = true
        connect.invoke(service)

        org.junit.Assert.assertEquals(
            "the accessibility callback must own no provider principal before plan lookup",
            0,
            AutomationService.providerBindAttempts
        )
        org.junit.Assert.assertEquals(
            "there is no bind result before a plan-specific bind exists",
            null,
            AutomationService.lastProviderBindReturnedTrue
        )
    }

    @Test
    fun `registry binds the frozen plan identity once and unbinds after the last owner closes`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val boundComponents = mutableListOf<ComponentName?>()
        var unbindCalls = 0
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                boundComponents += service.component
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbindCalls++
            }
        }
        val registry = ProviderExecutorRegistry(recordingContext, currentSignerDigest = { registrySigner }) { applicationId ->
            BinderExternalApplyExecutor(recordingContext, applicationId)
        }

        // This test runs in the debug variant (current target = bench), but a restored production
        // plan must still acquire production. BuildConfig is not consulted by the registry.
        val first = registry.acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val second = registry.acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)

        assertEquals(
            listOf(
                ComponentName(
                    ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                    ContractV1.SERVICE_CLASS_NAME,
                )
            ),
            boundComponents,
        )
        assertSame("same principal shares one executor", first.executor, second.executor)
        first.close()
        assertEquals("one remaining owner keeps the binding alive", 0, unbindCalls)
        second.close()
        assertEquals("last owner closes the binding", 1, unbindCalls)
        second.close()
        assertEquals("acquisition close is idempotent", 1, unbindCalls)
    }

    @Test
    fun `closed executor ignores stale and wrong-component callbacks after reacquire`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val connections = mutableListOf<ServiceConnection>()
        var unbindCalls = 0
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                connections += conn
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbindCalls++
            }
        }
        val registry = ProviderExecutorRegistry(recordingContext, currentSignerDigest = { registrySigner }) { applicationId ->
            BinderExternalApplyExecutor(recordingContext, applicationId)
        }
        val expected = ComponentName(
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            ContractV1.SERVICE_CLASS_NAME,
        )

        val oldAcquisition = registry.acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val oldExecutor = oldAcquisition.executor as BinderExternalApplyExecutor
        val oldConnection = connections.single()
        oldAcquisition.close()

        val newAcquisition = registry.acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val newExecutor = newAcquisition.executor as BinderExternalApplyExecutor
        val newConnection = connections.last()

        oldConnection.onServiceConnected(expected, Binder())
        assertFalse("a late callback cannot resurrect the closed executor", oldExecutor.isBound)
        assertFalse("an old callback cannot populate the new registry entry", newExecutor.isBound)

        newConnection.onServiceConnected(
            ComponentName(ContractV1.PROVIDER_APPLICATION_ID_BENCH, ContractV1.SERVICE_CLASS_NAME),
            Binder(),
        )
        assertFalse("a sibling component callback is rejected", newExecutor.isBound)

        newConnection.onServiceConnected(expected, NoopProviderService())
        assertTrue("the exact component callback is accepted", newExecutor.isBound)
        registry.unbindAll()
        assertFalse("service teardown clears the live binder", newExecutor.isBound)
        assertEquals("old close plus teardown each unbind one executor", 2, unbindCalls)
        newAcquisition.close()
        assertEquals("close after unbindAll is inert", 2, unbindCalls)
    }

    @Test
    fun `registry acquisition waits for the exact asynchronous binder callback before use`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var connection: ServiceConnection? = null
        var unbindCalls = 0
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                connection = conn
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbindCalls++
            }
        }
        // Exercise the production/default factory as well as the real async callback seam.
        val acquisition = ProviderExecutorRegistry(recordingContext, currentSignerDigest = { registrySigner })
            .acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val executor = acquisition.executor as BinderExternalApplyExecutor

        assertTrue("bindService accepted the request", acquisition.bindRequested)
        assertFalse(
            "bindService=true is only a request; the provider is not usable before its callback",
            executor.isBound,
        )
        val ready = async { acquisition.awaitBound(timeoutMs = 1_000L) }
        yield()
        assertFalse("acquire must suspend while the Binder callback is pending", ready.isCompleted)

        connection!!.onServiceConnected(
            ComponentName(
                ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                ContractV1.SERVICE_CLASS_NAME,
            ),
            NoopProviderService(),
        )

        assertTrue("the exact component callback makes the scoped executor usable", ready.await())
        acquisition.close()
        assertEquals(1, unbindCalls)
    }

    @Test
    fun `readiness timeout terminally closes acquisition and ignores an exact late callback`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var connection: ServiceConnection? = null
        var unbindCalls = 0
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                connection = conn
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbindCalls++
            }
        }
        var journeyCalls = 0
        val lateProvider = object :
            io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1.Stub() {
            override fun discover(): io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 {
                journeyCalls++
                return io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1.failure(0)
            }

            override fun preflight(request: io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1) =
                error("late provider must not receive preflight")

            override fun apply(request: io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1) =
                error("late provider must not receive apply")

            override fun observe(request: io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1) =
                error("late provider must not receive observe")

            override fun release(request: io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1) =
                error("late provider must not receive release")

            override fun completeAndAdvance(
                request: io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1,
            ) = error("late provider must not receive advance")
        }
        val registry = ProviderExecutorRegistry(recordingContext, currentSignerDigest = { registrySigner }) { applicationId ->
            BinderExternalApplyExecutor(recordingContext, applicationId)
        }
        val acquisition = registry.acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val executor = acquisition.executor as BinderExternalApplyExecutor

        assertFalse("the missing callback must time out", acquisition.awaitBound(timeoutMs = 1L))
        assertEquals("timeout revokes the accepted bind request immediately", 1, unbindCalls)

        // The exact callback races in after timeout but before a would-be engine dispatch. It belongs
        // to the terminally closed acquisition and must not resurrect its Binder interface.
        connection!!.onServiceConnected(
            ComponentName(
                ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                ContractV1.SERVICE_CLASS_NAME,
            ),
            lateProvider,
        )
        assertFalse("a late exact callback cannot revive a timed-out acquisition", executor.isBound)
        org.junit.Assert.assertNull(executor.discover())
        assertEquals("no provider journey RPC is reachable after timeout", 0, journeyCalls)

        acquisition.close()
        assertEquals("close remains idempotent after terminal timeout cleanup", 1, unbindCalls)
    }

    @Test
    fun `a rejected bind is terminal even if an exact callback races before the false result`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var journeyCalls = 0
        val racingProvider = object : NoopProviderService() {
            override fun discover(): io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 {
                journeyCalls++
                return super.discover()
            }
        }
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                conn.onServiceConnected(requireNotNull(service.component), racingProvider)
                return false
            }

            override fun unbindService(conn: ServiceConnection) {
                error("a rejected bind must not be unbound")
            }
        }
        val acquisition = ProviderExecutorRegistry(recordingContext, currentSignerDigest = { registrySigner })
            .acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val executor = acquisition.executor as BinderExternalApplyExecutor

        assertFalse(acquisition.bindRequested)
        assertFalse("bind=false must override a racing callback", acquisition.awaitBound(1_000L))
        assertFalse(executor.isBound)
        org.junit.Assert.assertNull(executor.discover())
        assertEquals(0, journeyCalls)
    }

    @Test
    fun `timeout overlapping callback conversion is terminal and cannot publish a remote`() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var connection: ServiceConnection? = null
        var unbindCalls = 0
        val recordingContext = object : ContextWrapper(app) {
            override fun bindService(
                service: Intent,
                conn: ServiceConnection,
                flags: Int,
            ): Boolean {
                connection = conn
                return true
            }

            override fun unbindService(conn: ServiceConnection) {
                unbindCalls++
            }
        }
        var journeyCalls = 0
        val provider = object : NoopProviderService() {
            override fun discover(): io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1 {
                journeyCalls++
                return super.discover()
            }
        }
        val conversionEntered = CountDownLatch(1)
        val allowConversion = CountDownLatch(1)
        val blockingBinder = object : Binder() {
            override fun queryLocalInterface(descriptor: String): android.os.IInterface? {
                conversionEntered.countDown()
                check(allowConversion.await(2, TimeUnit.SECONDS)) {
                    "test did not release Binder interface conversion"
                }
                return provider
            }
        }
        val acquisition = ProviderExecutorRegistry(recordingContext, currentSignerDigest = { registrySigner })
            .acquire(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, registrySigner)
        val executor = acquisition.executor as BinderExternalApplyExecutor
        val callback = Thread {
            connection!!.onServiceConnected(
                ComponentName(
                    ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
                    ContractV1.SERVICE_CLASS_NAME,
                ),
                blockingBinder,
            )
        }

        callback.start()
        assertTrue(
            "callback must pass admission and block before publication",
            conversionEntered.await(2, TimeUnit.SECONDS),
        )
        assertFalse("zero-time readiness failure terminally closes the executor", acquisition.awaitBound(0L))
        assertEquals("the accepted binding is unbound exactly once", 1, unbindCalls)

        allowConversion.countDown()
        callback.join(2_000L)
        assertFalse("callback thread must finish", callback.isAlive)
        assertFalse("a callback admitted before timeout still cannot publish after terminal close", executor.isBound)
        org.junit.Assert.assertNull(executor.discover())
        assertEquals("no provider journey is reachable from a resurrected remote", 0, journeyCalls)
    }
}
