package com.example.cellrebelauto.model.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0.1-5 计划-档案一致性判定 oracle。
 *
 * 实测痛点：51 行计划配 52 档案 → 逐行取档案时整体错位一档，attempts 一直"成功"
 * 但配额空转烧完，全程无任何预警。本判定要求：错配 → 显式警告（带两侧数字）；
 * 相等 → 无警告；任一侧取不到（null）→ 静默跳过。
 */
class PlanProfileConsistencyTest {

    @Test
    fun `51 rows against 52 profiles evaluates to a mismatch`() {
        val mismatch = PlanProfileConsistency.evaluate(planRows = 51, providerProfiles = 52)!!
        assertEquals(51, mismatch.planRows)
        assertEquals(52, mismatch.providerProfiles)
        // 警告必须同时展示两侧数字与可执行建议。
        assertTrue(mismatch.message.contains("51"))
        assertTrue(mismatch.message.contains("52"))
    }

    @Test
    fun `equal counts evaluate to no warning`() {
        assertNull(PlanProfileConsistency.evaluate(planRows = 51, providerProfiles = 51))
    }

    @Test
    fun `an unobtainable count on either side silently skips the check`() {
        assertNull(PlanProfileConsistency.evaluate(planRows = 51, providerProfiles = null))
        assertNull(PlanProfileConsistency.evaluate(planRows = null, providerProfiles = 52))
        assertNull(PlanProfileConsistency.evaluate(planRows = null, providerProfiles = null))
    }

    @Test
    fun `a zero-row plan never claims a mismatch`() {
        // 没有计划行数（导入失败/空表）时没有可比对象，不预警。
        assertNull(PlanProfileConsistency.evaluate(planRows = 0, providerProfiles = 52))
    }
}
