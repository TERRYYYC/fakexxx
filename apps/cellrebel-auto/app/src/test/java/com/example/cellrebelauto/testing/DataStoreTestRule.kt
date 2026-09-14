package com.example.cellrebelauto.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
 */
class DataStoreTestRule : TestWatcher() {

    lateinit var scope: CoroutineScope
        private set

    private lateinit var dir: File

    override fun starting(description: Description) {
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
    fun store(baseName: String): androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences> {
        val file = newFile(baseName)
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { file },
        )
    }

    override fun finished(description: Description) {
        scope.cancel()
        dir.deleteRecursively()
    }
}
