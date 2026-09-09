package name.caiyao.fakegps.ui.screen.editor

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.SpoofSettings
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.db.ProfileEntity
import name.caiyao.fakegps.data.db.ProfileEntityCodec
import name.caiyao.fakegps.data.repository.ProfileRepository
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
import java.io.File
import java.io.FileReader

/**
 * Issue #127 复现（Robolectric lane）：编辑器里把 WiFi SSID 切"不上报"（draft `--` token）→
 * 保存并验证 → 断言三件事：
 *  1. draft 侧：三态 reducer/split 保留 unavailable（纯 JVM，`wifi_ssid` 字段范围）；
 *  2. DB 侧：`temp.unavailable_fields` 落库、`wifi_ssid` 列保持 NULL；
 *  3. payload 侧：发布镜像 `unavailable` 数组含 `wifi_ssid`、`fields` 不含该键。
 *
 * 走真实 [ProfileEditorViewModel.save]（repo = AppDatabase 单例文件库 + 真实 ConfigPrefsSync
 * 发布），hook 读回按 XSharedPreferences 语义直接解析 prefs 文件（同 ModulesPublishReadbackTest）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class EditorUnavailablePersistenceTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AppDatabase.closeInstanceForTests()
        SpoofSettingsReset.run()
    }

    @After
    fun tearDown() {
        AppDatabase.closeInstanceForTests()
        Dispatchers.resetMain()
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun repo(): ProfileRepository =
        ProfileRepository(AppDatabase.getInstance(context()), context())

    /** What XSharedPreferences.getString(KEY_JSON, null) would return in the target process. */
    private fun hookReadPayload(): String? {
        val prefs = context().getSharedPreferences(ConfigPrefsSync.PREFS_NAME, Context.MODE_PRIVATE)
        val file = prefs.javaClass.getDeclaredField("mFile").apply { isAccessible = true }
            .get(prefs) as? File
            ?: return null
        if (!file.isFile) return null

        val parser = android.util.Xml.newPullParser()
        parser.setInput(FileReader(file))
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG &&
                parser.name == "string" &&
                parser.getAttributeValue(null, "name") == ConfigPrefsSync.KEY_JSON
            ) {
                return parser.nextText()
            }
            event = parser.next()
        }
        return null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20L)
        assertTrue(message, condition())
    }

    /** 纯 JVM 腿：`wifi_ssid` 在 split→entity 的字段范围与 `tac` 一致。 */
    @Test
    fun `wifi_ssid unavailable survives the draft to entity mapping on the jvm`() {
        val draft = mapOf(
            "latitude" to "50.45",
            "longitude" to "30.52",
            "wifi_ssid" to ProfileFieldDraft.UNAVAILABLE_TOKEN,
        )

        val split = ProfileFieldDraft.split(draft)
        assertEquals("split must carry wifi_ssid as unavailable", setOf("wifi_ssid"), split.unavailable)

        val entity = mapToEntity(draft, id = 3L, addname = "loc-ci")
        assertNull("unavailable field must not also be a typed value", entity.wifiSsid)
        assertEquals(
            "issue #127: unavailable_fields column must persist wifi_ssid",
            "[\"wifi_ssid\"]",
            entity.unavailableFields,
        )
        assertEquals(
            "--",
            ProfileEntityCodec.toDraft(entity)["wifi_ssid"],
        )
    }

    /** issue #127 主复现：VM save 路径 → DB 列 + 发布 payload。 */
    @Test
    fun `editor save with wifi_ssid unavailable persists the column and publishes it`() = runBlocking {
        // Device §7 scenario: an existing cellular profile (id like 3) edited in the editor.
        val db = AppDatabase.getInstance(context())
        val seededId = db.profileDao().insert(
            ProfileEntity(
                addname = "loc-ci",
                latitude = 50.4501,
                longitude = 30.5234,
                mcc = 255,
                mnc = 3,
                tac = 4101,
                ci = 13840,
                earfcn = 1650,
            ),
        )

        val vm = ProfileEditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        vm.load(seededId, 0.0, 0.0)
        await("editor loads the existing profile") { vm.fieldValues.value.containsKey("latitude") }

        // The editor's 不上报 button: onValueChange(UNAVAILABLE_TOKEN) → vm.updateField.
        vm.updateField("wifi_ssid", ProfileFieldDraft.UNAVAILABLE_TOKEN)
        assertEquals("--", vm.fieldValues.value["wifi_ssid"])
        assertTrue(
            "profile must stay valid so save is not blocked",
            ProfileFieldDraft.validationErrors(vm.fieldValues.value).isEmpty(),
        )

        vm.saveAndVerify()
        await("save completes") { !vm.saving.value }

        // Leg 2: DB column.
        val row = db.profileDao().getById(seededId)
        assertNotNull(row)
        assertNull("wifi_ssid column stays NULL (unavailable, not spoof)", row!!.wifiSsid)
        assertEquals(
            "issue #127: unavailable_fields must persist after editor save",
            "[\"wifi_ssid\"]",
            row.unavailableFields,
        )

        // Leg 3: the published payload the hook reads.
        val payload = hookReadPayload()
        assertNotNull("issue #127: publish must have written the payload", payload)
        val root = JSONObject(payload!!)
        val unavailable = root.getJSONArray("unavailable")
        val unavailableNames = buildList {
            for (i in 0 until unavailable.length()) add(unavailable.getString(i))
        }
        assertTrue(
            "issue #127: payload unavailable must contain wifi_ssid, was $unavailableNames",
            unavailableNames.contains("wifi_ssid"),
        )
        assertFalse(
            "unavailable field must not double-appear in fields",
            root.getJSONObject("fields").has("wifi_ssid"),
        )
    }

    /**
     * issue #127 的设备表现与"草稿在保存前被静默还原"完全一致（保存发布字节级相同的 payload、
     * DB 列为空、操作者看到的是未激活态的静态 hint）。唯一能走到这一幕的代码路径：编辑器
     * `LaunchedEffect(profileId) { vm.load(...) }` 的 DB 读取晚于操作者的第一次编辑完成，
     * `load()` 把 `_fieldValues` 无条件覆盖回库里旧行——"不上报" toggle 无声消失，随后的保存
     * 变成零影响写回。本测试确定性复现：load 在途时操作者切换 wifi_ssid=不上报，load 完成后
     * 编辑必须还在，且保存后照常落库+进 payload。
     */
    @Test
    fun `a late finishing load must not silently revert the operator's unavailable toggle`() = runBlocking {
        val db = AppDatabase.getInstance(context())
        val seededId = db.profileDao().insert(
            ProfileEntity(
                addname = "loc-ci",
                latitude = 50.4501,
                longitude = 30.5234,
                tac = 4101,
                ci = 13840,
            ),
        )

        val vm = ProfileEditorViewModel(ApplicationProvider.getApplicationContext<Application>())
        // Not awaited on purpose: the DB read is in flight while the operator starts editing.
        vm.load(seededId, 0.0, 0.0)
        vm.updateField("wifi_ssid", ProfileFieldDraft.UNAVAILABLE_TOKEN)
        assertEquals("--", vm.fieldValues.value["wifi_ssid"])

        // Give the in-flight DB read every chance to land and (wrongly) clobber the draft. With
        // the guard it is skipped, so the seeded row's columns never appear in the edited draft.
        Thread.sleep(500)

        assertEquals(
            "issue #127: a late load must not revert the operator's 不上报 toggle",
            "--",
            vm.fieldValues.value["wifi_ssid"],
        )
        assertFalse(
            "the guard must skip the stale row for the edited draft",
            vm.fieldValues.value.containsKey("latitude"),
        )

        vm.saveAndVerify()
        await("save completes") { !vm.saving.value }
        assertEquals(
            "issue #127: the toggle that survived the race must persist",
            "[\"wifi_ssid\"]",
            db.profileDao().getById(seededId)!!.unavailableFields,
        )
    }
}

private object SpoofSettingsReset {
    fun run() {
        SpoofSettings::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
    }
}
