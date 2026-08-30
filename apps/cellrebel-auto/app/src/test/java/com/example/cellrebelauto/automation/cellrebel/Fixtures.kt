package com.example.cellrebelauto.automation.cellrebel

/**
 * ScreenNode fixtures mirroring the two operator-confirmed CellRebel states
 * (feature-discussions/2026-07-30-f001-prioritized-location-plan/README.md).
 *
 * # 镜像运营确认的两个 CellRebel 屏幕状态的节点树：
 * # - completed：两张分数卡渲染完成、有评级+数值、无处理覆盖层、Start 可用
 * # - running：覆盖层文本+进度条、Start 禁用、旧评级/分数仍残留可见
 */
object CellRebelFixtures {

    // # 节点构建小帮手
    private fun n(
        text: String? = null,
        desc: String? = null,
        className: String? = null,
        clickable: Boolean = false,
        enabled: Boolean = true,
        children: List<ScreenNode> = emptyList()
    ) = ScreenNode(text, desc, className, clickable, enabled, children)

    private fun startButton(enabled: Boolean) =
        n(text = "Start", className = "android.widget.Button", clickable = true, enabled = enabled)

    private fun progressBar() = n(className = "android.widget.ProgressBar")

    /**
     * Operator state 1 — COMPLETED:
     * both score cards fully rendered (rating + numeric score), no overlay,
     * Start active.
     * # 运营状态 1 — 完成态：两张分数卡完整（评级+数值）、无覆盖层、Start 可用
     */
    fun completed(): ScreenNode = n(
        className = "android.widget.FrameLayout",
        children = listOf(
            n(children = listOf(
                n(text = "Web Browsing Score"),
                n(text = "EXCELLENT"),
                n(text = "10.00")
            )),
            n(children = listOf(
                n(text = "Video Streaming Score"),
                n(text = "GOOD"),
                n(text = "7.50")
            )),
            startButton(enabled = true)
        )
    )

    /**
     * Operator state 2 — RUNNING:
     * "Processing results..." over the web card, "Measuring video streaming
     * quality..." over the video card, progress bars, Start disabled, and the
     * OLD EXCELLENT/10.00 still faintly present behind the overlay (INV-6).
     * # 运营状态 2 — 运行态：覆盖层文本+进度条、Start 禁用，
     * # 旧的 EXCELLENT/10.00 仍残留在覆盖层后面（INV-6）
     */
    fun running(): ScreenNode = n(
        className = "android.widget.FrameLayout",
        children = listOf(
            n(children = listOf(
                n(text = "Web Browsing Score"),
                n(text = "EXCELLENT"),
                n(text = "10.00"),
                n(text = "Processing results..."),
                progressBar()
            )),
            n(children = listOf(
                n(text = "Video Streaming Score"),
                n(text = "GOOD"),
                n(text = "7.50"),
                n(text = "Measuring video streaming quality..."),
                progressBar()
            )),
            startButton(enabled = false)
        )
    )

    /**
     * READY: test screen, Start enabled, no scores rendered yet, no markers.
     * # 就绪态：测试页、Start 可用、还没有分数、无运行标记
     */
    fun ready(): ScreenNode = n(
        className = "android.widget.FrameLayout",
        children = listOf(
            n(text = "Web Browsing Score"),
            n(text = "Video Streaming Score"),
            startButton(enabled = true)
        )
    )

    /**
     * Start disabled but no processing markers and no scores.
     * # 仅 Start 禁用（无处理标记、无分数）
     */
    fun startDisabledOnly(): ScreenNode = n(
        className = "android.widget.FrameLayout",
        children = listOf(
            n(text = "Web Browsing Score"),
            n(text = "Video Streaming Score"),
            startButton(enabled = false)
        )
    )

    /**
     * Operator state 2 with ALL processing markers stripped (M-CO-06 precondition:
     * SDK version that never emits the running marker). Old scores remain, Start disabled.
     * # 运行态去掉全部处理标记（M-CO-06：完全不出现 running marker 的 SDK），
     * # 旧分数残留、Start 禁用 —— INV-11 下绝不能因此判 RUNNING
     */
    fun runningWithoutMarkers(): ScreenNode = n(
        className = "android.widget.FrameLayout",
        children = listOf(
            n(children = listOf(
                n(text = "Web Browsing Score"),
                n(text = "EXCELLENT"),
                n(text = "10.00")
            )),
            n(children = listOf(
                n(text = "Video Streaming Score"),
                n(text = "GOOD"),
                n(text = "7.50")
            )),
            startButton(enabled = false)
        )
    )

    /**
     * Not the test screen at all: no Start button, no score labels, no markers.
     * # 完全不是测试页：无 Start、无分数标签、无标记
     */
    fun unknown(): ScreenNode = n(
        className = "android.widget.FrameLayout",
        children = listOf(n(text = "CellRebel"), n(text = "Home"))
    )
}
