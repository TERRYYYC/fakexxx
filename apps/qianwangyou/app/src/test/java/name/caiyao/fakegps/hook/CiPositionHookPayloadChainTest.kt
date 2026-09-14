package name.caiyao.fakegps.hook

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import name.caiyao.fakegps.config.ConfigPrefsSync
import name.caiyao.fakegps.data.db.AppDatabase
import name.caiyao.fakegps.data.importer.ProfileArchiveParser
import name.caiyao.fakegps.data.importer.ProfileImportAnalysis
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #189 CI 按位置 hook —— 载荷链路整链钉死：
 *
 * 4 列站点档案 CSV（addname,latitude,longitude,ci）
 *   → [ProfileArchiveParser]（第 4 列 ci 解析，十进制 28-bit ECI 零换算）
 *   → Room temp 表 ci 列写入（ProfileImportPlanner 的 insert 路径）
 *   → [ConfigPrefsSync.sync]（ContentProvider 游标全列透传，无 per-field 代码）
 *   → 传输文件 `fields.ci`（XSharedPreferences 载荷字节）
 *   → [Snapshot.fromJson]（hook 侧 serving cell 伪造源）。
 *
 * 调研结论（issuecomment-5657510546 §3）：schema 无需变更——fields 是游标全列
 * 透传、Snapshot.fromJson 本就读 ci，缺口只是"日程项 profile 行的 ci 列无人填"。
 * 本测试把"导入即生效"钉死：若有人在导入器、透传或 Snapshot 读取任一环断开 ci，
 * 这里红。
 *
 * 3 列旧档案对照：载荷无 `ci` 键、Snapshot.ci = null = passthrough——读回门
 * 与 hook 层的完全向后兼容形态。
 */
@RunWith(RobolectricTestRunner::class)
class CiPositionHookPayloadChainTest {

    /** #189 调研报告 §2 的真实 ECI 样本（运行台读数 37054241 同域）。 */
    private val eci = 37_054_241L

    @org.junit.Before
    @org.junit.After
    fun resetRoomSingletonAcrossRobolectricEnvironments() {
        // Robolectric 每个测试方法一套全新数据目录；AppDatabase 的静态单例若跨方法
        // 存活会指向已销毁环境的文件，provider/Room 两侧各自为政。官方提供的
        // test-only reset 让每个方法都拿到当前环境的磁盘 DB。
        AppDatabase.closeInstanceForTests()
    }

    private val fourColumnCsv = """
        addname,latitude,longitude,ci
        Lvivska-01,49.8397,24.0297,$eci
    """.trimIndent()

    private val threeColumnCsv = """
        addname,latitude,longitude
        legacy-01,49.8397,24.0297
    """.trimIndent()

    @Test
    fun `imported 4-column archive carries ci through temp to the payload and the hook snapshot`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        // 1) 导入解析：第 4 列 ci 直读
        val record = parseSingle(fourColumnCsv)
        assertEquals(eci, record.ci!!.toLong())

        // 2) temp 表 ci 列写入（磁盘 DB——AppInfoProvider 只认文件）
        val db = AppDatabase.getInstance(app)
        val insertedIds = runBlocking { db.profileDao().insertAll(listOf(record)) }
        val insertedId = insertedIds.single()

        // 3) 发布：游标全列透传（applyEnvironment 的 ConfigPrefsSync.sync 同一路径）
        val published = ConfigPrefsSync.sync(app, profileId = insertedId, clearIfMissing = false)
        // Robolectric 无 Vector 重定向：crossProcessReadable 不可达，发布收据必然 false；
        // 本链路验证的是载荷内容本身，所以直接读传输文件字节。
        assertFalse(published)

        val fields = readPublishedFields(app)
        assertEquals("payload must carry the imported ECI verbatim", eci, fields.getLong("ci"))

        // 4) hook 侧最终读数：Snapshot.fromJson（CellRebel getCi() 的伪造源）
        val snapshot = Snapshot.fromJson(fields)
        assertEquals(eci, snapshot.ci!!.toLong())
        // 坐标同链路完整性：位置与小区同帧到达
        assertEquals(49.8397, fields.getDouble("latitude"), 0.0)
        assertEquals(24.0297, fields.getDouble("longitude"), 0.0)
    }

    @Test
    fun `imported 3-column legacy archive publishes no ci and the hook snapshot stays passthrough`() {
        val app = ApplicationProvider.getApplicationContext<Application>()

        val record = parseSingle(threeColumnCsv)
        assertNull("3 列旧档案必须保持 ci=null（读回门跳过 ci 腿）", record.ci)

        val db = AppDatabase.getInstance(app)
        val insertedId = runBlocking { db.profileDao().insertAll(listOf(record)) }.single()
        ConfigPrefsSync.sync(app, profileId = insertedId, clearIfMissing = false)

        val fields = readPublishedFields(app)
        assertFalse("legacy archive must not carry a ci key in the payload", fields.has("ci"))

        val snapshot = Snapshot.fromJson(fields)
        assertNull("no ci configured = passthrough (real serving cell)", snapshot.ci)
    }

    /** 解析单行档案 CSV，格式非法即失败（不让坏输入静默变成断言错误）。 */
    private fun parseSingle(csv: String) =
        when (val analysis = ProfileArchiveParser().parse("site-table.csv", csv.toByteArray())) {
            is ProfileImportAnalysis.Ready -> analysis.records.single()
            is ProfileImportAnalysis.Invalid -> throw AssertionError(
                "import must parse: ${analysis.issues.joinToString { "${it.code}:${it.message}" }}",
            )
        }

    /** 读传输文件字节（#176 读回同源），解出载荷 `fields` 对象。 */
    private fun readPublishedFields(app: Application): JSONObject {
        val bytes = ConfigPrefsSync.readPublishedFileBytes(app)
        assertTrue("transport file must exist after sync", bytes != null && bytes.isNotEmpty())
        val xml = String(bytes!!, Charsets.UTF_8)
        val jsonRaw = Regex("""<string name="json">([^\x00]*?)</string>""")
            .find(xml)?.groupValues?.get(1)
            ?: throw AssertionError("transport file must embed the json key")
        val unescaped = jsonRaw
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
        val root = JSONObject(unescaped)
        return root.getJSONObject("fields")
    }
}
