package name.caiyao.fakegps.ui

import name.caiyao.fakegps.verify.ObservationScope

/** Shared copy for verification scope and the editor's reference readings. No trust decisions. */
internal data class ObservationScopePresentation(
    val explanation: String,
    val observedLabel: String,
    val referenceLabel: String,
)

internal fun observationScopePresentation(scope: ObservationScope): ObservationScopePresentation =
    when (scope) {
        ObservationScope.SELF_HOOKED -> ObservationScopePresentation(
            explanation = "调试构建允许本模块 hook 自身进程；是否实际注入仍取决于 Vector/LSPosed 的启用和作用域。" +
                "基线读取会绕过本模块的 getter hook，但仍可能受系统模拟位置或其他模块影响。" +
                "若配置值恰好等于读取基线，该字段会标为「巧合」而不是「已生效」。" +
                "本进程的读回结果不能证明目标 App 内的 hook 是否生效。",
            observedLabel = "观测",
            referenceLabel = "本进程当前读到（调试构建允许自我 hook，可能已是伪造值）",
        )
        ObservationScope.HOOK_PROBE -> ObservationScopePresentation(
            explanation = "主界面进程中本模块未自 hook；本次观测来自独立、非导出的 :hook_verify 进程。" +
                "只有 Vector/LSPosed 已把模块注入该进程、且请求 ID 与配置指纹都匹配时，" +
                "这些公共 API 读回值才会进入字段判定。" +
                "探针结果仍可能受系统模拟位置或其他模块影响，不能证明目标 App 内的 hook 是否生效。",
            observedLabel = "探针观测",
            referenceLabel = "验证探针读到（已被 hook）",
        )
        ObservationScope.REAL_BASELINE -> ObservationScopePresentation(
            explanation = "本页展示本进程读取基线：本模块未自 hook，避免配置界面读回自身伪造值。" +
                "这些读数仍可能受系统模拟位置或其他模块影响，并不保证是设备的真实物理值。" +
                "它们仅供配置对照，不能证明目标 App 内的 hook 是否生效。",
            observedLabel = "本进程读取基线",
            referenceLabel = "本进程读取基线（本模块未自 hook；仍可能受系统模拟位置或其他模块影响）",
        )
    }
