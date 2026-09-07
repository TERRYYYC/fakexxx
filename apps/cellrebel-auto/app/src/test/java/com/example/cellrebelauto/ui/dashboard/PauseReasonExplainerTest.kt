package com.example.cellrebelauto.ui.dashboard

import com.example.cellrebelauto.automation.FailureReason
import com.example.cellrebelauto.model.AutomationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * T7 P1.1 — the PAUSE-reason → human-language mapping oracle.
 *
 * The engine already emits typed pauses (applusPause ERROR lines, typed
 * ANCHOR_MISMATCH, trust-gate rejections, EXHAUSTED terminals, the #15
 * SERVICE_RECYCLED state) — but the Run surface rendered only enum names.
 * The operator must read ONE line and know WHAT to do next.
 *
 * Killing mutations:
 *  - a mapping that drops a case (falls to a generic line) fails the
 *    per-fragment action assertions;
 *  - a mapping that only covers some enum values fails the exhaustive
 *    AutomationState / FailureReason loops;
 *  - an explainer that proposes a new control path (not one of the four
 *    existing entries) fails the action-enum closed-set assertions.
 *
 * # 暂停原因→人话映射 oracle：既有枚举/日志片段逐一断言文案+建议动作，穷尽不漏
 */
@RunWith(RobolectricTestRunner::class)
class PauseReasonExplainerTest {

    // ---- exhaustiveness -------------------------------------------------------

    @Test
    fun `every AutomationState has a non-blank explanation`() {
        for (state in AutomationState.values()) {
            val explanation = PauseReasonExplainer.explain(state)
            assertTrue(
                "state=$state headline blank",
                explanation.headline.isNotBlank()
            )
            assertTrue(
                "state=$state detail blank",
                explanation.detail.isNotBlank()
            )
        }
    }

    @Test
    fun `every FailureReason maps to a translated human text`() {
        for (reason in FailureReason.values()) {
            val text = PauseReasonExplainer.failureReasonText(reason.name)
            assertTrue("reason=$reason text blank", text.isNotBlank())
            assertNotEquals(
                "reason=$reason not translated (raw enum name leaked)",
                reason.name, text
            )
        }
    }

    @Test
    fun `unknown failure reason falls back to a readable line with the raw code`() {
        val text = PauseReasonExplainer.failureReasonText("SOME_FUTURE_REASON")
        assertTrue(text.contains("SOME_FUTURE_REASON"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `null failure reason yields a neutral non-crashing text`() {
        val text = PauseReasonExplainer.failureReasonText(null)
        assertTrue(text.isNotBlank())
    }

    // ---- typed terminal states ------------------------------------------------

    @Test
    fun `SERVICE_RECYCLED tells the operator the host died and offers Resume`() {
        val e = PauseReasonExplainer.explain(AutomationState.SERVICE_RECYCLED)
        assertTrue(e.headline.contains("回收") || e.headline.contains("已停止"))
        assertTrue(e.detail.contains("重开") || e.detail.contains("重新"))
        assertEquals(DashboardAction.RESUME, e.action)
        assertNotNull(e.actionLabel)
    }

    @Test
    fun `ERROR is a held terminal with a Resume entry`() {
        val e = PauseReasonExplainer.explain(AutomationState.ERROR, lastFailureReason = "CELLREBEL_TIMEOUT")
        assertEquals(DashboardAction.RESUME, e.action)
        assertTrue(e.headline.isNotBlank())
    }

    // ---- PAUSED + engine ERROR-log fragments ----------------------------------

    @Test
    fun `PAUSED with EXHAUSTED log maps to schedule-exhausted copy and plan reset entry`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            latestErrorLog =
                "[12:00:01] ERROR: provider schedule is already EXHAUSTED before the first attempt"
        )
        assertTrue("headline=${e.headline}", e.headline.contains("耗尽"))
        assertTrue("detail=${e.detail}", e.detail.contains("provider") || e.detail.contains("千网游"))
        assertEquals(DashboardAction.RESET_PLAN, e.action)
    }

    @Test
    fun `PAUSED with trust-gate rejection maps to provider re-approval entry`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            latestErrorLog =
                "[12:00:01] ERROR: provider discover failed or protocol incompatible (v1 required)" +
                    " — trust gate rejected name.caiyao.fakegps signer=abc (REVOKED)"
        )
        assertTrue("headline=${e.headline}", e.headline.contains("信任"))
        assertEquals(DashboardAction.OPEN_PROVIDERS, e.action)
    }

    @Test
    fun `PAUSED with ANCHOR_MISMATCH (T4 typed reason) maps to plan-profile check entry`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            latestErrorLog =
                "[12:00:01] ERROR: quota commit vetoed: ANCHOR_MISMATCH:PRE:|Δlat|=0.5 " +
                    "(tolerance 2.0E-4) expected=(50.0,30.0) observed=(50.5,30.0)"
        )
        assertTrue("headline=${e.headline}", e.headline.contains("错位") || e.headline.contains("坐标"))
        assertEquals(DashboardAction.OPEN_PLAN, e.action)
    }

    @Test
    fun `PAUSED with transport failure points at the QWY and Vector chain`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            latestErrorLog =
                "[12:00:01] ERROR: provider discover failed or protocol incompatible (v1 required)"
        )
        assertTrue(
            "headline=${e.headline} detail=${e.detail}",
            e.headline.contains("传输") || e.detail.contains("Vector") || e.detail.contains("千网游")
        )
        // 传输失败没有既有的"一键修复"入口 — 不允许发明新状态机入口
        assertEquals(DashboardAction.NONE, e.action)
    }

    @Test
    fun `PAUSED with recovery-ownership conflict explains and offers Resume`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            latestErrorLog =
                "[12:00:01] ERROR: A+ recovery ownership is inactive or ambiguous — " +
                    "replacement session stopped"
        )
        assertTrue("detail=${e.detail}", e.detail.contains("恢复") || e.detail.contains("归属"))
        assertEquals(DashboardAction.RESUME, e.action)
    }

    @Test
    fun `PAUSED with evidence-insufficient reconcile pauses with a readable line`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            latestErrorLog =
                "[12:00:01] ERROR: reconcile of attempt 3 = INSUFFICIENT_EVIDENCE (§8.2: 证据不足走 PAUSED)"
        )
        assertTrue(
            "headline=${e.headline} detail=${e.detail}",
            e.headline.contains("证据") || e.detail.contains("证据")
        )
    }

    @Test
    fun `PAUSED with no matching fragment falls back to a Resume-able generic line`() {
        val e = PauseReasonExplainer.explain(AutomationState.PAUSED, latestErrorLog = null)
        assertEquals(DashboardAction.RESUME, e.action)
        assertTrue(e.headline.isNotBlank())
    }

    // ---- last-failure-driven copy ----------------------------------------------

    @Test
    fun `PAUSED surfaces the last failure reason in human language`() {
        val e = PauseReasonExplainer.explain(
            AutomationState.PAUSED,
            lastFailureReason = FailureReason.FOREGROUND_SWITCH_FAILED.name
        )
        assertTrue(
            "detail=${e.detail}",
            e.detail.contains("前台") || e.headline.contains("前台")
        )
    }

    // ---- non-terminal states never propose actions -----------------------------

    @Test
    fun `running-phase states never offer an action`() {
        val runningStates = setOf(
            AutomationState.IDLE,
            AutomationState.LAUNCHING_FAKE_GPS,
            AutomationState.STOPPING_OLD_GPS,
            AutomationState.SETTING_LOCATION,
            AutomationState.CONFIRMING_LOCATION,
            AutomationState.STARTING_FAKE_GPS,
            AutomationState.LAUNCHING_CELLREBEL,
            AutomationState.NAVIGATING_TO_TEST,
            AutomationState.STARTING_TEST,
            AutomationState.WAITING_FOR_RESULT,
            AutomationState.COLLECTING_RESULT,
            AutomationState.WAITING_INTERVAL,
            AutomationState.PROCESSING,
            AutomationState.RECOVERING,
        )
        for (state in runningStates) {
            assertEquals(
                "state=$state must not offer an action while running",
                DashboardAction.NONE,
                PauseReasonExplainer.explain(state).action
            )
        }
    }

    @Test
    fun `COOLDOWN is visible as waiting, not as a fault`() {
        val e = PauseReasonExplainer.explain(AutomationState.COOLDOWN)
        assertEquals(DashboardAction.NONE, e.action)
        assertTrue(
            "headline=${e.headline}",
            e.headline.contains("缓冲") || e.headline.contains("等待")
        )
    }
}
