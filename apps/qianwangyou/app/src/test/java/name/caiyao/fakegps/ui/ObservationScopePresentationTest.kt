package name.caiyao.fakegps.ui

import name.caiyao.fakegps.verify.ObservationScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationScopePresentationTest {
    @Test
    fun baselineCopyDescribesAProcessReadingNotPhysicalTruth() {
        val copy = observationScopePresentation(ObservationScope.REAL_BASELINE)
        assertEquals("本进程读取基线", copy.observedLabel)
        assertEquals("本进程读取基线（本模块未自 hook；仍可能受系统模拟位置或其他模块影响）", copy.referenceLabel)
        assertTrue(copy.explanation.contains("本模块未自 hook"))
        assertTrue(copy.explanation.contains("系统模拟位置或其他模块"))
        assertTrue(copy.explanation.contains("不能证明目标 App 内的 hook 是否生效"))
        assertFalse(copy.explanation.contains("正式构建"))
        assertFalse(copy.explanation.contains("本机真实值"))
    }

    @Test
    fun probeCopyIsBuildNeutralAndKeepsItsEvidenceBoundary() {
        val copy = observationScopePresentation(ObservationScope.HOOK_PROBE)
        assertFalse(copy.explanation.contains("正式构建"))
        assertTrue(copy.explanation.contains(":hook_verify"))
        assertTrue(copy.explanation.contains("请求 ID 与配置指纹都匹配"))
        assertTrue(copy.explanation.contains("不能证明目标 App 内的 hook 是否生效"))
        assertEquals("探针观测", copy.observedLabel)
    }

    @Test
    fun selfHookCopyDistinguishesEligibilityFromFrameworkInjection() {
        val copy = observationScopePresentation(ObservationScope.SELF_HOOKED)
        assertTrue(copy.explanation.contains("允许本模块 hook 自身进程"))
        assertTrue(copy.explanation.contains("绕过本模块的 getter hook"))
        assertFalse(copy.explanation.contains("等于真值"))
        assertEquals("观测", copy.observedLabel)
    }

    @Test
    fun actualVariantUsesTheSameTruthfulCopyAsScreenConsumers() {
        val scope = ObservationScope.current()
        val copy = observationScopePresentation(scope)
        if (scope == ObservationScope.REAL_BASELINE) {
            assertEquals("本进程读取基线", copy.observedLabel)
            assertTrue(copy.referenceLabel.contains("系统模拟位置或其他模块"))
        } else {
            assertTrue(copy.explanation.contains("允许本模块 hook 自身进程"))
        }
    }
}
