package com.example.cellrebelauto.ui.dashboard.v2

import com.example.cellrebelauto.model.AutomationState

/**
 * T7v2 §A1-v2 #1 — the one-line status bar projection (pure).
 *
 * MINIMALISM RULE: one status word + one primary button + at most one small
 * line. The six pause reasons do NOT get six surfaces — every held state is
 * "已暂停" + 重启恢复; differences live only in the log drawer. No bypass
 * entries: RESUME = startOrResumePlan (same entry as T4 auto-resume and the
 * broadcast RESUME), STOP = stopAutomation, EXPORT = the v1 diagnostic bundle,
 * OPEN_LOG = the log drawer.
 *
 * # 极简状态条投影：一行状态词 + 单主按钮（暂停态同构；恢复失败转「查看日志」）
 */
object RunStatusBarProjection {

    enum class Tone { RUNNING, HELD, DONE, IDLE }

    enum class Primary { RESUME, STOP, EXPORT, OPEN_LOG, NONE }

    data class Model(
        val tone: Tone,
        val statusWord: String,
        val subLine: String?,
        val primary: Primary,
        val primaryLabel: String,
    )

    fun project(
        engineState: AutomationState,
        isRunning: Boolean,
        trustedDone: Int,
        trustedTotal: Int,
        resumeFailure: String?,
        hasPlan: Boolean = true,
    ): Model {
        // 1) Live run wins over a lagging terminal state: isRunning is the
        //    service-level truth, and a running engine must offer Stop only.
        if (isRunning) {
            return Model(Tone.RUNNING, "运行中", null, Primary.STOP, "停止")
        }
        // 2) A held engine over a COMPLETE plan: exporting is the operator's
        //    next move; Resume would be safe but pointless.
        val planComplete = trustedTotal > 0 && trustedDone >= trustedTotal
        // 3) Held states are ISOMORPHIC (极简铁律): same word, same button, no
        //    per-reason main surface. A failed one-tap resume flips the primary
        //    to 查看日志 and names the typed rejection in the small line.
        val held = engineState in HELD_STATES
        return when {
            planComplete -> Model(Tone.DONE, "已完成", null, Primary.EXPORT, "导出结果")
            resumeFailure != null && held -> Model(
                tone = Tone.HELD,
                statusWord = "已暂停",
                subLine = "恢复失败：$resumeFailure（可到 Plan 页或 Provider 页排查）",
                primary = Primary.OPEN_LOG,
                primaryLabel = "查看日志",
            )
            held -> Model(Tone.HELD, "已暂停", null, Primary.RESUME, "重启恢复")
            // Idle WITH a plan offers the same start/resume entry under its
            // honest name; idle WITHOUT a plan offers nothing to press.
            else -> if (hasPlan) {
                Model(Tone.IDLE, "待机", null, Primary.RESUME, "启动")
            } else {
                Model(Tone.IDLE, "待机", "导入计划后可启动", Primary.NONE, "")
            }
        }
    }

    /** Terminal states that park the engine until an operator acts (T3 precedent). */
    private val HELD_STATES =
        setOf(AutomationState.PAUSED, AutomationState.ERROR, AutomationState.SERVICE_RECYCLED)
}
