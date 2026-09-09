package com.example.cellrebelauto.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.integration.v1.EnvironmentControlClient
import io.github.terryyyc.fakexxx.contract.v1.ContractErrorCodeV1
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * T11c: the MainViewModel wiring behind the A2 provider-page todo bar —
 * `refreshPeerApproval()` rides the EXISTING discover channel seam (same pattern as
 * [ProfileCountProbe]) and projects into [PeerApprovalTodoBar]. The UI renders; it never decides.
 *
 * # ViewModel 待办条接线：注入式 discover 探针 → 纯投影
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PeerApprovalTodoViewModelTest {

    private lateinit var db: AppDatabase
    private val createdVms = mutableListOf<MainViewModel>()

    private fun newVm(probe: PeerApprovalProbe): MainViewModel = MainViewModel(
        ApplicationProvider.getApplicationContext(),
        injectedDb = db,
        injectedAccessGate = com.example.cellrebelauto.cutover.CutoverAccessGate.open(),
        peerApprovalProbe = probe,
    ).also { createdVms += it }

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
        createdVms.forEach { it.viewModelScope.cancel() }
        createdVms.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun notPairedRefusal() = EnvironmentControlClient.HandshakeResult.Refused(
        providerPackage = "name.caiyao.fakegps.fakexxx",
        cause = IllegalStateException(
            "discover validation failed: " +
                "PROVIDER_ERROR_${ContractErrorCodeV1.NOT_PAIRED.wire} (kind=ERROR)"
        ),
    )

    @Test
    fun `refresh projects NOT_PAIRED into the actionable todo`() = runTest {
        val vm = newVm { notPairedRefusal() }

        vm.refreshPeerApproval()

        assertEquals(
            PeerPairingStatus.WAITING_PEER_APPROVAL,
            vm.peerPairingStatus.value
        )
        val bar = PeerApprovalTodoBar.project(vm.peerPairingStatus.value)
        assertTrue(bar.isTodo)
        assertEquals("fakexxx-map://pending", bar.peerDeepLink.toString())
        assertEquals("去 QWY 批准", bar.actionLabel)
    }

    @Test
    fun `refresh projects a failed probe into the unknown line - no todo`() = runTest {
        val vm = newVm { null }

        vm.refreshPeerApproval()

        assertEquals(null, vm.peerPairingStatus.value)
        val bar = PeerApprovalTodoBar.project(vm.peerPairingStatus.value)
        assertFalse(bar.isTodo)
        assertTrue(bar.statusLine.contains("未知"))
    }

    @Test
    fun `refresh projects an up link into no todo`() = runTest {
        val vm = newVm {
            EnvironmentControlClient.HandshakeResult.NotBindable(listOf("pkg"))
        }

        vm.refreshPeerApproval()

        assertEquals(PeerPairingStatus.UNREACHABLE, vm.peerPairingStatus.value)
        assertFalse(PeerApprovalTodoBar.project(vm.peerPairingStatus.value).isTodo)
    }

    @Test
    fun `refresh never throws when the probe explodes`() = runTest {
        val vm = newVm { error("discover channel blew up") }

        vm.refreshPeerApproval()

        assertEquals(null, vm.peerPairingStatus.value)
    }
}
