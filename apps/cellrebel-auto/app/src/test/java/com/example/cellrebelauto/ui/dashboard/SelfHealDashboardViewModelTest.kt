package com.example.cellrebelauto.ui.dashboard

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.data.SelfHealSettings
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.testing.DataStoreTestRule
import com.example.cellrebelauto.testing.MainDispatcherRule
import com.example.cellrebelauto.testing.RetryRule
import com.example.cellrebelauto.testing.awaitUntil
import com.example.cellrebelauto.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
 * #143 governance: Main lifecycle (setMain / drain / resetMain) and the DataStore
 * lifecycle (per-test temp files, real-IO scope) live in the shared rules; write
 * assertions poll via the shared [awaitUntil] whose deadline is CI-safe.
 *
 * # 自愈三开关 ViewModel oracle：读写均落在 T4 的 SelfHealSettings，逐键断言
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SelfHealDashboardViewModelTest {

    private companion object {
        /**
         * #143 form-A remedy: this class's polls use the ASSERTING awaitUntil variant
         * (a timed-out poll must fail loudly with context, never silently hand control
         * to a follow-up assert on the default value), and this family gets its own
         * relaxed deadline: CI run 34874550513 died at 30.038s — exactly the shared 30s
         * bound — with the toggle write still not propagated (runner IO starvation past
         * 30s on a 6m20s build machine). 60s is 2x the observed starvation, still
         * bounded so a real hang fails inside the minute instead of hanging the lane.
         */
        const val TOGGLE_PERSIST_DEADLINE_MS = 60_000L
    }

    // Rule order (empirical): JUnit4 wraps later-declared @Rule fields OUTSIDE earlier
    // ones, so MainDispatcherRule runs INSIDE DataStoreTestRule — dataStore.starting →
    // setMain → test → drain + retry-guarded resetMain → dataStore.finished (scope cancel
    // + temp-dir delete after resetMain; safe — the DataStore scope is real IO and never
    // dispatches on Main, see DataStoreTestRule's KDoc).
    // #143 governance replaces the hand-rolled setMain/createdViewModels/Thread.sleep(250)
    // teardown, whose fixed settle window still raced trailing Main dispatches on slow CI
    // runners (watchdog toggle flakes, #185/#196).
    // `retry` is declared FIRST = INNERMOST: a retry round re-runs only the fixtures
    // (@Before/@After rebuild the db/store fresh) plus the test body, while the
    // Main/DataStore lifecycle rules stay OUTSIDE the retry loop, running once per
    // test method (a per-round setMain/store would widen the very race being ridden out).

    // CI 慢 runner 时序敏感 flaky（#143 归档）：`watchdog toggle writes the persisted
    // watchdog key` 的第二层时序——#197 治理后仍复发（2026-09-15 PR #210，productId，
    // AssertionError@L131；治理前同用例 30.038s@L132 亦三度命中）——形态=断言失败、rerun
    // 即绿、本地恒绿（含评审员 fresh 全量绿），实证为 runner IO 时序而非产品缺陷。
    // maxAttempts=3 的显式重试是过渡手段：终结"每个 PR 都被咬、rerun 赌运气"的三连
    // rerun 成本；根治走 #143。
    @get:Rule
    val retry = RetryRule(
        maxAttempts = 3,
        // 红线：白名单仅此一个已归档用例；同类其余用例不包装，新 flaky 必须首轮裸奔暴露。
        onlyMethods = setOf("watchdog toggle writes the persisted watchdog key"),
    )

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @get:Rule
    val dataStoreRule = DataStoreTestRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        // Drain every VM's Main-dispatching scope BEFORE db.close/resetMain (the #111
        // lesson); MainDispatcherRule.finished re-runs this (idempotent) and resets Main
        // with retry-on-conflict — deterministic convergence instead of sleep(250).
        mainDispatcherRule.cancelTracked()
        db.close()
    }

    private fun settings() = SelfHealSettings(
        // rule.store resolves ONE stable file per DataStore (produceFile must be idempotent).
        dataStoreRule.store("self-heal-dash-test")
    )

    // Rebase note (T7 isolation): inject the metrics store too — the VM's default
    // DashboardMetricsSettings(application) falls back to a PROCESS-PERSISTENT
    // preferencesDataStore delegate shared across Robolectric class boundaries.
    private fun metricsSettings() = com.example.cellrebelauto.data.DashboardMetricsSettings(
        dataStoreRule.store("dash-metrics-test")
    )

    private fun vm(selfHeal: SelfHealSettings): MainViewModel =
        MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            // #103 cutover architecture: inject an open gate (app gate = recoveryRequired
            // under Robolectric would gate every protected projection).
            injectedAccessGate = CutoverAccessGate.open(),
            injectedSelfHealSettings = selfHeal,
            injectedMetricsSettings = metricsSettings()
        ).also { mainDispatcherRule.trackViewModel(it) }

    @Test
    fun `config flow surfaces the persisted defaults`() = runTest {
        val viewModel = vm(settings())
        val config = viewModel.selfHealConfig.first()
        // P1.3 defaults: watchdog ON, coordinate guard ON, auto-resume ON (2026-09-08 decision).
        org.junit.Assert.assertTrue(config.attemptWatchdogEnabled)
        org.junit.Assert.assertTrue(config.coordinateGuardEnabled)
        org.junit.Assert.assertTrue(config.serviceReconnectAutoResumeEnabled)
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
        // ASSERTING variant (#143 form A): the boolean variant returned false silently on
        // timeout, so the failure surfaced later as a bare AssertionError at the follow-up
        // assertFalse ON THE DEFAULT VALUE (CI run 34874550513, :132, 30.038s) with zero
        // poll context. A timed-out poll must name itself.
        awaitUntil(
            "watchdog toggle write did not propagate attemptWatchdogEnabled=false to " +
                "selfHealConfig within ${TOGGLE_PERSIST_DEADLINE_MS}ms",
            TOGGLE_PERSIST_DEADLINE_MS,
        ) { readings.lastOrNull()?.attemptWatchdogEnabled == false }
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
        // ASSERTING variant — same form-A remedy as the watchdog toggle above.
        awaitUntil(
            "coordinate guard toggle write did not propagate coordinateGuardEnabled=false " +
                "to selfHealConfig within ${TOGGLE_PERSIST_DEADLINE_MS}ms",
            TOGGLE_PERSIST_DEADLINE_MS,
        ) { readings.lastOrNull()?.coordinateGuardEnabled == false }
        collector.cancel(); collectScope.cancel()
        org.junit.Assert.assertFalse(readings.last().coordinateGuardEnabled)
        org.junit.Assert.assertFalse(store.config.first().coordinateGuardEnabled)
    }

    @Test
    fun `auto-resume toggle writes the persisted resume key`() = runTest {
        val store = settings()
        val viewModel = vm(store)
        viewModel.setServiceReconnectAutoResumeEnabled(true)
        // ASSERTING variants (#143 form A) — the polling chain stays, but a timeout now
        // fails HERE with the key name, not downstream on a default-valued assertTrue.
        awaitUntil(
            "auto-resume toggle write did not surface in selfHealConfig within " +
                "${TOGGLE_PERSIST_DEADLINE_MS}ms",
            TOGGLE_PERSIST_DEADLINE_MS,
        ) { viewModel.selfHealConfig.first().serviceReconnectAutoResumeEnabled }
        awaitUntil(
            "auto-resume toggle write did not reach the persisted store config within " +
                "${TOGGLE_PERSIST_DEADLINE_MS}ms",
            TOGGLE_PERSIST_DEADLINE_MS,
        ) { store.config.first().serviceReconnectAutoResumeEnabled }
        org.junit.Assert.assertTrue(viewModel.selfHealConfig.first().serviceReconnectAutoResumeEnabled)
        org.junit.Assert.assertTrue(store.config.first().serviceReconnectAutoResumeEnabled)
    }

    @Test
    fun `toggles are independent - flipping one leaves the others at defaults`() = runTest {
        val store = settings()
        val viewModel = vm(store)
        viewModel.setServiceReconnectAutoResumeEnabled(true)
        viewModel.setAttemptWatchdogEnabled(false)
        // ASSERTING variant (#143 form A) — last boolean awaitUntil in this class.
        awaitUntil(
            "toggles (watchdog=off, auto-resume=on) did not both reach the persisted store " +
                "config within ${TOGGLE_PERSIST_DEADLINE_MS}ms",
            TOGGLE_PERSIST_DEADLINE_MS,
        ) {
            val c = store.config.first()
            !c.attemptWatchdogEnabled && c.serviceReconnectAutoResumeEnabled
        }
        val config = store.config.first()
        org.junit.Assert.assertFalse(config.attemptWatchdogEnabled)
        org.junit.Assert.assertTrue(config.coordinateGuardEnabled)
        org.junit.Assert.assertTrue(config.serviceReconnectAutoResumeEnabled)
    }
}
