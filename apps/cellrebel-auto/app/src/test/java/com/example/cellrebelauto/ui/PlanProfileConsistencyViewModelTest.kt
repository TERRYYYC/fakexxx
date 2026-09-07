package com.example.cellrebelauto.ui

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.plan.PlanProfileConsistency
import com.example.cellrebelauto.model.plan.PlanProfileMismatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * P0.1-5 计划-档案一致性校验的 ViewModel oracle：导入计划 CSV 后经既有 discover 通道
 * （[ProfileCountProbe] 生产端 = EnvironmentControlClient）取得 QWY 档案数，
 * 计划行数 ≠ 档案数 → Plan 页显著警告（带两侧数字）；相等 → 无警告；
 * 通道异常/取不到 → 静默跳过，不报警告也不崩。
 *
 * 实测痛点：51 行计划配 52 档案 → 错位一档空转烧配额，全程无任何预警。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PlanProfileConsistencyViewModelTest {

    private lateinit var db: AppDatabase

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

    private fun registerCsv(csv: String) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app.contentResolver).registerInputStream(
            Uri.parse("content://test/worklist.csv"),
            csv.byteInputStream(),
        )
    }

    private fun csvWithRows(rows: Int): String = buildString {
        appendLine("longitude,latitude,priority,required_successes")
        repeat(rows) { i ->
            appendLine("30.${i + 10},50.${i + 10},${i + 1},1")
        }
    }

    // Rebase note: importCsv refuses with "Plan data is unavailable or still loading" until the
    // DataStore config flow AND the Room-backed plan projection both land their first Ready
    // emission. Waiting on planConfig alone left a race where importCsv early-returned while
    // planUiState was still Loading — the mismatch/notice asserts then saw stale state.
    private fun awaitConfigReady(vm: MainViewModel) {
        await("plan config projection reached Ready") {
            vm.planConfig.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
        }
        await("plan UI projection reached Ready") {
            vm.planUiState.value is com.example.cellrebelauto.cutover.CutoverDataState.Ready<*>
        }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    @Test
    fun `importing a 51-row plan against 52 provider profiles raises the mismatch warning`() = runTest {
        registerCsv(csvWithRows(51))
        val vm = MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            // Rebase note: without an injected open gate the VM builds recoveryRequired()
            // (Robolectric has no cutover app state) and every import early-returns.
            injectedAccessGate = com.example.cellrebelauto.cutover.CutoverAccessGate.open(),
            profileCountProbe = { 52 },
        )

        awaitConfigReady(vm)
        vm.importCsv(Uri.parse("content://test/worklist.csv"))
        await("mismatch warning raised after import") { vm.planProfileMismatch.value != null }

        val mismatch = vm.planProfileMismatch.value!!
        assertEquals(51, mismatch.planRows)
        assertEquals(52, mismatch.providerProfiles)
        assertEquals(
            PlanProfileConsistency.evaluate(51, 52),
            mismatch,
        )
    }

    @Test
    fun `equal counts raise no warning`() = runTest {
        registerCsv(csvWithRows(3))
        val vm = MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            // Rebase note: without an injected open gate the VM builds recoveryRequired()
            // (Robolectric has no cutover app state) and every import early-returns.
            injectedAccessGate = com.example.cellrebelauto.cutover.CutoverAccessGate.open(),
            profileCountProbe = { 3 },
        )

        awaitConfigReady(vm)
        vm.importCsv(Uri.parse("content://test/worklist.csv"))
        await("import succeeded") { vm.importNotice.value?.startsWith("Imported") == true }

        assertNull(vm.planProfileMismatch.value)
    }

    @Test
    fun `a failing discover channel silently skips the check without crashing`() = runTest {
        registerCsv(csvWithRows(2))
        val vm = MainViewModel(
            ApplicationProvider.getApplicationContext(),
            injectedDb = db,
            // Rebase note: without an injected open gate the VM builds recoveryRequired()
            // (Robolectric has no cutover app state) and every import early-returns.
            injectedAccessGate = com.example.cellrebelauto.cutover.CutoverAccessGate.open(),
            profileCountProbe = { throw IllegalStateException("binder death") },
        )

        awaitConfigReady(vm)
        vm.importCsv(Uri.parse("content://test/worklist.csv"))
        await("import succeeded despite probe failure") {
            vm.importNotice.value?.startsWith("Imported") == true
        }

        // 通道异常 → 无警告、导入照常成功、不崩。
        assertNull(vm.planProfileMismatch.value)
        assertTrue(db.planDao().getLatestPlan() != null)
    }
}
