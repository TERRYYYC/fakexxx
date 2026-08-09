package com.example.cellrebelauto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sanity test to verify the unit-test source set and toolchain work.
 * # 验证单测源集与工具链可用的冒烟测试
 */
class SanityTest {
    @Test
    fun sanity() {
        assertEquals(4, 2 + 2)
    }
}
