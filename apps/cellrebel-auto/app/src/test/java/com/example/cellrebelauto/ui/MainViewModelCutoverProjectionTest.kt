package com.example.cellrebelauto.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.automation.ProviderPrincipal
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverDataState
import com.example.cellrebelauto.cutover.CutoverExclusiveAdmission
import com.example.cellrebelauto.cutover.CutoverExclusiveRelease
import com.example.cellrebelauto.cutover.CutoverRestoreIdentity
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.environment.ProviderTrustStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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

    @Test
    fun `complete close and reopen between Main dispatches refreshes cold provider queries`() =
        runBlocking {
            db.close()
            val scheduler = TestCoroutineScheduler()
            Dispatchers.setMain(StandardTestDispatcher(scheduler))
            val gate = CutoverAccessGate.open()
            val executor = Executors.newSingleThreadExecutor()
            db = Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext(),
                AppDatabase::class.java
            ).setQueryExecutor(gate.guardExecutor(executor))
                .setTransactionExecutor(gate.guardExecutor(executor))
                .build()
            db.providerPairingDao().insert(
                com.example.cellrebelauto.model.plan.ProviderPairingRecord(
                    applicationId = ProviderPrincipal.selected,
                    currentSignerDigest = "sha256:approved",
                    approvedAt = 1L,
                    revokedAt = null,
                    approvedVersionCode = 1
                )
            )
            val vm = viewModel(gate)

            suspend fun pumpUntil(predicate: () -> Boolean) {
                withTimeout(3_000L) {
                    while (!predicate()) {
                        scheduler.runCurrent()
                        delay(10L)
                    }
                }
            }

            try {
                pumpUntil {
                    (vm.providerEntries.value as? CutoverDataState.Ready)?.value?.singleOrNull()
                        ?.isApproved == true &&
                        vm.pairingUiState.value == CutoverDataState.Ready(PairingUiState.Trusted)
                }
                val admitted = CompletableDeferred<Unit>()
                val permitWrite = CompletableDeferred<Unit>()
                val writer = async(Dispatchers.Default) {
                    gate.withNormalAccess {
                        admitted.complete(Unit)
                        permitWrite.await()
                        ProviderTrustStore(db.providerPairingDao(), gate).revoke(
                            ProviderPrincipal.selected,
                            "sha256:approved",
                            2L
                        )
                    }
                }
                admitted.await()
                val exclusive = async(Dispatchers.Default) {
                    gate.acquireCaptureExclusive("close-reopen-between-main-dispatches") { true }
                }
                withTimeout(3_000L) {
                    while (gate.snapshot().phase !=
                        com.example.cellrebelauto.cutover.CutoverGatePhase.DRAINING
                    ) {
                        yield()
                    }
                }
                permitWrite.complete(Unit)
                writer.await()
                val lease = (exclusive.await() as CutoverExclusiveAdmission.Granted).lease
                assertTrue(lease.release(CutoverExclusiveRelease.OPEN))

                pumpUntil {
                    (vm.providerEntries.value as? CutoverDataState.Ready)?.value?.isEmpty() == true &&
                        vm.pairingUiState.value == CutoverDataState.Ready(PairingUiState.NotPaired)
                }
            } finally {
                vm.viewModelScope.cancel()
                scheduler.runCurrent()
                executor.shutdown()
                executor.awaitTermination(2L, TimeUnit.SECONDS)
            }
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
