package com.example.cellrebelauto.automation

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.BuildConfig
import com.example.cellrebelauto.integration.v1.EnvironmentControlClient
import com.example.cellrebelauto.recovery.BinderExternalApplyExecutor
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Executes against the actual codexBench variant, not a simulated resolver flag. */
@RunWith(RobolectricTestRunner::class)
class CodexBenchRuntimeIsolationTest {
    private val codexProvider = "name.caiyao.fakegps.codexbench"

    @Test
    fun `actual compiled variant is debug and has isolated caller and provider identities`() {
        assertTrue(BuildConfig.DEBUG)
        assertTrue(BuildConfig.CODEX_BENCH)
        assertEquals("com.example.cellrebelauto.codexbench", BuildConfig.APPLICATION_ID)
        assertEquals(codexProvider, ProviderPrincipal.selected)
        assertEquals(listOf(codexProvider), ProviderPrincipal.knownApplicationIds)
        assertEquals(listOf(codexProvider), EnvironmentControlClient.PROVIDER_PACKAGES)
    }

    @Test
    fun `missing codex provider never falls back to an installed old identity`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val attempted = mutableListOf<String>()
        val context = object : ContextWrapper(app) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                attempted += requireNotNull(intent.component).packageName
                return false
            }
        }
        val result = EnvironmentControlClient(context).handshake(1L)
        assertEquals(listOf(codexProvider), attempted)
        assertEquals(listOf(codexProvider), (result as EnvironmentControlClient.HandshakeResult.NotBindable).triedPackages)
    }

    @Test
    fun `old durable principal is rejected before any bind or external effect`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        var binds = 0
        val context = object : ContextWrapper(app) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                binds++
                return false
            }
        }
        for (oldIdentity in listOf(ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION, ContractV1.PROVIDER_APPLICATION_ID_BENCH)) {
            assertThrows(IllegalArgumentException::class.java) { ProviderPrincipal.requireKnownApplicationId(oldIdentity) }
            assertThrows(IllegalArgumentException::class.java) { EnvironmentControlClient(context, oldIdentity) }
            assertThrows(IllegalArgumentException::class.java) { BinderExternalApplyExecutor(context, providerApplicationId = oldIdentity) }
        }
        assertEquals(0, binds)
        assertEquals(codexProvider, BinderExternalApplyExecutor(context).targetApplicationId)
    }
}
