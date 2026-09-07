package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.support.FakeQwyEnvironment
import name.caiyao.fakegps.integration.v1.support.ProviderHarness
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * P0.1-5「一处导入、两侧生效」的 QWY 侧事实来源：discover() 必须把 provider 收藏档案
 * 集合投影为 profileRefs（元素 = profile-{dbId}），Auto 才能在导入计划 CSV 后经既有
 * AIDL 通道比对「计划行数 == 档案数」，在 51/52 错位一档空转烧配额之前给出警告。
 *
 * Killing mutation: profileRefs 恢复成硬编码 emptyList() —— 第一条断言红。
 */
class DiscoverProfileRefsTest {

    private lateinit var harness: ProviderHarness

    @Before
    fun setUp() {
        harness = ProviderHarness.create()
        harness.pair()
    }

    @Test
    fun `discover projects the provider profile collection as profileRefs`() {
        val refs = (1L..51L).map { "profile-$it" }
        harness.env.profileRefs = refs

        val snapshot = harness.handler.discover(ProviderHarness.AUTO_UID)

        assertEquals(refs, snapshot.profileRefs)
    }

    @Test
    fun `an empty profile collection projects no refs`() {
        val snapshot = harness.handler.discover(ProviderHarness.AUTO_UID)
        assertEquals(emptyList<String>(), snapshot.profileRefs)
    }
}
