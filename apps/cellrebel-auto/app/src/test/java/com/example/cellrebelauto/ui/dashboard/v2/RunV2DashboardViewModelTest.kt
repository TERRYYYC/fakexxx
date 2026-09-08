package com.example.cellrebelauto.ui.dashboard.v2

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.data.DashboardMetricsSettings
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * T7v2 — the run dashboard v2 ViewModel oracle.
 *
 * Pins the v2 wiring at the VM boundary:
 *  - CI hero: the raw device reading flows through UNMODIFIED and the badge is
 *    the honest three-state classification (configured present+equal → 注入;
 *    configured null → 设备读数, the production discover channel's answer);
 *  - map points: trusted-color states only (legacy column never leaks);
 *  - one-tap 重启恢复: failure surfaces (no plan / service-down rejection read
 *    from the EXISTING startStatus), no bypass engine entry is created;
 *  - metric pill selection persists to the injected DataStore (1..3, sanitized).
 *
 * Killing mutations:
 *  - a VM that fabricates a badge when configured == null fails
 *    `unknownConfigYieldsDeviceReadingBadge`;
 *  - a VM that filters/reshapes the reading fails `servingCellReadingFlowsThroughUnmodified`;
 *  - a resume path that invents a success without startStatus Accepted fails
 *    `resumeWithoutPlanFailsFast` / `resumeRejectionSurfacesAsFailure`;
 *  - a metrics toggle that only mutates memory fails the direct store readback.
 *
 * # T7v2 ViewModel oracle：CI 徽标诚实、地图可信配色、一键恢复复用既有入口、指标持久化
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RunV2DashboardViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var dataStoreFile: File
    private lateinit var selfHealStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetAutomationServiceStaticState()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).build()
        dataStoreFile = File(
            System.getProperty("java.io.tmpdir"),
            "metrics-settings-test-${UUID.randomUUID()}.preferences_pb"
        )
        selfHealStoreFile = File(
            System.getProperty("java.io.tmpdir"),
            "self-heal-settings-test-${UUID.randomUUID()}.preferences_pb"
        )
        dataStoreScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.Job())
    }

    @After
    fun tearDown() {
        collectorScope.cancel()
        // Test hygiene (Wave-1 #111 lesson): cancel every constructed MainViewModel's
        // scope BEFORE resetMain — leaked Main-dispatcher coroutines race the next
        // test class's setMain (the PlanProfileConsistencyViewModelTest flake).
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        // Full drain (review §②): viewModelScope.cancel() is asynchronous — in-flight
        // DataStore/Room continuations still dispatch through the process-global
        // TestMainDispatcher, whose RW lock the NEXT setMain/resetMain takes. Give
        // them a bounded settle window so the lock is free when the next class
        // swaps the delegate.
        Thread.sleep(250)
        db.close()
        Dispatchers.resetMain()
        dataStoreFile.delete()
    }

    // Every MainViewModel this class constructs, so tearDown can drain them all.
    private val createdViewModels = mutableListOf<MainViewModel>()

    private val collectorScope = CoroutineScope(
        Dispatchers.Unconfined + kotlinx.coroutines.Job()
    )

    private fun startDashboard(viewModel: MainViewModel) {
        collectorScope.launch { viewModel.dashboardState.collect {} }
    }

    private fun awaitUntil(deadlineMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + deadlineMs
        while (!kotlinx.coroutines.runBlocking { condition() } && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun metricsSettings() = DashboardMetricsSettings(
        PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { dataStoreFile })
    )

    // Rebase note (T7 isolation): without this injection the VM falls back to
    // SelfHealSettings' PROCESS-PERSISTENT preferencesDataStore delegate — whatever
    // another class (e.g. the reconnect oracle) last wrote there leaks in across
    // Robolectric class boundaries. Every store this VM touches is now per-test.
    private fun selfHealSettings() = com.example.cellrebelauto.data.SelfHealSettings(
        PreferenceDataStoreFactory.create(scope = dataStoreScope, produceFile = { selfHealStoreFile })
    )

    /**
     * The AutomationService companion flows are JVM-STATIC and other oracle
     * classes in this fork (AutomationServiceReconnectResumeTest) legitimately
     * leave them dirty (e.g. isServiceConnected=true, startStatus=Rejected).
     * resumeRun funnels into that REAL entry, so this test must start from a
     * clean engine projection: service down, idle, no start in flight.
     * Same reflection approach as AutomationServiceReconnectResumeTest.
     */
    private fun resetAutomationServiceStaticState() {
        companionFlow("_isServiceConnected").value = false
        companionFlow("_isRunning").value = false
        companionFlow("_currentState").value =
            com.example.cellrebelauto.model.AutomationState.IDLE
        companionFlow("_startStatus").value =
            com.example.cellrebelauto.automation.AutomationStartStatus.IDLE
        companionFlow("_currentTask").value = null
        // Rebase note (T7 isolation): the static `instance` IS the connection gate —
        // a service left connected by another oracle class routes resumeRun into the
        // REAL startWithPlan (app-singleton DB → PLAN_NOT_FOUND) instead of the
        // typed SERVICE_NOT_CONNECTED rejection this oracle pins.
        setCompanionField("instance", null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun setCompanionField(name: String, value: Any?) {
        val outer = com.example.cellrebelauto.automation.AutomationService::class.java
        val staticField = outer.declaredFields.firstOrNull { it.name == name }
        if (staticField != null) {
            staticField.isAccessible = true
            staticField.set(null, value)
            return
        }
        val companionClass = outer.declaredClasses.first { it.simpleName == "Companion" }
        val holder = outer.declaredFields.first { it.type == companionClass }
        holder.isAccessible = true
        val companionInstance = holder.get(null)
        val field = companionClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(companionInstance, value)
    }

    /** AutomationService's companion MutableStateFlows live as static or companion fields. */
    @Suppress("UNCHECKED_CAST")
    private fun companionFlow(name: String): kotlinx.coroutines.flow.MutableStateFlow<Any?> {
        val outer = com.example.cellrebelauto.automation.AutomationService::class.java
        val staticField = outer.declaredFields.firstOrNull { it.name == name }
        if (staticField != null) {
            staticField.isAccessible = true
            return staticField.get(null) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
        }
        val companionClass = outer.declaredClasses.first { it.simpleName == "Companion" }
        val holder = outer.declaredFields.first { it.type == companionClass }
        holder.isAccessible = true
        val companionInstance = holder.get(null)
        val field = companionClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(companionInstance) as kotlinx.coroutines.flow.MutableStateFlow<Any?>
    }

    private fun reading(ci: Long?, rat: String = "LTE") = ServingCellReading(
        rat = rat, ci = ci, tac = 31461, pci = 210, mcc = "460", mnc = "0",
        rsrpDbm = -95, registered = true, readAtMs = 1L,
    )

    /** v1.81 probe fake: full configured group, hook configured by default. */
    private fun configuredCellProbe(
        ci: Long?, cellularHookConfigured: Boolean = true,
    ): ConfiguredCellIdentityProbe = ConfiguredCellIdentityProbe {
        ConfiguredCellIdentity(
            ci = ci, tac = 31461, pci = 210, mcc = "460", mnc = "0",
            cellularHookConfigured = cellularHookConfigured,
        )
    }

    private fun viewModel(
        cell: (() -> ServingCellReading?)? = null,
        configuredCell: ConfiguredCellIdentityProbe? = null,
        metrics: DashboardMetricsSettings? = null,
    ): MainViewModel = MainViewModel(
        ApplicationProvider.getApplicationContext(),
        injectedDb = db,
        // #103 cutover architecture: inject an open gate (app gate = recoveryRequired
        // under Robolectric would hide the seeded plan behind CutoverDataState).
        injectedAccessGate = CutoverAccessGate.open(),
        injectedSelfHealSettings = selfHealSettings(),
        injectedMetricsSettings = metrics ?: metricsSettings(),
        cellProbe = cell,
        configuredCellProbe = configuredCell,
        cellPollIntervalMs = 60_000L, // one shot per test; no background churn
    ).also { createdViewModels += it }

    private suspend fun seedPlan(taskCount: Int = 2): Long {
        val tasks = (1..taskCount).map { row ->
            LocationTask(
                planId = 0, csvRow = row,
                longitude = 30.0 + row, latitude = 50.0 + row,
                priority = row, requiredSuccesses = 2,
                completedSuccesses = 99, // frozen legacy column — must never leak
                status = if (row == 1) "active" else "pending",
            )
        }
        return db.planDao().insertPlanWithTasks(
            LocationPlan(
                sourceFileName = "v2.csv",
                importedAt = 1_000L,
                globalBufferSeconds = 10,
                totalRows = taskCount,
                totalRequiredSuccesses = 2 * taskCount,
            ),
            tasks,
        )
    }

    // ---- CI hero ---------------------------------------------------------------

    @Test
    fun `servingCellReadingFlowsThroughUnmodified with honest badge`() = runTest {
        seedPlan()
        val vm = viewModel(
            cell = { reading(ci = 289001L) },
            configuredCell = configuredCellProbe(ci = 289001L),
        )
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.ciHero.reading != null }
        val hero = vm.dashboardState.value.ciHero
        assertEquals(289001L, hero.reading?.ci)
        assertEquals(31461, hero.reading?.tac)
        assertEquals(-95, hero.reading?.rsrpDbm)
        // observed == configured 且蜂窝组已配置 → 注入
        assertEquals(CiBadge.INJECTED, hero.badge)
    }

    @Test
    fun `unknownConfigYieldsDeviceReadingBadge - never a fabricated 注入`() = runTest {
        seedPlan()
        val vm = viewModel(
            cell = { reading(ci = 46692113L) },
            // production discover channel unreachable → null projection (fail-closed)
            configuredCell = null,
        )
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.ciHero.badge != null }
        assertEquals(CiBadge.DEVICE_READING, vm.dashboardState.value.ciHero.badge)
    }

    @Test
    fun `configuredButMismatchedYieldsPassthroughReal`() = runTest {
        seedPlan()
        val vm = viewModel(
            cell = { reading(ci = 46692113L) },
            configuredCell = configuredCellProbe(ci = 289001L),
        )
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.ciHero.badge != null }
        assertEquals(CiBadge.PASSTHROUGH_REAL, vm.dashboardState.value.ciHero.badge)
    }

    @Test
    fun `equalCiWithoutConfiguredCellularGroup_staysNonInjected - v1-81 fail-closed leg`() = runTest {
        seedPlan()
        // A contradictory projection (configured ci present, hook NOT configured)
        // must never mint 注入 from equality alone: the honest claim is 透传·真实.
        // Mutation: MainViewModel dropping the cellularHookConfigured leg → red.
        val vm = viewModel(
            cell = { reading(ci = 289001L) },
            configuredCell = configuredCellProbe(ci = 289001L, cellularHookConfigured = false),
        )
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.ciHero.badge != null }
        assertEquals(CiBadge.PASSTHROUGH_REAL, vm.dashboardState.value.ciHero.badge)
    }

    @Test
    fun `productionDiscoverCellProbe_failsClosedToNullWithoutProvider`() {
        // The production probe over the real Binder channel: on this host there
        // is no provider service, so the handshake cannot connect — the probe
        // MUST answer null (badge → 设备读数), never throw, never invent data.
        val probe = com.example.cellrebelauto.ui.dashboard.v2.DiscoverConfiguredCellProbe(
            ApplicationProvider.getApplicationContext()
        )
        assertNull(probe.configuredCell())
    }

    @Test
    fun `noCellProbeRendersPlaceholderWithoutBadge`() = runTest {
        seedPlan()
        // default production probe on Robolectric: permission-denied → null reading
        val vm = viewModel()
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.hasPlan }
        val hero = vm.dashboardState.value.ciHero
        assertNull(hero.reading)
        assertNull(hero.badge)
        assertEquals("--", hero.ciText)
    }

    // ---- map points --------------------------------------------------------------

    @Test
    fun `mapPointsProjectFromTrustedCounts_only - legacy 99 never leaks`() = runTest {
        val planId = seedPlan()
        val task1 = db.locationTaskDao().getTasksForPlan(planId).first()
        val vm = viewModel()
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.mapPoints.isNotEmpty() }
        val points = vm.dashboardState.value.mapPoints
        assertEquals(2, points.size)
        // execution order (csvRow ASC): row1 first (active → PENDING until quota met)
        assertEquals(1, points[0].csvRow)
        assertEquals(MapPointState.PENDING, points[0].state)
        assertEquals(2, points[1].csvRow)
        assertEquals(MapPointState.PENDING, points[1].state)
        // no trusted mints yet; the legacy completedSuccesses=99 must NOT color anything DONE
        assertTrue(points.none { it.state == MapPointState.DONE })
    }

    @Test
    fun `hasPlanFlipsTrueWithAPlan`() = runTest {
        seedPlan()
        val vm = viewModel()
        startDashboard(vm)
        awaitUntil { vm.dashboardState.value.hasPlan }
        assertTrue(vm.dashboardState.value.hasPlan)
    }

    // ---- one-tap resume ------------------------------------------------------------

    @Test
    fun `resumeWithoutPlanFailsFast`() = runTest {
        val vm = viewModel()
        vm.resumeRun()
        assertEquals("NO_PLAN", vm.resumeFailure.value)
        val outcome = vm.resumeOutcome.value
        assertFalse(outcome?.succeeded ?: true)
        assertEquals("NO_PLAN", outcome?.reason)
    }

    @Test
    fun `resumeRejectionSurfacesAsFailure - same startStatus entry`() = runTest {
        seedPlan()
        val vm = viewModel()
        startDashboard(vm)
        // The run surface only offers 重启恢复 once the plan projection landed
        // (hasPlan drives the button); mirror that ordering here.
        awaitUntil { vm.dashboardState.value.hasPlan }
        // Robolectric: the accessibility service is not connected, so the REAL
        // AutomationService.startAutomation answers with the typed rejection —
        // exactly what the operator sees on a dead engine host.
        vm.resumeRun()
        awaitUntil { vm.resumeFailure.value != null }
        assertEquals("SERVICE_NOT_CONNECTED", vm.resumeFailure.value)
        assertFalse(vm.resumeOutcome.value?.succeeded ?: true)
        assertEquals("SERVICE_NOT_CONNECTED", vm.resumeOutcome.value?.reason)
    }

    @Test
    fun `consumingTheOutcomeClearsTheOneShotEvent`() = runTest {
        val vm = viewModel()
        vm.resumeRun()
        assertNotNull(vm.resumeOutcome.value)
        vm.consumeResumeOutcome()
        assertNull(vm.resumeOutcome.value)
    }

    private fun assertNotNull(any: Any?) = org.junit.Assert.assertNotNull(any)

    // ---- metric pills persistence ----------------------------------------------------

    @Test
    fun `metricSelectionPersistsToDataStore sanitized`() = runTest {
        val store = metricsSettings()
        val vm = viewModel(metrics = store)
        vm.setMetricSelection(
            listOf(MetricKey.ETA, MetricKey.ETA, MetricKey.THROUGHPUT, MetricKey.COORDINATE, MetricKey.CI_SOURCE)
        )
        awaitUntil {
            kotlinx.coroutines.runBlocking { store.selection.first() } ==
                listOf(MetricKey.ETA, MetricKey.THROUGHPUT, MetricKey.COORDINATE)
        }
        // the VM's own flow converges to the same sanitized selection
        awaitUntil {
            vm.metricSelection.value == listOf(MetricKey.ETA, MetricKey.THROUGHPUT, MetricKey.COORDINATE)
        }
        // a fresh store over the SAME file reads the persisted choice
        val reread = kotlinx.coroutines.runBlocking { store.selection.first() }
        assertEquals(listOf(MetricKey.ETA, MetricKey.THROUGHPUT, MetricKey.COORDINATE), reread)
    }
}
