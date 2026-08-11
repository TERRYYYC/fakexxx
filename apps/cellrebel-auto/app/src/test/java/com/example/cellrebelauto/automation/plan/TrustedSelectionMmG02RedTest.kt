package com.example.cellrebelauto.automation.plan

import com.example.cellrebelauto.model.plan.LocationTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * M-MG-02: the trusted-ledger projection — not the legacy v4 `completedSuccesses` counter — must
 * drive quota completion and address selection (Issue #5 Task 4, area 6). The legacy counter has
 * NO A+ evidence chain (no observation, no intent hash, no continuity proof), so routing on it is
 * exactly the bug.
 *
 * TRUSTWORTHY RED: the skeleton trusted methods ALIAS the legacy counter path, so every assertion
 * that the trusted projection disagrees with the counter FAILS until GREEN rewires to
 * `count(trusted_quota_entries where taskId=…)`. A GREEN that forgets to rewire (keeps delegating)
 * also fails. Both polarities are covered: counter-complete/trusted-incomplete, and
 * counter-incomplete/trusted-complete.
 *
 * # M-MG-02（RED）：可信账本投影而非 v4 计数器驱动配额完成/选址；骨架别名为计数器=正是 bug
 */
class TrustedSelectionMmG02RedTest {

    private fun task(
        id: Long,
        csvRow: Int,
        priority: Int = 1,
        required: Int = 3,
        completed: Int = 0,
        status: String = "active"
    ): LocationTask = LocationTask(
        id = id,
        planId = 1L,
        csvRow = csvRow,
        longitude = -74.0,
        latitude = 40.0,
        priority = priority,
        requiredSuccesses = required,
        completedSuccesses = completed,
        status = status
    )

    // ---- isTrustedQuotaComplete: trusted count, NOT the legacy counter ----

    @Test
    fun `counter-complete but trusted-incomplete is NOT trusted-complete`() {
        // Legacy counter says done (3/3) but the trusted ledger has zero A+ entries. M-MG-02: the
        // trusted projection must rule — this task is NOT complete.
        val t = task(id = 1L, csvRow = 1, required = 3, completed = 3)
        assertEquals(
            "counter-complete with zero trusted entries must NOT be trusted-complete",
            false,
            PlanScheduler.isTrustedQuotaComplete(t, trustedCount = 0)
        )
    }

    @Test
    fun `trusted-complete with a zero legacy counter IS trusted-complete`() {
        // Inverse polarity: the trusted ledger reached the quota even though the legacy counter is 0.
        val t = task(id = 1L, csvRow = 1, required = 3, completed = 0)
        assertEquals(
            "trusted-complete must hold even when the legacy counter is zero",
            true,
            PlanScheduler.isTrustedQuotaComplete(t, trustedCount = 3)
        )
    }

    @Test
    fun `isTrustedQuotaComplete ignores a wildly inflated legacy counter`() {
        // Reinforces M-MG-02: an absurd legacy counter (99) must not force trusted-complete when
        // the trusted ledger is short.
        val t = task(id = 1L, csvRow = 1, required = 3, completed = 99)
        assertEquals(false, PlanScheduler.isTrustedQuotaComplete(t, trustedCount = 1))
    }

    // ---- selectNextTrusted: skip only on the trusted projection ----

    @Test
    fun `selectNextTrusted does not skip a counter-complete but trusted-incomplete active task`() {
        // taskA: active, counter-complete (3/3), but trustedCount=0 → must STILL be selected.
        // taskB: pending, lower priority by csvRow order.
        // Legacy selectNext would skip A (counter-complete) and pick B — exactly the bug.
        val a = task(id = 10L, csvRow = 1, required = 3, completed = 3, status = "active")
        val b = task(id = 11L, csvRow = 2, required = 3, completed = 0, status = "pending")
        val selected = PlanScheduler.selectNextTrusted(listOf(a, b), mapOf(10L to 0, 11L to 0))
        assertEquals(
            "a counter-complete/trusted-incomplete active task must NOT be skipped (M-MG-02)",
            10L,
            selected?.id
        )
    }

    @Test
    fun `selectNextTrusted skips a trusted-complete task even when its legacy counter is zero`() {
        // Inverse polarity: taskA is trusted-complete (3/3 trusted) but counter=0. Trusted selection
        // must SKIP it and fall through to the pending task. Legacy would pick A (counter-incomplete).
        val a = task(id = 10L, csvRow = 1, required = 3, completed = 0, status = "active")
        val b = task(id = 11L, csvRow = 2, required = 3, completed = 0, status = "pending")
        val selected = PlanScheduler.selectNextTrusted(listOf(a, b), mapOf(10L to 3, 11L to 0))
        assertEquals(
            "a trusted-complete task must be skipped even with a zero counter",
            11L,
            selected?.id
        )
    }

    @Test
    fun `selectNextTrusted returns null when every task is trusted-complete and none is pending`() {
        val a = task(id = 10L, csvRow = 1, required = 3, completed = 0, status = "active")
        val b = task(id = 11L, csvRow = 2, required = 3, completed = 0, status = "active")
        assertNull(
            PlanScheduler.selectNextTrusted(listOf(a, b), mapOf(10L to 3, 11L to 3))
        )
    }
}
