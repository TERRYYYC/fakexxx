package com.example.cellrebelauto.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import com.example.cellrebelauto.recovery.ProviderPackageTarget
import com.example.cellrebelauto.recovery.testApplyIntent
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowApplication

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
            "debug Auto must target the QWY debug applicationIdSuffix, not the absent release package",
            ContractV1.PROVIDER_APPLICATION_ID_BENCH,
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
            ProviderPackageTarget.forDebugBuild(true),
        )
        assertEquals(
            ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION,
            ProviderPackageTarget.forDebugBuild(false),
        )
    }

    @Test
    fun `an unresolvable provider fails closed - bind dispatches, remote never connects, apply PROVIDER_NOT_BOUND`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // No service registered for this provider id: the bind intent cannot resolve to a
        // connection, `remote` stays null, and every apply fail-closes with a typed outcome.
        val executor = BinderExternalApplyExecutor(app, providerApplicationId = "no.such.provider")
        executor.bind() // dispatches the intent; the connection never delivers in this scenario
        val outcome = executor.apply(1L, testApplyIntent(attemptId = 1L), "k-1", "d", 1000L)
        assertEquals("an unconnected provider fail-closes with a typed outcome", "PROVIDER_NOT_BOUND", outcome.outcome)
        assertEquals("never a lease from an unbound provider", null, outcome.leaseId)
        val release = executor.release(1L, "r-1", "lease-x", "rd", 1000L)
        assertEquals("release fail-closes identically", "PROVIDER_NOT_BOUND", release.outcome)
    }

    @Test
    fun `the PRODUCTION onServiceConnected attempts the provider bind (Sol GREEN-review-3 F1 oracle)`() {
        // Drives the REAL AutomationService connect callback — not a self-constructed executor.
        // Sol's surviving mutation made onServiceConnected's bind() call vanish while the executor
        // self-test stayed green; this oracle counts the PRODUCTION callback's bind attempts via
        // the service's observability counters.
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
            "the PRODUCTION connect callback must attempt exactly one provider bind (0 = the Sol mutation)",
            1,
            AutomationService.providerBindAttempts
        )
        // The attempt dispatched against the frozen component (bindService returned true in the
        // shadow environment where the component resolves).
        org.junit.Assert.assertEquals(
            "the production bind dispatched against the frozen provider component",
            java.lang.Boolean.TRUE,
            AutomationService.lastProviderBindReturnedTrue
        )
    }
}
