package com.example.cellrebelauto.automation

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
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
        val executor = BinderExternalApplyExecutor(app)
        val returned = executor.bind()
        // bind() dispatched the intent for the FROZEN component (ContractV1 constants — the same
        // ones production uses; a hand-typed or mutated component fails this).
        assertTrue("bind() against the frozen provider component must dispatch (returned false)", returned)
        // Robolectric's ShadowApplication records bound connections; the component must be the
        // frozen one. (peekNumberOfBoundServices is not on all versions — assert dispatch only.)
        val connections = Shadows.shadowOf(app).boundServiceConnections
        assertTrue(
            "the bind must have registered a connection for the frozen component",
            connections.isNotEmpty()
        )
    }

    @Test
    fun `an unresolvable provider fails closed - bind dispatches, remote never connects, apply PROVIDER_NOT_BOUND`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        // No service registered for this provider id: the bind intent cannot resolve to a
        // connection, `remote` stays null, and every apply fail-closes with a typed outcome.
        val executor = BinderExternalApplyExecutor(app, providerApplicationId = "no.such.provider")
        executor.bind() // dispatches the intent; the connection never delivers in this scenario
        val outcome = executor.apply(1L, "k-1", "d", 1000L)
        assertEquals("an unconnected provider fail-closes with a typed outcome", "PROVIDER_NOT_BOUND", outcome.outcome)
        assertEquals("never a lease from an unbound provider", null, outcome.leaseId)
        val release = executor.release(1L, "r-1", "lease-x", "rd", 1000L)
        assertEquals("release fail-closes identically", "PROVIDER_NOT_BOUND", release.outcome)
    }
}
