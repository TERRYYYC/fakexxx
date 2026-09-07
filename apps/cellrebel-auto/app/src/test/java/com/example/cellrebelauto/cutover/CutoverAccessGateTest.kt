package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.Executor
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
