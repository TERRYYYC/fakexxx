package com.example.cellrebelauto.automation.cellrebel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CellRebel screen-state detector tests (AC-B1/B2, INV-6).
 * # CellRebel 屏幕状态检测器测试：旧分数绝不作为完成证据
 */
class CellRebelStateDetectorTest {

    private val detector = CellRebelStateDetector()

    @Test
    fun `completed fixture classifies as COMPLETED with both scores extracted`() {
        val nodes = CellRebelFixtures.completed().flatten()

        assertEquals(CellRebelScreenState.COMPLETED, detector.classify(nodes))

        val scores = detector.extractScores(nodes)
        assertNotNull(scores)
        assertEquals(10.0, scores!!.webBrowsingScore, 0.001)
        assertEquals(7.5, scores.videoStreamingScore, 0.001)
    }

    @Test
    fun `running fixture with stale scores classifies as RUNNING and scores are not completion`() {
        val nodes = CellRebelFixtures.running().flatten()

        // # INV-6：旧 EXCELLENT/10.00 残留 + Start 禁用 + 处理标记 → 必须是 RUNNING
        assertEquals(CellRebelScreenState.RUNNING, detector.classify(nodes))

        // # 分数文本确实能被解析出来（它们是真实的旧值），
        // # 但生命周期只允许在 classify == COMPLETED 时采纳分数 —— 调用方靠状态门控
        assertNotNull(detector.extractScores(nodes))
        assertTrue(detector.classify(nodes) != CellRebelScreenState.COMPLETED)
    }

    @Test
    fun `start disabled alone is RUNNING evidence`() {
        val nodes = CellRebelFixtures.startDisabledOnly().flatten()
        assertEquals(CellRebelScreenState.RUNNING, detector.classify(nodes))
    }

    @Test
    fun `start enabled without scores is READY`() {
        val nodes = CellRebelFixtures.ready().flatten()
        assertEquals(CellRebelScreenState.READY, detector.classify(nodes))
        assertNull(detector.extractScores(nodes))
    }

    @Test
    fun `screen without start button or scores is UNKNOWN`() {
        val nodes = CellRebelFixtures.unknown().flatten()
        assertEquals(CellRebelScreenState.UNKNOWN, detector.classify(nodes))
    }

    @Test
    fun `real device web progress marker alone classifies as RUNNING`() {
        // # 真机证据（moto g54, 2026-08-02 device-smoke）：此版本 CellRebel 没有
        // # "Processing results..."，web 阶段标记是 "Measuring web browsing quality…"
        // #（U+2026 省略号，resource-id web_progress_text）。仅该标记 + Start enabled 也必须判 RUNNING
        val nodes = listOf(
            ScreenNode(
                text = "Measuring web browsing quality…",
                contentDescription = null,
                className = "android.widget.TextView",
                clickable = false, enabled = true
            ),
            ScreenNode(
                text = "Start",
                contentDescription = null,
                className = "android.widget.Button",
                clickable = true, enabled = true
            )
        )
        assertEquals(CellRebelScreenState.RUNNING, detector.classify(nodes))
    }

    @Test
    fun `extractScores maps rating words to numeric fallback scores`() {
        // # 只有评级词、没有数值时也要能解析
        val nodes = ScreenNode(
            null, null, null, clickable = false, enabled = true,
            children = listOf(
                ScreenNode("Web Browsing Score", null, null, false, true),
                ScreenNode("Excellent", null, null, false, true),
                ScreenNode("Video Streaming Score", null, null, false, true),
                ScreenNode("Poor", null, null, false, true)
            )
        ).flatten()

        val scores = detector.extractScores(nodes)
        assertNotNull(scores)
        assertEquals(9.0, scores!!.webBrowsingScore, 0.001)
        assertEquals(3.0, scores.videoStreamingScore, 0.001)
    }

    @Test
    fun `hasScoreLabels detects the score section even when values are unparseable`() {
        // # 标签在但附近无数值/评级 → 分数段存在但不可解析（供 SCORE_PARSE_FAILED 判断）
        val nodes = ScreenNode(
            null, null, null, clickable = false, enabled = true,
            children = listOf(
                ScreenNode("Web Browsing Score", null, null, false, true),
                ScreenNode("Video Streaming Score", null, null, false, true)
            )
        ).flatten()

        assertTrue(detector.hasScoreLabels(nodes))
        assertNull(detector.extractScores(nodes))
    }
}
