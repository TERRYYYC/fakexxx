package com.example.cellrebelauto.testing

import org.junit.Test

/**
 * #143 form-A regression pin — the polling oracle's two variants have OPPOSITE timeout
 * semantics, and the difference is load-bearing:
 *
 *  - the boolean variant returns false on timeout WITHOUT throwing. That silence is the
 *    anti-pattern that produced CI run 34874550513: the watchdog toggle poll consumed its
 *    full 30.038s deadline, returned false quietly, and the failure surfaced two lines
 *    later as a bare AssertionError on the DEFAULT value (SelfHealDashboardViewModelTest)
 *    with zero poll context. Persistence-assert call sites must therefore use the
 *    message (asserting) variant — every poll in SelfHealDashboardViewModelTest does.
 *  - the asserting variant fails WITH the message when the condition never converges.
 *
 * Deliberately short deadlines (200ms) — these pin timeout SEMANTICS, not convergence.
 */
class AwaitTest {

    @Test
    fun `boolean variant times out silently - the form-A blind spot persistence polls must avoid`() {
        val converged = awaitUntil(deadlineMs = 200) { false }
        // No throw, just false: whoever ignores the return value learns nothing about
        // the poll — exactly why toggle-persist call sites must not use this variant.
        org.junit.Assert.assertFalse("a never-true condition must report no convergence", converged)
    }

    @Test
    fun `asserting variant fails with the given message when the condition never converges`() {
        val error = org.junit.Assert.assertThrows(
            java.lang.AssertionError::class.java
        ) {
            awaitUntil(
                "toggle write did not reach the persisted store",
                deadlineMs = 200,
            ) { false }
        }
        org.junit.Assert.assertEquals(
            "timeout must carry the poll's own message",
            "toggle write did not reach the persisted store",
            error.message
        )
    }

    @Test
    fun `asserting variant returns normally when the condition converges`() {
        awaitUntil("immediately true", deadlineMs = 5_000) { true }
    }

    @Test
    fun `boolean variant reports convergence when the condition flips true in window`() {
        var flipped = false
        val converged = awaitUntil(deadlineMs = 5_000) {
            if (!flipped) flipped = true
            flipped
        }
        org.junit.Assert.assertTrue("an in-window flip must converge", converged)
    }
}
