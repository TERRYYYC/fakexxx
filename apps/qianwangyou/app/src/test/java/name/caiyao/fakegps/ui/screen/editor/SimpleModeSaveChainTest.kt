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
import name.caiyao.fakegps.data.db.ProfileEntity
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * T11d：简单模式必须能独立完成 W1 全流程——新建 → 填坐标（+名称）→ 保存 →（发布探针），
 * 全程不出现、也不依赖任何蜂窝/WiFi 字段。
 *
 * 走与专家编辑器同一条 [ProfileEditorViewModel.save] 链（#129 语义原样：校验失败未保存、
 * 写库成功后才发布、发布不可达如实提示），因此这里的断言全部落在持久化后果上：
 * DB 行只有 名称+坐标，无任何蜂窝/WiFi 列；发布的 payload 同样只有坐标字段。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SimpleModeSaveChainTest {

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

    /** W1 主链：新建档案，只填名称与坐标，仅保存 → 行入库 + payload 发布，零蜂窝/WiFi 字段。 */
    @Test
    fun `new profile from the simple editor saves name and coords and publishes without any cellular field`() =
        runBlocking {
            val vm = ProfileEditorViewModel(ApplicationProvider.getApplicationContext<Application>())
            vm.load(-1L, defaultLat = 24.0297, defaultLon = 49.8397)
            await("simple editor loads a new draft") { vm.fieldValues.value.containsKey("latitude") }

            vm.updateName("新机点位")
            vm.updateField("latitude", "50.450864")
            vm.updateField("longitude", "30.523367")
            assertTrue(
                "name+coords must validate on their own",
                ProfileFieldDraft.validationErrors(vm.fieldValues.value).isEmpty(),
            )

            vm.save() // 简单模式的「仅保存」——同一条链，#129 语义不变
            await("save completes") { !vm.saving.value }

            val rows = db().profileDao().getAll()
            assertEquals("exactly one profile is created", 1, rows.size)
            val row = rows.single()
            assertEquals("新机点位", row.addname)
            assertEquals(50.450864, row.latitude!!, 1e-9)
            assertEquals(30.523367, row.longitude!!, 1e-9)
            assertNull("simple mode never writes cellular columns", row.mcc)
            assertNull(row.cid)
            assertNull(row.tac)
            assertNull(row.wifiSsid)
            // Robolectric 拒绝 MODE_WORLD_READABLE（见 ModulesPublishReadbackTest 注），发布可达性
            // 这条腿在 JVM 上结构性失败 → sync() 返回 false。因此这里的【正确】结果正是 #129 的
            // PUBLISH_UNREACHABLE 文案：如实说“档案已保存”，而不是“保存失败”。DB 行与 payload
            // 断言（上文/下文）证明保存与数据面都成立。
            assertTrue(
                "the notice must be the honest #129 publish-unreachable copy",
                vm.notice.value?.contains("档案已保存") == true,
            )

            // 发布探针：payload 与 DB 同构——只有坐标，没有蜂窝/WiFi 键。
            val payload = hookReadPayload()
            assertNotNull("simple save must publish the payload", payload)
            val fields = JSONObject(payload!!).getJSONObject("fields")
            assertTrue(fields.has("latitude"))
            assertTrue(fields.has("longitude"))
            assertFalse(fields.has("mcc"))
            assertFalse(fields.has("cid"))
            assertFalse(fields.has("wifi_ssid"))
        }

    /** 编辑既有路线档案（简单模式）：路线列必须原样带回（P3.1 round-trip 在简单链上同样成立）。 */
    @Test
    fun `simple editor save keeps the route column of an existing route profile`() = runBlocking {
        val seededId = db().profileDao().insert(
            ProfileEntity(
                addname = "loc-route",
                latitude = 50.4501,
                longitude = 30.5234,
                routeWaypointsJson =
                    "[{\"lat\":50.4501,\"lng\":30.5234},{\"lat\":50.4398,\"lng\":30.5327}]",
            ),
        )

        val vm = ProfileEditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.load(seededId, 0.0, 0.0)
        await("route profile loads") { vm.routeSummary.value != null }
        assertEquals(2, vm.routeSummary.value!!.waypointCount)

        vm.updateField("latitude", "50.4600")
        vm.save()
        await("save completes") { !vm.saving.value }

        val row = db().profileDao().getById(seededId)
        assertNotNull(row)
        assertEquals(
            "route waypoints must round-trip through a simple-mode save",
            "[{\"lat\":50.4501,\"lng\":30.5234},{\"lat\":50.4398,\"lng\":30.5327}]",
            row!!.routeWaypointsJson,
        )
    }

    /** #127 同型竞态在名称上的版本：load 在途时输入的名称不得被迟到的行静默还原。 */
    @Test
    fun `a late finishing load must not silently revert the operator's typed name`() = runBlocking {
        val seededId = db().profileDao().insert(
            ProfileEntity(addname = "loc-ci", latitude = 50.4501, longitude = 30.5234),
        )

        val vm = ProfileEditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.load(seededId, 0.0, 0.0)
        vm.updateName("我的位置")
        Thread.sleep(500)

        assertEquals(
            "the typed name must survive a late load",
            "我的位置",
            vm.profileName.value,
        )

        vm.save()
        await("save completes") { !vm.saving.value }
        assertEquals("我的位置", db().profileDao().getById(seededId)!!.addname)
    }

    /** 名称留空 = 回落到坐标自动命名（导入档案的自定义名在未编辑时原样保留，已有测试覆盖）。 */
    @Test
    fun `clearing the name falls back to the generated coords name`() = runBlocking {
        val vm = ProfileEditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.load(-1L, 50.4501, 30.5234)
        await("new draft loads") { vm.fieldValues.value.containsKey("latitude") }

        vm.updateName("临时名")
        vm.updateName("")
        vm.save()
        await("save completes") { !vm.saving.value }

        val row = db().profileDao().getAll().single()
        assertNotNull("blank name must regenerate from coords", row.addname)
        assertTrue(row.addname!!.contains("50.4501"))
    }

    private fun hookReadPayload(): String? = HookPayloadReadback.read(context())
}

/** EditorUnavailablePersistenceTest 同款读法抽出：模拟 hook 进程 XSharedPreferences 读 payload。 */
private object HookPayloadReadback {
    fun read(context: android.content.Context): String? {
        val prefs = context.getSharedPreferences(
            name.caiyao.fakegps.config.ConfigPrefsSync.PREFS_NAME,
            android.content.Context.MODE_PRIVATE,
        )
        val file = try {
            prefs.javaClass.getDeclaredField("mFile").apply { isAccessible = true }
                .get(prefs) as? java.io.File
        } catch (_: Exception) {
            null
        } ?: return null
        if (!file.isFile) return null

        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.FileReader(file))
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "string" &&
                parser.getAttributeValue(null, "name") ==
                name.caiyao.fakegps.config.ConfigPrefsSync.KEY_JSON
            ) {
                return parser.nextText()
            }
            event = parser.next()
        }
        return null
    }
}
