package name.caiyao.fakegps.testing

/**
 * #143 governance — the single polling oracle for async projections (Room flows, IO
 * workers, publish chains). ViewModel actions run on viewModelScope and their effects
 * land on Room/IO executors — assertions must POLL, and the deadline must be generous:
 * on loaded CI runners the whole suite's parallel IO work can starve those executors for
 * many seconds (the #143 issue log). 30s keeps the bound meaningful (still fails on a
 * real hang) while riding out runner contention.
 *
 * Centralized so the deadline can never silently regress to a per-class constant again.
 */
fun awaitUntil(deadlineMs: Long = 30_000, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + deadlineMs
    while (!condition() && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
    }
    return condition()
}

/** Asserting variant: fails with [message] when the condition does not converge. */
fun awaitUntil(
    message: String,
    deadlineMs: Long = 30_000,
    condition: () -> Boolean,
) {
    org.junit.Assert.assertTrue(message, awaitUntil(deadlineMs, condition))
}
