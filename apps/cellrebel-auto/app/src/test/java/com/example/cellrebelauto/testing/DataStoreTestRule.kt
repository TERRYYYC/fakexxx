package com.example.cellrebelauto.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * #143 governance — per-test lifecycle for Preference DataStore instances used by
 * ViewModel persistence oracles.
 *
 * Why a rule (the family's DataStore half):
 *  - every store gets a FRESH file inside a per-test temp directory — no cross-test /
 *    cross-Robolectric-class state can leak through a shared preferencesDataStore delegate
 *    (the #120 "poison writer" lesson, generalized);
 *  - the store scope is a REAL IO scope (never runTest's backgroundScope — DataStore's
 *    writer must run on real threads so assertions can poll it), and the rule cancels it
 *    in [finished] so no write continuation survives into the next test;
 *  - every file is deleted in [finished] — the hand-rolled teardowns leaked some files
 *    (SelfHeal's metrics file, RunV2's self-heal file).
 *
 * Ordering (empirically verified): declared after [MainDispatcherRule], this rule wraps
 * OUTSIDE it (JUnit4 applies later `@Rule` fields on the outside), so [finished] runs
 * AFTER the Main rule's drain + resetMain — scope cancel + temp-dir delete happen with
 * Main already reset. That is safe: this scope is a real-IO scope (SupervisorJob +
 * Dispatchers.IO) and DataStore's actor/writer never dispatch on Main, so nothing here
 * can hit the Main-swap concurrency guard; conversely, the Main-traffic sources are
 * still drained inside MainDispatcherRule.finished BEFORE its resetMain. No write
 * continuation survives into the next test (scope.cancel runs in this same per-test
 * teardown); the delete can only race a writer that has not yet observed cancellation —
 * i.e. an abandoned file in this test's own temp dir, never cross-test state, since
 * every test gets a fresh directory.
 *
 * #143 form-B (CI run 34871213797) — two INDEPENDENT remedies, the first fixing the
 * actual failure:
 *  - the FNF fix is [store] pre-creating the store file (empty): the reviewed mechanism
 *    (bytecode-verified against datastore 1.1.1) is the in-test first read racing the
 *    first write's atomicMove commit through readData's catch-handler exists-check —
 *    see the store() KDoc for the full chain;
 *  - teardown hygiene (kept): [finished] joins the scope to quiescence (bounded)
 *    before deleteRecursively — the delete racing an in-flight read cannot throw (the
 *    catch handler swallows post-delete ENOENT into the default) but would silently
 *    serve that read a stale default; joining pins the ordering. Both pinned by
 *    DataStoreTestRuleTest.
 */
open class DataStoreTestRule(
    /** Bounded wait in [finished] for the store scope to quiesce before the temp-dir delete. */
    protected val scopeQuiesceTimeoutMs: Long = 30_000,
) : TestWatcher() {

    lateinit var scope: CoroutineScope
        private set

    private lateinit var dir: File

    /** Test visibility: whether this test's temp dir is still on disk (lifecycle pins). */
    fun tempDirExists(): Boolean = this::dir.isInitialized && dir.exists()

    /** Test visibility: the temp dir's current entries (lifecycle pins). */
    fun tempDirEntries(): List<File> =
        if (this::dir.isInitialized) dir.listFiles()?.toList() ?: emptyList() else emptyList()

    open override fun starting(description: Description) {
        dir = Files.createTempDirectory("datastore-test").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** A fresh DataStore file inside this test's temp directory. */
    fun newFile(baseName: String): File =
        File(dir, "$baseName-${UUID.randomUUID()}.preferences_pb")

    /**
     * Standard factory wiring: one DataStore over ONE stable per-test file.
     * The path is resolved exactly ONCE — DataStore's produceFile lambda must be
     * idempotent (it may be invoked per read/write connection; a fresh path per
     * invocation sends reads and writes to different files, i.e. an intermittent
     * FileNotFoundException / stale-defaults flake).
     *
     * The file is PRE-CREATED empty (reviewed #143 form-B fix, CI run 34871213797).
     * Bytecode-verified mechanism (datastore 1.1.1): OkioReadScope.readData's exists()
     * check lives in its FileNotFoundException CATCH handler (handler@364:
     * `exists ? rethrow : getDefaultValue`), so an open-time ENOENT only escapes when
     * the file exists AGAIN by catch time — the creator being the first write's
     * atomicMove(scratch→path) commit (OkioStorageConnection write path). Reads and
     * writes are not excluded in-process (SingleProcessCoordinator.tryLock is a
     * non-blocking Mutex.tryLock), and the in-test first read starts when the VM's
     * stateIn subscription arrives (DataStoreImpl's only shareIn is the
     * WhileSubscribed update NOTIFICATION flow) — so read-before-commit, then
     * commit-before-catch is exactly the escaping FNF. Pre-creating the file means
     * open can never ENOENT (nothing deletes until teardown), killing that whole
     * race class; an empty file reads back as default/empty prefs (protobuf
     * parseFrom of empty input = default instance) — behavior-equivalent to the
     * missing-file default path.
     */
    open fun store(baseName: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val file = newFile(baseName)
        file.createNewFile()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    open override fun finished(description: Description) {
        scope.cancel()
        // Teardown hygiene (kept after #143 review): cancel() is COOPERATIVE — a
        // store-scope coroutine left mid-flight keeps running through cancel(). The
        // temp-dir delete racing such a read does NOT throw (readData's FNF catch
        // handler swallows post-delete ENOENT into the default) — it would silently
        // feed the read a stale default value after the test's assertions are done.
        // Joining the scope to quiescence (bounded) stops in-flight work from
        // outliving the test. The CI FNF itself is fixed at the SOURCE by store()
        // pre-creating the file (see the store() KDoc for the reviewed mechanism).
        awaitScopeQuiescence()
        dir.deleteRecursively()
    }

    private fun awaitScopeQuiescence() {
        val job = scope.coroutineContext[Job] ?: return
        try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(scopeQuiesceTimeoutMs) { job.join() }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Bounded: a child wedged past this deadline must not hang the suite's
            // teardown — fall back to the previous delete-anyway behavior. The deadline
            // is generous (CI starvation evidence is >30s for POLLING loops, but join
            // only waits out the current syscall + next cancellation checkpoint).
        }
    }
}
