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
 * #143 form-B (CI run 34871213797): the "delete can only race a writer" framing missed
 * that cancel() is cooperative — the EAGERLY-shared `store.data` first read can still be
 * mid-syscall when [finished] runs, and the delete landing inside its exists→open window
 * surfaces as a transient FileNotFoundException on a test that then passes. [finished]
 * therefore JOINS the scope to quiescence (bounded by [scopeQuiesceTimeoutMs]) before
 * deleting; pinned by DataStoreTestRuleTest.
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
     */
    open fun store(baseName: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val file = newFile(baseName)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    open override fun finished(description: Description) {
        scope.cancel()
        // #143 form-B remedy: cancel() is COOPERATIVE — a store-scope coroutine that a
        // starved runner left mid-syscall (DataStore shares `store.data` EAGERLY in this
        // scope, so the first read starts at store creation, subscriber or not) keeps
        // running through cancel(). DataStore 1.1.1's read path checks exists() then
        // opens the file (non-atomic, no suspension between), and this method's
        // deleteRecursively() is the only deleter in the codebase — when the delete lands
        // inside that exists→open window, a transient FileNotFoundException escapes the
        // SupervisorJob scope (no CoroutineExceptionHandler) and is attributed to
        // whatever test is running: CI run 34871213797, `auto-resume toggle…`, 0.031s.
        // Joining the scope to quiescence (bounded) serializes every in-flight
        // read/write tail BEFORE the delete, closing the window deterministically.
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
