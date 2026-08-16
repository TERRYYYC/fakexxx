package com.example.cellrebelauto.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.ProviderPairingRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * R44 (DSF review P2-1): the ViewModel provider-DISCOVERY oracle.
 *
 * Sol's original finding: revoked rows were mapped as pending candidates. The fix discovers
 * candidates from the INSTALLED package state (signer resolvable + not approved). Killing
 * mutation: revert refreshProviders to the revoked→pending mapping — must FAIL.
 *
 * Robolectric shadows the PackageManager: the frozen provider package is INSTALLED with a known
 * signing certificate, so packageManagerSignerDigest resolves a real digest for it.
 *
 * # ViewModel 发现链路 oracle：pending 来自真实安装包发现，绝不来自 revoked 历史
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProviderDiscoveryViewModelTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `pending candidates come from INSTALLED provider discovery - never from revoked history`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Install the frozen production provider package with a signing certificate (Robolectric shadow).
        val providerPkg = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_PRODUCTION
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val signature = android.content.pm.Signature("308201a308201a0".padEnd(2048, '0'))
        val packageInfo = android.content.pm.PackageInfo().apply {
            packageName = providerPkg
            versionName = "1.0"
            // The legacy signatures array — the shadow serves it on every SDK level, and our
            // ProviderTrustGate's API-split reads it directly on 26/27 (and via signingInfo on 28+,
            // where the shadow derives it from this array too).
            signatures = arrayOf(signature)
        }
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(packageInfo)

        // Seed a REVOKED provider row for the same application id (the M-PA-10 re-approval state).
        db.providerPairingDao().insert(
            ProviderPairingRecord(
                applicationId = providerPkg,
                currentSignerDigest = "sha256:old-revoked",
                approvedAt = 1000L, revokedAt = 2000L, approvedVersionCode = 1
            )
        )

        val vm = MainViewModel(app, injectedDb = db)
        vm.refreshProviders()
        // The refresh launches on viewModelScope; Room suspend calls hop to Room's own executor,
        // which is OUTSIDE runTest's scheduler — await the StateFlow value with a bounded spin.
        var entries = vm.providerEntries.value
        val deadline = System.currentTimeMillis() + 5_000
        while (entries.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            entries = vm.providerEntries.value
        }

        // Killing mutation (revoked→pending): a pending entry with signerDigest "sha256:old-revoked"
        // FAILS the assertion below — the pending candidate's signer must be the RESOLVED CURRENT
        // signer (discovered), never the revoked history row's digest.
        val pending = entries.filter { !it.isApproved }
        val approved = entries.filter { it.isApproved }
        assertTrue("at least one pending candidate is discovered (the provider is installed)", pending.isNotEmpty())
        assertTrue("no approved entries (the only row is revoked)", approved.isEmpty())
        pending.forEach { p ->
            assertFalse(
                "a pending candidate's signer is the RESOLVED current signer — never the revoked history digest (M-PA-10)",
                p.signerDigest == "sha256:old-revoked"
            )
        }
    }

    @Test
    fun `an approved principal is listed as approved - not rediscovered as pending`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val providerPkg = io.github.terryyyc.fakexxx.contract.v1.ContractV1.PROVIDER_APPLICATION_ID_BENCH
        db.providerPairingDao().insert(
            ProviderPairingRecord(
                applicationId = providerPkg,
                currentSignerDigest = "sha256:approved",
                approvedAt = 1000L, revokedAt = null, approvedVersionCode = 3
            )
        )
        val vm = MainViewModel(app, injectedDb = db)
        vm.refreshProviders()
        var entries = vm.providerEntries.value
        val deadline = System.currentTimeMillis() + 5_000
        while (entries.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            entries = vm.providerEntries.value
        }
        val approved = entries.filter { it.isApproved }
        assertEquals("the approved principal is listed", 1, approved.size)
        assertEquals(providerPkg, approved[0].applicationId)
        assertEquals("sha256:approved", approved[0].signerDigest)
        // And the same app id is NOT duplicated as pending.
        assertEquals(
            "an approved app id is not rediscovered as pending",
            0, entries.filter { !it.isApproved && it.applicationId == providerPkg }.size
        )
    }
}
