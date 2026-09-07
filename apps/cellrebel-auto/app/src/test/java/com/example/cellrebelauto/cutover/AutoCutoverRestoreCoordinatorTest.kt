package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoCutoverRestoreCoordinatorTest {
    private val identity = CutoverRestoreIdentity(
        archiveDigest = "sha256:${"b".repeat(64)}",
        captureId = "capture-b"
    )
    private val archive = DecodedCutoverArchiveV2(
        archive = CutoverArchiveV2(
            sourcePackage = "com.example.cellrebelauto",
            captureId = identity.captureId,
            schemaVersion = 9,
            tables = emptyList(),
            preferences = emptyList()
        ),
        archiveDigest = identity.archiveDigest
    )

    @Test
    fun nonEmptyTargetFailsBeforeJournalOrTargetMutation() = runTest {
        val journal = FakeJournalPort()
        val room = FakeGenerationPort(initial = CutoverGenerationState.MISMATCH)
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val coordinator = coordinator(journal, room, preferences)

        val result = coordinator.restore(archive)

        assertEquals(
            AutoCutoverRestoreResult.Rejected(CutoverCoordinatorRejection.TARGET_NOT_EMPTY, null),
            result
        )
        assertTrue(journal.writes.isEmpty())
        assertEquals(0, room.restoreCalls)
        assertEquals(0, room.clearCalls)
        assertEquals(0, preferences.restoreCalls)
        assertEquals(0, preferences.clearCalls)
    }

    @Test
    fun stagedRestartRecognizesPriorAtomicRoomCommitWithoutDuplicateInsert() = runTest {
        val staged = CutoverRestoreJournal(identity, CutoverRestorePhase.STAGED)
        val journal = FakeJournalPort(staged)
        val room = FakeGenerationPort(initial = CutoverGenerationState.EXACT)
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val eligibility = FakeEligibilityPort(CutoverEligibility.ELIGIBLE, CutoverEligibility.ELIGIBLE)
        val coordinator = coordinator(journal, room, preferences, eligibility)

        val result = coordinator.restore(archive)

        assertEquals(CutoverRestorePhase.READY, result.journal?.phase)
        assertEquals(0, room.restoreCalls)
        assertEquals(1, preferences.restoreCalls)
        assertEquals(
            listOf(
                CutoverRestorePhase.ROOM_WRITTEN,
                CutoverRestorePhase.DATASTORE_WRITTEN,
                CutoverRestorePhase.VERIFIED,
                CutoverRestorePhase.READY
            ),
            journal.writes.map { it.phase }
        )
    }

    @Test
    fun freshEmptyTargetCommitsEachProofBeforeAdvancingAndPublishesOnlyAfterFreshEligibility() = runTest {
        val journal = FakeJournalPort()
        val room = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val eligibility = FakeEligibilityPort(
            CutoverEligibility.ELIGIBLE,
            CutoverEligibility.ELIGIBLE,
            CutoverEligibility.ELIGIBLE
        )
        val coordinator = coordinator(journal, room, preferences, eligibility)

        val result = coordinator.restore(archive)

        assertEquals(CutoverRestorePhase.READY, result.journal?.phase)
        assertEquals(1, room.restoreCalls)
        assertEquals(1, preferences.restoreCalls)
        assertEquals(3, eligibility.calls)
        assertEquals(
            listOf(
                CutoverRestorePhase.STAGED,
                CutoverRestorePhase.ROOM_WRITTEN,
                CutoverRestorePhase.DATASTORE_WRITTEN,
                CutoverRestorePhase.VERIFIED,
                CutoverRestorePhase.READY
            ),
            journal.writes.map { it.phase }
        )
    }

    @Test
    fun mixedPreferenceStateAfterRoomCommitFailsClosedAndRollsBackToEmptyBaseline() = runTest {
        val roomWritten = CutoverRestoreJournal(identity, CutoverRestorePhase.ROOM_WRITTEN)
        val journal = FakeJournalPort(roomWritten)
        val room = FakeGenerationPort(initial = CutoverGenerationState.EXACT)
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.MISMATCH)
        val coordinator = coordinator(journal, room, preferences)

        val result = coordinator.restore(archive)

        assertEquals(CutoverRestorePhase.ROLLED_BACK, result.journal?.phase)
        assertEquals(CutoverRestoreFailureReason.DATASTORE_WRITE_FAILED, result.journal?.failureReason)
        assertEquals(1, room.clearCalls)
        assertEquals(1, preferences.clearCalls)
        assertEquals(CutoverGenerationState.EMPTY, room.state)
        assertEquals(CutoverGenerationState.EMPTY, preferences.state)
        assertEquals(
            listOf(CutoverRestorePhase.ROLLBACK_REQUIRED, CutoverRestorePhase.ROLLED_BACK),
            journal.writes.map { it.phase }
        )
    }

    @Test
    fun staleEligibilityAtPublicationRollsBackInsteadOfOpeningGate() = runTest {
        val journal = FakeJournalPort()
        val room = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val eligibility = FakeEligibilityPort(
            CutoverEligibility.ELIGIBLE,
            CutoverEligibility.ELIGIBLE,
            CutoverEligibility.INDETERMINATE
        )
        val gate = CutoverAccessGate.open()
        val coordinator = AutoCutoverRestoreCoordinator(
            gate,
            journal,
            room,
            preferences,
            eligibility
        )

        val result = coordinator.restore(archive)

        assertEquals(CutoverRestorePhase.ROLLED_BACK, result.journal?.phase)
        assertEquals(CutoverRestoreFailureReason.ELIGIBILITY_LOST, result.journal?.failureReason)
        assertEquals(CutoverGateSnapshot.open(), gate.snapshot())
    }

    @Test
    fun cancellationPersistsInterruptedRecoveryBeforeRethrowAndKeepsGateClosed() = runTest {
        val journal = FakeJournalPort()
        val room = FakeGenerationPort(
            initial = CutoverGenerationState.EMPTY,
            restoreFailure = CancellationException("cancelled")
        )
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.EMPTY)
        val gate = CutoverAccessGate.open()
        val coordinator = AutoCutoverRestoreCoordinator(
            gate,
            journal,
            room,
            preferences,
            FakeEligibilityPort(CutoverEligibility.ELIGIBLE, CutoverEligibility.ELIGIBLE)
        )

        val failure = runCatching { coordinator.restore(archive) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(CutoverRestorePhase.ROLLBACK_REQUIRED, journal.current?.phase)
        assertEquals(CutoverRestoreFailureReason.INTERRUPTED, journal.current?.failureReason)
        assertEquals(CutoverGatePhase.RECOVERY_REQUIRED, gate.snapshot().phase)
    }

    @Test
    fun stalePreExclusiveJournalReadCannotRollBackANewerReadyGeneration() = runTest {
        val staged = CutoverRestoreJournal(identity, CutoverRestorePhase.STAGED)
        val journal = BlockingFirstReadJournalPort(staged)
        val room = FakeGenerationPort(initial = CutoverGenerationState.EXACT)
        val preferences = FakeGenerationPort(initial = CutoverGenerationState.EXACT)
        val gate = CutoverAccessGate.open()
        val coordinator = AutoCutoverRestoreCoordinator(
            gate,
            journal,
            room,
            preferences,
            FakeEligibilityPort(*Array(8) { CutoverEligibility.ELIGIBLE })
        )

        val older = async { coordinator.restore(archive) }
        journal.firstReadCaptured.await()
        val newer = async { coordinator.restore(archive) }
        runCurrent()
        val newerResult = newer.await()
        if (newerResult is AutoCutoverRestoreResult.Completed) {
            room.state = CutoverGenerationState.MISMATCH
            preferences.state = CutoverGenerationState.MISMATCH
        }
        journal.resumeFirstRead.complete(Unit)
        older.await()

        assertEquals(CutoverRestorePhase.READY, journal.current?.phase)
        assertEquals(0, room.clearCalls)
        assertEquals(0, preferences.clearCalls)
    }

    @Test
    fun journalWriteWithUnknownOutcomeCannotReopenNormalAdmission() = runTest {
        val journal = CommitThenThrowJournalPort()
        val gate = CutoverAccessGate.open()
        val coordinator = AutoCutoverRestoreCoordinator(
            gate,
            journal,
            FakeGenerationPort(CutoverGenerationState.EMPTY),
            FakeGenerationPort(CutoverGenerationState.EMPTY),
            FakeEligibilityPort(CutoverEligibility.ELIGIBLE)
        )

        runCatching { coordinator.restore(archive) }

        assertEquals(CutoverGatePhase.RECOVERY_REQUIRED, gate.snapshot().phase)
        assertTrue(gate.withNormalAccess { "must not run" } is CutoverAccessResult.Unavailable)
    }

    @Test
    fun cancelledJournalConfirmationPersistsInterruptedStateAndKeepsGateClosed() = runTest {
        val journal = CommitThenCancelOnceJournalPort()
        val gate = CutoverAccessGate.open()
        val coordinator = AutoCutoverRestoreCoordinator(
            gate,
            journal,
            FakeGenerationPort(CutoverGenerationState.EMPTY),
            FakeGenerationPort(CutoverGenerationState.EMPTY),
            FakeEligibilityPort(CutoverEligibility.ELIGIBLE)
        )

        val failure = runCatching { coordinator.restore(archive) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertEquals(CutoverRestorePhase.ROLLBACK_REQUIRED, journal.current?.phase)
        assertEquals(CutoverRestoreFailureReason.INTERRUPTED, journal.current?.failureReason)
        assertEquals(CutoverGatePhase.RECOVERY_REQUIRED, gate.snapshot().phase)
    }

    @Test
    fun unreadableDurableJournalCannotOpenNormalAdmission() = runTest {
        val gate = CutoverAccessGate.open()
        val coordinator = AutoCutoverRestoreCoordinator(
            gate,
            ReadFailingJournalPort(),
            FakeGenerationPort(CutoverGenerationState.EMPTY),
            FakeGenerationPort(CutoverGenerationState.EMPTY),
            FakeEligibilityPort(CutoverEligibility.ELIGIBLE)
        )

        runCatching { coordinator.restore(archive) }

        assertEquals(
            CutoverGateSnapshot(CutoverGatePhase.RECOVERY_REQUIRED, 0, null),
            gate.snapshot()
        )
        assertTrue(gate.withNormalAccess { "must not run" } is CutoverAccessResult.Unavailable)
    }

    @Test
    fun emptyExactGenerationsAdvanceWithoutManufacturingTargetWrites() = runTest {
        val journal = FakeJournalPort()
        val room = FakeGenerationPort(CutoverGenerationState.EMPTY_EXACT)
        val preferences = FakeGenerationPort(CutoverGenerationState.EMPTY_EXACT)
        val coordinator = coordinator(journal, room, preferences)

        val result = coordinator.restore(archive)

        assertEquals(CutoverRestorePhase.READY, result.journal?.phase)
        assertEquals(0, room.restoreCalls)
        assertEquals(0, preferences.restoreCalls)
        assertEquals(CutoverGenerationState.EMPTY_EXACT, room.state)
        assertEquals(CutoverGenerationState.EMPTY_EXACT, preferences.state)
    }

    private fun coordinator(
        journal: FakeJournalPort,
        room: FakeGenerationPort,
        preferences: FakeGenerationPort,
        eligibility: FakeEligibilityPort = FakeEligibilityPort(
            CutoverEligibility.ELIGIBLE,
            CutoverEligibility.ELIGIBLE,
            CutoverEligibility.ELIGIBLE
        )
    ) = AutoCutoverRestoreCoordinator(
        CutoverAccessGate.fromJournal(journal.current),
        journal,
        room,
        preferences,
        eligibility
    )

    private class FakeJournalPort(
        initial: CutoverRestoreJournal? = null
    ) : CutoverRestoreJournalPort {
        var current: CutoverRestoreJournal? = initial
        val writes = mutableListOf<CutoverRestoreJournal>()

        override suspend fun read(): CutoverRestoreJournal? = current

        override suspend fun write(journal: CutoverRestoreJournal) {
            current = journal
            writes += journal
        }
    }

    private class BlockingFirstReadJournalPort(
        initial: CutoverRestoreJournal
    ) : CutoverRestoreJournalPort {
        var current: CutoverRestoreJournal? = initial
        val firstReadCaptured = CompletableDeferred<Unit>()
        val resumeFirstRead = CompletableDeferred<Unit>()
        private var reads = 0

        override suspend fun read(): CutoverRestoreJournal? {
            val captured = current
            reads += 1
            if (reads == 1) {
                firstReadCaptured.complete(Unit)
                resumeFirstRead.await()
            }
            return captured
        }

        override suspend fun write(journal: CutoverRestoreJournal) {
            current = journal
        }
    }

    private class CommitThenThrowJournalPort : CutoverRestoreJournalPort {
        var current: CutoverRestoreJournal? = null

        override suspend fun read(): CutoverRestoreJournal? = current

        override suspend fun write(journal: CutoverRestoreJournal) {
            current = journal
            throw JournalConfirmationFailure()
        }
    }

    private class CommitThenCancelOnceJournalPort : CutoverRestoreJournalPort {
        var current: CutoverRestoreJournal? = null
        private var writes = 0

        override suspend fun read(): CutoverRestoreJournal? = current

        override suspend fun write(journal: CutoverRestoreJournal) {
            current = journal
            writes += 1
            if (writes == 1) throw CancellationException("journal confirmation cancelled")
        }
    }

    private class ReadFailingJournalPort : CutoverRestoreJournalPort {
        override suspend fun read(): CutoverRestoreJournal? = throw JournalConfirmationFailure()
        override suspend fun write(journal: CutoverRestoreJournal) = error("must not write")
    }

    private class JournalConfirmationFailure : RuntimeException()

    private class FakeGenerationPort(
        initial: CutoverGenerationState,
        private val restoreFailure: Throwable? = null
    ) : CutoverRoomGenerationPort, CutoverPreferenceGenerationPort {
        var state = initial
        var restoreCalls = 0
        var clearCalls = 0

        override suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState = state

        override suspend fun restore(archive: CutoverArchiveV2) {
            restoreCalls += 1
            restoreFailure?.let { throw it }
            state = CutoverGenerationState.EXACT
        }

        override suspend fun clear() {
            clearCalls += 1
            state = CutoverGenerationState.EMPTY
        }
    }

    private class FakeEligibilityPort(
        vararg observations: CutoverEligibility
    ) : CutoverEligibilityPort {
        private val values = ArrayDeque(observations.asList())
        var calls = 0

        override suspend fun observe(): CutoverEligibility {
            calls += 1
            return values.removeFirstOrNull() ?: error("unexpected eligibility observation")
        }
    }
}
