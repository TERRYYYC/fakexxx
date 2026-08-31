package com.example.cellrebelauto.automation

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.cellrebelauto.automation.cellrebel.ScreenNode
import com.example.cellrebelauto.automation.cellrebel.toScreenNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Handles all CellRebel app interaction: launch → navigate → verified attempt lifecycle.
 * Launch/navigation use the battle-tested bridge path (MIUI recents switching);
 * the attempt lifecycle (Start → RUNNING evidence → stable COMPLETED) runs on
 * [CellRebelAttemptFlow] behind the [CellRebelDriver] seam.
 *
 * # CellRebel 应用交互处理器：启动 → 导航 → 已验证的尝试生命周期
 * # 启动/导航保留久经考验的桥接路径（MIUI 最近任务切换）；
 * # 尝试生命周期（Start → RUNNING 证据 → 稳定完成）通过 CellRebelDriver 接缝
 * # 交给 CellRebelAttemptFlow，可在 JVM 上用假驱动单测
 */
class CellRebelHandler(
    private val bridge: AccessibilityBridge,
    private val onLog: (String) -> Unit,
    private val attemptFlow: CellRebelAttemptFlow = CellRebelAttemptFlow(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) : CellRebelRunner {
    companion object {
        const val PACKAGE = "com.cellrebel.mobile"
        private const val TAG = "CellRebelHandler"
        // # 轮询间隔（毫秒）
        private const val POLL_INTERVAL = 1500L
        // # 等待应用启动的超时时间（含 MIUI 弹窗自动放行的时间）
        private const val APP_LAUNCH_TIMEOUT = 20_000L
        // # MIUI SecurityCenter 包名
        private const val MIUI_SECURITY_PKG = "com.miui.securitycenter"
        // # 导航到测试页面的超时时间
        private const val NAVIGATION_TIMEOUT = 20_000L
    }

    /**
     * Runs a complete verified CellRebel test attempt.
     * Returns a typed [AttemptOutcome]; failures always carry a [FailureReason].
     *
     * # 运行一次完整且经过验证的 CellRebel 测试尝试。
     * # 返回类型化结果，失败必带原因。
     *
     * # [2026-07-31] F001：用真实 testTimeoutMs 取代原先被忽略的 collectDelayMs
     * # 与固定 30s 等待；成功必须观察到 RUNNING → 稳定 COMPLETED 转换（AC-B2）。
     */
    override suspend fun runTest(
        startedAt: Long,
        testTimeoutMs: Long,
        onRunningObserved: suspend (Long) -> Unit
    ): AttemptOutcome = runTest(
        startedAt = startedAt,
        testTimeoutMs = testTimeoutMs,
        onStartDispatched = {},
        onRunningObserved = onRunningObserved,
    )

    override suspend fun runTest(
        startedAt: Long,
        testTimeoutMs: Long,
        onStartDispatched: suspend () -> Unit,
        onRunningObserved: suspend (Long) -> Unit
    ): AttemptOutcome {
        // # 第 1 步：启动应用并等待前台 + 导航到测试页面
        try {
            log("Launching CellRebel...")
            launchAndWaitForForeground()
            log("Navigating to test screen...")
            navigateToTestScreen()
        } catch (e: CancellationException) {
            throw e // # 不拦截取消
        } catch (e: Exception) {
            log("ERROR: launch/navigation failed: ${e.message}")
            return AttemptOutcome.Failure(
                reason = FailureReason.FOREGROUND_SWITCH_FAILED,
                detail = e.message,
                startedAt = startedAt,
                endedAt = nowMs()
            )
        }

        // # 第 2 步：已验证的尝试生命周期（点击 Start → RUNNING 证据 → 稳定完成）
        log("Starting verified attempt lifecycle...")
        return attemptFlow.run(
            AndroidCellRebelDriver(bridge, onLog = ::log),
            startedAt,
            testTimeoutMs,
            onStartDispatched = onStartDispatched,
            onRunningObserved = onRunningObserved
        )
    }

    // ---- Launch / navigation (bridge-based, MIUI-proven) ----

    /**
     * Launches CellRebel and polls until it's in the foreground.
     * # 启动 CellRebel 并轮询直到它出现在前台
     *
     * # [2026-04-02] MIUI 会静默拦截从 AccessibilityService 发起的 startActivity，
     * # 当前台是第三方 app 时连 launchSelf() 都被封杀。
     * # 解决方案：通过最近任务（GLOBAL_ACTION_RECENTS）切换，
     * # 这走系统 UI 层面，MIUI 不会拦截。
     */
    private suspend fun launchAndWaitForForeground() {
        if (bridge.getCurrentPackage() == PACKAGE) {
            delay(1000)
            return
        }

        val selfPkg = bridge.getServicePackageName()
        val currentPkg = bridge.getCurrentPackage()

        // # 第三方 app 在前台时，startActivity 被 MIUI 封杀，走最近任务切换
        if (currentPkg != null && currentPkg != selfPkg && currentPkg != PACKAGE) {
            log("[MIUI] foreground=$currentPkg, switching via Recent Apps...")
            val switched = switchViaRecents()
            if (switched && bridge.getCurrentPackage() == PACKAGE) {
                log("CellRebel is foreground (via recents)")
                delay(1000)
                return
            }
        }

        // # 回退：直接 startActivity（当我们自己在前台时能工作）
        bridge.launchApp(PACKAGE)
        log("Launch CellRebel intent sent (direct)")

        // # 等待 CellRebel 出现在前台
        val launched = kotlinx.coroutines.withTimeoutOrNull(APP_LAUNCH_TIMEOUT) {
            while (true) {
                delay(1500)
                val pkg = bridge.getCurrentPackage()
                if (pkg == PACKAGE) {
                    log("CellRebel is foreground")
                    return@withTimeoutOrNull true
                }
                if (pkg == MIUI_SECURITY_PKG) {
                    log("[MIUI] Confirmation dialog showing, waiting for auto-dismiss...")
                    continue
                }
                log("[DIAG] foreground=$pkg, retrying via recents...")
                switchViaRecents()
            }
        }

        if (launched != true) {
            error("Failed to launch CellRebel after ${APP_LAUNCH_TIMEOUT / 1000}s (foreground=${bridge.getCurrentPackage()})")
        }
        delay(1000)
    }

    /**
     * Opens Recent Apps, finds CellRebel's card, and taps it.
     * Returns true if the card was found and tapped.
     *
     * # 打开最近任务 → 找到 CellRebel 卡片 → 点击
     * # 返回 true 表示找到并点击了卡片
     */
    private suspend fun switchViaRecents(): Boolean {
        bridge.openRecents()
        delay(1500) // # 等待最近任务动画完成

        for (attempt in 1..3) {
            val root = bridge.getRootNode()
            if (root == null) {
                delay(500)
                continue
            }

            val card = findCellRebelInRecents(root)
            if (card != null) {
                log("Found CellRebel in recents, tapping...")
                bridge.clickNode(card)
                delay(300)
                // # dispatchTap 兜底
                val (cx, cy) = bridge.getNodeCenter(card)
                bridge.dispatchTap(cx, cy)
                delay(1500) // # 等待切换动画
                return true
            }

            // # 第一次找不到时输出诊断信息
            if (attempt == 1) {
                val allNodes = NodeFinder.flatten(root)
                val texts = allNodes.mapNotNull { it.text?.toString() }
                    .take(20).joinToString(" | ")
                log("[DIAG] Recents texts: [$texts]")
                val descs = allNodes.mapNotNull { it.contentDescription?.toString() }
                    .take(20).joinToString(" | ")
                log("[DIAG] Recents descriptions: [$descs]")
            }
            delay(500)
        }

        log("CellRebel not found in recents")
        bridge.goBack() // # 关闭最近任务
        delay(500)
        return false
    }

    /**
     * Searches for CellRebel's card in the recents accessibility tree.
     * # 在最近任务的无障碍树中查找 CellRebel 的卡片
     */
    private fun findCellRebelInRecents(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // # 按 app 名称搜索（text 和 contentDescription 都试）
        return NodeFinder.findByText(root, "CellRebel")
            ?: NodeFinder.findByText(root, "Cell Rebel")
            ?: NodeFinder.findByContentDescription(root, "CellRebel")
            ?: NodeFinder.findByContentDescription(root, "Cell Rebel")
    }

    /**
     * Navigates to the test screen. If already there, does nothing.
     * Otherwise opens the menu and clicks "Connection Test".
     *
     * # 导航到测试页面。如果已经在测试页面则跳过，
     * # 否则打开菜单点击 "Connection Test"
     */
    private suspend fun navigateToTestScreen() {
        withTimeout(NAVIGATION_TIMEOUT) {
            while (true) {
                val root = bridge.getRootNode() ?: run { delay(POLL_INTERVAL); continue }

                // # 检查是否已在测试页面（有 Start 按钮 + 有分数标签）
                if (isTestScreen(root)) {
                    log("Already on test screen")
                    return@withTimeout
                }

                // # 尝试通过菜单导航
                val menuItem = NodeFinder.findByText(root, "Connection Test")
                if (menuItem != null) {
                    log("Found 'Connection Test' menu item, clicking...")
                    bridge.clickNode(menuItem)
                    delay(2000)
                    continue
                }

                // # 尝试打开菜单（汉堡菜单或导航按钮）
                val menuBtn = NodeFinder.findByContentDescription(root, "Menu")
                    ?: NodeFinder.findByContentDescription(root, "Navigate up")
                    ?: NodeFinder.findByContentDescription(root, "Open navigation")
                if (menuBtn != null) {
                    log("Opening menu...")
                    bridge.clickNode(menuBtn)
                    delay(1500)
                    continue
                }

                delay(POLL_INTERVAL)
            }
        }
    }

    // ---- Detection helpers ----

    /**
     * Checks if we're on the CellRebel test screen.
     * # 检查是否在测试页面（需要同时有 Start 按钮和分数标签）
     */
    private fun isTestScreen(root: AccessibilityNodeInfo): Boolean {
        val hasStart = NodeFinder.findByText(root, "Start", clickable = true) != null
        val hasScoreLabel = NodeFinder.containsText(root, "Web Browsing Score") ||
            NodeFinder.containsText(root, "Video Streaming Score") ||
            NodeFinder.containsText(root, "Connection Test")
        return hasStart && hasScoreLabel
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        onLog(msg)
    }
}

/**
 * Android [CellRebelDriver]: snapshots the a11y tree as ScreenNode and clicks
 * Start via ACTION_CLICK, with a coordinate tap as the AC-B3 fallback.
 * # CellRebelDriver 的 Android 实现：快照无障碍树为 ScreenNode，
 * # ACTION_CLICK 点击 Start，坐标点按作为 AC-B3 兜底
 */
class AndroidCellRebelDriver(
    private val bridge: AccessibilityBridge,
    private val onLog: (String) -> Unit = {}
) : CellRebelDriver {

    override suspend fun snapshot(): ScreenNode? =
        bridge.getRootNode()?.toScreenNode()

    override suspend fun clickStart(): Boolean {
        val startNode = findStartNode() ?: run {
            onLog("ERROR: Start button not found for ACTION_CLICK")
            return false
        }
        val clicked = bridge.clickNode(startNode)
        onLog("ACTION_CLICK dispatched (result=$clicked)")
        return clicked
    }

    override suspend fun dispatchStartTap(): Boolean {
        val startNode = findStartNode() ?: run {
            onLog("ERROR: Start button not found for coordinate tap")
            return false
        }
        val (cx, cy) = bridge.getNodeCenter(startNode)
        onLog("dispatchTap($cx, $cy) dispatched as Start fallback")
        return bridge.dispatchTap(cx, cy)
    }

    // # 查找可点击的 Start 按钮节点
    private fun findStartNode(): AccessibilityNodeInfo? {
        val root = bridge.getRootNode() ?: return null
        return NodeFinder.findByText(root, "Start", clickable = true)
            ?: NodeFinder.findByText(root, "Start")
    }
}
