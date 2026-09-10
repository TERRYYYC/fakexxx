package name.caiyao.fakegps.ui.screen.editor

import name.caiyao.fakegps.data.model.FieldSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T11d 极简铁律的结构化闸门：简单模式编辑器只暴露 名称 + 坐标（纬度/经度）+（路线卡）。
 * 该合同由 [SimpleProfileEditorSpec] 供 UI 渲染直接引用（不可漂移），并在此锁定：
 * 除「定位」组外，任何 14 组字段（蜂窝/WiFi/IP/运营商/信号…）都不得进入简单模式。
 */
class SimpleProfileEditorContractTest {

    @Test
    fun `simple editor exposes exactly latitude and longitude`() {
        assertEquals(setOf("latitude", "longitude"), SimpleProfileEditorSpec.COLUMNS)
    }

    @Test
    fun `no category other than location leaks a column into simple mode`() {
        val leaked = FieldSpec.allCategories()
            .filterKeys { it != "定位" }
            .flatMap { it.value }
            .filter { it.dbColumn in SimpleProfileEditorSpec.COLUMNS }
            .map { it.dbColumn }

        assertTrue("simple mode must not expose $leaked", leaked.isEmpty())
    }

    @Test
    fun `simple columns all belong to the location category`() {
        val locationColumns = FieldSpec.allCategories()["定位"]!!.map { it.dbColumn }.toSet()
        assertTrue(
            "simple columns must be editable location fields",
            SimpleProfileEditorSpec.COLUMNS.all { it in locationColumns },
        )
    }
}
