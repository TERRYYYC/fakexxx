package io.github.terryyyc.fakexxx.integration.pr63issue66

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalIntentDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentControlResultV1
import io.github.terryyyc.fakexxx.contract.v1.IEnvironmentControlV1
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import name.caiyao.fakegps.integration.v1.toTypedResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LocalBinderJourneyTest {

    @Test
    fun `real Auto registry and executor drive real QWY handler over one local Binder`() {
        val autoApplicationId = AutoIntegrationBridge.autoApplicationId()
        val providerTarget = AutoIntegrationBridge.selectedProviderTarget()
        val qwy = ProviderHarness.create()
        qwy.resolver.register(AUTO_UID, autoApplicationId, REPO_BENCH_SIGNER)
        qwy.pair(autoApplicationId, REPO_BENCH_SIGNER)

        val context = RoutingContext(
            ApplicationProvider.getApplicationContext(),
            providerTarget,
            HandlerBinder(qwy, AUTO_UID),
        )
        AutoIntegrationBridge.connect(context, providerTarget, REPO_BENCH_SIGNER).use { auto ->
            val capability = auto.discover()
            assertNotNull(capability)
            assertEquals(ContractV1.PROTOCOL_VERSION, capability!!.protocolVersion)

            val intent = qwy.intent(runId = "integration-run", attemptId = "41")
            val digest = CanonicalIntentDigestV1.compute(intent)
            val applied = auto.apply(41L, intent, "integration-apply-41", digest, qwy.clock.epochMs())

            assertEquals("APPLIED", applied.outcome)
            assertNotNull(applied.leaseId)
            assertEquals(digest, applied.acceptedIntentHash)
            assertEquals(1, qwy.env.applyCount)
        }

        assertEquals(listOf(providerTarget), context.bindTargets)
        assertTrue(context.unbound)
    }

    private class HandlerBinder(
        private val qwy: ProviderHarness,
        private val callingUid: Int,
    ) : IEnvironmentControlV1.Stub() {
        override fun discover(): EnvironmentControlResultV1 =
            toTypedResult { EnvironmentControlResultV1.discover(qwy.handler.discover(callingUid)) }

        override fun preflight(request: PreflightRequestV1): EnvironmentControlResultV1 =
            toTypedResult {
                EnvironmentControlResultV1.preflight(qwy.handler.preflight(callingUid, request))
            }

        override fun apply(request: ApplyRequestV1): EnvironmentControlResultV1 =
            toTypedResult { EnvironmentControlResultV1.apply(qwy.handler.apply(callingUid, request)) }

        override fun observe(request: ObserveRequestV1): EnvironmentControlResultV1 =
            toTypedResult { EnvironmentControlResultV1.observe(qwy.handler.observe(callingUid, request)) }

        override fun release(request: ReleaseRequestV1): EnvironmentControlResultV1 =
            toTypedResult { EnvironmentControlResultV1.release(qwy.handler.release(callingUid, request)) }

        override fun completeAndAdvance(
            request: CompleteAndAdvanceRequestV1,
        ): EnvironmentControlResultV1 = toTypedResult {
            EnvironmentControlResultV1.completeAndAdvance(
                qwy.handler.completeAndAdvance(callingUid, request),
            )
        }
    }

    private class RoutingContext(
        base: Context,
        private val providerApplicationId: String,
        private val binder: IBinder,
    ) : ContextWrapper(base) {
        val bindTargets = mutableListOf<String>()
        var unbound = false
            private set

        override fun getApplicationContext(): Context = this

        override fun bindService(service: Intent, connection: ServiceConnection, flags: Int): Boolean {
            val component = requireNotNull(service.component)
            bindTargets += component.packageName
            if (component.packageName != providerApplicationId ||
                component.className != ContractV1.SERVICE_CLASS_NAME
            ) return false
            connection.onServiceConnected(ComponentName(providerApplicationId, component.className), binder)
            return true
        }

        override fun unbindService(connection: ServiceConnection) {
            unbound = true
        }
    }

    private companion object {
        const val AUTO_UID = 10101
        // Mechanically pinned by both repo keystores and scripts/selftest-debug-signer.sh.
        const val REPO_BENCH_SIGNER =
            "sha256:7a598cbe6fb816ba74f01b58e3f43b8ff0f463989157e590ebd86c89b53f7e41"
    }
}
