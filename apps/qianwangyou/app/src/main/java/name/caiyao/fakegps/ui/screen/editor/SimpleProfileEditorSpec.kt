package name.caiyao.fakegps.ui.screen.editor

/**
 * T11d 极简铁律的结构化闸门：简单模式编辑器可编辑字段的**完整**清单。
 *
 * 简单模式 = 名称 + 坐标 +（若挂路线）路线卡 +「高级字段 ▸」入口，没有别的。
 * UI 渲染直接遍历 [COLUMNS]，因此新增字段不可能只加进 UI 而绕过本合同
 * （[SimpleProfileEditorContractTest] 锁定：除「定位」组外任何一组都不得进入简单模式）。
 */
object SimpleProfileEditorSpec {
    val COLUMNS: Set<String> = setOf("latitude", "longitude")
}
