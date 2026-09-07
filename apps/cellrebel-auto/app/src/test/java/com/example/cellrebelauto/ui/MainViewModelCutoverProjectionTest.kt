package com.example.cellrebelauto.ui

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverDataState
import com.example.cellrebelauto.cutover.CutoverExclusiveAdmission
import com.example.cellrebelauto.cutover.CutoverExclusiveRelease
import com.example.cellrebelauto.cutover.CutoverRestoreIdentity
import com.example.cellrebelauto.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MainViewModelCutoverProjectionTest {
    private lateinit var db: AppDatabase
    private val identity = CutoverRestoreIdentity(
        archiveDigest = "sha256:${"c".repeat(64)}",
        captureId = "view-model-projection"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `recovery closed startup exposes typed unavailability for every restored-data projection`() {
        val vm = viewModel(CutoverAccessGate.recoveryRequired(identity))

        val projections = linkedMapOf(
            "providerEntries" to vm.providerEntries.value,
            "pairingUiState" to vm.pairingUiState.value,
            "planConfig" to vm.planConfig.value,
            "planUiState" to vm.planUiState.value,
            "attempts" to vm.attempts.value,
            "legacyResults" to vm.legacyResults.value
        )

        projections.forEach { (owner, state) ->
            assertEquals("$owner must not masquerade as an empty/default value", state,
                CutoverDataState.Unavailable(
                    com.example.cellrebelauto.cutover.CutoverUnavailableReason.RECOVERY_REQUIRED,
                    identity
                ))
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `pairing projection survives provider refresh completing while gate closes`() = runTest {
        val gate = CutoverAccessGate.open()
        val vm = viewModel(gate)
        val initial = awaitProjection(vm) { it is CutoverDataState.Ready } as
            CutoverDataState.Ready<PairingUiState>
        assertEquals(PairingUiState.NotPaired, initial.value)

        val lease = (gate.acquireCaptureExclusive("provider-refresh-race") { true } as
            CutoverExclusiveAdmission.Granted).lease
        lease.withExclusiveAccess {
            db.providerPairingDao().insert(
                com.example.cellrebelauto.model.plan.ProviderPairingRecord(
                    applicationId = com.example.cellrebelauto.automation.ProviderPrincipal.selected,
                    currentSignerDigest = "sha256:approved",
                    approvedAt = 1L,
                    revokedAt = null,
                    approvedVersionCode = 1
                )
            )
        }
        vm.refreshProviders()

        val unavailable = awaitProjection(vm) { it is CutoverDataState.Unavailable }
        assertTrue(unavailable is CutoverDataState.Unavailable)

        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        val reopened = awaitProjection(vm) {
            it is CutoverDataState.Ready && it.value == PairingUiState.Trusted
        } as CutoverDataState.Ready<PairingUiState>
        assertEquals(PairingUiState.Trusted, reopened.value)
    }

    private fun viewModel(gate: CutoverAccessGate) = MainViewModel(
        ApplicationProvider.getApplicationContext<Application>(),
        injectedDb = db,
        injectedAccessGate = gate
    )

    private fun awaitProjection(
        vm: MainViewModel,
        predicate: (CutoverDataState<PairingUiState>) -> Boolean
    ): CutoverDataState<PairingUiState> {
        val deadline = System.currentTimeMillis() + 5_000L
        var state = vm.pairingUiState.value
        while (!predicate(state) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L)
            state = vm.pairingUiState.value
        }
        assertTrue("pairing projection did not reach the expected state: $state", predicate(state))
        return state
    }
}
