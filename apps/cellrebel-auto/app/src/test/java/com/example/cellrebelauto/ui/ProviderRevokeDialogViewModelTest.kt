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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #10 RED: revoke is one irreversible touch away from bricking every provider call.
 *
 * Device truth: a mis-touch on the Provider page's revoke button made the trust gate intercept
 * everything (discover → null, engine pauses) with NO confirmation and NO after-the-fact hint.
 * The ViewModel must (1) stage the revoke behind a confirm dialog — requestRevoke mutates
 * NOTHING; (2) confirmRevoke performs it and posts an impact notice (the engine will refuse ALL
 * calls from this provider); (3) dismiss leaves the principal active.
 *
 * # 撤销确认对话框 ViewModel oracle：请求=零副作用；确认=撤销+影响横幅；取消=保持 active
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProviderRevokeDialogViewModelTest {

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

    private suspend fun seedApproved(): ProviderEntry {
        db.providerPairingDao().insert(
            ProviderPairingRecord(
                applicationId = "name.caiyao.fakegps.bench",
                currentSignerDigest = "sha256:approved",
                approvedAt = 1000L, revokedAt = null, approvedVersionCode = 1
            )
        )
        return ProviderEntry(
            applicationId = "name.caiyao.fakegps.bench",
            signerDigest = "sha256:approved",
            approvedVersionCode = 1,
            isApproved = true
        )
    }

    @Test
    fun `requestRevoke only stages the dialog - the principal stays active`() = runTest {
        val entry = seedApproved()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), injectedDb = db)

        vm.requestRevoke(entry)

        assertEquals("the dialog candidate is the requested entry", entry, vm.revokeCandidate.value)
        assertNotNull(
            "request alone must NOT revoke — the principal stays active",
            db.providerPairingDao().activeFor("name.caiyao.fakegps.bench", "sha256:approved"),
        )
    }

    @Test
    fun `confirmRevoke revokes clears the dialog and posts the engine impact notice`() = runTest {
        val entry = seedApproved()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), injectedDb = db)
        vm.requestRevoke(entry)

        vm.confirmRevoke()
        // The revoke runs on viewModelScope + Room's executor — await with a bounded spin.
        val deadline = System.currentTimeMillis() + 5_000
        while (
            db.providerPairingDao().activeFor("name.caiyao.fakegps.bench", "sha256:approved") != null &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }

        assertNull("the dialog is gone", vm.revokeCandidate.value)
        assertNull(
            "the principal is revoked (revokedAt set, not deleted)",
            db.providerPairingDao().activeFor("name.caiyao.fakegps.bench", "sha256:approved"),
        )
        val notice = vm.revokeImpactNotice.value
        assertTrue(
            "an impact notice must explain the consequence, naming the provider",
            notice != null && notice.contains("name.caiyao.fakegps.bench"),
        )
        assertTrue(
            "the notice must say the engine will REFUSE this provider's calls",
            notice!!.contains("拒绝"),
        )
    }

    @Test
    fun `dismissRevokeDialog leaves the principal active with no notice`() = runTest {
        val entry = seedApproved()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), injectedDb = db)
        vm.requestRevoke(entry)

        vm.dismissRevokeDialog()

        assertNull("the dialog is gone", vm.revokeCandidate.value)
        assertNotNull(
            "dismiss must NOT revoke",
            db.providerPairingDao().activeFor("name.caiyao.fakegps.bench", "sha256:approved"),
        )
        assertNull(vm.revokeImpactNotice.value)
    }

    @Test
    fun `dismissRevokeNotice clears the banner only`() = runTest {
        val entry = seedApproved()
        val vm = MainViewModel(ApplicationProvider.getApplicationContext<Application>(), injectedDb = db)
        vm.requestRevoke(entry)
        vm.confirmRevoke()
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.revokeImpactNotice.value == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(vm.revokeImpactNotice.value != null)

        vm.dismissRevokeNotice()
        assertNull(vm.revokeImpactNotice.value)
    }
}
