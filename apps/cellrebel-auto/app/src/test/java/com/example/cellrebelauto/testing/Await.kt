package com.example.cellrebelauto.testing

/**
 * #143 governance — the single polling oracle for real-IO persistence assertions.
 *
 * DataStore writes complete on the store scope's real IO threads, far outside runTest's
 * virtual clock; Room projections land on Room's executors. Assertions must POLL, and the
 * deadline must be generous: on loaded CI runners the whole suite's parallel IO work can
 * starve DataStore's single-thread executor (or Room's) for many seconds — the 5s bounds
 * flaked there while passing locally 100% (SelfHeal watchdog toggle, RunV2 metric
 * selection; see the #143 issue log). 30s keeps the bound meaningful (still fails on a
 * real hang) while riding out runner contention.
 *
 * Centralized so the deadline can never silently regress to a per-class constant again.
 *
 * Mirror file of apps/qianwangyou/app/src/test/java/name/caiyao/fakegps/testing/Await.kt —
 * any change here must be double-written to the mirror.
 */
fun awaitUntil(deadlineMs: Long = 30_000, condition: suspend () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + deadlineMs
    while (!kotlinx.coroutines.runBlocking { condition() } && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
    }
    return kotlinx.coroutines.runBlocking { condition() }
}

/** Asserting variant: fails with [message] when the condition does not converge. */
fun awaitUntil(
    message: String,
    deadlineMs: Long = 30_000,
    condition: suspend () -> Boolean,
) {
    org.junit.Assert.assertTrue(message, awaitUntil(deadlineMs, condition))
}
