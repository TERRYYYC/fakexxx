package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class CutoverGatePhase {
    OPEN,
    DRAINING,
    EXCLUSIVE,
    RECOVERY_REQUIRED
}

data class CutoverGateSnapshot(
    val phase: CutoverGatePhase,
    val activeNormalAccesses: Int,
    val identity: CutoverRestoreIdentity?
) {
    companion object {
        fun open(): CutoverGateSnapshot = CutoverGateSnapshot(
            phase = CutoverGatePhase.OPEN,
            activeNormalAccesses = 0,
            identity = null
        )
    }
}

enum class CutoverUnavailableReason {
    CUTOVER_IN_PROGRESS,
    RECOVERY_REQUIRED
}

sealed interface CutoverAccessResult<out T> {
    data class Granted<T>(val value: T) : CutoverAccessResult<T>

    data class Unavailable(
        val reason: CutoverUnavailableReason,
        val identity: CutoverRestoreIdentity?
    ) : CutoverAccessResult<Nothing>
}

sealed interface CutoverExclusiveAdmission {
    data class Granted(val lease: CutoverExclusiveLease) : CutoverExclusiveAdmission
    data class Busy(val identity: CutoverRestoreIdentity?) : CutoverExclusiveAdmission
    data object QuiescenceFailed : CutoverExclusiveAdmission
}

sealed interface CutoverExclusiveRelease {
    data object OPEN : CutoverExclusiveRelease
    data object RECOVERY_REQUIRED : CutoverExclusiveRelease
    data class RecoveryRequiredFor(
        val identity: CutoverRestoreIdentity?
    ) : CutoverExclusiveRelease
}

/**
 * Process-local admission gate shared by every ordinary Room/DataStore owner.
 *
 * A normal access admitted before drain is allowed to finish. The first exclusive request closes
 * admission immediately, then suspends until those already-admitted accesses release. A restore
 * journal in a non-terminal phase bootstraps the gate closed, so application restart cannot expose
 * a partially restored generation while the coordinator is being reconstructed.
 */
class CutoverAccessGate private constructor(initial: GateState) {
    private val mutex = Mutex()
    private var state: GateState = initial
    private var nextLeaseId = 1L

    suspend fun <T> withNormalAccess(block: suspend () -> T): CutoverAccessResult<T> {
        val unavailable: CutoverAccessResult.Unavailable? = mutex.withLock {
            when (val current = state) {
                is GateState.Open -> {
                    state = current.copy(activeNormalAccesses = current.activeNormalAccesses + 1)
                    null
                }
                is GateState.Draining -> CutoverAccessResult.Unavailable(
                    CutoverUnavailableReason.CUTOVER_IN_PROGRESS,
                    current.owner.restoreIdentity
                )
                is GateState.Exclusive -> CutoverAccessResult.Unavailable(
                    CutoverUnavailableReason.CUTOVER_IN_PROGRESS,
                    current.owner.restoreIdentity
                )
                is GateState.RecoveryRequired -> CutoverAccessResult.Unavailable(
                    CutoverUnavailableReason.RECOVERY_REQUIRED,
                    current.identity
                )
            }
        }
        if (unavailable != null) return unavailable

        return try {
            CutoverAccessResult.Granted(block())
        } finally {
            withContext(NonCancellable) { releaseNormalAccess() }
        }
    }

    suspend fun acquireExclusive(
        identity: CutoverRestoreIdentity,
        quiesce: suspend () -> Boolean = { true }
    ): CutoverExclusiveAdmission = acquireExclusive(GateOwner.Restore(identity), quiesce)

    suspend fun acquireCaptureExclusive(
        captureId: String,
        quiesce: suspend () -> Boolean
    ): CutoverExclusiveAdmission {
        require(captureId.isNotBlank()) { "capture id cannot be blank" }
        return acquireExclusive(GateOwner.Capture(captureId), quiesce)
    }

    private suspend fun acquireExclusive(
        owner: GateOwner,
        quiesce: suspend () -> Boolean
    ): CutoverExclusiveAdmission {
        val waitForDrain = mutex.withLock {
            when (val current = state) {
                is GateState.Open -> {
                    val drained = CompletableDeferred<Unit>()
                    val requestId = nextLeaseId++
                    state = GateState.Draining(owner, current.activeNormalAccesses, drained, requestId)
                    if (current.activeNormalAccesses == 0) drained.complete(Unit)
                    DrainRequest(drained, requestId)
                }
                is GateState.RecoveryRequired -> {
                    if (owner !is GateOwner.Restore ||
                        current.identity != null && current.identity != owner.identity
                    ) {
                        return CutoverExclusiveAdmission.Busy(current.identity)
                    }
                    state = GateState.Exclusive(owner, nextLeaseId++)
                    return CutoverExclusiveAdmission.Granted(newExclusiveLease(owner, state.leaseId()))
                }
                is GateState.Draining -> return CutoverExclusiveAdmission.Busy(current.owner.restoreIdentity)
                is GateState.Exclusive -> return CutoverExclusiveAdmission.Busy(current.owner.restoreIdentity)
            }
        }
        val quiesced = try {
            quiesce()
        } catch (failure: Throwable) {
            withContext(NonCancellable) { reopenFailedDrain(owner, waitForDrain.requestId) }
            throw failure
        }
        if (!quiesced) {
            withContext(NonCancellable) { reopenFailedDrain(owner, waitForDrain.requestId) }
            return CutoverExclusiveAdmission.QuiescenceFailed
        }
        return try {
            waitForDrain.drained.await()
            mutex.withLock {
                val current = state
                check(current is GateState.Draining && current.owner == owner &&
                    current.requestId == waitForDrain.requestId
                ) {
                    "cutover drain ownership changed"
                }
                check(current.activeNormalAccesses == 0) { "cutover drain completed with active access" }
                val leaseId = nextLeaseId++
                state = GateState.Exclusive(owner, leaseId)
                CutoverExclusiveAdmission.Granted(newExclusiveLease(owner, leaseId))
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) { reopenFailedDrain(owner, waitForDrain.requestId) }
            throw failure
        }
    }

    suspend fun snapshot(): CutoverGateSnapshot = mutex.withLock {
        when (val current = state) {
            is GateState.Open -> CutoverGateSnapshot(
                CutoverGatePhase.OPEN,
                current.activeNormalAccesses,
                null
            )
            is GateState.Draining -> CutoverGateSnapshot(
                CutoverGatePhase.DRAINING,
                current.activeNormalAccesses,
                current.owner.restoreIdentity
            )
            is GateState.Exclusive -> CutoverGateSnapshot(
                CutoverGatePhase.EXCLUSIVE,
                0,
                current.owner.restoreIdentity
            )
            is GateState.RecoveryRequired -> CutoverGateSnapshot(
                CutoverGatePhase.RECOVERY_REQUIRED,
                0,
                current.identity
            )
        }
    }

    private fun newExclusiveLease(
        owner: GateOwner,
        leaseId: Long
    ): CutoverExclusiveLease = CutoverExclusiveLease { release ->
        releaseExclusive(owner, leaseId, release)
    }

    private suspend fun reopenFailedDrain(owner: GateOwner, requestId: Long) {
        mutex.withLock {
            val current = state
            if (current is GateState.Draining && current.owner == owner && current.requestId == requestId) {
                state = GateState.Open(current.activeNormalAccesses)
            }
        }
    }

    private suspend fun releaseNormalAccess() {
        mutex.withLock {
            when (val current = state) {
                is GateState.Open -> {
                    check(current.activeNormalAccesses > 0) { "normal access underflow" }
                    state = current.copy(activeNormalAccesses = current.activeNormalAccesses - 1)
                }
                is GateState.Draining -> {
                    check(current.activeNormalAccesses > 0) { "normal access underflow while draining" }
                    val remaining = current.activeNormalAccesses - 1
                    state = current.copy(activeNormalAccesses = remaining)
                    if (remaining == 0) current.drained.complete(Unit)
                }
                is GateState.Exclusive,
                is GateState.RecoveryRequired -> error("normal access outlived its drain")
            }
        }
    }

    private suspend fun releaseExclusive(
        owner: GateOwner,
        leaseId: Long,
        release: CutoverExclusiveRelease
    ): Boolean = mutex.withLock {
        val current = state
        if (current !is GateState.Exclusive || current.owner != owner || current.leaseId != leaseId) {
            return@withLock false
        }
        state = when (release) {
            CutoverExclusiveRelease.OPEN -> GateState.Open(activeNormalAccesses = 0)
            CutoverExclusiveRelease.RECOVERY_REQUIRED -> GateState.RecoveryRequired(owner.restoreIdentity)
            is CutoverExclusiveRelease.RecoveryRequiredFor -> GateState.RecoveryRequired(release.identity)
        }
        true
    }

    companion object {
        fun open(): CutoverAccessGate = CutoverAccessGate(GateState.Open(activeNormalAccesses = 0))

        fun fromJournal(journal: CutoverRestoreJournal?): CutoverAccessGate {
            val open = journal == null || journal.phase == CutoverRestorePhase.READY ||
                journal.phase == CutoverRestorePhase.ROLLED_BACK
            return if (open) {
                open()
            } else {
                CutoverAccessGate(GateState.RecoveryRequired(journal.identity))
            }
        }
    }
}

class CutoverExclusiveLease internal constructor(
    private val releaseAction: suspend (CutoverExclusiveRelease) -> Boolean
) {
    private val mutex = Mutex()
    private var released = false

    suspend fun release(release: CutoverExclusiveRelease): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            if (released) return@withLock false
            val result = releaseAction(release)
            released = true
            result
        }
    }
}

private sealed interface GateState {
    data class Open(val activeNormalAccesses: Int) : GateState

    data class Draining(
        val owner: GateOwner,
        val activeNormalAccesses: Int,
        val drained: CompletableDeferred<Unit>,
        val requestId: Long
    ) : GateState

    data class Exclusive(
        val owner: GateOwner,
        val leaseId: Long
    ) : GateState

    data class RecoveryRequired(val identity: CutoverRestoreIdentity?) : GateState
}

private sealed interface GateOwner {
    val restoreIdentity: CutoverRestoreIdentity?

    data class Capture(val captureId: String) : GateOwner {
        override val restoreIdentity: CutoverRestoreIdentity? = null
    }

    data class Restore(val identity: CutoverRestoreIdentity) : GateOwner {
        override val restoreIdentity: CutoverRestoreIdentity = identity
    }
}

private data class DrainRequest(
    val drained: CompletableDeferred<Unit>,
    val requestId: Long
)

private fun GateState.leaseId(): Long = (this as GateState.Exclusive).leaseId
