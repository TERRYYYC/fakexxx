package name.caiyao.fakegps.ui.screen.collection

import name.caiyao.fakegps.data.db.ProfileSummary

/**
 * T11d：档案页「专家模式 ▸」的编辑对象——入口必须指向一个真实档案：
 * 生效档案优先（v3 原型 M2→M3 即编辑 loc-k1），无生效（或生效 id 已失效）回落第一个档案，
 * 空列表没有入口（返回 null，UI 不渲染按钮）。
 */
internal fun expertEntryTarget(profiles: List<ProfileSummary>, effectiveId: Long?): ProfileSummary? =
    profiles.firstOrNull { it.id == effectiveId } ?: profiles.firstOrNull()
