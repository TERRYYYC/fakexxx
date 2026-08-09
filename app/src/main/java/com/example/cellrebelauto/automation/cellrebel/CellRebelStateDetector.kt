package com.example.cellrebelauto.automation.cellrebel

/**
 * CellRebel test-screen states, per the two operator-confirmed screenshots.
 * # CellRebel 测试页状态（依据运营确认的两张截图）
 */
enum class CellRebelScreenState { READY, RUNNING, COMPLETED, UNKNOWN }

/**
 * Scores extracted from a completed test screen.
 * # 从完成态屏幕解析出的分数
 */
data class ExtractedScores(
    val webBrowsingScore: Double,
    val videoStreamingScore: Double,
    val webBrowsingLabel: String = "",
    val videoStreamingLabel: String = ""
)

/**
 * Pure state classification over a flattened ScreenNode list (AC-B1/B2).
 *
 * RUNNING:   processing markers present, OR Start clickable-but-disabled.
 * COMPLETED: scores extractable AND no markers AND Start enabled.
 * READY:     Start enabled, no markers, no scores.
 * UNKNOWN:   everything else.
 *
 * Score presence alone is NEVER completion evidence (INV-6): stale scores
 * behind the running overlay stay RUNNING because markers / disabled Start win.
 *
 * # 纯状态分类器：分数存在本身绝不是完成证据（INV-6），
 * # 覆盖层残留的旧分数在标记存在或 Start 禁用时仍判为 RUNNING
 */
class CellRebelStateDetector {

    companion object {
        // # 运行中覆盖层标记文本
        // # 真机锚点（2026-08-02 moto g54 device-smoke）：此版本无 "Processing results"，
        // # web/video 阶段分别为 "Measuring web browsing quality…" / "Measuring video
        // # streaming quality…"（U+2026），按子串匹配兼容截图版文案
        private val RUNNING_MARKERS = listOf(
            "Processing results",
            "Measuring web browsing quality",
            "Measuring video streaming quality"
        )
        // # 分数标签
        private const val WEB_LABEL = "Web Browsing Score"
        private const val VIDEO_LABEL = "Video Streaming Score"
        // # 标签附近查找分数的窗口大小
        private const val PROXIMITY_RANGE = 5

        // # 文字评级到数值的映射
        private val RATING_TO_SCORE = mapOf(
            "excellent" to 9.0,
            "good" to 7.0,
            "fair" to 5.0,
            "poor" to 3.0,
            "bad" to 1.0
        )
    }

    /**
     * Classifies the screen state from a flattened node list.
     * # 从展平后的节点列表分类屏幕状态
     */
    fun classify(nodes: List<ScreenNode>): CellRebelScreenState {
        val hasMarker = nodes.any { n ->
            RUNNING_MARKERS.any { m -> n.text?.contains(m, ignoreCase = true) == true }
        }
        val start = nodes.firstOrNull { it.text.equals("Start", ignoreCase = true) && it.clickable }

        if (hasMarker || (start != null && !start.enabled)) return CellRebelScreenState.RUNNING
        if (extractScores(nodes) != null && start?.enabled == true) return CellRebelScreenState.COMPLETED
        if (start?.enabled == true) return CellRebelScreenState.READY
        return CellRebelScreenState.UNKNOWN
    }

    /**
     * True when the score section (labels) is present — even if values are
     * not yet parseable. Used by the lifecycle to detect SCORE_PARSE_FAILED.
     * # 分数区（标签）是否存在——即使数值尚不可解析，用于判断 SCORE_PARSE_FAILED
     */
    fun hasScoreLabels(nodes: List<ScreenNode>): Boolean =
        nodes.any { it.text?.contains(WEB_LABEL, ignoreCase = true) == true } ||
            nodes.any { it.text?.contains(VIDEO_LABEL, ignoreCase = true) == true }

    /**
     * Extracts web/video scores via label proximity (moved from CellRebelHandler).
     * Returns null unless BOTH scores parse.
     * # 标签邻近法解析两个分数（逻辑从 CellRebelHandler 迁来），缺一返回 null
     */
    fun extractScores(nodes: List<ScreenNode>): ExtractedScores? {
        var webLabel: String? = null
        var videoLabel: String? = null
        var webScore: Double? = null
        var videoScore: Double? = null

        for (i in nodes.indices) {
            val text = nodes[i].text ?: continue
            if (text.contains(WEB_LABEL, ignoreCase = true)) {
                val (score, label) = findNearbyScoreOrLabel(nodes, i)
                webScore = score
                webLabel = label
            }
            if (text.contains(VIDEO_LABEL, ignoreCase = true)) {
                val (score, label) = findNearbyScoreOrLabel(nodes, i)
                videoScore = score
                videoLabel = label
            }
        }

        return if (webScore != null && videoScore != null) {
            ExtractedScores(webScore, videoScore, webLabel ?: "", videoLabel ?: "")
        } else null
    }

    // # 在标签附近查找数值（0..10）或评级词：
    // # 先向前（同一张分数卡在 DFS 序中位于标签之后），再向后；
    // # 数值优先于评级词，避免邻近卡片的评级/数值串扰
    private fun findNearbyScoreOrLabel(
        nodes: List<ScreenNode>,
        labelIndex: Int
    ): Pair<Double?, String?> {
        val forward = labelIndex..minOf(nodes.size - 1, labelIndex + PROXIMITY_RANGE)
        val backward = (maxOf(0, labelIndex - PROXIMITY_RANGE) until labelIndex).reversed()
        val order = forward + backward

        // # 第一遍：数值
        for (i in order) {
            val text = nodes[i].text ?: continue
            val numScore = text.toDoubleOrNull()
            if (numScore != null && numScore in 0.0..10.0) return Pair(numScore, text)
        }
        // # 第二遍：文字评级
        for (i in order) {
            val text = nodes[i].text ?: continue
            val rating = RATING_TO_SCORE[text.lowercase().trim()]
            if (rating != null) return Pair(rating, text)
        }
        return Pair(null, null)
    }
}
