package com.example.cellrebelauto.ui

import com.example.cellrebelauto.automation.StageProgress
import com.example.cellrebelauto.model.AutomationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Issue #15b — the Run-surface disconnect projection oracle (pure).
 *
 * Device truth: when the OEM recycles the accessibility service mid-run (or the destroy
 * terminal never reaches the UI), the Run page keeps a Running-like state and the StageProgress
 * anchor keeps ticking locally forever ("已等待 4m47s（上限 45s）") — a dead run rendered as
 * live progress. The projection makes the rule explicit:
 *
 *  - connected → live wait line, no warning;
 *  - disconnected && (Run-like state || stale stage anchor) → the prominent
 *    "服务已断开 — 引擎已停止，请重新开始" warning row and NO ticking wait line (frozen);
 *  - disconnected && a terminal state && no anchor → quiet terminal (no warning).
 *
 * The UI renders this projection; it never decides it (DeviceReadinessProjection pattern).
 *
 * # #15b Run 页断开投影 oracle：断开 + Running 态 → 醒目警告行，等待行冻结
 */
class RunSurfaceProjectionTest {

    private val settleAnchor = StageProgress(
        stageLabel = "GPS settling", detailLabel = "gps settle",
        startedAtMs = 1_000_000L, budgetMs = 45_000L
    )

    @Test
    fun `connected run ticks the wait line and shows no warning`() {
        val projection = runSurfaceProjection(
            isServiceConnected = true,
            currentState = AutomationState.WAITING_FOR_RESULT,
            stageProgress = settleAnchor,
            nowMs = 1_030_000L
        )
        assertNull("no warning while the service is connected", projection.serviceWarning)
        assertEquals("已等待 30s（上限 45s）", projection.stageWaitLine)
    }

    @Test
    fun `disconnected run-like state shows the warning and freezes the wait line`() {
        val projection = runSurfaceProjection(
            isServiceConnected = false,
            currentState = AutomationState.WAITING_INTERVAL, // the device's stuck state
            stageProgress = settleAnchor,
            nowMs = 1_287_000L // 287s past the anchor — the device's "已等待 4m47s（上限 45s）"
        )
        assertNotNull("the disconnected warning must be visible (issue #15)", projection.serviceWarning)
        assertEquals(SERVICE_DISCONNECTED_WARNING, projection.serviceWarning)
        assertNull(
            "the local tick must be frozen while disconnected — no runaway wait line",
            projection.stageWaitLine
        )
    }

    @Test
    fun `disconnected run-like state without a stage anchor still warns`() {
        val projection = runSurfaceProjection(
            isServiceConnected = false,
            currentState = AutomationState.LAUNCHING_CELLREBEL,
            stageProgress = null,
            nowMs = 0L
        )
        assertNotNull(projection.serviceWarning)
    }

    @Test
    fun `a stale stage anchor alone is enough to warn while disconnected`() {
        val projection = runSurfaceProjection(
            isServiceConnected = false,
            currentState = AutomationState.IDLE, // even a quiet state
            stageProgress = settleAnchor,        // …with a ticking anchor = illusion
            nowMs = 1_287_000L
        )
        assertNotNull("a stale anchor must trigger the warning (the tick is the illusion)", projection.serviceWarning)
        assertNull("and its wait line must be frozen", projection.stageWaitLine)
    }

    @Test
    fun `disconnected quiet terminal with no anchor stays quiet`() {
        listOf(
            AutomationState.SERVICE_RECYCLED,
            AutomationState.FAILED,
            AutomationState.DONE,
            AutomationState.IDLE
        ).forEach { terminal ->
            val projection = runSurfaceProjection(
                isServiceConnected = false,
                currentState = terminal,
                stageProgress = null,
                nowMs = 0L
            )
            assertNull("terminal $terminal must not warn", projection.serviceWarning)
            assertNull(projection.stageWaitLine)
        }
    }

    @Test
    fun `connected quiet states render neither warning nor wait line`() {
        val projection = runSurfaceProjection(
            isServiceConnected = true,
            currentState = AutomationState.IDLE,
            stageProgress = null,
            nowMs = 0L
        )
        assertNull(projection.serviceWarning)
        assertNull(projection.stageWaitLine)
    }
}
