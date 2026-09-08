package com.example.cellrebelauto.ui.dashboard.v2

import com.example.cellrebelauto.model.AutomationState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T7v2 §A1-v2 #1 — the minimal status bar oracle.
 *
 * THE minimalism rule: ONE status word + ONE primary button + at most one
 * small line. The six pause REASONS do NOT get six different main surfaces —
 * every held state looks the same ("已暂停" + 重启恢复); the differences live
 * only inside the log drawer. Buttons reuse EXISTING entries only:
 * RESUME → startOrResumePlan (T4/broadcast RESUME entry), STOP → stopAutomation,
 * EXPORT → the v1 diagnostic bundle, OPEN_LOG → the log drawer.
 *
 * # 极简状态条 oracle：一行状态词 + 单主按钮；暂停态同构；失败后主按钮转「查看日志」
 */
class RunStatusBarTest {

    @Test
    fun runningState_showsStop() {
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.WAITING_FOR_RESULT, isRunning = true,
            trustedDone = 3, trustedTotal = 10, resumeFailure = null,
        )
        assertEquals("运行中", bar.statusWord)
        assertEquals(RunStatusBarProjection.Primary.STOP, bar.primary)
        assertEquals("停止", bar.primaryLabel)
    }

    @Test
    fun heldStates_areAllIsomorphic_pausedPlusResume() {
        for (state in listOf(
            AutomationState.PAUSED,
            AutomationState.ERROR,
            AutomationState.SERVICE_RECYCLED,
        )) {
            val bar = RunStatusBarProjection.project(
                engineState = state, isRunning = false,
                trustedDone = 3, trustedTotal = 10, resumeFailure = null,
            )
            assertEquals(state.name, "已暂停", bar.statusWord)
            assertEquals(state.name, RunStatusBarProjection.Primary.RESUME, bar.primary)
            assertEquals(state.name, "重启恢复", bar.primaryLabel)
        }
    }

    @Test
    fun completedPlan_showsExport() {
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.DONE, isRunning = false,
            trustedDone = 10, trustedTotal = 10, resumeFailure = null,
        )
        assertEquals("已完成", bar.statusWord)
        assertEquals(RunStatusBarProjection.Primary.EXPORT, bar.primary)
        assertEquals("导出结果", bar.primaryLabel)
    }

    @Test
    fun idleState_showsIdleWord() {
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.IDLE, isRunning = false,
            trustedDone = 0, trustedTotal = 10, resumeFailure = null,
        )
        assertEquals("待机", bar.statusWord)
    }

    @Test
    fun runningWinsOverEverything_evenIfEngineStateLags() {
        // The service-level isRunning is the live truth; a stale terminal state
        // must not offer a Resume over a running engine.
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.PAUSED, isRunning = true,
            trustedDone = 3, trustedTotal = 10, resumeFailure = null,
        )
        assertEquals(RunStatusBarProjection.Primary.STOP, bar.primary)
    }

    @Test
    fun resumeFailure_switchesHeldPrimaryToOpenLog_andNamesTheReason() {
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.SERVICE_RECYCLED, isRunning = false,
            trustedDone = 3, trustedTotal = 10, resumeFailure = "SERVICE_NOT_CONNECTED",
        )
        assertEquals(RunStatusBarProjection.Primary.OPEN_LOG, bar.primary)
        assertEquals("查看日志", bar.primaryLabel)
        assertEquals(true, bar.subLine?.contains("SERVICE_NOT_CONNECTED"))
    }

    @Test
    fun doneBeatsHeld_whenQuotaFullyMet() {
        // A held engine over a complete plan: the operator's next move is
        // exporting, not resuming (resume is safe but pointless).
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.PAUSED, isRunning = false,
            trustedDone = 10, trustedTotal = 10, resumeFailure = null,
        )
        assertEquals(RunStatusBarProjection.Primary.EXPORT, bar.primary)
    }

    @Test
    fun heldWithoutFailure_hasNoSubLine_minimalism() {
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.PAUSED, isRunning = false,
            trustedDone = 3, trustedTotal = 10, resumeFailure = null,
        )
        assertEquals(null, bar.subLine)
    }

    @Test
    fun idleWithoutPlan_offersNoButton() {
        val bar = RunStatusBarProjection.project(
            engineState = AutomationState.IDLE, isRunning = false,
            trustedDone = 0, trustedTotal = 0, resumeFailure = null,
            hasPlan = false,
        )
        assertEquals(RunStatusBarProjection.Primary.NONE, bar.primary)
    }
}
