package com.example.cellrebelauto.ui.dashboard

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.data.SelfHealSettings
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.ui.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * Bounded spin: the DataStore write runs on its own IO executor outside
 * runTest's scheduler, so assertions poll instead of assuming completion.
 */
private fun awaitUntil(deadlineMs: Long = 5_000, condition: suspend () -> Boolean) {
    val deadline = System.currentTimeMillis() + deadlineMs
    while (!kotlinx.coroutines.runBlocking { condition() } && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
    }
}

/**
 * T7 — the run dashboard's self-heal switch section, pinned at the ViewModel
 * boundary. T4 shipped the persisted [SelfHealSettings] trio; the dashboard
 * exposes them 1:1 (read flow + three setters) and NOTHING else — the engine
 * reads the same DataStore on its next attempt, there is no second source.
 *
 * Killing mutations:
 *  - a toggle that only flips local UI state (never the DataStore) fails the
 *    direct settings readback assertions;
 *  - a swapped wiring (watchdog toggle writes the guard key) fails per-key.
 *
 * # 自愈三开关 ViewModel oracle：读写均落在 T4 的 SelfHealSettings，逐键断言
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelfHealDashboardViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var dataStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dataStoreFile = File(
            System.getProperty("java.io.tmpdir"),
            "self-heal-dash-test-${UUID.randomUUID()}.preferences_pb"
        )
        // A REAL scope, not runTest's backgroundScope: DataStore IO dispatched to the
        // test scheduler would deadlock under the oracle's runBlocking polling.
        dataStoreScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.Job())
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        db.close()
        Dispatchers.resetMain()
        dataStoreFile.delete()
    }

    private fun settings() = SelfHealSettings(
        PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { dataStoreFile })
    )

    private fun vm(selfHeal: SelfHealSettings): MainViewModel =
        MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            injectedSelfHealSettings = selfHeal
        )

    @Test
    fun `config flow surfaces the persisted defaults`() = runTest {
        val viewModel = vm(settings())
        val config = viewModel.selfHealConfig.first()
        // P1.3 defaults: watchdog ON, coordinate guard ON, auto-resume OFF.
        org.junit.Assert.assertTrue(config.attemptWatchdogEnabled)
        org.junit.Assert.assertTrue(config.coordinateGuardEnabled)
        org.junit.Assert.assertFalse(config.serviceReconnectAutoResumeEnabled)
    }


    @Test
    fun `watchdog toggle writes the persisted watchdog key`() = runTest {
        val store = settings()
        val viewModel = vm(store)
        // selfHealConfig is stateIn(Lazily, defaults): a PERSISTENT subscriber is what
        // starts the upstream. first()-polling a Lazily StateFlow races the initial
        // defaults and can silently time out — collect instead, then assert on the tail.
        val readings = mutableListOf<com.example.cellrebelauto.data.SelfHealConfig>()
        // Real dispatcher (NOT backgroundScope): the test body blocks its own scheduler
        // inside awaitUntil's Thread.sleep, which would starve a scheduler-queued collector.
        val collectScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job())
        val collector = collectScope.launch { viewModel.selfHealConfig.toList(readings) }
        viewModel.setAttemptWatchdogEnabled(false)
        awaitUntil { readings.lastOrNull()?.attemptWatchdogEnabled == false }
        collector.cancel(); collectScope.cancel()
        org.junit.Assert.assertFalse(readings.last().attemptWatchdogEnabled)
        org.junit.Assert.assertFalse(store.config.first().attemptWatchdogEnabled)
    }

    @Test
    fun `coordinate guard toggle writes the persisted guard key`() = runTest {
        val store = settings()
        val viewModel = vm(store)
        val readings = mutableListOf<com.example.cellrebelauto.data.SelfHealConfig>()
        // Real dispatcher (NOT backgroundScope): the test body blocks its own scheduler
        // inside awaitUntil's Thread.sleep, which would starve a scheduler-queued collector.
        val collectScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.Job())
        val collector = collectScope.launch { viewModel.selfHealConfig.toList(readings) }
        viewModel.setCoordinateGuardEnabled(false)
        awaitUntil { readings.lastOrNull()?.coordinateGuardEnabled == false }
        collector.cancel(); collectScope.cancel()
        org.junit.Assert.assertFalse(readings.last().coordinateGuardEnabled)
        org.junit.Assert.assertFalse(store.config.first().coordinateGuardEnabled)
    }

    @Test
    fun `auto-resume toggle writes the persisted resume key`() = runTest {
        val store = settings()
        val viewModel = vm(store)
        viewModel.setServiceReconnectAutoResumeEnabled(true)
        awaitUntil { viewModel.selfHealConfig.first().serviceReconnectAutoResumeEnabled }
        awaitUntil { store.config.first().serviceReconnectAutoResumeEnabled }
        org.junit.Assert.assertTrue(viewModel.selfHealConfig.first().serviceReconnectAutoResumeEnabled)
        org.junit.Assert.assertTrue(store.config.first().serviceReconnectAutoResumeEnabled)
    }

    @Test
    fun `toggles are independent - flipping one leaves the others at defaults`() = runTest {
        val store = settings()
        val viewModel = vm(store)
        viewModel.setServiceReconnectAutoResumeEnabled(true)
        viewModel.setAttemptWatchdogEnabled(false)
        awaitUntil {
            val c = store.config.first()
            !c.attemptWatchdogEnabled && c.serviceReconnectAutoResumeEnabled
        }
        val config = store.config.first()
        org.junit.Assert.assertFalse(config.attemptWatchdogEnabled)
        org.junit.Assert.assertTrue(config.coordinateGuardEnabled)
        org.junit.Assert.assertTrue(config.serviceReconnectAutoResumeEnabled)
    }
}
