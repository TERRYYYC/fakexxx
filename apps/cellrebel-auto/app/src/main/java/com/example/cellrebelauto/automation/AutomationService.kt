package com.example.cellrebelauto.automation

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.cellrebelauto.automation.aplus.APlusAttemptDriver
import com.example.cellrebelauto.automation.aplus.APlusBackend
import com.example.cellrebelauto.automation.plan.BufferGate
import com.example.cellrebelauto.data.PlanConfigStore
import com.example.cellrebelauto.db.AppDatabase
import com.example.cellrebelauto.model.AutomationState
import com.example.cellrebelauto.model.plan.StageToggles
import com.example.cellrebelauto.repository.PlanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Android AccessibilityService that hosts the automation engine.
 * Thin wrapper — all logic lives in AutomationEngine and Handlers.
 *
 * # 无障碍服务：承载自动化引擎的 Android 系统服务
 * # 自身逻辑很薄，所有业务逻辑在 Engine 和 Handler 中
 *
 * Communication with UI:
 *   - Companion object exposes StateFlow fields for Compose to collect
 *   - startAutomation() / stopAutomation() for user control
 *
 * # 与 UI 的通信：
 * #   - 通过 companion object 的 StateFlow 让 Compose 采集状态
 * #   - startAutomation() / stopAutomation() 供用户控制
 */
class AutomationService : AccessibilityService() {

    // # 服务级别的协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // # 当前运行的自动化任务
    private var automationJob: Job? = null
    // # 当前引擎实例
    private var engine: AutomationEngine? = null
    // # MIUI 弹窗去抖：上次自动点击"允许"的时间戳
    private var lastMiuiDismissTime = 0L

    companion object {
        private const val TAG = "AutomationSvc"
        // # MIUI SecurityCenter 包名（后台启动拦截弹窗的来源）
        private const val MIUI_SECURITY_PKG = "com.miui.securitycenter"

        // # 服务实例引用（供 UI 调用）
        private var instance: AutomationService? = null

        // ---- Shared state (collected by UI) ----

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _currentState = MutableStateFlow(AutomationState.IDLE)
        val currentState: StateFlow<AutomationState> = _currentState

        private val _cycleCount = MutableStateFlow(0)
        val cycleCount: StateFlow<Int> = _cycleCount

        private val _logs = MutableStateFlow<List<String>>(emptyList())
        val logs: StateFlow<List<String>> = _logs

        // # 服务是否已连接（用于 UI 检查）
        private val _isServiceConnected = MutableStateFlow(false)
        val isServiceConnected: StateFlow<Boolean> = _isServiceConnected

        // # 当前任务快照（Run 页状态卡）
        private val _currentTask = MutableStateFlow<EngineTaskSnapshot?>(null)
        val currentTask: StateFlow<EngineTaskSnapshot?> = _currentTask

        // # scheduler 缓冲倒计时投影（Run 页 cooldown 卡）
        private val _cooldown = MutableStateFlow<CooldownInfo?>(null)
        val cooldown: StateFlow<CooldownInfo?> = _cooldown

        // # 最近一次失败尝试（Run 页 last failure 行）
        private val _lastFailure = MutableStateFlow<LastFailureInfo?>(null)
        val lastFailure: StateFlow<LastFailureInfo?> = _lastFailure

        /**
         * Starts automation for the given plan.
         * # 启动指定位置计划的自动化
         */
        fun startAutomation(planId: Long) {
            instance?.startWithPlan(planId) ?: run {
                Log.e(TAG, "Service not connected — cannot start")
            }
        }

        /**
         * Stops the running automation.
         * # 停止正在运行的自动化
         */
        fun stopAutomation() {
            instance?.stopRunning()
        }

        /**
         * Returns the current foreground window's root node for tree dump.
         * # 返回当前前台窗口的根节点，用于无障碍树 dump
         */
        fun getRootNodeForDump(): android.view.accessibility.AccessibilityNodeInfo? {
            return instance?.rootInActiveWindow
        }

        /**
         * Returns the current foreground package name.
         * # 返回当前前台应用的包名
         */
        fun getCurrentForegroundPackage(): String? {
            return instance?.rootInActiveWindow?.packageName?.toString()
        }
    }

    // ---- Lifecycle ----

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceConnected.value = true
        Log.d(TAG, "Service connected")
        addLog("Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || !_isRunning.value) return
        // # 检测 MIUI SecurityCenter 弹窗并自动点击"允许"
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString() ?: return
                if (pkg == MIUI_SECURITY_PKG) {
                    autoAllowMiuiStartConfirmation()
                }
            }
        }
    }

    /**
     * Detects MIUI's ConfirmStartActivity and auto-clicks the "Allow" button.
     *
     * # MIUI 后台启动管控会弹出 ConfirmStartActivity 要求用户确认。
     * # 利用无障碍服务自动找到"允许"按钮并点击，绕过拦截。
     *
     * # 已知弹窗类名：com.miui.wakepath.ui.ConfirmStartActivity
     * # 按钮文本因 MIUI 版本和语言不同可能为：允许/Allow/确定/确认
     */
    private fun autoAllowMiuiStartConfirmation() {
        // # 去抖：2 秒内不重复处理
        val now = System.currentTimeMillis()
        if (now - lastMiuiDismissTime < 2000L) return

        val root = findSecurityCenterRoot()
        if (root == null) {
            Log.d(TAG, "[MIUI] SecurityCenter event received but window root not found")
            return
        }

        // # 按优先级尝试各种"允许"按钮文本
        val allowTexts = listOf("允许", "Allow", "确定", "确认", "OK", "同意")
        for (text in allowTexts) {
            val btn = NodeFinder.findByText(root, text)
            if (btn != null) {
                clickNodeWalkingUp(btn)
                lastMiuiDismissTime = now
                Log.d(TAG, "[MIUI] Auto-clicked '$text' on ConfirmStartActivity")
                addLog("[MIUI] Auto-allowed app start ('$text')")
                return
            }
        }

        // # 兜底：MIUI 对话框通常两个按钮，右边是"允许"
        val allNodes = NodeFinder.flatten(root)
        val buttons = allNodes.filter {
            it.isClickable && it.className?.toString()?.contains("Button") == true
        }
        if (buttons.size >= 2) {
            val rightBtn = buttons.maxByOrNull {
                val rect = android.graphics.Rect()
                it.getBoundsInScreen(rect)
                rect.centerX()
            }
            rightBtn?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            lastMiuiDismissTime = now
            Log.d(TAG, "[MIUI] Clicked rightmost button as fallback")
            addLog("[MIUI] Auto-allowed app start (fallback)")
        } else {
            Log.w(TAG, "[MIUI] SecurityCenter dialog detected but no allow button found")
            addLog("[MIUI] WARNING: Could not find allow button in confirmation dialog")
        }
    }

    /**
     * Finds the root node of the MIUI SecurityCenter window.
     * Tries rootInActiveWindow first, then searches all interactive windows.
     * # 查找 MIUI SecurityCenter 窗口的根节点
     */
    private fun findSecurityCenterRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow
        if (root?.packageName?.toString() == MIUI_SECURITY_PKG) return root
        // # rootInActiveWindow 可能不是 SecurityCenter，遍历所有窗口
        return try {
            windows?.firstOrNull {
                it.root?.packageName?.toString() == MIUI_SECURITY_PKG
            }?.root
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Clicks a node, walking up to the nearest clickable ancestor.
     * # 点击节点，向上查找可点击的父节点
     */
    private fun clickNodeWalkingUp(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return
            }
            target = target.parent
        }
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        automationJob?.cancel()
        serviceScope.cancel()
        instance = null
        _isServiceConnected.value = false
        _isRunning.value = false
        Log.d(TAG, "Service destroyed")
    }

    // ---- Control ----

    /**
     * Creates the engine, handlers, and starts the plan-driven automation coroutine.
     * # 创建引擎和处理器，启动计划驱动的自动化协程
     */
    private fun startWithPlan(planId: Long) {
        if (_isRunning.value) {
            addLog("Already running, ignoring start request")
            return
        }

        val bridge = AccessibilityBridge(this)
        val db = AppDatabase.getInstance(applicationContext)
        val planRepository = PlanRepository(db)
        val configStore = PlanConfigStore(applicationContext)

        val cellRebelHandler = CellRebelHandler(bridge, onLog = { addLog(it) })
        val fakeGpsHandler = FakeGpsHandler(bridge) { addLog(it) }

        _isRunning.value = true

        automationJob = serviceScope.launch {
            // # 读取计划与高级配置（超时/GPS 稳定）
            val plan = planRepository.getPlan(planId)
            val planConfig = configStore.config.first()
            if (plan == null) {
                addLog("ERROR: plan #$planId not found")
                _isRunning.value = false
                return@launch
            }

            // # R8-F1/F2（Sol round-7 P1-1）：A+ 组合根。pre-freeze backend = null（纯 legacy 引擎行为）；
            // # #3 冻结 + GREEN 落地后，同样的 APlusComposition 两函数用真实 backend（frozen Room
            // # receipt schema + contract RPC）构造 coordinator + evidence。生产与测试走同一组合点。
            val aplusBackend: APlusBackend? = null // GREEN: 由 frozen schema + contract RPC 构造
            val aplusCoordinator = aplusBackend?.let { APlusComposition.recoveryCoordinator(it) }
            val aplusEvidence = aplusBackend?.let { APlusComposition.completionEvidenceSource(it) }
            val newEngine = AutomationEngine(
                planId = planId,
                planRepository = planRepository,
                cellRebelRunner = cellRebelHandler,
                gpsSetter = fakeGpsHandler,
                bufferGate = BufferGate(plan.globalBufferSeconds) { System.currentTimeMillis() },
                testTimeoutMs = planConfig.testTimeoutSeconds * 1000L,
                gpsSettleMs = planConfig.gpsSettleSeconds * 1000L,
                // # F003：每次 attempt 重新读取开关（AC-F3-5 中途切换下个 attempt 生效）
                stageToggles = {
                    val c = configStore.config.first()
                    StageToggles(c.locationStageEnabled, c.testStageEnabled)
                },
                bridge = bridge,
                // # R6-F4（§11.7）：生产构造真实 driver——使 §8.1 状态机在生产有真实消费者，
                // # 杜绝"driver 仅测试构造、生产零调用点"。driver 当前为 no-op 骨架，注入无运行时副作用。
                attemptDriver = APlusAttemptDriver(db.auditEventDao()),
                recoveryCoordinator = aplusCoordinator,
                completionEvidenceSource = aplusEvidence
            )
            engine = newEngine

            // # F8：6 个转发 collector 随单次 run 明确取消（流永不完成，
            // # 不取消则 engine.run 返回后 automationJob 仍存活 → 泄漏）
            withForwarders(
                forwarders = listOf(
                    // # 监听引擎状态并转发到 companion 的 StateFlow
                    { newEngine.state.collect { _currentState.value = it } },
                    // # 收集循环计数
                    { newEngine.cycleCount.collect { _cycleCount.value = it } },
                    // # 收集日志
                    { newEngine.logs.collect { _logs.value = it } },
                    // # 转发 Run 页所需的引擎投影流
                    { newEngine.currentTask.collect { _currentTask.value = it } },
                    { newEngine.cooldown.collect { _cooldown.value = it } },
                    { newEngine.lastFailure.collect { _lastFailure.value = it } }
                )
            ) {
                // # 运行引擎（阻塞直到完成/取消/出错）
                try {
                    newEngine.run()
                } finally {
                    _isRunning.value = false
                    engine = null
                }
            }
        }
    }

    /**
     * Cancels the automation coroutine.
     * # 取消自动化协程
     */
    private fun stopRunning() {
        if (automationJob?.isActive == true) {
            addLog("Stopping automation...")
            automationJob?.cancel()
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val entry = "[$timestamp] $message"
        _logs.value = (_logs.value + entry).takeLast(200)
        Log.d(TAG, message)
    }
}

/**
 * Runs [block] while [forwarders] are active, then cancels every forwarder.
 * StateFlow collectors never complete on their own — without this they would
 * keep the parent job alive after a run returns and leak one collector set
 * per plan run (F8).
 * # block 运行期间转发器活跃，返回后全部取消。
 * # StateFlow collector 自身永不完成，不取消会拖住父 job，每跑一次泄漏一组
 */
internal suspend fun withForwarders(
    forwarders: List<suspend CoroutineScope.() -> Unit>,
    block: suspend () -> Unit
) = coroutineScope {
    val jobs = forwarders.map { forwarder ->
        launch { forwarder() }
    }
    try {
        block()
    } finally {
        jobs.forEach { it.cancel() }
    }
}
