package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
