package com.example.cellrebelauto.integration.v1

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * Serializes probe launches so that at most one probe body runs at a time.
 *
 * WHY THIS EXISTS (R5 P1-1)
 * -------------------------
 * `FullLoopProbeActivity.launchProbe()` spawned a new thread on every
 * `onNewIntent` with no gate. Two concurrent `runLoop()` threads raced on the
 * same provider and device mock state: lease cleanup, advance decisions, and UI
 * callbacks all corrupted. The same pattern existed in `HandshakeProbeActivity`.
 *
 * HOW IT WORKS
 * ------------
 * Each [launch] spawns a worker thread that acquires [runGate] before entering
 * the probe body. If a previous run is still active (holding the lock), the new
 * thread blocks until the previous run's `finally` block releases the lock —
 * which includes the lease-release cleanup in `FullLoopProbeActivity.runLoop()`.
 *
 * This satisfies the claim in the `onNewIntent` comment: "the previous run's
 * mock state is cleaned up before the new run starts." The blocking happens on
 * the WORKER thread, never the main/UI thread, so there is no ANR risk.
 *
 * The lock is `fair = true` so queued runs execute in launch order rather than
 * racing for the gate — a third `am start` while the second is queued gets
 * position 3, not a random slot.
 *
 * GATE GUARANTEE
 * --------------
 * The gate is always released in a `finally` block, even if the probe body
 * throws. A crashed probe must not deadlock subsequent launches.
 *
 * THREAD NAMING
 * -------------
 * Caller supplies [threadName] so logcat attribution stays clear when multiple
 * probe types share the same runner pattern (they don't share the same runner
 * instance — each Activity has its own).
 */
class SerialProbeRunner {

    private val runGate = ReentrantLock(/* fair = */ true)

    /**
     * Launch [body] on a new worker thread, serialized behind any active run.
     *
     * @param threadName Name for the worker thread (appears in logcat/thread dumps).
     * @param body The probe logic to execute. Runs under [runGate] — only one body
     *   executes at a time across all [launch] calls on this runner instance.
     */
    fun launch(threadName: String = "serial-probe", body: () -> Unit) {
        thread(name = threadName) {
            runGate.lock()
            try {
                body()
            } finally {
                runGate.unlock()
            }
        }
    }
}
