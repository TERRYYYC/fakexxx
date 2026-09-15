package com.example.cellrebelauto.testing

import org.junit.rules.MethodRule
import org.junit.runners.model.FrameworkMethod
import org.junit.runners.model.Statement

/**
 * Explicit retry for ALREADY-ARCHIVED CI-flaky cases only (#143 archive) — never a
 * default.
 *
 * Why not gradle test-retry / class-wide retry: a blanket retry silently absorbs NEW
 * flaky tests, converting "CI red = investigate now" into "CI green = maybe flaky".
 * The two archived cases (#143) are the opposite: timing-sensitive, always green
 * locally (including stress loops), red only on loaded CI runners, green on rerun —
 * rerun-lottery costs three CI rounds per PR while an EXPLICIT per-method opt-in
 * documents the flake at the use site. This rule exists ONLY to make that opt-in
 * possible; the root-cause work stays tracked in #143.
 *
 * Hard lines (all pinned by RetryRuleTest):
 *  - Whitelist-scoped: [onlyMethods] names the archived cases EXPLICITLY. Any other
 *    method — including siblings in the same class — is returned UNWRAPPED: zero
 *    behavior change, a new flaky surfaces on its first failure. The constructor
 *    rejects an empty whitelist so this can never degrade into class-level retry.
 *  - AssertionError-only: the archived form is assertion failure under runner timing
 *    pressure. Any other Throwable (a real crash, infrastructure breakage) escapes on
 *    the FIRST attempt — retrying those would hide genuine breakage.
 *  - Aggregated exhaustion: after [maxAttempts] failures the LAST AssertionError is
 *    rethrown as the cause (real trace preserved), earlier attempts attached as
 *    suppressed, and the message lists every round.
 *  - Every round is logged to stdout (visible in the gradle test report) so a pass-on-
 *    retry is never silent.
 *
 * Fixture note: a JUnit4 MethodRule wraps the statement WITH @Before/@After inside it
 * (befores/afters re-run per attempt), so each retry runs against FRESH fixtures —
 * retrying a dirty state would test nothing. Declare this rule FIRST in the field
 * order when combined with the lifecycle rules (first-declared = innermost), so the
 * Main/DataStore lifecycle still runs ONCE per test method, outside the retry loop.
 */
class RetryRule(
    /** Total attempts INCLUDING the first (1 = no retry). */
    private val maxAttempts: Int,
    /**
     * Explicit allow-list of archived flaky method names. Must be non-empty on
     * purpose: an accidental class-wide default is exactly the red line this rule
     * refuses to cross.
     */
    private val onlyMethods: Set<String>,
) : MethodRule {

    init {
        require(maxAttempts >= 1) { "maxAttempts must be >= 1 (got $maxAttempts)" }
        require(onlyMethods.isNotEmpty()) {
            "onlyMethods must name the archived flaky cases explicitly — " +
                "a blanket/class-wide retry is forbidden (#143 red line)"
        }
    }

    override fun apply(base: Statement, method: FrameworkMethod, target: Any?): Statement {
        // Not on the archived list: pass the statement through untouched.
        if (method.name !in onlyMethods) return base
        return object : Statement() {
            override fun evaluate() {
                val failures = mutableListOf<AssertionError>()
                for (attempt in 1..maxAttempts) {
                    try {
                        base.evaluate()
                        if (attempt > 1) {
                            println(
                                "[RetryRule] ${method.name}: PASSED on attempt " +
                                    "$attempt/$maxAttempts — earlier rounds: " +
                                    failures.mapIndexed { i, f -> "#${i + 1}: ${f.message}" }
                            )
                        }
                        return
                    } catch (e: AssertionError) {
                        failures += e
                        if (attempt < maxAttempts) {
                            println(
                                "[RetryRule] ${method.name}: attempt $attempt/$maxAttempts " +
                                    "failed with AssertionError (${e.message}) — retrying"
                            )
                        }
                        // AssertionError is the ONLY retryable kind; anything else falls
                        // through to the rethrow below on its first occurrence.
                    }
                }
                val last = failures.last()
                println(
                    "[RetryRule] ${method.name}: EXHAUSTED all $maxAttempts attempts — " +
                        "aggregating ${failures.size} AssertionError rounds"
                )
                throw AssertionError(
                    "${method.name}: still failing after $maxAttempts attempts " +
                        "(archived CI flaky, AssertionError-only retry, #143). Per attempt: " +
                        failures.mapIndexed { i, f -> "attempt ${i + 1}: ${f.message}" }
                            .joinToString("; "),
                    last
                ).apply {
                    // Earlier rounds ride along as suppressed; the cause keeps the last
                    // round's original trace as the primary failure evidence.
                    failures.dropLast(1).forEach { addSuppressed(it) }
                }
            }
        }
    }
}
