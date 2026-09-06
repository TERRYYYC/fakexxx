package com.example.cellrebelauto.cutover

enum class CutoverEligibility {
    ELIGIBLE,
    INELIGIBLE,
    INDETERMINATE
}

data class CutoverRestoreIdentity(
    val archiveDigest: String,
    val captureId: String
) {
    init {
        require(ARCHIVE_DIGEST.matches(archiveDigest)) { "invalid archive digest" }
        require(captureId.isNotBlank()) { "capture id cannot be blank" }
    }

    private companion object {
        val ARCHIVE_DIGEST = Regex("sha256:[0-9a-f]{64}")
    }
}

enum class CutoverRestorePhase {
    STAGED,
    ROOM_WRITTEN,
    DATASTORE_WRITTEN,
    VERIFIED,
    READY,
    ROLLBACK_REQUIRED,
    ROLLED_BACK
}

enum class CutoverRestoreFailureReason {
    ELIGIBILITY_LOST,
    ROOM_WRITE_FAILED,
    DATASTORE_WRITE_FAILED,
    ARCHIVE_VERIFICATION_FAILED,
    INTERRUPTED
}

data class CutoverRestoreJournal(
    val identity: CutoverRestoreIdentity,
    val phase: CutoverRestorePhase,
    val failureReason: CutoverRestoreFailureReason? = null
) {
    init {
        val requiresFailure = phase == CutoverRestorePhase.ROLLBACK_REQUIRED ||
            phase == CutoverRestorePhase.ROLLED_BACK
        require(requiresFailure == (failureReason != null)) {
            "failure reason must exist only for rollback states"
        }
    }

    /** Normal readers must use this projection; it is never persisted as a second state bit. */
    val isVisible: Boolean
        get() = phase == CutoverRestorePhase.READY
}

enum class CutoverRestoreAction {
    WRITE_ROOM,
    WRITE_DATASTORE,
    VERIFY,
    RECHECK_ELIGIBILITY_AND_PUBLISH,
    ROLLBACK,
    NONE
}

sealed interface CutoverRestoreEvent {
    val identity: CutoverRestoreIdentity

    data class Begin(
        override val identity: CutoverRestoreIdentity,
        val eligibility: CutoverEligibility
    ) : CutoverRestoreEvent

    data class RoomWritten(
        override val identity: CutoverRestoreIdentity
    ) : CutoverRestoreEvent

    data class DataStoreWritten(
        override val identity: CutoverRestoreIdentity
    ) : CutoverRestoreEvent

    data class Verified(
        override val identity: CutoverRestoreIdentity
    ) : CutoverRestoreEvent

    data class PublishReady(
        override val identity: CutoverRestoreIdentity,
        val eligibility: CutoverEligibility
    ) : CutoverRestoreEvent

    data class Failed(
        override val identity: CutoverRestoreIdentity,
        val reason: CutoverRestoreFailureReason
    ) : CutoverRestoreEvent

    data class RollbackCompleted(
        override val identity: CutoverRestoreIdentity
    ) : CutoverRestoreEvent
}

enum class CutoverRestoreRejectionReason {
    NOT_ELIGIBLE,
    NO_ACTIVE_RESTORE,
    ARCHIVE_CONFLICT,
    INVALID_PHASE
}

sealed class CutoverRestoreTransition {
    abstract val journal: CutoverRestoreJournal?

    data class Advanced(
        override val journal: CutoverRestoreJournal
    ) : CutoverRestoreTransition()

    data class Idempotent(
        override val journal: CutoverRestoreJournal
    ) : CutoverRestoreTransition()

    data class Rejected(
        override val journal: CutoverRestoreJournal?,
        val reason: CutoverRestoreRejectionReason
    ) : CutoverRestoreTransition()
}

/**
 * Pure durable-state reducer for the cross-store restore.
 *
 * An Android coordinator persists the returned journal before performing the projected next
 * action. Event types represent completed proofs, so the reducer never accepts an arbitrary phase
 * assignment and cannot skip Room, DataStore, verification, or the publication eligibility check.
 */
class CutoverRestoreReducer {
    fun reduce(
        current: CutoverRestoreJournal?,
        event: CutoverRestoreEvent
    ): CutoverRestoreTransition {
        if (event is CutoverRestoreEvent.Begin) {
            return begin(current, event)
        }
        if (current == null) {
            return rejected(null, CutoverRestoreRejectionReason.NO_ACTIVE_RESTORE)
        }
        if (current.identity != event.identity) {
            return rejected(current, CutoverRestoreRejectionReason.ARCHIVE_CONFLICT)
        }

        return when (event) {
            is CutoverRestoreEvent.Begin -> error("begin handled above")
            is CutoverRestoreEvent.RoomWritten -> completedStep(
                current,
                expected = CutoverRestorePhase.STAGED,
                completed = CutoverRestorePhase.ROOM_WRITTEN
            )
            is CutoverRestoreEvent.DataStoreWritten -> completedStep(
                current,
                expected = CutoverRestorePhase.ROOM_WRITTEN,
                completed = CutoverRestorePhase.DATASTORE_WRITTEN
            )
            is CutoverRestoreEvent.Verified -> completedStep(
                current,
                expected = CutoverRestorePhase.DATASTORE_WRITTEN,
                completed = CutoverRestorePhase.VERIFIED
            )
            is CutoverRestoreEvent.PublishReady -> publishReady(current, event.eligibility)
            is CutoverRestoreEvent.Failed -> fail(current, event.reason)
            is CutoverRestoreEvent.RollbackCompleted -> rollbackCompleted(current)
        }
    }

    fun nextAction(journal: CutoverRestoreJournal): CutoverRestoreAction = when (journal.phase) {
        CutoverRestorePhase.STAGED -> CutoverRestoreAction.WRITE_ROOM
        CutoverRestorePhase.ROOM_WRITTEN -> CutoverRestoreAction.WRITE_DATASTORE
        CutoverRestorePhase.DATASTORE_WRITTEN -> CutoverRestoreAction.VERIFY
        CutoverRestorePhase.VERIFIED -> CutoverRestoreAction.RECHECK_ELIGIBILITY_AND_PUBLISH
        CutoverRestorePhase.ROLLBACK_REQUIRED -> CutoverRestoreAction.ROLLBACK
        CutoverRestorePhase.READY,
        CutoverRestorePhase.ROLLED_BACK -> CutoverRestoreAction.NONE
    }

    private fun begin(
        current: CutoverRestoreJournal?,
        event: CutoverRestoreEvent.Begin
    ): CutoverRestoreTransition {
        if (current != null && current.phase != CutoverRestorePhase.ROLLED_BACK) {
            return if (current.identity == event.identity) {
                CutoverRestoreTransition.Idempotent(current)
            } else {
                rejected(current, CutoverRestoreRejectionReason.ARCHIVE_CONFLICT)
            }
        }
        if (event.eligibility != CutoverEligibility.ELIGIBLE) {
            return rejected(current, CutoverRestoreRejectionReason.NOT_ELIGIBLE)
        }
        return advanced(
            CutoverRestoreJournal(
                identity = event.identity,
                phase = CutoverRestorePhase.STAGED
            )
        )
    }

    private fun completedStep(
        current: CutoverRestoreJournal,
        expected: CutoverRestorePhase,
        completed: CutoverRestorePhase
    ): CutoverRestoreTransition {
        if (current.phase == expected) {
            return advanced(current.copy(phase = completed))
        }
        val currentRank = HAPPY_PATH_RANK[current.phase]
        val completedRank = HAPPY_PATH_RANK.getValue(completed)
        return if (currentRank != null && currentRank >= completedRank) {
            CutoverRestoreTransition.Idempotent(current)
        } else {
            rejected(current, CutoverRestoreRejectionReason.INVALID_PHASE)
        }
    }

    private fun publishReady(
        current: CutoverRestoreJournal,
        eligibility: CutoverEligibility
    ): CutoverRestoreTransition {
        if (current.phase == CutoverRestorePhase.READY) {
            return CutoverRestoreTransition.Idempotent(current)
        }
        if (current.phase != CutoverRestorePhase.VERIFIED) {
            return rejected(current, CutoverRestoreRejectionReason.INVALID_PHASE)
        }
        return if (eligibility == CutoverEligibility.ELIGIBLE) {
            advanced(current.copy(phase = CutoverRestorePhase.READY))
        } else {
            advanced(
                current.copy(
                    phase = CutoverRestorePhase.ROLLBACK_REQUIRED,
                    failureReason = CutoverRestoreFailureReason.ELIGIBILITY_LOST
                )
            )
        }
    }

    private fun fail(
        current: CutoverRestoreJournal,
        reason: CutoverRestoreFailureReason
    ): CutoverRestoreTransition = when (current.phase) {
        CutoverRestorePhase.STAGED,
        CutoverRestorePhase.ROOM_WRITTEN,
        CutoverRestorePhase.DATASTORE_WRITTEN,
        CutoverRestorePhase.VERIFIED -> advanced(
            current.copy(
                phase = CutoverRestorePhase.ROLLBACK_REQUIRED,
                failureReason = reason
            )
        )
        CutoverRestorePhase.ROLLBACK_REQUIRED,
        CutoverRestorePhase.ROLLED_BACK -> CutoverRestoreTransition.Idempotent(current)
        CutoverRestorePhase.READY -> rejected(current, CutoverRestoreRejectionReason.INVALID_PHASE)
    }

    private fun rollbackCompleted(current: CutoverRestoreJournal): CutoverRestoreTransition =
        when (current.phase) {
            CutoverRestorePhase.ROLLBACK_REQUIRED -> advanced(
                current.copy(phase = CutoverRestorePhase.ROLLED_BACK)
            )
            CutoverRestorePhase.ROLLED_BACK -> CutoverRestoreTransition.Idempotent(current)
            else -> rejected(current, CutoverRestoreRejectionReason.INVALID_PHASE)
        }

    private fun advanced(journal: CutoverRestoreJournal) = CutoverRestoreTransition.Advanced(journal)

    private fun rejected(
        journal: CutoverRestoreJournal?,
        reason: CutoverRestoreRejectionReason
    ) = CutoverRestoreTransition.Rejected(journal, reason)

    private companion object {
        val HAPPY_PATH_RANK = mapOf(
            CutoverRestorePhase.STAGED to 0,
            CutoverRestorePhase.ROOM_WRITTEN to 1,
            CutoverRestorePhase.DATASTORE_WRITTEN to 2,
            CutoverRestorePhase.VERIFIED to 3,
            CutoverRestorePhase.READY to 4
        )
    }
}
