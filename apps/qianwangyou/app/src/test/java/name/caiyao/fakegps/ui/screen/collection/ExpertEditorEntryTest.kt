package name.caiyao.fakegps.ui.screen.collection

import name.caiyao.fakegps.data.db.ProfileSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * T11d：档案页「专家模式 ▸」的可达性——入口总是指向一个真实档案：
 * 生效档案优先（v3 原型 M2→M3 即编辑 loc-k1），无生效则回落第一个档案，空列表无入口。
 */
class ExpertEditorEntryTest {

    private fun profile(id: Long, name: String) =
        ProfileSummary(id = id, addname = name, latitude = 50.45, longitude = 30.52)

    @Test
    fun `the effective profile is the expert entry target`() {
        val profiles = listOf(profile(1, "loc-k1"), profile(2, "loc-k2"))
        assertEquals(2L, expertEntryTarget(profiles, effectiveId = 2L)!!.id)
    }

    @Test
    fun `the first profile is the fallback when nothing is effective`() {
        val profiles = listOf(profile(7, "loc-a"), profile(9, "loc-b"))
        assertEquals(7L, expertEntryTarget(profiles, effectiveId = null)!!.id)
    }

    @Test
    fun `a stale effective id falls back to the first profile`() {
        val profiles = listOf(profile(7, "loc-a"), profile(9, "loc-b"))
        assertEquals(7L, expertEntryTarget(profiles, effectiveId = 404L)!!.id)
    }

    @Test
    fun `an empty collection has no expert entry`() {
        assertNull(expertEntryTarget(emptyList(), effectiveId = null))
    }
}
