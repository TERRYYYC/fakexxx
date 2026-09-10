package com.example.cellrebelauto.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Bridge between automation logic and the Android AccessibilityService.
 * Provides clean suspend-based APIs for UI interaction.
 *
 * # 无障碍服务桥接层：将 AccessibilityService 的底层操作
 * # 封装为干净的挂起函数接口，供 Handler 调用
 */
class AccessibilityBridge(
    private val service: AccessibilityService,
    // # [2026-09-09] #147：手势 binder 派发线程。默认为单 daemon 串行线程（手势系统本就互斥——
    // # 新手势会取消在途手势，串行化把"binder 挂死丢线程"的损失上限定为 1）；测试注入直接执行器。
    private val gestureDispatchExecutor: Executor = sharedGestureDispatchExecutor(),
    // # [2026-09-09] #147：手势回调等待上限。须覆盖最长手势（1s long-press）+ 系统调度余量，
    // # 远小于 runner testTimeoutMs 与看门狗 90s 阈值——超时是失败信号，不是正常路径。
    private val gestureDispatchTimeoutMs: Long = GESTURE_DISPATCH_TIMEOUT_MS
) {

    companion object {
        private const val TAG = "A11yBridge"

        // # [2026-09-09] #147：手势派发等待上限（8s）。回调永不触发（display binder 无响应）
        // # 时按超时返回 false，不再永久挂起。
        const val GESTURE_DISPATCH_TIMEOUT_MS = 8_000L

        @Volatile
        private var sharedExecutor: Executor? = null

        /** #147: 进程级单例派发线程（daemon，不阻止 JVM 退出；服务重建后复用同一实例）。 */
        internal fun sharedGestureDispatchExecutor(): Executor {
            sharedExecutor?.let { return it }
            synchronized(this) {
                sharedExecutor?.let { return it }
                val created = Executors.newSingleThreadExecutor { r ->
                    Thread(r, "a11y-gesture-dispatch").apply { isDaemon = true }
                }
                sharedExecutor = created
                return created
            }
        }
    }

    // ---- Node access ----

    /**
     * Returns the root node of the active window, or null.
     * # 获取当前活动窗口的根节点
     */
    fun getRootNode(): AccessibilityNodeInfo? = service.rootInActiveWindow

    /**
     * Returns the package name of the current foreground app.
     * # 获取当前前台应用的包名
     */
    fun getCurrentPackage(): String? = getRootNode()?.packageName?.toString()

    // ---- Click ----

    /**
     * Performs ACTION_CLICK on the given node.
     * Walks up the tree to find a clickable ancestor if the node itself isn't clickable.
     * # 点击节点；如果节点本身不可点击，会向上查找可点击的父节点
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null) {
            if (target.isClickable) {
                return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            target = target.parent
        }
        // # 回退：直接点击原始节点
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    // ---- Text input ----

    /**
     * Focuses the node, clears existing text, then sets new text.
     * # 聚焦节点 → 清空旧文本 → 输入新文本
     */
    fun setText(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // # 先清空
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
        )
        // # 再输入
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
        )
    }

    /**
     * Sends an IME Enter key action on API 30+.
     * # 在 API 30+ 上发送回车键
     */
    fun pressEnter(node: AccessibilityNodeInfo): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        } else {
            false
        }
    }

    // ---- Gesture dispatch (for map interaction) ----

    /**
     * Dispatches a tap gesture at screen coordinates (x, y).
     * Uses AccessibilityService.dispatchGesture for reliable map interaction.
     * # 在屏幕坐标 (x, y) 处发送点击手势，用于地图交互
     */
    suspend fun dispatchTap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        // # [2026-04-02] 150ms 比 50ms 更接近真实手指触摸，CellRebel 按钮响应更稳定
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,   // # 起始延迟 0ms
            150L  // # 持续 150ms（模拟真实点击）
        )
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture)
    }

    /**
     * Dispatches a swipe gesture from (x1,y1) to (x2,y2).
     * Useful for scrolling or panning the map.
     * # 从 (x1,y1) 滑动到 (x2,y2)，可用于滚动或平移地图
     */
    suspend fun dispatchSwipe(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long = 300L
    ): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture)
    }

    /**
     * Dispatches a long press at (x, y) for the given duration.
     * # 在 (x, y) 处长按指定时间
     */
    suspend fun dispatchLongPress(x: Float, y: Float, durationMs: Long = 1000L): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        return dispatchGesture(gesture)
    }

    // # 底层手势派发，使用 suspendCancellableCoroutine 包装回调。
    //
    // # [2026-09-09] #147（285 压测现场：attempt 卡 LAUNCHING_CELLREBEL 17 分钟，main 被
    // # binder 占住导致引擎与看门狗同线程双重僵死）——两道防线：
    // #   1) binder 派发移出 main（gestureDispatchExecutor，单 daemon 串行线程）；
    // #   2) 回调等待包 withTimeout（默认 8s，超时返回 false + Log.w，不再永久挂起）。
    //
    // # AOSP 依据（frameworks/base/core/java/android/accessibilityservice/AccessibilityService.java）：
    // # dispatchGesture 无主线程约束。阻塞点 calculateGestureSampleTimeMs
    // # （DisplayManager.getDisplay → DisplayManagerGlobal.getDisplayInfo → binder transact，
    // # 即 285 现场挂死点）与 MotionEventGenerator.getGestureStepsFromGestureDescription
    // # （纯本地计算）都在 synchronized(mLock) 之外执行；callback 登记与
    // # connection.dispatchGesture 段在 mLock 内——任意线程调用皆安全。
    // # handler=null 的回调线程语义（AOSP javadoc）："If null, the object is called back on
    // # the service's main thread"——本修复后 main 不再进入 binder 调用，回调可及时投递；
    // # 且协程恢复（cont.resume）与线程无关，回调线程不构成约束。
    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean =
        dispatchGestureCore(gestureDispatchTimeoutMs) { callback ->
            service.dispatchGesture(gesture, callback, null)
        }

    /**
     * #147 dispatch core: runs [serviceCall] on [gestureDispatchExecutor] (off main) and bounds
     * the wait for the system gesture callback with [timeoutMs]. internal 供 #147 oracle 直接驱动
     * （GestureDescription 在 Robolectric JVM 上无法构造，端到端走真机复验）。
     *
     * # 超时语义：withTimeout 只能打断"已挂起等回调"的等待；若 binder transact 本身不返回，
     * # 挂起的只是派发 daemon 线程（main/引擎/看门狗全部存活），attempt 由既有 P1.3 看门狗收尸重试。
     */
    internal suspend fun dispatchGestureCore(
        timeoutMs: Long,
        serviceCall: (AccessibilityService.GestureResultCallback) -> Boolean
    ): Boolean {
        return try {
            withTimeout(timeoutMs) {
                suspendCancellableCoroutine { cont ->
                    val callback = object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            Log.w(TAG, "Gesture cancelled")
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                    try {
                        gestureDispatchExecutor.execute {
                            // # 超时/取消后才轮到的陈旧派发绝不落地（迟到的手势会误点屏幕上的任意 UI）
                            if (!cont.isActive) return@execute
                            val dispatched = try {
                                serviceCall(callback)
                            } catch (t: Throwable) {
                                Log.w(TAG, "dispatchGesture threw ${t.javaClass.simpleName}: ${t.message}")
                                false
                            }
                            if (!dispatched && cont.isActive) cont.resume(false)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "gesture dispatch executor rejected: ${t.message}")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            }
        } catch (e: CancellationException) {
            // # 区分自身超时与父协程取消（#15a 纪律：绝不吞掉外部取消——取消须继续传播）
            if (currentCoroutineContext().isActive) {
                Log.w(
                    TAG,
                    "dispatchGesture did not complete within ${timeoutMs}ms — " +
                        "returning false (display binder unresponsive?)"
                )
                false
            } else {
                throw e
            }
        }
    }

    // ---- Global actions ----

    /**
     * Presses the Home button.
     * # 按 Home 键回到桌面
     */
    fun goHome() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    /**
     * Presses the Back button.
     * # 按返回键
     */
    fun goBack() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    /**
     * Opens the Recent Apps screen.
     * # 打开最近任务界面
     */
    fun openRecents() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    }

    // ---- App launching ----

    /**
     * Returns the package name of this accessibility service's host app.
     * # 获取宿主应用的包名
     */
    fun getServicePackageName(): String = service.packageName

    /**
     * Returns the user-visible label of this app (e.g. "CellRebelAuto").
     * Used to find our own app in the Recent Apps screen.
     * # 获取本 app 的用户可见名称，用于在最近任务中查找自己
     */
    fun getSelfAppLabel(): String {
        return try {
            val appInfo = service.packageManager.getApplicationInfo(service.packageName, 0)
            service.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) {
            service.packageName
        }
    }

    /**
     * Launches the host app itself (brings it to foreground).
     * # 启动自身宿主应用（拉回前台）
     * # MIUI workaround: 从自己的前台启动第三方 app 不会被拦截
     */
    fun launchSelf(): Boolean = launchApp(service.packageName)

    /**
     * Launches the app with the given package name.
     * # 启动指定包名的应用
     */
    fun launchApp(packageName: String): Boolean {
        val intent = service.packageManager.getLaunchIntentForPackage(packageName)
        return if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(intent)
            true
        } else {
            Log.e(TAG, "Cannot find launch intent for $packageName")
            false
        }
    }

    // ---- Screen info ----

    /**
     * Returns the screen height in pixels.
     * # 获取屏幕高度（像素）
     */
    fun getScreenHeight(): Float {
        val dm = service.resources.displayMetrics
        return dm.heightPixels.toFloat()
    }

    fun getScreenWidth(): Float {
        val dm = service.resources.displayMetrics
        return dm.widthPixels.toFloat()
    }

    // ---- Node utilities ----

    /**
     * Gets the screen bounds of a node.
     * # 获取节点在屏幕上的矩形区域
     */
    fun getNodeBounds(node: AccessibilityNodeInfo): Rect {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return rect
    }

    /**
     * Gets the center point of a node's screen bounds.
     * # 获取节点屏幕区域的中心点坐标
     */
    fun getNodeCenter(node: AccessibilityNodeInfo): Pair<Float, Float> {
        val rect = getNodeBounds(node)
        return Pair(rect.exactCenterX(), rect.exactCenterY())
    }
}
