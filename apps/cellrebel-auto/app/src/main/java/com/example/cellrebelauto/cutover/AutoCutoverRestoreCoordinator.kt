package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class CutoverGenerationState(
    val isEmpty: Boolean,
    val matchesArchive: Boolean
) {
    companion object {
        val EMPTY = CutoverGenerationState(isEmpty = true, matchesArchive = false)
        val EMPTY_EXACT = CutoverGenerationState(isEmpty = true, matchesArchive = true)
        val EXACT = CutoverGenerationState(isEmpty = false, matchesArchive = true)
        val MISMATCH = CutoverGenerationState(isEmpty = false, matchesArchive = false)
    }
}

interface CutoverRestoreJournalPort {
    suspend fun read(): CutoverRestoreJournal?
    suspend fun write(journal: CutoverRestoreJournal)
}

interface CutoverRoomGenerationPort {
    suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState
    suspend fun restore(archive: CutoverArchiveV2)
    suspend fun clear()
}

interface CutoverPreferenceGenerationPort {
    suspend fun classify(archive: CutoverArchiveV2): CutoverGenerationState
    suspend fun restore(archive: CutoverArchiveV2)
    suspend fun clear()
}

interface CutoverEligibilityPort {
    suspend fun observe(): CutoverEligibility
}

fun interface CutoverRunQuiescencePort {
    suspend fun quiesce(): Boolean
}

enum class CutoverCoordinatorRejection {
    BUSY,
    QUIESCENCE_FAILED,
    TARGET_NOT_EMPTY,
    NOT_ELIGIBLE,
    ARCHIVE_CONFLICT,
    INVALID_PHASE
}

sealed interface AutoCutoverRestoreResult {
    val journal: CutoverRestoreJournal?

    data class Completed(
        override val journal: CutoverRestoreJournal
    ) : AutoCutoverRestoreResult

    data class Rejected(
        val reason: CutoverCoordinatorRejection,
        override val journal: CutoverRestoreJournal?
    ) : AutoCutoverRestoreResult

    data class RecoveryRequired(
        override val journal: CutoverRestoreJournal
    ) : AutoCutoverRestoreResult
}

/**
 * Single owner for the product-side cross-store restore lifecycle.
 *
 * The coordinator persists every reducer proof before attempting the following side effect. Room
 * and preference ports classify restart state as empty, exact, or mismatch; only empty/exact are
 * progress. A mismatch is never guessed to be a partial success and is rolled back to the staging
 * precondition (an empty product target).
 */
class AutoCutoverRestoreCoordinator(
    private val accessGate: CutoverAccessGate,
    private val journalPort: CutoverRestoreJournalPort,
    private val roomPort: CutoverRoomGenerationPort,
    private val preferencePort: CutoverPreferenceGenerationPort,
    private val eligibilityPort: CutoverEligibilityPort,
    private val quiescencePort: CutoverRunQuiescencePort = CutoverRunQuiescencePort { true },
    private val reducer: CutoverRestoreReducer = CutoverRestoreReducer()
) {
    suspend fun restore(decoded: DecodedCutoverArchiveV2): AutoCutoverRestoreResult {
        val identity = CutoverRestoreIdentity(decoded.archiveDigest, decoded.archive.captureId)
        var current: CutoverRestoreJournal? = null
        var durableReadConfirmed = false
        var journalWriteConfirmed = true

        val admission = accessGate.acquireExclusive(identity) { quiescencePort.quiesce() }
        when (admission) {
            is CutoverExclusiveAdmission.Busy ->
                return AutoCutoverRestoreResult.Rejected(CutoverCoordinatorRejection.BUSY, current)
            CutoverExclusiveAdmission.QuiescenceFailed ->
                return AutoCutoverRestoreResult.Rejected(
                    CutoverCoordinatorRejection.QUIESCENCE_FAILED,
                    current
                )
            is CutoverExclusiveAdmission.Granted -> Unit
        }
        val lease = (admission as CutoverExclusiveAdmission.Granted).lease
        var terminal: AutoCutoverRestoreResult? = null

        suspend fun <T> roomAccess(block: suspend () -> T): T = lease.withExclusiveAccess(block)

        suspend fun readCurrent(): CutoverRestoreJournal? {
            durableReadConfirmed = false
            val durable = journalPort.read()
            current = durable
            durableReadConfirmed = true
            return durable
        }

        suspend fun writeCurrent(journal: CutoverRestoreJournal) {
            current = journal
            journalWriteConfirmed = false
            journalPort.write(journal)
            journalWriteConfirmed = true
        }

        suspend fun persistTracked(transition: CutoverRestoreTransition): CutoverRestoreJournal? =
            when (transition) {
                is CutoverRestoreTransition.Advanced -> transition.journal.also { writeCurrent(it) }
                is CutoverRestoreTransition.Idempotent -> transition.journal
                is CutoverRestoreTransition.Rejected -> null
            }

        suspend fun persistFailureTracked(
            journal: CutoverRestoreJournal,
            reason: CutoverRestoreFailureReason
        ): CutoverRestoreJournal = requireNotNull(
            persistTracked(reducer.reduce(journal, CutoverRestoreEvent.Failed(journal.identity, reason)))
        )

        try {
            current = readCurrent()
            if (current != null &&
                current?.phase != CutoverRestorePhase.ROLLED_BACK &&
                current?.identity != identity
            ) {
                return AutoCutoverRestoreResult.Rejected(
                    CutoverCoordinatorRejection.ARCHIVE_CONFLICT,
                    current
                ).also { terminal = it }
            }
            if (current == null || current?.phase == CutoverRestorePhase.ROLLED_BACK) {
                val roomState = roomAccess { roomPort.classify(decoded.archive) }
                val preferenceState = preferencePort.classify(decoded.archive)
                if (!roomState.isEmpty || !preferenceState.isEmpty) {
                    return AutoCutoverRestoreResult.Rejected(
                        CutoverCoordinatorRejection.TARGET_NOT_EMPTY,
                        current
                    ).also { terminal = it }
                }
                when (val begun = reducer.reduce(
                    current,
                    CutoverRestoreEvent.Begin(identity, eligibilityPort.observe())
                )) {
                    is CutoverRestoreTransition.Advanced -> {
                        writeCurrent(begun.journal)
                    }
                    is CutoverRestoreTransition.Idempotent -> current = begun.journal
                    is CutoverRestoreTransition.Rejected -> {
                        return AutoCutoverRestoreResult.Rejected(
                            begun.reason.toCoordinatorRejection(),
                            begun.journal
                        ).also { terminal = it }
                    }
                }
            }

            while (true) {
                val journal = requireNotNull(current)
                when (journal.phase) {
                    CutoverRestorePhase.STAGED -> {
                        val eligibility = eligibilityPort.observe()
                        if (eligibility != CutoverEligibility.ELIGIBLE) {
                            current = persistTracked(
                                reducer.reduce(journal, CutoverRestoreEvent.Begin(identity, eligibility))
                            ) ?: return invalidTransition(journal).also { terminal = it }
                            continue
                        }
                        val roomState = roomAccess { roomPort.classify(decoded.archive) }
                        when {
                            roomState.matchesArchive -> Unit
                            roomState.isEmpty -> roomAccess { roomPort.restore(decoded.archive) }
                            else -> {
                                current = persistFailureTracked(
                                    journal,
                                    CutoverRestoreFailureReason.ROOM_WRITE_FAILED
                                )
                                continue
                            }
                        }
                        if (!roomAccess { roomPort.classify(decoded.archive) }.matchesArchive) {
                            current = persistFailureTracked(
                                journal,
                                CutoverRestoreFailureReason.ROOM_WRITE_FAILED
                            )
                            continue
                        }
                        current = persistTracked(
                            reducer.reduce(journal, CutoverRestoreEvent.RoomWritten(identity))
                        ) ?: return invalidTransition(journal).also { terminal = it }
                    }

                    CutoverRestorePhase.ROOM_WRITTEN -> {
                        val preferenceState = preferencePort.classify(decoded.archive)
                        when {
                            preferenceState.matchesArchive -> Unit
                            preferenceState.isEmpty -> preferencePort.restore(decoded.archive)
                            else -> {
                                current = persistFailureTracked(
                                    journal,
                                    CutoverRestoreFailureReason.DATASTORE_WRITE_FAILED
                                )
                                continue
                            }
                        }
                        if (!preferencePort.classify(decoded.archive).matchesArchive) {
                            current = persistFailureTracked(
                                journal,
                                CutoverRestoreFailureReason.DATASTORE_WRITE_FAILED
                            )
                            continue
                        }
                        current = persistTracked(
                            reducer.reduce(journal, CutoverRestoreEvent.DataStoreWritten(identity))
                        ) ?: return invalidTransition(journal).also { terminal = it }
                    }

                    CutoverRestorePhase.DATASTORE_WRITTEN -> {
                        val exact = roomAccess { roomPort.classify(decoded.archive) }.matchesArchive &&
                            preferencePort.classify(decoded.archive).matchesArchive
                        val readbackDigest = if (exact) identity.archiveDigest else "sha256:${"0".repeat(64)}"
                        current = persistTracked(
                            reducer.reduce(journal, CutoverRestoreEvent.Verified(identity, readbackDigest))
                        ) ?: return invalidTransition(journal).also { terminal = it }
                    }

                    CutoverRestorePhase.VERIFIED -> {
                        current = persistTracked(
                            reducer.reduce(
                                journal,
                                CutoverRestoreEvent.PublishReady(identity, eligibilityPort.observe())
                            )
                        ) ?: return invalidTransition(journal).also { terminal = it }
                    }

                    CutoverRestorePhase.ROLLBACK_REQUIRED -> {
                        preferencePort.clear()
                        roomAccess { roomPort.clear() }
                        val empty = roomAccess { roomPort.classify(decoded.archive) }.isEmpty &&
                            preferencePort.classify(decoded.archive).isEmpty
                        if (!empty) {
                            return AutoCutoverRestoreResult.RecoveryRequired(journal).also { terminal = it }
                        }
                        current = persistTracked(
                            reducer.reduce(journal, CutoverRestoreEvent.RollbackCompleted(identity))
                        ) ?: return invalidTransition(journal).also { terminal = it }
                    }

                    CutoverRestorePhase.READY,
                    CutoverRestorePhase.ROLLED_BACK -> {
                        return AutoCutoverRestoreResult.Completed(journal).also { terminal = it }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            val journal = current
            if (journal != null && journal.phase !in TERMINAL_PHASES) {
                current = withContext(NonCancellable) {
                    persistFailureTracked(journal, CutoverRestoreFailureReason.INTERRUPTED)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            val journal = current
            if (journal != null && journal.phase !in TERMINAL_PHASES) {
                current = persistFailureTracked(journal, failureReasonFor(journal.phase))
                return AutoCutoverRestoreResult.RecoveryRequired(requireNotNull(current)).also { terminal = it }
            }
            throw failure
        } finally {
            val journal = terminal?.journal ?: current
            val release = if (durableReadConfirmed && journalWriteConfirmed &&
                (journal == null || journal.phase in TERMINAL_PHASES)
            ) {
                CutoverExclusiveRelease.OPEN
            } else {
                CutoverExclusiveRelease.RecoveryRequiredFor(
                    if (durableReadConfirmed) journal?.identity else null
                )
            }
            lease.release(release)
        }
    }

    private fun invalidTransition(journal: CutoverRestoreJournal) = AutoCutoverRestoreResult.Rejected(
        CutoverCoordinatorRejection.INVALID_PHASE,
        journal
    )

    private fun failureReasonFor(phase: CutoverRestorePhase): CutoverRestoreFailureReason = when (phase) {
        CutoverRestorePhase.STAGED -> CutoverRestoreFailureReason.ROOM_WRITE_FAILED
        CutoverRestorePhase.ROOM_WRITTEN -> CutoverRestoreFailureReason.DATASTORE_WRITE_FAILED
        CutoverRestorePhase.DATASTORE_WRITTEN,
        CutoverRestorePhase.VERIFIED -> CutoverRestoreFailureReason.ARCHIVE_VERIFICATION_FAILED
        CutoverRestorePhase.ROLLBACK_REQUIRED -> CutoverRestoreFailureReason.INTERRUPTED
        CutoverRestorePhase.READY,
        CutoverRestorePhase.ROLLED_BACK -> error("terminal phase has no failure transition")
    }

    private companion object {
        val TERMINAL_PHASES = setOf(CutoverRestorePhase.READY, CutoverRestorePhase.ROLLED_BACK)
    }
}

private fun CutoverRestoreRejectionReason.toCoordinatorRejection(): CutoverCoordinatorRejection = when (this) {
    CutoverRestoreRejectionReason.NOT_ELIGIBLE -> CutoverCoordinatorRejection.NOT_ELIGIBLE
    CutoverRestoreRejectionReason.ARCHIVE_CONFLICT -> CutoverCoordinatorRejection.ARCHIVE_CONFLICT
    CutoverRestoreRejectionReason.NO_ACTIVE_RESTORE,
    CutoverRestoreRejectionReason.INVALID_PHASE -> CutoverCoordinatorRejection.INVALID_PHASE
}
