package name.caiyao.fakegps.ui.screen.editor

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import name.caiyao.fakegps.data.db.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * T11d review P2-1 pin：两条核心切换语义此前实现正确但无测试钉住——
 *
 * 1) 跨模式草稿零丢失：专家模式填的蜂窝值 → 切到简单模式 → 保存后必须【保留】
 *    （"看不见但保留"，save() 写整个共享 _fieldValues，不是数据损失）。
 * 2) 同键 load 去重（loadedProfileId）：同一目的地（新档案）二次 load 不重置草稿，
 *    且它同时是"新档案二次保存重复插入"的唯一屏障——模式来回切换后 save() 必须只落一行。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorModeDraftPinTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppDatabase.closeInstanceForTests()
    }

    @After
    fun tearDown() {
        AppDatabase.closeInstanceForTests()
        Dispatchers.resetMain()
    }

    private fun context() = RuntimeEnvironment.getApplication()

    private fun db() = AppDatabase.getInstance(context())

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    @Test
    fun `expert draft survives a round trip through simple mode and saves exactly one row`() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = ProfileEditorViewModel(app)

            // 新档案，专家模式姿态填入蜂窝 + 坐标 + 名称
            vm.load(-1L, defaultLat = 24.0297, defaultLon = 49.8397)
            await("new draft loads") { vm.fieldValues.value.containsKey("latitude") }
            vm.updateName("跨模式档案")
            vm.updateField("latitude", "50.450864")
            vm.updateField("longitude", "30.5234")
            vm.updateField("mcc", "460")
            vm.updateField("ci", "289001")

            // T11d 模式切换 = ProfileEditorHost 原地换渲染层，同 VM 二次 load 同键
            vm.load(-1L, defaultLat = 24.0297, defaultLon = 49.8397)
            await("draft survives re-entry") { vm.fieldValues.value.containsKey("latitude") }

            // 草稿零丢失：蜂窝值仍在（"看不见但保留"），坐标没有被 load 的默认值重置
            val draft = vm.fieldValues.value
            assertEquals("50.450864", draft["latitude"])
            assertEquals("30.5234", draft["longitude"])
            assertEquals("460", draft["mcc"])
            assertEquals("289001", draft["ci"])

            vm.save()
            await("save completes") { !vm.saving.value }

            val rows = db().profileDao().getAll()
            assertEquals("mode round trip must insert exactly one row", 1, rows.size)
            val row = rows.single()
            assertEquals("跨模式档案", row.addname)
            assertEquals(50.450864, row.latitude!!, 1e-9)
            assertEquals("hidden-but-preserved cellular draft", "460", row.mcc.toString())
            assertEquals("hidden-but-preserved cellular draft", "289001", row.ci.toString())
        }

    @Test
    fun `expert values are not lost when the simple editor saves after a mode flip`() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<Application>()
            val vm = ProfileEditorViewModel(app)

            vm.load(-1L, defaultLat = 24.0297, defaultLon = 49.8397)
            await("new draft loads") { vm.fieldValues.value.containsKey("latitude") }
            // 简单模式可编辑集 = Spec 钉死的 {latitude, longitude}（SimpleProfileEditorSpec 合同）
            vm.updateField("latitude", "49.9935")
            vm.updateField("longitude", "36.2304")
            // 专家姿态补的值在切换前已在草稿里
            vm.updateField("wifi_ssid", "hidden-net")

            vm.save()
            await("save completes") { !vm.saving.value }

            val row = db().profileDao().getAll().single()
            assertEquals(49.9935, row.latitude!!, 1e-9)
            assertEquals("hidden-net", row.wifiSsid)
        }
}
