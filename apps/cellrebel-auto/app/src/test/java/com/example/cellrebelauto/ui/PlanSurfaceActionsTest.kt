package com.example.cellrebelauto.ui

import com.example.cellrebelauto.model.AutomationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #187(a) — the Plan page's in-process Resume parity oracle (pure).
 *
 * FIELD SHAPE (SKILL §4, issue #187): a durably PAUSED session used to leave
 * the Plan page with ONLY Stop + Run ▸ while the engine projection still read
 * "running" (the parked job window) — the same durable state that, after a
 * process restart, shows the full-width 「⏸ Resume Plan」. The operator had no
 * in-process entry; the SOP workaround was force-stop + relaunch (or a root
 * RESUME broadcast). [PlanSurfaceActions.offerResumeWhileRunning] is the
 * visibility discriminator for the parity button shown NEXT TO Stop in that
 * exact branch — it routes to the SAME startOrResumePlan entry as the
 * post-restart button and the T3 broadcast RESUME (INV-9 idempotent).
 *
 * Killing mutations:
 *  - a discriminator keyed on isRunning alone (resume offered over a genuinely
 *    advancing engine) fails the exhaustive non-held loop;
 *  - a discriminator keyed on held alone (duplicate Resume button in the
 *    !isRunning branches, which already own the full-width Resume) fails the
 *    not-running loop;
 *  - dropping a held state from the set fails the per-state assertions (the
 *    oracle iterates the whole enum, so a future state lands somewhere explicit).
 */
class PlanSurfaceActionsTest {

    @Test
    fun `held engine while running offers the in-process resume parity entry`() {
        for (state in listOf(
            AutomationState.PAUSED,
            AutomationState.ERROR,
            AutomationState.SERVICE_RECYCLED,
        )) {
            assertTrue(
                "state=$state must offer Resume next to Stop",
                PlanSurfaceActions.offerResumeWhileRunning(isRunning = true, engineState = state)
            )
        }
    }

    @Test
    fun `advancing engine while running never offers the parity resume`() {
        for (state in AutomationState.values()) {
            if (state in PlanSurfaceActions.HELD_STATES) continue
            assertFalse(
                "state=$state is an advancing/terminal state — no parity Resume",
                PlanSurfaceActions.offerResumeWhileRunning(isRunning = true, engineState = state)
            )
        }
    }

    @Test
    fun `not-running never offers the parity resume - the existing branches own it`() {
        for (state in AutomationState.values()) {
            assertFalse(
                "state=$state with isRunning=false must route through the existing when-chain",
                PlanSurfaceActions.offerResumeWhileRunning(isRunning = false, engineState = state)
            )
        }
    }

    @Test
    fun `held set matches the T3 broadcast HELD discipline`() {
        // Same terminal set the RemoteControlReceiver STATUS codes and the run
        // status bar treat as "parked until an operator acts".
        assertEquals(
            setOf(AutomationState.PAUSED, AutomationState.ERROR, AutomationState.SERVICE_RECYCLED),
            PlanSurfaceActions.HELD_STATES
        )
    }
}
