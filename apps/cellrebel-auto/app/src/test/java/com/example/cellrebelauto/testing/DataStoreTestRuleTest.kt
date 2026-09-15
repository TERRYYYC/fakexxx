package com.example.cellrebelauto.testing

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.Description
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #143 form-B regression pins — the DataStoreTestRule lifecycle (reviewed mechanism).
 *
 * CI evidence (run 34871213797): a transient FileNotFoundException on the store's FIRST
 * state load (`DataStoreImpl.readDataOrHandleCorruption` → `OkioReadScope.readData`) in
 * the `auto-resume toggle` case, 0.031s in — an IN-TEST failure, not teardown.
 * Bytecode-verified mechanism (datastore 1.1.1): readData's exists() check lives in its
 * FileNotFoundException catch handler (`exists ? rethrow : default`), so an ENOENT on
 * open escapes ONLY if the file exists again by catch time — the creator being the
 * first write's `atomicMove(scratch→path)` commit. Reads and writes are not excluded
 * in-process (`SingleProcessCoordinator.tryLock` is a non-blocking Mutex.tryLock) and
 * the first read starts at the VM's stateIn subscription (DataStoreImpl's only shareIn
 * is the WhileSubscribed update-notification flow), so read→(commit)→catch is the
 * escaping FNF.
 *
 * Two pins:
 *  1. "store pre-creates its file so the first read can never ENOENT" — the FNF fix:
 *     with the file created at store() time, open can never ENOENT (nothing deletes
 *     until teardown).
 *  2. the ordering pin below — teardown hygiene: `finished` must join the scope to
 *     quiescence BEFORE deleting the temp dir. The delete racing an in-flight read
 *     cannot throw (post-delete ENOENT is swallowed into the default) but would
 *     silently serve that read a stale default value; the deterministic stand-in for
 *     the in-flight read is a scope child already running and stuck below a
 *     cancellation checkpoint on a single-thread executor.
 */
class DataStoreTestRuleTest {

    /** Widens the protected lifecycle hooks so the test can drive them by hand. */
    private class LifecycleExposedRule : DataStoreTestRule() {
        public override fun starting(description: Description) = super.starting(description)
        public override fun finished(description: Description) = super.finished(description)
    }

    @Test
    fun `store pre-creates its file so the first read can never ENOENT`() {
        val rule = LifecycleExposedRule()
        val description = Description.createSuiteDescription(javaClass)
        rule.starting(description)
        try {
            rule.store("probe")
            val entries = rule.tempDirEntries()
            org.junit.Assert.assertEquals(
                "store() must create the DataStore file EAGERLY (empty) — the reviewed " +
                    "#143 form-B fix: the in-test first read (VM stateIn subscription) must " +
                    "never open-then-ENOENT before the first write's atomicMove commit",
                1,
                entries.size
            )
            org.junit.Assert.assertTrue(
                "the pre-created file must be the store's own .preferences_pb",
                entries.single().isFile && entries.single().name.startsWith("probe-") &&
                    entries.single().name.endsWith(".preferences_pb")
            )
        } finally {
            rule.finished(description)
        }
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
                        "in flight — cancel() is cooperative; finished must join the scope " +
                        "to quiescence BEFORE deleteRecursively, else the delete silently " +
                        "feeds an in-flight read a stale default (post-delete ENOENT is " +
                        "swallowed by readData's catch handler)",
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
