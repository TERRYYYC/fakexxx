package com.example.cellrebelauto.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Issue #147 — dispatchGesture main-thread binder hang (285 run live hit).
 *
 * Device truth: `AccessibilityService.dispatchGesture` synchronously calls
 * `calculateGestureSampleTimeMs` → `DisplayManager.getDisplay` → a binder transact that hung
 * against an unresponsive display binder; the gesture callback never arrived and the suspend
 * wrapper waited forever — with the whole call chain running on `Dispatchers.Main`
 * (AutomationService.serviceScope), wedging the engine AND the watchdog (same-thread scheduling).
 *
 * The oracle drives the bridge's real dispatch core ([AccessibilityBridge.dispatchGestureCore])
 * with a fake service call — "registered but never completes", exactly the incident shape. It
 * must:
 *  1. return `false` (bounded by the bridge's dispatch timeout), never hang;
 *  2. run the binder dispatch OFF the caller (main) thread;
 *  3. never let a stale queued dispatch land after the timeout (a late stray tap would
 *     mis-click whatever is on screen — a11y semantics must stay intact).
 *
 * Robolectric note: `GestureDescription$Builder.addStroke` cannot execute on the Robolectric JVM
 * (no shadow for it; the sandbox bootstrap falls back to the "not mocked" stub), so gesture
 * construction end-to-end is verified on device (issue #147 B-layer re-check), not here.
 *
 * #147 oracle：dispatchGesture 回调永不触发时必须限时返回 false 而非永久挂起；
 * binder 派发不得在调用方（main）线程执行；超时后陈旧派发绝不落地。
 */
@RunWith(RobolectricTestRunner::class)
class AccessibilityBridgeGestureTimeoutTest {

    /** Bare service — only a constructor target for the bridge. */
    class StubAccessibilityService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }

    private lateinit var service: StubAccessibilityService

    /** Runs the dispatch lambda inline on the caller thread (test determinism; off-main is covered separately). */
    private val directExecutor = Executor { it.run() }

    @Before
    fun setUp() {
        service = Robolectric.buildService(StubAccessibilityService::class.java).get()
    }

    @Test
    fun dispatchReturnsFalseInsteadOfHangingWhenGestureCallbackNeverArrives() = runTest {
        val bridge = AccessibilityBridge(service, directExecutor)

        // The incident: dispatch registers, the callback never fires. The bridge's own dispatch
        // timeout must convert the hang into a bounded `false`; the outer bound only guards the
        // test itself (RED = outer fires first because the core has no timeout).
        val outcome = withTimeoutOrNull(60_000) { bridge.dispatchGestureCore(8_000) { true } }

        assertNotNull(
            "gesture dispatch hung past the outer bound — no timeout on dispatchGesture (issue #147)",
            outcome
        )
        assertFalse(
            "dispatch must report failure (false) when the callback never arrives",
            outcome!!
        )
    }

    @Test
    fun dispatchReturnsTrueWhenCallbackCompletes() = runTest {
        val bridge = AccessibilityBridge(service, directExecutor)
        var captured: AccessibilityService.GestureResultCallback? = null

        val outcome = withTimeoutOrNull(60_000) {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.dispatchGestureCore(8_000) { callback -> captured = callback; true }
            }
            // UNDISPATCHED ran the bridge up to its suspension; the fake registered the callback.
            captured!!.onCompleted(null)
            deferred.await()
        }

        assertNotNull("dispatch hung even though the callback completed", outcome)
        assertTrue(outcome!!)
    }

    @Test
    fun dispatchReturnsFalseWhenCallbackReportsCancelled() = runTest {
        val bridge = AccessibilityBridge(service, directExecutor)

        val outcome = withTimeoutOrNull(60_000) {
            bridge.dispatchGestureCore(8_000) { callback ->
                callback.onCancelled(null)
                true
            }
        }

        assertNotNull(outcome)
        assertFalse(outcome!!)
    }

    @Test
    fun dispatchReturnsFalseImmediatelyWhenServiceRejectsDispatch() = runTest {
        val bridge = AccessibilityBridge(service, directExecutor)

        val outcome = withTimeoutOrNull(60_000) { bridge.dispatchGestureCore(8_000) { false } }

        assertNotNull("dispatch hung on the sync-reject path", outcome)
        assertFalse(outcome!!)
    }

    @Test
    fun binderDispatchRunsOffTheCallerThread() = runTest {
        val ranOn = AtomicReference<Thread>()
        val bridge = AccessibilityBridge(
            service,
            gestureDispatchExecutor = Executor { r ->
                thread(name = "a11y-gesture-test") {
                    ranOn.set(Thread.currentThread())
                    r.run()
                }
            }
        )

        val outcome = withTimeoutOrNull(60_000) {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) {
                bridge.dispatchGestureCore(8_000) { callback ->
                    callback.onCompleted(null)
                    true
                }
            }
            deferred.await()
        }

        assertTrue(outcome ?: false)
        assertNotEquals(
            "service.dispatchGesture must not run on the caller (main) thread — issue #147",
            Thread.currentThread(),
            ranOn.get()
        )
    }

    @Test
    fun staleQueuedDispatchNeverLandsAfterTimeout() = runTest {
        // Manual executor: nothing runs until the test pulls it — simulating a dispatch queued
        // behind a wedged binder call.
        val queue = ArrayDeque<Runnable>()
        val bridge = AccessibilityBridge(service, Executor { r -> queue.addLast(r) })
        var serviceCalls = 0

        val outcome = withTimeoutOrNull(60_000) {
            bridge.dispatchGestureCore(1_000) { serviceCalls++; true }
        }

        // The bridge's own 1s timeout fires (well inside the outer guard) and reports failure.
        assertFalse("expected the bridge dispatch timeout to fire (false)", outcome ?: true)
        assertEquals(1, queue.size) // the stale runnable is still queued behind the wedged binder
        queue.removeFirst().run()
        assertEquals(
            "a dispatch queued past its timeout must never reach the service (late stray tap)",
            0,
            serviceCalls
        )
    }
}
