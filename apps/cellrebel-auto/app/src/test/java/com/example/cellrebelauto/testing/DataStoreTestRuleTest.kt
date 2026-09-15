package com.example.cellrebelauto.testing

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.Description
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #143 form-B regression pin — the DataStoreTestRule lifecycle ordering.
 *
 * CI evidence (run 34871213797): a transient FileNotFoundException on the store's FIRST
 * state load (`DataStoreImpl.readDataOrHandleCorruption` → `OkioReadScope.readData`) in
 * the `auto-resume toggle` case, 0.031s in. DataStore 1.1.1 checks `exists()` before
 * opening (decompiled OkioStorage), so the FNF is a genuine TOCTOU: the only deleter in
 * the codebase is this rule's `dir.deleteRecursively()` in [finished], and `scope.cancel()`
 * is cooperative — it cannot stop a first-read coroutine that a starved runner has left
 * mid-flight (DataStore shares `store.data` EAGERLY in the rule scope, so the read starts
 * at store creation, no subscriber needed). When the delete lands inside the read's
 * exists→open window, the FNF escapes the SupervisorJob scope (no handler) and is
 * attributed to whatever test is running.
 *
 * The pin: `finished` must NOT return — i.e. must not have deleted the temp dir — while
 * store-scope work is still in flight. The deterministic stand-in for the in-flight read
 * is a rule-scope child queued behind a thread-holding blocker on a single-thread
 * executor: it cannot observe `scope.cancel()` until released, exactly like a read stuck
 * mid-syscall on a starved runner thread.
 */
class DataStoreTestRuleTest {

    /** Widens the protected lifecycle hooks so the test can drive them by hand. */
    private class LifecycleExposedRule : DataStoreTestRule() {
        public override fun starting(description: Description) = super.starting(description)
        public override fun finished(description: Description) = super.finished(description)
    }

    @Test
    fun `finished waits for in-flight store-scope work to quiesce before deleting the temp dir`() {
        val rule = LifecycleExposedRule()
        val description = Description.createSuiteDescription(javaClass)
        rule.starting(description)

        val executor = Executors.newSingleThreadExecutor()
        val childStarted = CountDownLatch(1)
        val releaseAll = CountDownLatch(1)
        var childTailObserved = false
        try {
            // The child must be RUNNING (past dispatch) and stuck BELOW a cancellation
            // checkpoint — a blocking latch await, not a suspending one: scope.cancel()
            // cannot finish it, exactly like a DataStore read blocked mid-syscall on a
            // starved runner thread. (A cancelled-but-not-yet-started coroutine would
            // skip its body entirely, which models nothing.)
            rule.scope.launch(executor.asCoroutineDispatcher()) {
                childStarted.countDown()
                releaseAll.await()
                childTailObserved = true
            }
            childStarted.await()

            val finishedReturned = CountDownLatch(1)
            val teardown = Thread {
                rule.finished(description)
                finishedReturned.countDown()
            }
            teardown.start()
            try {
                org.junit.Assert.assertFalse(
                    "DataStoreTestRule.finished returned while store-scope work was still " +
                        "in flight — cancel() is cooperative, finished must join the scope " +
                        "to quiescence BEFORE deleteRecursively (form-B FNF mechanism)",
                    finishedReturned.await(500, TimeUnit.MILLISECONDS)
                )
            } finally {
                releaseAll.countDown()
                teardown.join(TimeUnit.SECONDS.toMillis(30))
            }

            org.junit.Assert.assertEquals(
                "finished must return only after the scope has quiesced",
                0L,
                finishedReturned.count
            )
            org.junit.Assert.assertTrue(
                "the in-flight child must be allowed to run its tail to completion " +
                    "(not abandoned mid-syscall)",
                childTailObserved
            )
            org.junit.Assert.assertFalse(
                "the temp dir must still be deleted after quiescence",
                rule.tempDirExists()
            )
        } finally {
            releaseAll.countDown()
            executor.shutdownNow()
        }
    }
}
