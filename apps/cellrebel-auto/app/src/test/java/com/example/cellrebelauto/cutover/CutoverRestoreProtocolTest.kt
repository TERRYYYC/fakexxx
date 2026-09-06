package com.example.cellrebelauto.cutover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoverRestoreProtocolTest {
    private val reducer = CutoverRestoreReducer()
    private val identity = CutoverRestoreIdentity(DIGEST_A, "capture-a")

    @Test
    fun `first durable write requires eligible observation`() {
        val eligible = reducer.reduce(null, CutoverRestoreEvent.Begin(identity, CutoverEligibility.ELIGIBLE))
        val ineligible = reducer.reduce(null, CutoverRestoreEvent.Begin(identity, CutoverEligibility.INELIGIBLE))
        val indeterminate = reducer.reduce(null, CutoverRestoreEvent.Begin(identity, CutoverEligibility.INDETERMINATE))

        assertAdvanced(eligible, CutoverRestorePhase.STAGED)
        assertRejected(ineligible, CutoverRestoreRejectionReason.NOT_ELIGIBLE)
        assertRejected(indeterminate, CutoverRestoreRejectionReason.NOT_ELIGIBLE)
        assertNull(ineligible.journal)
        assertNull(indeterminate.journal)
    }

    @Test
    fun `happy path is exact and invisible until fresh eligibility publishes ready`() {
        var journal: CutoverRestoreJournal? = begin()
        assertState(
            journal,
            CutoverRestorePhase.STAGED,
            CutoverRestoreAction.RECHECK_ELIGIBILITY_AND_WRITE_ROOM,
            visible = false
        )

        journal = advance(journal, CutoverRestoreEvent.RoomWritten(identity), CutoverRestorePhase.ROOM_WRITTEN)
        assertState(journal, CutoverRestorePhase.ROOM_WRITTEN, CutoverRestoreAction.WRITE_DATASTORE, visible = false)

        journal = advance(
            journal,
            CutoverRestoreEvent.DataStoreWritten(identity),
            CutoverRestorePhase.DATASTORE_WRITTEN
        )
        assertState(journal, CutoverRestorePhase.DATASTORE_WRITTEN, CutoverRestoreAction.VERIFY, visible = false)

        journal = advance(
            journal,
            CutoverRestoreEvent.Verified(identity, DIGEST_A),
            CutoverRestorePhase.VERIFIED
        )
        assertState(
            journal,
            CutoverRestorePhase.VERIFIED,
            CutoverRestoreAction.RECHECK_ELIGIBILITY_AND_PUBLISH,
            visible = false
        )

        journal = advance(
            journal,
            CutoverRestoreEvent.PublishReady(identity, CutoverEligibility.ELIGIBLE),
            CutoverRestorePhase.READY
        )
        assertState(journal, CutoverRestorePhase.READY, CutoverRestoreAction.NONE, visible = true)
    }

    @Test
    fun `out of order proof is rejected without changing durable state`() {
        val staged = begin()
        val skippedRoom = reducer.reduce(staged, CutoverRestoreEvent.DataStoreWritten(identity))
        val skippedDataStore = reducer.reduce(staged, CutoverRestoreEvent.Verified(identity, DIGEST_A))
        val prematurePublish = reducer.reduce(
            staged,
            CutoverRestoreEvent.PublishReady(identity, CutoverEligibility.ELIGIBLE)
        )

        assertRejected(skippedRoom, CutoverRestoreRejectionReason.INVALID_PHASE, staged)
        assertRejected(skippedDataStore, CutoverRestoreRejectionReason.INVALID_PHASE, staged)
        assertRejected(prematurePublish, CutoverRestoreRejectionReason.INVALID_PHASE, staged)
    }

    @Test
    fun `same archive retries are idempotent and do not redispatch proven work`() {
        val staged = begin()
        val duplicateBegin = reducer.reduce(
            staged,
            CutoverRestoreEvent.Begin(identity, CutoverEligibility.ELIGIBLE)
        )
        assertIdempotent(duplicateBegin, staged)

        val roomWritten = advance(staged, CutoverRestoreEvent.RoomWritten(identity), CutoverRestorePhase.ROOM_WRITTEN)
        assertIdempotent(reducer.reduce(roomWritten, CutoverRestoreEvent.RoomWritten(identity)), roomWritten)

        val dataStoreWritten = advance(
            roomWritten,
            CutoverRestoreEvent.DataStoreWritten(identity),
            CutoverRestorePhase.DATASTORE_WRITTEN
        )
        assertIdempotent(reducer.reduce(dataStoreWritten, CutoverRestoreEvent.RoomWritten(identity)), dataStoreWritten)
        assertIdempotent(
            reducer.reduce(dataStoreWritten, CutoverRestoreEvent.DataStoreWritten(identity)),
            dataStoreWritten
        )
    }

    @Test
    fun `same archive reentry cannot reuse stale eligibility in any non-ready phase`() {
        val nonReadyStates = listOf(begin(), roomWritten(), dataStoreWritten(), verified())

        nonReadyStates.forEach { state ->
            listOf(CutoverEligibility.INELIGIBLE, CutoverEligibility.INDETERMINATE).forEach { eligibility ->
                val result = reducer.reduce(
                    state,
                    CutoverRestoreEvent.Begin(identity, eligibility)
                )

                val rollback = assertAdvanced(result, CutoverRestorePhase.ROLLBACK_REQUIRED)
                assertEquals(CutoverRestoreFailureReason.ELIGIBILITY_LOST, rollback.failureReason)
                assertFalse(rollback.isVisible)
            }
        }
    }

    @Test
    fun `different archive cannot interleave with an active or ready generation`() {
        val staged = begin()
        val other = CutoverRestoreIdentity(DIGEST_B, "capture-b")

        assertRejected(
            reducer.reduce(staged, CutoverRestoreEvent.Begin(other, CutoverEligibility.ELIGIBLE)),
            CutoverRestoreRejectionReason.ARCHIVE_CONFLICT,
            staged
        )
        assertRejected(
            reducer.reduce(staged, CutoverRestoreEvent.RoomWritten(other)),
            CutoverRestoreRejectionReason.ARCHIVE_CONFLICT,
            staged
        )

        val ready = ready()
        assertRejected(
            reducer.reduce(ready, CutoverRestoreEvent.Begin(other, CutoverEligibility.ELIGIBLE)),
            CutoverRestoreRejectionReason.ARCHIVE_CONFLICT,
            ready
        )
    }

    @Test
    fun `lost eligibility at publication requires rollback and never exposes state`() {
        listOf(CutoverEligibility.INELIGIBLE, CutoverEligibility.INDETERMINATE).forEach { eligibility ->
            val verified = verified()
            val result = reducer.reduce(
                verified,
                CutoverRestoreEvent.PublishReady(identity, eligibility)
            )

            val journal = assertAdvanced(result, CutoverRestorePhase.ROLLBACK_REQUIRED)
            assertEquals(CutoverRestoreFailureReason.ELIGIBILITY_LOST, journal.failureReason)
            assertFalse(journal.isVisible)
            assertEquals(CutoverRestoreAction.ROLLBACK, reducer.nextAction(journal))
        }
    }

    @Test
    fun `readback digest mismatch cannot mint verified state`() {
        val dataStoreWritten = dataStoreWritten()

        val result = reducer.reduce(
            dataStoreWritten,
            CutoverRestoreEvent.Verified(identity, DIGEST_B)
        )

        val rollback = assertAdvanced(result, CutoverRestorePhase.ROLLBACK_REQUIRED)
        assertEquals(CutoverRestoreFailureReason.ARCHIVE_VERIFICATION_FAILED, rollback.failureReason)
        assertFalse(rollback.isVisible)
        assertEquals(CutoverRestoreAction.ROLLBACK, reducer.nextAction(rollback))
    }

    @Test
    fun `new readback mismatch revokes an unpublished verified state`() {
        val verified = verified()

        val result = reducer.reduce(
            verified,
            CutoverRestoreEvent.Verified(identity, DIGEST_B)
        )

        val rollback = assertAdvanced(result, CutoverRestorePhase.ROLLBACK_REQUIRED)
        assertEquals(CutoverRestoreFailureReason.ARCHIVE_VERIFICATION_FAILED, rollback.failureReason)
        assertFalse(rollback.isVisible)
    }

    @Test
    fun `failure from every non-ready phase becomes rollback required`() {
        val states = listOf(
            begin(),
            roomWritten(),
            dataStoreWritten(),
            verified()
        )

        states.forEach { state ->
            val result = reducer.reduce(
                state,
                CutoverRestoreEvent.Failed(identity, CutoverRestoreFailureReason.INTERRUPTED)
            )
            val rollback = assertAdvanced(result, CutoverRestorePhase.ROLLBACK_REQUIRED)
            assertEquals(CutoverRestoreFailureReason.INTERRUPTED, rollback.failureReason)
            assertFalse(rollback.isVisible)
        }
    }

    @Test
    fun `rollback requires a rollback-required journal and completion is idempotent`() {
        val staged = begin()
        assertRejected(
            reducer.reduce(staged, CutoverRestoreEvent.RollbackCompleted(identity)),
            CutoverRestoreRejectionReason.INVALID_PHASE,
            staged
        )

        val rollbackRequired = advance(
            staged,
            CutoverRestoreEvent.Failed(identity, CutoverRestoreFailureReason.ROOM_WRITE_FAILED),
            CutoverRestorePhase.ROLLBACK_REQUIRED
        )
        val rolledBack = advance(
            rollbackRequired,
            CutoverRestoreEvent.RollbackCompleted(identity),
            CutoverRestorePhase.ROLLED_BACK
        )

        assertFalse(rolledBack.isVisible)
        assertEquals(CutoverRestoreAction.NONE, reducer.nextAction(rolledBack))
        assertIdempotent(
            reducer.reduce(rolledBack, CutoverRestoreEvent.RollbackCompleted(identity)),
            rolledBack
        )
    }

    @Test
    fun `a completed rollback releases ownership for a new eligible archive`() {
        val rollbackRequired = advance(
            begin(),
            CutoverRestoreEvent.Failed(identity, CutoverRestoreFailureReason.ARCHIVE_VERIFICATION_FAILED),
            CutoverRestorePhase.ROLLBACK_REQUIRED
        )
        val rolledBack = advance(
            rollbackRequired,
            CutoverRestoreEvent.RollbackCompleted(identity),
            CutoverRestorePhase.ROLLED_BACK
        )
        val nextIdentity = CutoverRestoreIdentity(DIGEST_B, "capture-b")

        val next = reducer.reduce(
            rolledBack,
            CutoverRestoreEvent.Begin(nextIdentity, CutoverEligibility.ELIGIBLE)
        )

        val staged = assertAdvanced(next, CutoverRestorePhase.STAGED)
        assertEquals(nextIdentity, staged.identity)
        assertNull(staged.failureReason)
    }

    @Test
    fun `restart projection resumes only the next unproven phase`() {
        val states = listOf(
            begin() to CutoverRestoreAction.RECHECK_ELIGIBILITY_AND_WRITE_ROOM,
            roomWritten() to CutoverRestoreAction.WRITE_DATASTORE,
            dataStoreWritten() to CutoverRestoreAction.VERIFY,
            verified() to CutoverRestoreAction.RECHECK_ELIGIBILITY_AND_PUBLISH
        )

        states.forEach { (persisted, expectedAction) ->
            val reconstructed = persisted.copy()
            assertEquals(expectedAction, CutoverRestoreReducer().nextAction(reconstructed))
            assertFalse(reconstructed.isVisible)
        }
    }

    @Test
    fun `ready is terminal and later failures cannot silently hide a published generation`() {
        val ready = ready()
        assertRejected(
            reducer.reduce(
                ready,
                CutoverRestoreEvent.Failed(identity, CutoverRestoreFailureReason.INTERRUPTED)
            ),
            CutoverRestoreRejectionReason.INVALID_PHASE,
            ready
        )
        assertIdempotent(
            reducer.reduce(
                ready,
                CutoverRestoreEvent.PublishReady(identity, CutoverEligibility.INDETERMINATE)
            ),
            ready
        )
    }

    @Test
    fun `journal constructor rejects impossible failure-state combinations`() {
        assertThrows(IllegalArgumentException::class.java) {
            CutoverRestoreJournal(identity, CutoverRestorePhase.STAGED, CutoverRestoreFailureReason.INTERRUPTED)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CutoverRestoreJournal(identity, CutoverRestorePhase.ROLLBACK_REQUIRED, null)
        }
    }

    private fun begin(): CutoverRestoreJournal = assertAdvanced(
        reducer.reduce(null, CutoverRestoreEvent.Begin(identity, CutoverEligibility.ELIGIBLE)),
        CutoverRestorePhase.STAGED
    )

    private fun roomWritten(): CutoverRestoreJournal = advance(
        begin(),
        CutoverRestoreEvent.RoomWritten(identity),
        CutoverRestorePhase.ROOM_WRITTEN
    )

    private fun dataStoreWritten(): CutoverRestoreJournal = advance(
        roomWritten(),
        CutoverRestoreEvent.DataStoreWritten(identity),
        CutoverRestorePhase.DATASTORE_WRITTEN
    )

    private fun verified(): CutoverRestoreJournal = advance(
        dataStoreWritten(),
        CutoverRestoreEvent.Verified(identity, DIGEST_A),
        CutoverRestorePhase.VERIFIED
    )

    private fun ready(): CutoverRestoreJournal = advance(
        verified(),
        CutoverRestoreEvent.PublishReady(identity, CutoverEligibility.ELIGIBLE),
        CutoverRestorePhase.READY
    )

    private fun advance(
        journal: CutoverRestoreJournal?,
        event: CutoverRestoreEvent,
        expectedPhase: CutoverRestorePhase
    ): CutoverRestoreJournal = assertAdvanced(reducer.reduce(journal, event), expectedPhase)

    private fun assertAdvanced(
        result: CutoverRestoreTransition,
        expectedPhase: CutoverRestorePhase
    ): CutoverRestoreJournal {
        assertTrue("expected advanced but was $result", result is CutoverRestoreTransition.Advanced)
        return requireNotNull(result.journal).also { assertEquals(expectedPhase, it.phase) }
    }

    private fun assertIdempotent(
        result: CutoverRestoreTransition,
        expected: CutoverRestoreJournal
    ) {
        assertTrue("expected idempotent but was $result", result is CutoverRestoreTransition.Idempotent)
        assertEquals(expected, result.journal)
    }

    private fun assertRejected(
        result: CutoverRestoreTransition,
        reason: CutoverRestoreRejectionReason,
        expectedJournal: CutoverRestoreJournal? = null
    ) {
        assertTrue("expected rejected but was $result", result is CutoverRestoreTransition.Rejected)
        assertEquals(reason, (result as CutoverRestoreTransition.Rejected).reason)
        assertEquals(expectedJournal, result.journal)
    }

    private fun assertState(
        journal: CutoverRestoreJournal?,
        phase: CutoverRestorePhase,
        action: CutoverRestoreAction,
        visible: Boolean
    ) {
        val required = requireNotNull(journal)
        assertEquals(phase, required.phase)
        assertEquals(action, reducer.nextAction(required))
        assertEquals(visible, required.isVisible)
    }

    private companion object {
        const val DIGEST_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
