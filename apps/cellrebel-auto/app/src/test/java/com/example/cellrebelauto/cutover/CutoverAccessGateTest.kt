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
import java.util.concurrent.CopyOnWriteArrayList
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
     * #161 (kept, retargeted by #164): the ANR trace pinned `main` in
     * `runBlocking { acquireNormalAccess() }` inside the guardExecutor lambda while the gate mutex
     * was owned by a coroutine that itself needed the parked thread to make progress. A wedged
     * mutex stands in for that holder: the submitting thread must still return within the bounded
     * admission budget — but since #164 the bounded lapse requeues the command instead of throwing
     * into the framework submitter, and once the wedge clears the queued command must run.
     */
    @Test
    fun ownerlessGuardExecutorSubmissionRequeuesInsteadOfParkingBehindTheGateMutex() {
        val budgetMs = 250L
        val gate = CutoverAccessGate.open(admissionWaitBudgetMs = budgetMs)
        val mutex = mutexOf(gate)
        runBlocking { mutex.lock() } // pathological holder: the admission mutex never frees on its own
        var executed = false
        val outcome = CompletableFuture<Throwable?>()
        val submitting = Thread {
            try {
                gate.guardExecutor(Executor { command -> command.run() }).execute { executed = true }
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
        assertEquals(
            "#164: the executor path must requeue instead of throwing into the framework submitter",
            null,
            result
        )
        assertTrue("queued command must run once the wedge clears", awaitTrue { executed })
        assertEquals(CutoverGateSnapshot.open(), runBlocking { gate.snapshot() })
    }

    // ---- #164: admission failure requeues the command with backoff instead of throwing ----

    /**
     * #164 RED: a gate transient (CUTOVER_IN_PROGRESS) hitting a framework-internal submission
     * (Room InvalidationTracker.createFlow emits on main through the gated queryExecutor) used to
     * fail closed with CutoverAccessUnavailableException — no framework path handles it, so the
     * process crashed. Cutover is transient: the command must be requeued, retried with backoff
     * (observable via counters, never thrown), and must run once the cutover reopens.
     */
    @Test
    fun admissionFailureUnderPermanentExclusiveRequeuesAndRunsAfterCutoverReopens() {
        val gate = CutoverAccessGate.open(
            admissionWaitBudgetMs = 250L,
            retryBudgetMs = 60_000L,
            retryBaseBackoffMs = 50L,
            retryMaxBackoffMs = 1_000L
        )
        val logs = CopyOnWriteArrayList<String>()
        gate.retryLogSink = { logs += it }
        val lease = exclusiveLease(gate)
        var executed = false

        gate.guardExecutor(Executor { command -> command.run() }).execute { executed = true }

        assertFalse("command must not run while the gate is exclusive", executed)
        assertTrue("the command must be requeued for retry", gate.gateRetryStats().enqueued >= 1)

        Thread {
            Thread.sleep(400) // hold exclusive across several backoff attempts
            runBlocking { assertTrue(lease.release(CutoverExclusiveRelease.OPEN)) }
        }.start()

        assertTrue("command must run after cutover reopens", awaitTrue { executed })
        assertTrue("the requeued command must actually have retried", gate.gateRetryStats().attempts >= 1)
        assertEquals("a recovered command must never be dropped", 0L, gate.gateRetryStats().dropped)
        assertTrue("retries must emit diagnostics", logs.isNotEmpty())
    }

    /** Transient exclusive: the command lands after roughly the transient duration plus a backoff. */
    @Test
    fun transientExclusiveDelaysTheCommandButNeverDropsOrThrows() {
        val gate = CutoverAccessGate.open(
            admissionWaitBudgetMs = 250L,
            retryBudgetMs = 30_000L,
            retryBaseBackoffMs = 50L,
            retryMaxBackoffMs = 1_000L
        )
        val lease = exclusiveLease(gate)
        var executed = false
        val submittedAtMs = System.currentTimeMillis()

        gate.guardExecutor(Executor { command -> command.run() }).execute { executed = true }

        Thread {
            Thread.sleep(300)
            runBlocking { assertTrue(lease.release(CutoverExclusiveRelease.OPEN)) }
        }.start()

        assertTrue(awaitTrue { executed })
        val elapsedMs = System.currentTimeMillis() - submittedAtMs
        assertTrue(
            "command ran ($elapsedMs ms) before the transient window ended — gate bypass",
            elapsedMs >= 300
        )
        assertTrue("command took too long after the transient: $elapsedMs ms", elapsedMs < 5_000)
        assertEquals(0L, gate.gateRetryStats().dropped)
    }

    /**
     * The budget is the last resort (a dropped DAO command leaves its caller suspended): when it
     * lapses the command is dropped WITHOUT throwing, with a WARN that names the loss and a
     * counter — and the command stays dead even after the gate reopens.
     */
    @Test
    fun retryBudgetExhaustionDropsTheCommandQuietlyInsteadOfThrowing() {
        val gate = CutoverAccessGate.open(
            admissionWaitBudgetMs = 250L,
            retryBudgetMs = 300L,
            retryBaseBackoffMs = 40L,
            retryMaxBackoffMs = 200L
        )
        val logs = CopyOnWriteArrayList<String>()
        gate.retryLogSink = { logs += it }
        val lease = exclusiveLease(gate)
        var executed = false

        gate.guardExecutor(Executor { command -> command.run() }).execute { executed = true }

        assertTrue(
            "budget exhaustion must surface as a drop counter, not an exception",
            awaitTrue(timeoutMs = 10_000) { gate.gateRetryStats().dropped == 1L }
        )
        assertFalse("a dropped command must never execute", executed)
        assertTrue(
            "the drop must WARN that the command was lost",
            logs.any { it.contains("DROPPED", ignoreCase = true) }
        )
        runBlocking { assertTrue(lease.release(CutoverExclusiveRelease.OPEN)) }
        Thread.sleep(300)
        assertFalse("a dropped command stays dead after reopen", executed)
    }

    /** Ordering: queued retries run in submission order (FIFO queue + single scheduler). */
    @Test
    fun requeuedCommandsKeepSubmissionOrder() {
        val gate = CutoverAccessGate.open(
            admissionWaitBudgetMs = 250L,
            retryBudgetMs = 60_000L,
            retryBaseBackoffMs = 50L,
            retryMaxBackoffMs = 1_000L
        )
        val lease = exclusiveLease(gate)
        val order = CopyOnWriteArrayList<String>()
        val guarded = gate.guardExecutor(Executor { command -> command.run() })

        guarded.execute { order += "a" }
        guarded.execute { order += "b" }
        guarded.execute { order += "c" }
        assertEquals(3L, gate.gateRetryStats().enqueued)

        runBlocking { assertTrue(lease.release(CutoverExclusiveRelease.OPEN)) }
        assertTrue(awaitTrue { order.size == 3 })
        assertEquals(listOf("a", "b", "c"), order.toList())
    }

    /**
     * #162 semantic preservation (#164 scope): only the executor wrapper requeues. Direct,
     * exception-aware callers keep the typed failure.
     */
    @Test
    fun directCallersStillReceiveTheTypedExceptionWhenTheGateIsClosed() {
        val gate = CutoverAccessGate.recoveryRequired(identity)
        val failure = runCatching { gate.withNormalAccessBlockingOrThrow { "x" } }.exceptionOrNull()
        assertTrue("expected CutoverAccessUnavailableException, got $failure", failure is CutoverAccessUnavailableException)
        val rejection = failure as CutoverAccessUnavailableException
        assertEquals(CutoverUnavailableReason.RECOVERY_REQUIRED, rejection.reason)
        assertEquals(identity, rejection.identity)
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

    /**
     * #164: a closed gate must not throw at submission (that exception used to travel up Room's
     * InvalidationTracker emit chain to main and kill the process). The command is requeued on an
     * independent scheduler instead — and never reaches the worker while the gate is closed.
     */
    @Test
    fun closedGateQueuesOwnerlessSubmissionWithoutThrowingOrReachingTheWorker() {
        val gate = CutoverAccessGate.recoveryRequired(identity)
        var workerSawCommand = false
        gate.guardExecutor(Executor { command ->
            workerSawCommand = true
            command.run()
        }).execute { workerSawCommand = true }
        assertFalse("a queued command must never reach the delegate executor", workerSawCommand)
        assertTrue("the command must be requeued for retry", gate.gateRetryStats().enqueued >= 1)
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

    private fun exclusiveLease(gate: CutoverAccessGate): CutoverExclusiveLease =
        requireType<CutoverExclusiveAdmission.Granted>(
            runBlocking { gate.acquireExclusive(identity) }
        ).lease

    /** Wall-clock poll for wall-clock (real scheduler) assertions. */
    private fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    private inline fun <reified T> requireType(value: Any?): T {
        assertTrue("expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }

    private class QuiescenceFailure : RuntimeException()
}
