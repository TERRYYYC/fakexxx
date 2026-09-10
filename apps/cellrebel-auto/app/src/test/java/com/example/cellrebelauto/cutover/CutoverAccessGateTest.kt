package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CutoverAccessGateTest {
    private val identity = CutoverRestoreIdentity(
        archiveDigest = "sha256:${"a".repeat(64)}",
        captureId = "capture-a"
    )

    @Test
    fun exclusiveRequestClosesAdmissionThenWaitsForAdmittedAccessToDrain() = runTest {
        val gate = CutoverAccessGate.open()
        val normalEntered = CompletableDeferred<Unit>()
        val finishNormal = CompletableDeferred<Unit>()
        val normal = async {
            gate.withNormalAccess {
                normalEntered.complete(Unit)
                finishNormal.await()
                "normal-result"
            }
        }
        normalEntered.await()

        val exclusive = async { gate.acquireExclusive(identity) }
        runCurrent()

        assertEquals(
            CutoverGateSnapshot(CutoverGatePhase.DRAINING, activeNormalAccesses = 1, identity),
            gate.snapshot()
        )
        val rejected = gate.withNormalAccess { error("must not run") }
        assertEquals(
            CutoverAccessResult.Unavailable(CutoverUnavailableReason.CUTOVER_IN_PROGRESS, identity),
            rejected
        )

        finishNormal.complete(Unit)
        assertEquals(CutoverAccessResult.Granted("normal-result"), normal.await())
        val lease = requireType<CutoverExclusiveAdmission.Granted>(exclusive.await()).lease
        assertEquals(
            CutoverGateSnapshot(CutoverGatePhase.EXCLUSIVE, activeNormalAccesses = 0, identity),
            gate.snapshot()
        )
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun completeCloseAndReopenBetweenCollectorDispatchesRestartsGateFlow() = runTest {
        val gate = CutoverAccessGate.open()
        var subscriptions = 0
        val observed = mutableListOf<Int>()
        val collection = backgroundScope.launch {
            gate.gateFlow(
                flow {
                    subscriptions += 1
                    emit(subscriptions)
                    awaitCancellation()
                }
            ).collect { observed += it }
        }
        runCurrent()
        assertEquals(listOf(1), observed)

        val lease = requireType<CutoverExclusiveAdmission.Granted>(
            gate.acquireCaptureExclusive("gate-flow-open-cycle") { true }
        ).lease
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
        runCurrent()

        assertEquals("a distinct open cycle must resubscribe even if close was conflated", listOf(1, 2), observed)
        collection.cancel()
    }

    @Test
    fun secondExclusiveOwnerGetsTypedConflictWithoutStealingLease() = runTest {
        val gate = CutoverAccessGate.open()
        val first = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        val other = identity.copy(captureId = "capture-b")

        assertEquals(
            CutoverExclusiveAdmission.Busy(identity),
            gate.acquireExclusive(other)
        )
        assertEquals(identity, gate.snapshot().identity)
        assertTrue(first.release(CutoverExclusiveRelease.OPEN))
    }

    @Test
    fun quiescenceRunsAfterAdmissionClosesAndFailureReopensWithoutWaitingForDrain() = runTest {
        val gate = CutoverAccessGate.open()
        val normalStarted = CompletableDeferred<Unit>()
        val finishNormal = CompletableDeferred<Unit>()
        val normal = async {
            gate.withNormalAccess {
                normalStarted.complete(Unit)
                finishNormal.await()
            }
        }
        normalStarted.await()

        val admission = gate.acquireExclusive(identity) {
            assertEquals(
                CutoverUnavailableReason.CUTOVER_IN_PROGRESS,
                (gate.withNormalAccess { error("must not run") } as CutoverAccessResult.Unavailable).reason
            )
            false
        }

        assertEquals(CutoverExclusiveAdmission.QuiescenceFailed, admission)
        assertEquals(CutoverGatePhase.OPEN, gate.snapshot().phase)
        finishNormal.complete(Unit)
        normal.await()
    }

    @Test
    fun thrownQuiescenceCannotLeaveAdmissionPermanentlyDraining() = runTest {
        val gate = CutoverAccessGate.open()

        val failure = runCatching {
            gate.acquireExclusive(identity) { throw QuiescenceFailure() }
        }.exceptionOrNull()

        assertTrue(failure is QuiescenceFailure)
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
        assertEquals(CutoverAccessResult.Granted("open"), gate.withNormalAccess { "open" })
    }

    @Test
    fun restartWithActiveJournalBlocksNormalAccessAndOnlyMatchingGenerationMayResume() = runTest {
        val staged = CutoverRestoreJournal(identity, CutoverRestorePhase.STAGED)
        val gate = CutoverAccessGate.fromJournal(staged)

        assertEquals(
            CutoverAccessResult.Unavailable(CutoverUnavailableReason.RECOVERY_REQUIRED, identity),
            gate.withNormalAccess { error("must not run") }
        )
        assertEquals(
            CutoverExclusiveAdmission.Busy(identity),
            gate.acquireExclusive(identity.copy(captureId = "different"))
        )

        val resumed = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        assertTrue(resumed.release(CutoverExclusiveRelease.RECOVERY_REQUIRED))
        assertEquals(CutoverGatePhase.RECOVERY_REQUIRED, gate.snapshot().phase)
    }

    @Test
    fun terminalOrAbsentJournalStartsOpenButEveryNonTerminalPhaseStartsClosed() = runTest {
        assertEquals(CutoverGateSnapshot.open(), CutoverAccessGate.fromJournal(null).snapshot())
        assertEquals(
            CutoverGateSnapshot.open(),
            CutoverAccessGate.fromJournal(CutoverRestoreJournal(identity, CutoverRestorePhase.READY)).snapshot()
        )
        val rolledBack = CutoverRestoreJournal(
            identity,
            CutoverRestorePhase.ROLLED_BACK,
            CutoverRestoreFailureReason.INTERRUPTED
        )
        assertEquals(CutoverGateSnapshot.open(), CutoverAccessGate.fromJournal(rolledBack).snapshot())

        val active = CutoverRestorePhase.entries - setOf(
            CutoverRestorePhase.READY,
            CutoverRestorePhase.ROLLED_BACK
        )
        active.forEach { phase ->
            val journal = CutoverRestoreJournal(
                identity,
                phase,
                if (phase == CutoverRestorePhase.ROLLBACK_REQUIRED) {
                    CutoverRestoreFailureReason.INTERRUPTED
                } else {
                    null
                }
            )
            assertEquals(
                CutoverGatePhase.RECOVERY_REQUIRED,
                CutoverAccessGate.fromJournal(journal).snapshot().phase
            )
        }
    }

    @Test
    fun blockedWriteHasNoSideEffectAndStaleLeaseCannotReopenNewerRecovery() = runTest {
        val gate = CutoverAccessGate.open()
        val lease = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        var writes = 0

        val blocked = gate.withNormalAccess { writes += 1 }
        requireType<CutoverAccessResult.Unavailable>(blocked)
        assertEquals(0, writes)

        assertTrue(lease.release(CutoverExclusiveRelease.RECOVERY_REQUIRED))
        val resumed = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        assertFalse(lease.release(CutoverExclusiveRelease.OPEN))
        assertEquals(CutoverGatePhase.EXCLUSIVE, gate.snapshot().phase)
        assertTrue(resumed.release(CutoverExclusiveRelease.OPEN))
    }

    @Test
    fun cancellingExclusiveWhileWaitingForDrainReopensAdmission() = runTest {
        val gate = CutoverAccessGate.open()
        val normalStarted = CompletableDeferred<Unit>()
        val finishNormal = CompletableDeferred<Unit>()
        val normal = async {
            gate.withNormalAccess {
                normalStarted.complete(Unit)
                finishNormal.await()
            }
        }
        normalStarted.await()
        val exclusive = async { gate.acquireExclusive(identity) }
        runCurrent()

        exclusive.cancelAndJoin()

        assertEquals(CutoverGatePhase.OPEN, gate.snapshot().phase)
        finishNormal.complete(Unit)
        normal.await()
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun cancelledNormalCleanupCannotLeakAnActiveAdmission() = runTest {
        val gate = CutoverAccessGate.open()
        val normalStarted = CompletableDeferred<Unit>()
        val normal = async {
            gate.withNormalAccess {
                normalStarted.complete(Unit)
                awaitCancellation()
            }
        }
        normalStarted.await()
        val mutex = mutexOf(gate)
        mutex.lock()

        normal.cancel(CancellationException("cancel normal while cleanup lock is contended"))
        runCurrent()
        mutex.unlock()
        normal.join()

        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun cancelledExclusiveReleaseCompletesBeforeConsumingTheLease() = runTest {
        val gate = CutoverAccessGate.open()
        val lease = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        val mutex = mutexOf(gate)
        mutex.lock()
        val release = async { lease.release(CutoverExclusiveRelease.OPEN) }
        runCurrent()

        release.cancel()
        mutex.unlock()
        release.join()

        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun rejectionDetailsStayTypedWhenExclusiveReleaseWinsTheNextLock() = runTest {
        val gate = CutoverAccessGate.open()
        val lease = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        val mutex = mutexOf(gate)
        mutex.lock()
        val blocked = async {
            runCatching { gate.withNormalAccess { "must not run" } }
        }
        runCurrent()
        val release = async { lease.release(CutoverExclusiveRelease.OPEN) }
        runCurrent()

        mutex.unlock()
        val blockedResult = blocked.await()
        release.await()

        assertEquals(
            CutoverAccessResult.Unavailable(CutoverUnavailableReason.CUTOVER_IN_PROGRESS, identity),
            blockedResult.getOrThrow()
        )
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun cancellingFalseQuiescenceWhileCleanupLockIsContendedStillReopensAdmission() = runTest {
        val gate = CutoverAccessGate.open()
        val quiescenceEntered = CompletableDeferred<Unit>()
        val returnFalse = CompletableDeferred<Unit>()
        val requester = async {
            gate.acquireExclusive(identity) {
                quiescenceEntered.complete(Unit)
                returnFalse.await()
                false
            }
        }
        quiescenceEntered.await()
        val mutex = mutexOf(gate)
        mutex.lock()
        returnFalse.complete(Unit)
        runCurrent()

        requester.cancel()
        runCurrent()
        mutex.unlock()
        requester.join()

        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
        assertEquals(CutoverAccessResult.Granted("open"), gate.withNormalAccess { "open" })
    }

    @Test
    fun admittedOwnerCanReenterAndDispatchRoomCleanupAfterDrainClosesNewAdmission() = runTest {
        val gate = CutoverAccessGate.open()
        val normalEntered = CompletableDeferred<Unit>()
        val drainStarted = CompletableDeferred<Unit>()
        var executorRuns = 0
        val directExecutor = Executor { command -> command.run() }
        val normal = async {
            gate.withNormalAccess {
                normalEntered.complete(Unit)
                drainStarted.await()
                assertEquals(CutoverAccessResult.Granted("cleanup"), gate.withNormalAccess { "cleanup" })
                gate.guardExecutor(directExecutor).execute { executorRuns += 1 }
            }
        }
        normalEntered.await()
        val exclusive = async { gate.acquireExclusive(identity) }
        runCurrent()
        assertEquals(CutoverGatePhase.DRAINING, gate.snapshot().phase)
        drainStarted.complete(Unit)

        normal.await()
        assertEquals(1, executorRuns)
        val lease = requireType<CutoverExclusiveAdmission.Granted>(exclusive.await()).lease
        assertTrue(lease.release(CutoverExclusiveRelease.OPEN))
    }

    @Test
    fun exclusiveReleaseWaitsForExecutorChildrenThatTailTheCallerContinuation() = runTest {
        val gate = CutoverAccessGate.open()
        val lease = requireType<CutoverExclusiveAdmission.Granted>(gate.acquireExclusive(identity)).lease
        var queued: Runnable? = null
        val queuedExecutor = Executor { command -> queued = command }
        var childRan = false

        lease.withExclusiveAccess {
            gate.guardExecutor(queuedExecutor).execute { childRan = true }
        }

        val release = async { lease.release(CutoverExclusiveRelease.OPEN) }
        runCurrent()
        assertFalse("release waits instead of treating a legal executor tail as caller error", release.isCompleted)
        assertEquals(CutoverGatePhase.EXCLUSIVE, gate.snapshot().phase)

        requireNotNull(queued).run()

        assertTrue(release.await())
        assertTrue(childRan)
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    // ---- #161: guardExecutor must never park the submitting thread on the gate mutex ----

    /**
     * #161 RED: the ANR trace pinned `main` in `runBlocking { acquireNormalAccess() }` inside the
     * guardExecutor lambda while the gate mutex was owned by a coroutine that itself needed the
     * parked thread to make progress — a self-deadlock that also killed the attempt watchdog.
     * A wedged mutex stands in for that holder: an ownerless submission must fail closed within
     * the admission budget (Room callers already handle gate rejection) instead of parking forever.
     */
    @Test
    fun ownerlessGuardExecutorSubmissionFailsClosedInsteadOfParkingBehindTheGateMutex() {
        val budgetMs = 250L
        val gate = CutoverAccessGate.open(admissionWaitBudgetMs = budgetMs)
        val mutex = mutexOf(gate)
        runBlocking { mutex.lock() } // pathological holder: the admission mutex never frees on its own
        val outcome = CompletableFuture<Throwable?>()
        val submitting = Thread {
            try {
                gate.guardExecutor(Executor { }).execute { }
                outcome.complete(null)
            } catch (failure: Throwable) {
                outcome.complete(failure)
            }
        }
        submitting.start()
        val result = try {
            outcome.get(10, TimeUnit.SECONDS)
        } catch (timeout: java.util.concurrent.TimeoutException) {
            throw AssertionError(
                "submitting thread parked past the ${budgetMs}ms admission budget — #161 self-deadlock",
                timeout
            )
        } finally {
            mutex.unlock()
        }
        assertTrue(
            "expected fail-closed gate rejection at submission, got $result",
            result is CutoverAccessUnavailableException
        )
    }

    /**
     * #161 release path: the same self-deadlock shape lives in runGuardedCommand's finally. A
     * command admitted before the mutex wedges must still release its slot once the holder makes
     * progress — bounded waits with retry, never an unbounded park and never a leaked slot (a
     * leaked slot wedges the next drain for the whole process).
     */
    @Test
    fun guardedCommandReleaseRetriesInsteadOfParkingForeverWhenTheGateMutexIsWedged() {
        val gate = CutoverAccessGate.open(admissionWaitBudgetMs = 250L)
        var queued: Runnable? = null
        gate.guardExecutor(Executor { command -> queued = command }).execute { }
        val mutex = mutexOf(gate)
        runBlocking { mutex.lock() } // wedge AFTER admission, BEFORE the command runs
        Thread {
            Thread.sleep(1_000)
            mutex.unlock()
        }.start()
        val released = CountDownLatch(1)
        Thread {
            requireNotNull(queued).run()
            released.countDown()
        }.start()
        assertTrue(
            "command worker parked past the admission budget without retrying — #161 release deadlock",
            released.await(10, TimeUnit.SECONDS)
        )
        assertEquals(CutoverGateSnapshot.open(), runBlocking { gate.snapshot() })
    }

    /** Semantic preservation: a closed gate rejects the DAO call at submission, never on a worker. */
    @Test
    fun closedGateRejectsOwnerlessSubmissionSynchronouslyWithoutReachingTheWorker() = runTest {
        val gate = CutoverAccessGate.recoveryRequired(identity)
        var workerSawCommand = false
        val failure = runCatching {
            gate.guardExecutor(Executor { command ->
                workerSawCommand = true
                command.run()
            }).execute { workerSawCommand = true }
        }.exceptionOrNull()
        assertTrue("expected CutoverAccessUnavailableException, got $failure", failure is CutoverAccessUnavailableException)
        val rejection = failure as CutoverAccessUnavailableException
        assertEquals(CutoverUnavailableReason.RECOVERY_REQUIRED, rejection.reason)
        assertEquals(identity, rejection.identity)
        assertFalse("a rejected command must never reach the delegate executor", workerSawCommand)
    }

    /** Semantic preservation: an open gate admits the submission and the slot returns after the run. */
    @Test
    fun uncontendedOpenGateAdmitsSubmissionAndReturnsTheSlotAfterTheCommandRuns() = runTest {
        val gate = CutoverAccessGate.open()
        var ran = false
        gate.guardExecutor(Executor { command -> command.run() }).execute { ran = true }
        assertTrue(ran)
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    private fun mutexOf(gate: CutoverAccessGate): Mutex {
        val field = CutoverAccessGate::class.java.getDeclaredField("mutex")
        field.isAccessible = true
        return field.get(gate) as Mutex
    }

    private inline fun <reified T> requireType(value: Any?): T {
        assertTrue("expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }

    private class QuiescenceFailure : RuntimeException()
}
