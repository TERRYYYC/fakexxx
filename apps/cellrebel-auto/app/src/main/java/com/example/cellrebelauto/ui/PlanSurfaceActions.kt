package com.example.cellrebelauto.ui

import com.example.cellrebelauto.model.AutomationState

/**
 * #187(a) — the Plan page's action-visibility discriminator for the IN-PROCESS
 * Resume parity entry (pure; JVM-oracle-drivable).
 *
 * FIELD SHAPE (SKILL §4, issue #187): a durably PAUSED session used to leave
 * the Plan page with ONLY Stop + Run ▸ while the engine projection still read
 * "running" (the parked-job window before the run coroutine finishes exiting).
 * The SAME durable state, after a process restart, shows the full-width
 * 「⏸ Resume Plan」 — the SOP workaround was force-stop + relaunch (or the root
 * RESUME broadcast). [offerResumeWhileRunning] gates a parity Resume button
 * rendered NEXT TO Stop in that exact branch; it routes to the SAME
 * startOrResumePlan entry as the post-restart button and the T3 broadcast
 * RESUME action — one code path, no second engine (INV-9: the start is
 * idempotent, and [com.example.cellrebelauto.automation.AutomationService]
 * refuses a concurrent start while a run is still retiring).
 *
 * The RUN console's primary-button discipline is untouched: a running engine
 * keeps Stop as its ONE primary there (RunStatusBarProjection "running wins").
 * The Plan page is the operator's planning surface — this adds visibility of
 * the existing entry, not a new control path.
 *
 * # #187(a)：持久 paused 会话在当前进程内的 Resume 可见性判别（纯函数）——
 * # 引擎仍报 running 但已挂起在 held 终态时，计划页同样提供 Resume 入口，
 * # 与重启后出现的按钮同一条 startOrResumePlan 路径
 */
object PlanSurfaceActions {

    /**
     * Terminal states that park the engine until an operator acts — the same
     * set the T3 RemoteControlReceiver STATUS codes (RESULT_HELD) and the run
     * status bar treat as held.
     */
    val HELD_STATES =
        setOf(AutomationState.PAUSED, AutomationState.ERROR, AutomationState.SERVICE_RECYCLED)

    /**
     * True ONLY while the engine reports running AND is parked in a held
     * terminal: the parked window where the durable session is paused but the
     * surface would otherwise show Stop + Run ▸ alone. Not-running is
     * deliberately false — the existing when-chain branches already own the
     * full-width Resume there; this entry must never duplicate it.
     */
    fun offerResumeWhileRunning(isRunning: Boolean, engineState: AutomationState): Boolean =
        isRunning && engineState in HELD_STATES
}
