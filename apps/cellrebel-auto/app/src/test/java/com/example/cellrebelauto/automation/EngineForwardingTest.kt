package com.example.cellrebelauto.automation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F8 regression: StateFlow forwarding collectors must be cancelled when a
 * single run returns — repeated plan runs must not leak collector jobs.
 * # F8 回归：转发 collector 必须随单次 run 结束取消，重复运行不泄漏
 */
class EngineForwardingTest {

    @Test
    fun `forwarders are cancelled when the run block returns, across repeated runs`() = runTest {
        val source = MutableStateFlow(0)
        var activeCollectors = 0
        var totalEmissions = 0

        // # 连续跑 3 次：若 collector 不取消，第一次 withForwarders 就永远不返回
        repeat(3) { round ->
            withForwarders(
                forwarders = listOf(
                    {
                        activeCollectors++
                        try {
                            source.collect { totalEmissions++ } // # 永不完成的流
                        } finally {
                            activeCollectors--
                        }
                    }
                )
            ) {
                // # 模拟 engine.run()：做一些事然后返回
                source.value = round
                kotlinx.coroutines.yield() // # 让 collector 先起跑（engine.run 内部同理挂起）
            }
            // # 每次 run 结束后：无存活 collector（无泄漏）
            assertEquals(0, activeCollectors)
        }

        // # 每轮只有一次发射被采集（若泄漏，后续轮次会有多个 collector 重复计数）
        assertEquals(3, totalEmissions)
    }
}
