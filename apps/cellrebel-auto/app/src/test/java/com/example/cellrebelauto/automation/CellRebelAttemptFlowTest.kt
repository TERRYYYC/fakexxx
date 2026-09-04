package com.example.cellrebelauto.automation

import com.example.cellrebelauto.automation.cellrebel.CellRebelFixtures
import com.example.cellrebelauto.automation.cellrebel.ScreenNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verified attempt-lifecycle tests driving CellRebelAttemptFlow against a
 * scripted fake driver with virtual time (AC-B2/B3/B5, INV-6/7).
 * # 已验证尝试生命周期测试：脚本化假驱动 + 虚拟时间
 */
class CellRebelAttemptFlowTest {

    /**
     * Scripted fake driver: each snapshot() consumes one frame; when the queue
     * runs out it keeps returning the last frame. Click effects are pre-scripted
     * via the frame sequence; counters record click/tap invocations.
     * # 脚本化假驱动：snapshot() 逐帧消费，耗尽后重复最后一帧；
     * # 点击效果由帧序列预先编排，计数器记录点击/坐标点按次数
     */
    private class FakeDriver(
        frames: List<ScreenNode?>,
        private val clickStartResult: Boolean = true,
        private val dispatchTapResult: Boolean = true,
        private val interactionEvents: MutableList<String>? = null
    ) : CellRebelDriver {
        private val frames = frames.toMutableList()
        var clickStartCount = 0
            private set
        var dispatchTapCount = 0
            private set

        override suspend fun snapshot(): ScreenNode? {
            val current = frames.firstOrNull()
            if (frames.size > 1) frames.removeAt(0)
            return current
        }

        override suspend fun clickStart(): Boolean {
            clickStartCount++
            interactionEvents?.add("ACTION_CLICK")
            return clickStartResult
        }

        override suspend fun dispatchStartTap(): Boolean {
            dispatchTapCount++
            interactionEvents?.add("COORDINATE_TAP")
            return dispatchTapResult
        }
    }

    /** # 虚拟时钟：只在 delayMs 时前进 */
    private class VirtualClock {
        var now = 0L
        val nowMs: () -> Long = { now }
        val delayMs: suspend (Long) -> Unit = { ms -> now += ms }
    }

    private fun newFlow(clock: VirtualClock) = CellRebelAttemptFlow(
        nowMs = clock.nowMs,
        delayMs = clock.delayMs
    )

    @Test
    fun `Start callback fires after the successful ACTION_CLICK boundary`() {
        val clock = VirtualClock()
        val events = mutableListOf<String>()
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),
                CellRebelFixtures.running(),
                CellRebelFixtures.completed(),
                CellRebelFixtures.completed()
            ),
            interactionEvents = events
        )

        kotlinx.coroutines.test.runTest {
            newFlow(clock).run(
                driver,
                startedAt = 0L,
                testTimeoutMs = 90_000L,
                onStartInteraction = { events += "START_CALLBACK" }
            )
        }

        assertEquals(listOf("ACTION_CLICK", "START_CALLBACK"), events)
    }

    @Test
    fun `failed ACTION_CLICK reports Start only after the coordinate fallback is dispatched`() {
        val clock = VirtualClock()
        val events = mutableListOf<String>()
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),
                CellRebelFixtures.running(),
                CellRebelFixtures.completed(),
                CellRebelFixtures.completed()
            ),
            clickStartResult = false,
            interactionEvents = events
        )

        kotlinx.coroutines.test.runTest {
            newFlow(clock).run(
                driver,
                startedAt = 0L,
                testTimeoutMs = 90_000L,
                onStartInteraction = { events += "START_CALLBACK" }
            )
        }

        assertEquals(listOf("ACTION_CLICK", "COORDINATE_TAP", "START_CALLBACK"), events)
    }

    @Test
    fun `failed ACTION_CLICK and failed coordinate fallback never report a Start interaction`() {
        val clock = VirtualClock()
        var startCallbacks = 0
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),
                CellRebelFixtures.unknown()
            ),
            clickStartResult = false,
            dispatchTapResult = false
        )

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = newFlow(clock).run(
                driver,
                startedAt = 0L,
                testTimeoutMs = 9_000L,
                onStartInteraction = { startCallbacks++ }
            )
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(0, startCallbacks)
        assertEquals(1, driver.clickStartCount)
        assertEquals(1, driver.dispatchTapCount)
    }

    @Test
    fun `never transitions to running yields NO_RUNNING_EVIDENCE after fallback tap`() {
        val clock = VirtualClock()
        val driver = FakeDriver(listOf(CellRebelFixtures.ready()))
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 15_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.NO_RUNNING_EVIDENCE, (outcome as AttemptOutcome.Failure).reason)
        // # AC-B3：ACTION_CLICK 一次 + 3 秒无 running 证据后坐标点按兜底一次
        assertEquals(1, driver.clickStartCount)
        assertEquals(1, driver.dispatchTapCount)
    }

    @Test
    fun `fallback tap starts run, identical scores to previous run are a valid success`() {
        val clock = VirtualClock()
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),      // t=0: pre-click 基线 READY（F1）
                CellRebelFixtures.ready(),      // t=0: 第一次 ACTION_CLICK 后仍 READY（点击无效）
                CellRebelFixtures.ready(),      // t=1500: 仍 READY
                CellRebelFixtures.running(),    // t=3000: 坐标点按兜底后进入 RUNNING
                CellRebelFixtures.completed(),  // 完成轮询 1（分数 10.00/7.50）
                CellRebelFixtures.completed()   // 完成轮询 2（稳定一致 → 采纳）
            )
        )
        val flow = newFlow(clock)
        var startCallbacks = 0

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(
                driver,
                startedAt = 0L,
                testTimeoutMs = 90_000L,
                onStartInteraction = { startCallbacks++ }
            )
        }

        // # INV-7：与"上一次运行"完全相同的分数同样是合法成功，不做跨尝试比较
        assertTrue(outcome is AttemptOutcome.Success)
        val success = outcome as AttemptOutcome.Success
        assertEquals(10.0, success.webScore, 0.001)
        assertEquals(7.5, success.videoScore, 0.001)
        // # AC-B2：记录了 runningObservedAt（t=3000 观察到 RUNNING）
        assertEquals(3000L, success.runningObservedAt)
        assertEquals(1, driver.clickStartCount)
        assertEquals(1, driver.dispatchTapCount)
        assertEquals("the delayed fallback must not re-report Start", 1, startCallbacks)
    }

    @Test
    fun `running persisting past timeout yields CELLREBEL_TIMEOUT`() {
        val clock = VirtualClock()
        // # 新鲜基线（F1）：先 READY，本次点击后进入 RUNNING 并一直保持
        val driver = FakeDriver(listOf(CellRebelFixtures.ready(), CellRebelFixtures.running()))
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 10_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.CELLREBEL_TIMEOUT, (outcome as AttemptOutcome.Failure).reason)
    }

    @Test
    fun `markers gone with unparseable scores yields SCORE_PARSE_FAILED`() {
        val clock = VirtualClock()
        // # 分数标签在但附近没有可解析的数值/评级（损坏的完成页）
        val brokenCompleted = ScreenNode(
            null, null, null, clickable = false, enabled = true,
            children = listOf(
                ScreenNode("Web Browsing Score", null, null, false, true),
                ScreenNode("Video Streaming Score", null, null, false, true),
                ScreenNode("Start", null, "android.widget.Button", clickable = true, enabled = true)
            )
        )
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),      // # 新鲜基线（F1）：先 READY
                CellRebelFixtures.running(),    // # 本次点击后进入 RUNNING
                brokenCompleted,
                brokenCompleted
            )
        )
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.SCORE_PARSE_FAILED, (outcome as AttemptOutcome.Failure).reason)
    }

    @Test
    fun `pre-existing RUNNING is rejected as stale and never counted as this attempt`() {
        // # F1 回归（INV-6 生命周期侧）：恢复后屏幕上残留的旧 RUNNING
        // # 绝不允许归属为本次 attempt——必须 typed failure，且不得触碰 Start
        val clock = VirtualClock()
        val driver = FakeDriver(listOf(CellRebelFixtures.running()))
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        assertTrue(outcome is AttemptOutcome.Failure)
        assertEquals(FailureReason.PRE_EXISTING_RUN, (outcome as AttemptOutcome.Failure).reason)
        // # 未发生任何 Start 交互：本次 attempt 什么都没做，什么也不认领
        assertEquals(0, driver.clickStartCount)
        assertEquals(0, driver.dispatchTapCount)
    }

    @Test
    fun `timeout budget anchors at lifecycle entry not at engine side attempt start`() {
        val clock = VirtualClock()
        // # F2 回归：GPS settle/切换已在引擎侧消耗 60s；审计 startedAt=0 保留，
        // # 但 CellRebel 的 deadline 必须锚在进入本生命周期的此刻
        clock.now = 60_000L
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),
                CellRebelFixtures.ready(),
                CellRebelFixtures.running(),
                CellRebelFixtures.completed(),
                CellRebelFixtures.completed()
            )
        )
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        // # 完整 90s 预算仍可用 → 正常成功；锚错位置会立刻 NO_RUNNING_EVIDENCE
        assertTrue(outcome is AttemptOutcome.Success)
        // # 审计 startedAt 原样保留
        assertEquals(0L, (outcome as AttemptOutcome.Success).startedAt)
    }

    @Test
    fun `completion requires two identical consecutive score polls`() {
        val clock = VirtualClock()
        val completedA = CellRebelFixtures.completed() // # 10.00 / 7.50
        val completedB = ScreenNode(
            null, null, null, clickable = false, enabled = true,
            children = listOf(
                ScreenNode(null, null, null, false, true, listOf(
                    ScreenNode("Web Browsing Score", null, null, false, true),
                    ScreenNode("GOOD", null, null, false, true),
                    ScreenNode("8.25", null, null, false, true)
                )),
                ScreenNode(null, null, null, false, true, listOf(
                    ScreenNode("Video Streaming Score", null, null, false, true),
                    ScreenNode("FAIR", null, null, false, true),
                    ScreenNode("6.00", null, null, false, true)
                )),
                ScreenNode("Start", null, "android.widget.Button", clickable = true, enabled = true)
            )
        )
        val driver = FakeDriver(
            listOf(
                CellRebelFixtures.ready(),   // # 新鲜基线（F1）：先 READY
                CellRebelFixtures.running(), // # 本次点击后进入 RUNNING
                completedA,  // t=0: 第一次完成轮询，分数 A
                completedB,  // t=1500: 分数变了 → 不采纳
                completedB   // t=3000: 第二次连续相同 → 采纳
            )
        )
        val flow = newFlow(clock)

        lateinit var outcome: AttemptOutcome
        kotlinx.coroutines.test.runTest {
            outcome = flow.run(driver, startedAt = 0L, testTimeoutMs = 90_000L)
        }

        assertTrue(outcome is AttemptOutcome.Success)
        val success = outcome as AttemptOutcome.Success
        // # 采纳的是稳定后的 B 分数
        assertEquals(8.25, success.webScore, 0.001)
        assertEquals(6.0, success.videoScore, 0.001)
        // # 不稳时点（t=0、t=1500）绝不能产出成功；成功只发生在稳定轮询 t=3000
        assertEquals(3000L, success.endedAt)
    }
}
