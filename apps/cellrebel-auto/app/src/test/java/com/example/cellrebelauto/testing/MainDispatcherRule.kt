package com.example.cellrebelauto.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * #143 governance — the ONE place that owns Dispatchers.setMain/resetMain for
 * ViewModel+dispatcher oracles.
 *
 * Root cause of the flaky family (9 CI hits across 4 test classes): kotlinx-coroutines-test
 * 1.9's TestMainDispatcher guards its delegate with a lock-free
 * NonConcurrentlyModifiable — `Dispatchers.setMain`/`resetMain` throw
 * `IllegalStateException("Dispatchers.Main is used concurrently with setting it")`
 * when ANY in-flight dispatch on the current Main overlaps the swap (or overlapped a
 * PAST swap — those are deferred and thrown on the next set/reset). ViewModel tests leak
 * Main-dispatching coroutines across class boundaries (viewModelScope init collectors,
 * Eagerly stateIn, DataStore/Room continuations resuming on real IO threads), so on slow
 * CI runners a trailing dispatch from class A lands exactly on class B's setMain.
 *
 * Two disciplines, both enforced here:
 *  1. [cancelTracked] before resetMain — cancelled scopes stop producing new Main traffic
 *     (the #111 lesson). Tracked ViewModels/scopes are drained in [finished], and can be
 *     drained earlier in a test's @After (before closing DBs/files) — cancelling twice is
 *     idempotent.
 *  2. Retry the swap on the concurrency IllegalStateException — the offending window is
 *     microseconds wide and every offender is finite (trailing continuations of cancelled
 *     scopes), so retrying converges deterministically instead of racing a fixed
 *     `Thread.sleep(250)` settle window. Note the library's write may take effect BEFORE
 *     its post-write check throws, so retrying set/reset is idempotent-safe.
 *
 * Ordering (empirically verified): JUnit4 applies later-declared `@Rule` fields on the
 * OUTSIDE, so declaring this rule FIRST makes it the INNERMOST rule. Observed order with
 * declaration [this first, DataStoreTestRule second]:
 *   dataStore.starting → main.starting (setMain) → test →
 *   main.finished (cancelTracked + retry-guarded resetMain) → dataStore.finished.
 * The invariant that matters lives inside [finished]: every tracked Main-traffic source
 * is drained BEFORE resetMain — that property, not outermost nesting, is what the swap
 * retry relies on. setMain running after dataStore.starting is harmless (that only
 * creates a temp dir + real-IO scope, no Main traffic), and sibling-rule cleanup running
 * after resetMain is safe (real-IO scope, never dispatches on Main — see
 * DataStoreTestRule's KDoc).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    /** Deadline for converging a contended setMain/resetMain swap. */
    private val swapTimeoutMs: Long = 30_000,
) : TestWatcher() {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()
    private val scopes = mutableListOf<CoroutineScope>()

    /** Register a ViewModel whose viewModelScope must be drained before resetMain. */
    fun trackViewModel(vm: ViewModel) {
        viewModels += vm
    }

    /** Register any extra real scope (collectors, DataStore writers) to drain before resetMain. */
    fun trackScope(scope: CoroutineScope) {
        scopes += scope
    }

    /**
     * Drain every tracked ViewModel scope and scope. Safe to call multiple times
     * (e.g. at the top of @After, again in [finished]).
     */
    fun cancelTracked() {
        viewModels.forEach { it.viewModelScope.cancel() }
        scopes.forEach { it.cancel() }
    }

    override fun starting(description: Description) {
        swapWithRetry { Dispatchers.setMain(testDispatcher) }
    }

    override fun finished(description: Description) {
        cancelTracked()
        swapWithRetry { Dispatchers.resetMain() }
    }

    private fun swapWithRetry(block: () -> Unit) {
        val deadline = System.currentTimeMillis() + swapTimeoutMs
        while (true) {
            try {
                block()
                return
            } catch (e: IllegalStateException) {
                // Only retry the library's own concurrency guard; other ISEs are real bugs.
                if (!e.message.orEmpty().contains("Dispatchers.Main")) throw e
                if (System.currentTimeMillis() >= deadline) throw e
                Thread.sleep(10)
            }
        }
    }
}
