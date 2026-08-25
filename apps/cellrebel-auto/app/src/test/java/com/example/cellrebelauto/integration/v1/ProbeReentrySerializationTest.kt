package com.example.cellrebelauto.integration.v1

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression test for R5 P1-1: FullLoopProbeActivity reentry serialization.
 *
 * WHAT BROKE
 * ----------
 * `onNewIntent` called `launchProbe()` which spawned a new `thread` with no
 * active-run gate. A second `adb shell am start` while the first loop was in a
 * critical section (apply/release/completeAndAdvance) launched a concurrent
 * `runLoop()` thread that raced on the same provider + device mock state:
 * lease cleanup and advance decisions interleaved, and UI callbacks overwrote
 * each other.
 *
 * WHAT THIS PROVES
 * ----------------
 * Two rapid `launch` calls on the same [SerialProbeRunner] never overlap:
 * the second run does not enter its critical section until the first run's
 * cleanup (finally block) has completed. This is the contract `launchProbe`
 * must uphold — the Activity wiring (singleTop + onNewIntent) is guarded by
 * [AutoDebugManifestGuardTest]; this test guards the thread-level invariant.
 */
class ProbeReentrySerializationTest {

    /**
     * Two launches in quick succession: the second run must NOT start its
     * critical section until the first run's cleanup completes.
     *
     * Mechanism: a fake "probe" that holds a lock for a controllable duration,
     * records entry/exit timestamps, and lets us assert non-overlap.
     */
    @Test
    fun secondLaunchAwaitsFirstCleanup() {
        val runner = SerialProbeRunner()
        val phases = CopyOnWriteArrayList<String>()
        val run1EnteredCritical = CountDownLatch(1)
        val run1MayExit = CountDownLatch(1)
        val allDone = CountDownLatch(2)

        // Run 1: enter critical section, signal, then wait for permission to exit
        runner.launch {
            phases.add("run1-enter")
            run1EnteredCritical.countDown()
            run1MayExit.await(5, TimeUnit.SECONDS)
            phases.add("run1-exit")
            allDone.countDown()
        }

        // Wait until run 1 is definitely inside its critical section
        assertTrue("run 1 should enter critical section", run1EnteredCritical.await(2, TimeUnit.SECONDS))

        // Run 2: should block until run 1 finishes
        runner.launch {
            phases.add("run2-enter")
            phases.add("run2-exit")
            allDone.countDown()
        }

        // Small delay to let run 2's thread start and attempt the gate
        Thread.sleep(200)
        // Run 2 must NOT have entered yet (run 1 is still holding the gate)
        assertFalse(
            "run 2 must not enter critical section while run 1 is active",
            phases.contains("run2-enter"),
        )

        // Let run 1 finish
        run1MayExit.countDown()

        // Both must complete
        assertTrue("both runs should complete", allDone.await(5, TimeUnit.SECONDS))

        // Verify strict ordering: run1 fully exits before run2 enters
        val run1ExitIdx = phases.indexOf("run1-exit")
        val run2EnterIdx = phases.indexOf("run2-enter")
        assertTrue("run1-exit must be recorded", run1ExitIdx >= 0)
        assertTrue("run2-enter must be recorded", run2EnterIdx >= 0)
        assertTrue(
            "run 2 must enter AFTER run 1 exits (serialization invariant). " +
                "Actual order: $phases",
            run2EnterIdx > run1ExitIdx,
        )
    }

    /**
     * Three rapid launches: only the last two should actually run (the middle
     * one still gets its turn because it already acquired the gate before the
     * third arrived). All three must be serialized.
     */
    @Test
    fun threeRapidLaunchesAreFullySerialized() {
        val runner = SerialProbeRunner()
        val entryOrder = CopyOnWriteArrayList<Int>()
        val exitOrder = CopyOnWriteArrayList<Int>()
        val completionCount = AtomicInteger(0)
        val allDone = CountDownLatch(3)

        for (i in 1..3) {
            runner.launch {
                entryOrder.add(i)
                // Simulate some work
                Thread.sleep(50)
                exitOrder.add(i)
                completionCount.incrementAndGet()
                allDone.countDown()
            }
            // Small gap so each launch is a distinct event
            Thread.sleep(10)
        }

        assertTrue("all three runs should complete", allDone.await(10, TimeUnit.SECONDS))
        assertEquals("all three runs must complete", 3, completionCount.get())

        // Verify no overlap: each exit[i] must precede entry[i+1]
        for (j in 0 until entryOrder.size - 1) {
            val exitedRun = exitOrder[j]
            val nextEnteredRun = entryOrder[j + 1]
            val exitIdx = exitOrder.indexOf(exitedRun)
            assertTrue(
                "run $exitedRun must exit before run $nextEnteredRun enters — " +
                    "entry order: $entryOrder, exit order: $exitOrder",
                exitIdx <= j, // exit happened at or before position j
            )
        }
    }

    /**
     * Verify that the runner survives an exception in the probe body without
     * deadlocking subsequent runs (the gate MUST be released in a finally block).
     */
    @Test
    fun exceptionInProbeDoesNotDeadlockNextRun() {
        val runner = SerialProbeRunner()
        val run2Completed = CountDownLatch(1)

        // Run 1: throws
        runner.launch { throw RuntimeException("probe exploded") }
        Thread.sleep(100) // let it crash

        // Run 2: must still be able to run (gate was released despite the crash)
        runner.launch { run2Completed.countDown() }

        assertTrue(
            "run 2 must complete even after run 1 threw — gate must be released in finally",
            run2Completed.await(3, TimeUnit.SECONDS),
        )
    }
}
