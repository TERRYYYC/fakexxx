package com.example.cellrebelauto.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * Pins for [RetryRule] — the #143 transitional-retry hard lines. The rule is driven by
 * hand (apply + evaluate, the DataStoreTestRuleTest pattern) so each pin observes the
 * exact attempt count without needing a flaky test class in the suite.
 *
 *  1. retries an AssertionError and passes within the budget (the archived-case shape);
 *  2. aggregates ALL rounds when the budget is exhausted — last failure as cause,
 *     earlier rounds suppressed, every round named in the message;
 *  3. never retries a non-AssertionError — a real crash escapes on attempt 1, same
 *     instance, untouched;
 *  4. non-whitelisted methods are never wrapped — a NEW flaky in a sibling method of
 *     the same class must surface immediately (the anti-blanket red line);
 *  5. the constructor rejects blanket/zero configurations so the rule cannot degrade
 *     into a class-wide or global default.
 */
class RetryRuleTest {

    /** Reflection fixture supplying FrameworkMethods with real (whitelistable) names. */
    private class Probe {
        @Suppress("unused")
        fun archivedCase() {
        }

        @Suppress("unused")
        fun siblingCase() {
        }
    }

    private fun probeMethod(name: String) = FrameworkMethod(Probe::class.java.getDeclaredMethod(name))

    /** Wraps the body as a Statement (an abstract class — no SAM conversion) and evaluates the rule. */
    private fun evaluate(rule: RetryRule, methodName: String, body: () -> Unit) {
        val base = object : Statement() {
            override fun evaluate() = body()
        }
        rule.apply(base, probeMethod(methodName), Probe()).evaluate()
    }

    @Test
    fun `retries assertion failures until a pass within the budget`() {
        val rule = RetryRule(maxAttempts = 3, onlyMethods = setOf("archivedCase"))
        var attempts = 0

        evaluate(rule, "archivedCase") {
            attempts++
            if (attempts < 3) throw AssertionError("runner-timing flake, round $attempts")
        }

        assertEquals("must run exactly until the first passing round", 3, attempts)
    }

    @Test
    fun `aggregates every round when the retry budget is exhausted`() {
        val rule = RetryRule(maxAttempts = 3, onlyMethods = setOf("archivedCase"))
        var attempts = 0

        val thrown = org.junit.Assert.assertThrows(AssertionError::class.java) {
            evaluate(rule, "archivedCase") {
                attempts++
                throw AssertionError("round-$attempts assertion detail")
            }
        }

        assertEquals(3, attempts)
        assertTrue(
            "the aggregate message must name every round (CI log must show the full history)",
            thrown.message.orEmpty().contains("attempt 1: round-1 assertion detail") &&
                thrown.message.orEmpty().contains("attempt 2: round-2 assertion detail") &&
                thrown.message.orEmpty().contains("attempt 3: round-3 assertion detail")
        )
        assertEquals(
            "the LAST failure must be the cause (its trace is the primary evidence)",
            "round-3 assertion detail",
            thrown.cause!!.message
        )
        assertEquals(
            "earlier rounds must ride along as suppressed, in order",
            listOf("round-1 assertion detail", "round-2 assertion detail"),
            thrown.suppressed.map { it.message }
        )
    }

    @Test
    fun `never retries a non-assertion failure`() {
        val rule = RetryRule(maxAttempts = 3, onlyMethods = setOf("archivedCase"))
        var attempts = 0
        val crash = IllegalStateException("real breakage, not a timing flake")

        val thrown = org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            evaluate(rule, "archivedCase") {
                attempts++
                throw crash
            }
        }

        assertSame("must escape on the FIRST attempt, same instance, untouched", crash, thrown)
        assertEquals("no retry for non-AssertionError", 1, attempts)
    }

    @Test
    fun `non-whitelisted siblings are never wrapped even on assertion failures`() {
        val rule = RetryRule(maxAttempts = 3, onlyMethods = setOf("archivedCase"))
        var attempts = 0

        org.junit.Assert.assertThrows(AssertionError::class.java) {
            evaluate(rule, "siblingCase") {
                attempts++
                throw AssertionError("a NEW flaky must surface immediately, not retry")
            }
        }

        assertEquals("outside the whitelist: single attempt, zero wrapping", 1, attempts)
    }

    @Test
    fun `rejects blanket and zero configurations`() {
        val emptyWhitelist = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RetryRule(maxAttempts = 3, onlyMethods = emptySet())
        }
        assertTrue(
            "an empty whitelist must be rejected at construction — it would silently become " +
                "a class-wide retry (#143 red line)",
            emptyWhitelist.message.orEmpty().contains("blanket")
        )

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            RetryRule(maxAttempts = 0, onlyMethods = setOf("archivedCase"))
        }
    }
}
