package com.example.cellrebelauto.configbundle

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.cutover.CutoverAccessGate
import com.example.cellrebelauto.cutover.CutoverDataState
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.LocationPlan
import com.example.cellrebelauto.model.plan.LocationTask
import com.example.cellrebelauto.model.plan.PlanConfig
import com.example.cellrebelauto.model.plan.WorklistRow
import com.example.cellrebelauto.repository.PlanRepository
import com.example.cellrebelauto.ui.MainViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows
import java.io.ByteArrayInputStream

/**
 * T8: Auto-side bundle import, driven at the ViewModel seam the T2 CSV import uses.
 * Idempotent re-import, fail-closed on unknown schemaVersion, conflict policy
 * (overwrite through the EXISTING T2 replacement machinery / skip), count
 * reconciliation over the T2 warning channel, and the trust red line: the import
 * NEVER writes provider_pairing_records from the bundle.
 * # 配置包导入：幂等 + fail-closed + 覆盖/跳过 + 行数对账 + 绝不写配对授权
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BundleImportViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var app: Application

    private val rows = listOf(
        WorklistRow(30.11, 50.11, priority = 1, requiredSuccesses = 2, csvRow = 1),
        WorklistRow(30.12, 50.12, priority = 0, requiredSuccesses = 1, csvRow = 2),
    )

    private fun bundleBytes(
        rows: List<WorklistRow>,
        buffer: Int = 42,
        schemaVersion: Int? = null,
        declaredCountOverride: Int? = null,
    ): ByteArray {
        val export = AutoBundleExporter.export(
            AutoBundleExport(
                planRows = rows.map {
                    WorklistRowData(it.longitude, it.latitude, it.priority, it.requiredSuccesses)
                },
                planSourceFileName = "bundle-plan.csv",
                planBufferSeconds = buffer,
                planConfig = PlanConfig(
                    globalBufferSeconds = buffer,
                    testTimeoutSeconds = 90,
                    gpsSettleSeconds = 60,
                    locationStageEnabled = true,
                    testStageEnabled = true,
                ),
                pairingFingerprints = listOf(
                    ProviderFingerprint("test.bundle.provider", "deadbeef", approvedVersionCode = 7),
                ),
                lane = AutoBundleSections.LaneMetadata(
                    qwyApplicationId = null,
                    qwyVersionName = null,
                    transportSchemaVersion = null,
                    autoApplicationId = "com.example.cellrebelauto",
                    autoVersionName = "2.0-test",
                    providerPrincipal = "name.caiyao.fakegps.glmbench",
                ),
                createdAtEpochMs = 42L,
            ),
        )
        if (schemaVersion == null && declaredCountOverride == null) return export.zipBytes
        val files = (ConfigBundleContract.parseBundle(export.zipBytes) as ConfigBundleParseResult.Ok)
            .bundle.files
        val manifest = files.getValue("manifest.json").toString(Charsets.UTF_8)
        val patched = schemaVersion?.let {
            manifest.replace(
                "\"schemaVersion\":${ConfigBundleContract.BUNDLE_SCHEMA_VERSION}",
                "\"schemaVersion\":$it",
            )
        } ?: manifest
        val patched2 = declaredCountOverride?.let {
            patched.replace("\"count\":${rows.size}", "\"count\":$it")
        } ?: patched
        return ConfigBundleZipWriter.write(
            files + ("manifest.json" to patched2.toByteArray(Charsets.UTF_8)),
        )
    }

    private fun registerBundle(zip: ByteArray) {
        Shadows.shadowOf(app.contentResolver).registerInputStream(
            Uri.parse("content://test/bundle.zip"),
            ByteArrayInputStream(zip),
        )
    }

    private val bundleUri = Uri.parse("content://test/bundle.zip")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        createdViewModels.forEach { it.viewModelScope.cancel() }
        createdViewModels.clear()
        db.close()
        Dispatchers.resetMain()
    }

    private fun vm(profileCount: Int? = null) = MainViewModel(
        app,
        injectedDb = db,
        // #103 cutover architecture: the app-singleton gate is recoveryRequired under
        // Robolectric — inject an open gate so the protected flows can go Ready.
        injectedAccessGate = CutoverAccessGate.open(),
        profileCountProbe = profileCount?.let { n -> ({ n }) },
    ).also { createdViewModels += it }

    // Test hygiene (Wave-1 #111 lesson): drain every constructed MainViewModel BEFORE
    // resetMain — leaked Main-dispatcher coroutines race the next test class's setMain.
    private val createdViewModels = mutableListOf<MainViewModel>()

    // #103 cutover architecture: protected flows emit CutoverDataState — unwrap for asserts.
    private fun <T> CutoverDataState<T>.readyOrNull(): T? = (this as? CutoverDataState.Ready)?.value

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
    }

    // ---- fresh import ----

    @Test
    fun `fresh import applies plan rows buffer and surfaces fingerprints for verification`() = runTest {
        registerBundle(bundleBytes(rows, buffer = 42))
        val vm = vm()
        val collectConfig = launch { vm.planConfig.collect {} }

        vm.importConfigBundle(bundleUri)
        await { vm.planUiState.value.readyOrNull()?.plan != null }
        await { vm.planConfig.value.readyOrNull()?.globalBufferSeconds == 42 }

        val plan = db.planDao().getLatestPlan()!!
        assertEquals(2, plan.totalRows)
        assertEquals(42, plan.globalBufferSeconds)
        assertEquals("bundle-plan.csv", plan.sourceFileName)
        val tasks = db.locationTaskDao().getTasksForPlan(plan.id).sortedBy { it.csvRow }
        assertEquals(listOf(30.11, 30.12), tasks.map { it.longitude })
        // Fingerprints are surfaced for HUMAN verification only — never written as trust.
        await { vm.bundlePairingFingerprints.value.isNotEmpty() }
        assertEquals("deadbeef", vm.bundlePairingFingerprints.value.single().signerDigest)
        assertEquals(0, db.providerPairingDao().count())
        collectConfig.cancel()
    }

    @Test
    fun `fresh import runs the T2 plan-profile consistency check`() = runTest {
        registerBundle(bundleBytes(rows))
        val vm = vm(profileCount = 5)

        vm.importConfigBundle(bundleUri)
        await { vm.planProfileMismatch.value != null }

        assertEquals(2, vm.planProfileMismatch.value!!.planRows)
        assertEquals(5, vm.planProfileMismatch.value!!.providerProfiles)
    }

    // ---- idempotency ----

    @Test
    fun `importing the same bundle twice does not create a second plan`() = runTest {
        registerBundle(bundleBytes(rows, buffer = 42))
        val vm = vm()

        vm.importConfigBundle(bundleUri)
        await { vm.planUiState.value.readyOrNull()?.plan != null }
        val firstPlanId = db.planDao().getLatestPlan()!!.id

        registerBundle(bundleBytes(rows, buffer = 42))
        vm.importConfigBundle(bundleUri)
        await { vm.importNotice.value?.contains("already current") == true }

        assertEquals(firstPlanId, db.planDao().getLatestPlan()!!.id)
    }

    // ---- conflict policy ----

    @Test
    fun `unfinished plan with different rows stages a conflict prompt instead of importing`() = runTest {
        val repository = PlanRepository(db, CutoverAccessGate.open())
        repository.importPlan("local.csv", 5, listOf(WorklistRow(1.0, 2.0, 0, 1, csvRow = 1)), 100L)

        registerBundle(bundleBytes(rows))
        val vm = vm()

        vm.importConfigBundle(bundleUri)
        await { vm.bundleConflict.value != null }

        // Nothing imported while the prompt is up.
        assertEquals("local.csv", db.planDao().getLatestPlan()!!.sourceFileName)
        assertNotNull(vm.bundleConflict.value)
    }

    @Test
    fun `skip decision keeps the current plan and still applies the bundle parameters`() = runTest {
        val repository = PlanRepository(db, CutoverAccessGate.open())
        repository.importPlan("local.csv", 5, listOf(WorklistRow(1.0, 2.0, 0, 1, csvRow = 1)), 100L)
        val localPlanId = db.planDao().getLatestPlan()!!.id

        registerBundle(bundleBytes(rows, buffer = 77))
        val vm = vm()
        val collectConfig = launch { vm.planConfig.collect {} }
        vm.importConfigBundle(bundleUri)
        await { vm.bundleConflict.value != null }

        vm.skipBundlePlanApply()
        await { vm.planConfig.value.readyOrNull()?.globalBufferSeconds == 77 }

        assertEquals(localPlanId, db.planDao().getLatestPlan()!!.id)
        assertNull(vm.bundleConflict.value)
        collectConfig.cancel()
    }

    @Test
    fun `overwrite decision routes the bundle rows through the existing T2 replacement proposal`() = runTest {
        val repository = PlanRepository(db, CutoverAccessGate.open())
        repository.importPlan("local.csv", 5, listOf(WorklistRow(1.0, 2.0, 0, 1, csvRow = 1)), 100L)

        registerBundle(bundleBytes(rows, buffer = 42))
        val vm = vm()
        vm.importConfigBundle(bundleUri)
        await { vm.bundleConflict.value != null }

        vm.confirmBundleOverwrite()
        await { vm.importProposal.value != null }

        val proposal = vm.importProposal.value!!
        assertEquals("local.csv", proposal.oldSourceFileName)
        assertEquals("bundle-plan.csv", proposal.sourceFileName)
        assertEquals(2, proposal.rows.size)
        assertEquals(42, proposal.globalBufferSeconds)
        assertNull(vm.bundleConflict.value)
    }

    // ---- fail-closed ----

    @Test
    fun `unknown schemaVersion rejects with an explicit error and writes nothing`() = runTest {
        registerBundle(bundleBytes(rows, schemaVersion = 99))
        val vm = vm()

        vm.importConfigBundle(bundleUri)
        await { vm.importNotice.value != null }

        assertTrue(vm.importNotice.value!!.contains("99"))
        assertNull(db.planDao().getLatestPlan())
        assertTrue(vm.bundlePairingFingerprints.value.isEmpty())
    }

    // ---- reconciliation over the T2 warning channel ----

    @Test
    fun `manifest plan-count mismatch surfaces a reconciliation warning with both numbers`() = runTest {
        registerBundle(bundleBytes(rows, declaredCountOverride = 9))
        val vm = vm()

        vm.importConfigBundle(bundleUri)
        await { vm.planUiState.value.readyOrNull()?.plan != null }
        await { vm.bundleWarnings.value.isNotEmpty() }

        val warning = vm.bundleWarnings.value.joinToString(" ")
        assertTrue("warning should carry both counts: $warning", warning.contains("9") && warning.contains("2"))
    }

    // ---- export path (pure exporter over the VM data owners) ----

    @Test
    fun `exporter over vm data owners produces a bundle whose plan csv matches the plan rows`() = runTest {
        val repository = PlanRepository(db, CutoverAccessGate.open())
        val planId = repository.importPlan(
            "local.csv", 12,
            listOf(
                WorklistRow(30.11, 50.11, 1, 2, csvRow = 1),
                WorklistRow(30.12, 50.12, 0, 1, csvRow = 2),
            ),
            100L,
        )

        val zip = AutoBundleExporter.export(
            BundleExportSource.read(
                db = db,
                accessGate = CutoverAccessGate.open(),
                planId = planId,
                planConfig = PlanConfig(12),
            ),
            createdAtEpochMs = 42L,
        ).zipBytes

        val parsed = ConfigBundleContract.parseBundle(zip) as ConfigBundleParseResult.Ok
        val csv = parsed.bundle.files.getValue("auto/plan.csv").toString(Charsets.UTF_8)
        val back = com.example.cellrebelauto.model.plan.WorklistParser.parse(csv)
        assertTrue(back is com.example.cellrebelauto.model.plan.ParseResult.Success)
        assertEquals(
            listOf(30.11, 30.12),
            (back as com.example.cellrebelauto.model.plan.ParseResult.Success).rows.map { it.longitude },
        )
    }

    @Test
    fun `same rows with a different buffer are not treated as already current`() = runTest {
        val repository = PlanRepository(db, CutoverAccessGate.open())
        repository.importPlan("local.csv", 5, listOf(WorklistRow(30.11, 50.11, 1, 2, csvRow = 1)), 100L)

        registerBundle(bundleBytes(rows = listOf(WorklistRow(30.11, 50.11, 1, 2, csvRow = 1)), buffer = 99))
        val vm = vm()
        vm.importConfigBundle(bundleUri)
        await { vm.bundleConflict.value != null }

        assertNotNull(vm.bundleConflict.value)
    }

    /**
     * #142 RED: cancelled tasks are TERMINAL (#135 abandon) — an ABANDONED plan is no
     * longer unfinished, so importing a bundle with different rows must NOT stage the
     * conflict prompt anymore; the import passes straight through. Before the fix the
     * inline predicate `status != "completed"` still counted cancelled tasks and the
     * abandoned plan raised the conflict dialog (a confirm-overwrite escape existed, so
     * this was friction, not a dead wall — hence the one-line fix).
     */
    @Test
    fun `abandoned plan with cancelled tasks no longer stages the bundle conflict`() = runTest {
        val repository = PlanRepository(db, CutoverAccessGate.open())
        repository.importPlan("local.csv", 5, listOf(WorklistRow(1.0, 2.0, 0, 1, csvRow = 1)), 100L)
        val abandonOutcome = repository.abandonCurrentPlan()
        assertTrue(
            "seed must abandon the plan, got $abandonOutcome",
            abandonOutcome is PlanRepository.PlanAbandonOutcome.Abandoned,
        )

        registerBundle(bundleBytes(rows))
        val vm = vm()

        vm.importConfigBundle(bundleUri)
        await { vm.importNotice.value?.contains("Imported") == true }

        assertNull("an abandoned plan must not raise the conflict prompt", vm.bundleConflict.value)
        assertEquals(
            "the bundle plan must import straight through",
            "bundle-plan.csv",
            db.planDao().getLatestPlan()!!.sourceFileName,
        )
    }
}
