package com.example.cellrebelauto.cutover

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor

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

/** Public restored-data projection: absence/defaults are values only after a gated read succeeds. */
sealed interface CutoverDataState<out T> {
    data object Loading : CutoverDataState<Nothing>

    data class Ready<T>(val value: T) : CutoverDataState<T>

    data class Unavailable(
        val reason: CutoverUnavailableReason,
        val identity: CutoverRestoreIdentity?
    ) : CutoverDataState<Nothing>
}

class CutoverAccessUnavailableException(
    val reason: CutoverUnavailableReason,
    val identity: CutoverRestoreIdentity?
) : IllegalStateException("cutover access unavailable: $reason")

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
    private var dataAvailabilityRevision = 0L
    private val dataAvailability = MutableStateFlow(initial.toDataAvailability(dataAvailabilityRevision))
    private val currentAccess = ThreadLocal<GateAccessContext?>()

    suspend fun <T> withNormalAccess(block: suspend () -> T): CutoverAccessResult<T> {
        val inherited = currentAccess.get()
        if (inherited?.isActiveNormalFor(this) == true) {
            return CutoverAccessResult.Granted(block())
        }
        val admission = acquireNormalAccess()
        if (admission is CutoverAccessResult.Unavailable) return admission
        val access = (admission as CutoverAccessResult.Granted).value

        return try {
            withContext(currentAccess.asContextElement(access)) {
                CutoverAccessResult.Granted(block())
            }
        } finally {
            withContext(NonCancellable) { releaseAccessReference(access) }
        }
    }

    private suspend fun acquireNormalAccess(): CutoverAccessResult<GateAccessContext> = mutex.withLock {
        when (val current = state) {
            is GateState.Open -> {
                setState(current.copy(activeNormalAccesses = current.activeNormalAccesses + 1))
                CutoverAccessResult.Granted(GateAccessContext(this, GateAccessKind.NORMAL))
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

    suspend fun <T> withNormalAccessOrThrow(block: suspend () -> T): T =
        when (val result = withNormalAccess(block)) {
            is CutoverAccessResult.Granted -> result.value
            is CutoverAccessResult.Unavailable -> throw result.toException()
        }

    fun <T> withNormalAccessBlockingOrThrow(block: () -> T): T = runBlocking {
        withNormalAccessOrThrow { block() }
    }

    /**
     * Room executors preserve an already-admitted owner across their worker-thread hop. A query
     * submitted without an owner acquires synchronously, so a closed gate rejects the DAO call at
     * submission instead of throwing later on a worker and stranding Room's continuation.
     */
    fun guardExecutor(delegate: Executor): Executor = Executor { command ->
        val inherited = currentAccess.get()?.retainIfActiveFor(this)
        val access = inherited ?: runBlocking {
            when (val admission = acquireNormalAccess()) {
                is CutoverAccessResult.Granted -> admission.value
                is CutoverAccessResult.Unavailable -> throw admission.toException()
            }
        }
        try {
            delegate.execute { runGuardedCommand(access, command) }
        } catch (failure: Throwable) {
            runBlocking { releaseAccessReference(access) }
            throw failure
        }
    }

    private fun runGuardedCommand(access: GateAccessContext, command: Runnable) {
        val prior = currentAccess.get()
        currentAccess.set(access)
        try {
            command.run()
        } finally {
            currentAccess.set(prior)
            runBlocking { releaseAccessReference(access) }
        }
    }

    private suspend fun releaseAccessReference(access: GateAccessContext) {
        if (access.releaseReference() && access.kind == GateAccessKind.NORMAL) {
            releaseNormalAccess()
        }
    }

    /** Closed generations stop collection; reopening resubscribes and reads a fresh projection. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> gateFlow(upstream: Flow<T>): Flow<T> =
        dataAvailability.flatMapLatest { availability ->
            if (availability is GateDataAvailability.Available) {
                upstream.retryWhen { failure, _ ->
                    if (failure !is CutoverAccessUnavailableException) return@retryWhen false
                    dataAvailability.first {
                        it is GateDataAvailability.Available && it.revision != availability.revision
                    }
                    true
                }
            } else {
                emptyFlow()
            }
        }.buffer(capacity = 0)

    /**
     * UI-facing restored-data projection. Closing the gate cancels the complete upstream graph and
     * emits typed unavailability; reopening creates a fresh subscription and first reports loading.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun <T> dataFlow(upstream: Flow<T>): Flow<CutoverDataState<T>> =
        dataAvailability.flatMapLatest { availability ->
            when (availability) {
                is GateDataAvailability.Available -> upstream
                    .map<T, CutoverDataState<T>> { CutoverDataState.Ready(it) }
                    .onStart { emit(CutoverDataState.Loading) }
                is GateDataAvailability.Unavailable -> flowOf(
                    CutoverDataState.Unavailable(availability.reason, availability.identity)
                )
            }
        }.buffer(capacity = 0)

    /** Exact initial value for StateFlow owners; recovery-closed startup never emits a default. */
    fun <T> initialDataState(): CutoverDataState<T> = when (val availability = dataAvailability.value) {
        is GateDataAvailability.Available -> CutoverDataState.Loading
        is GateDataAvailability.Unavailable ->
            CutoverDataState.Unavailable(availability.reason, availability.identity)
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
                    setState(GateState.Draining(owner, current.activeNormalAccesses, drained, requestId))
                    if (current.activeNormalAccesses == 0) drained.complete(Unit)
                    DrainRequest(drained, requestId)
                }
                is GateState.RecoveryRequired -> {
                    if (owner !is GateOwner.Restore ||
                        current.identity != null && current.identity != owner.identity
                    ) {
                        return CutoverExclusiveAdmission.Busy(current.identity)
                    }
                    setState(GateState.Exclusive(owner, nextLeaseId++))
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
                setState(GateState.Exclusive(owner, leaseId))
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
    ): CutoverExclusiveLease = CutoverExclusiveLease(
        gate = this,
        access = GateAccessContext(this, GateAccessKind.EXCLUSIVE)
    ) { release -> releaseExclusive(owner, leaseId, release) }

    internal suspend fun <T> withExclusiveAccess(
        access: GateAccessContext,
        block: suspend () -> T
    ): T {
        check(access.isActiveExclusiveFor(this)) { "exclusive cutover lease is not active" }
        return withContext(currentAccess.asContextElement(access)) { block() }
    }

    private suspend fun reopenFailedDrain(owner: GateOwner, requestId: Long) {
        mutex.withLock {
            val current = state
            if (current is GateState.Draining && current.owner == owner && current.requestId == requestId) {
                setState(GateState.Open(current.activeNormalAccesses))
            }
        }
    }

    private suspend fun releaseNormalAccess() {
        mutex.withLock {
            when (val current = state) {
                is GateState.Open -> {
                    check(current.activeNormalAccesses > 0) { "normal access underflow" }
                    setState(current.copy(activeNormalAccesses = current.activeNormalAccesses - 1))
                }
                is GateState.Draining -> {
                    check(current.activeNormalAccesses > 0) { "normal access underflow while draining" }
                    val remaining = current.activeNormalAccesses - 1
                    setState(current.copy(activeNormalAccesses = remaining))
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
        setState(when (release) {
            CutoverExclusiveRelease.OPEN -> GateState.Open(activeNormalAccesses = 0)
            CutoverExclusiveRelease.RECOVERY_REQUIRED -> GateState.RecoveryRequired(owner.restoreIdentity)
            is CutoverExclusiveRelease.RecoveryRequiredFor -> GateState.RecoveryRequired(release.identity)
        })
        true
    }

    private fun setState(next: GateState) {
        state = next
        val nextAvailability = next.toDataAvailability(dataAvailabilityRevision)
        if (!dataAvailability.value.hasSameVisibilityAs(nextAvailability)) {
            dataAvailabilityRevision += 1L
            dataAvailability.value = next.toDataAvailability(dataAvailabilityRevision)
        }
    }

    companion object {
        fun open(): CutoverAccessGate = CutoverAccessGate(GateState.Open(activeNormalAccesses = 0))

        fun recoveryRequired(identity: CutoverRestoreIdentity? = null): CutoverAccessGate =
            CutoverAccessGate(GateState.RecoveryRequired(identity))

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
    private val gate: CutoverAccessGate,
    private val access: GateAccessContext,
    private val releaseAction: suspend (CutoverExclusiveRelease) -> Boolean
) {
    private val mutex = Mutex()
    private var released = false

    suspend fun <T> withExclusiveAccess(block: suspend () -> T): T = mutex.withLock {
        check(!released) { "exclusive cutover lease already released" }
        gate.withExclusiveAccess(access, block)
    }

    suspend fun release(release: CutoverExclusiveRelease): Boolean = withContext(NonCancellable) {
        mutex.withLock {
            if (released) return@withLock false
            access.awaitOnlyExclusiveRoot()
            val result = releaseAction(release)
            released = true
            check(access.releaseExclusiveRoot())
            result
        }
    }
}

internal enum class GateAccessKind { NORMAL, EXCLUSIVE }

internal class GateAccessContext(
    private val gate: CutoverAccessGate,
    internal val kind: GateAccessKind
) {
    private val referenceLock = Any()
    private var references: Int = 1
    private var exclusiveChildrenDrained = completedSignal()
    @Volatile private var active: Boolean = true

    fun isActiveFor(expected: CutoverAccessGate): Boolean = active && gate === expected
    fun isActiveNormalFor(expected: CutoverAccessGate): Boolean =
        isActiveFor(expected) && kind == GateAccessKind.NORMAL
    fun isActiveExclusiveFor(expected: CutoverAccessGate): Boolean =
        isActiveFor(expected) && kind == GateAccessKind.EXCLUSIVE

    fun retainIfActiveFor(expected: CutoverAccessGate): GateAccessContext? = synchronized(referenceLock) {
        if (!active || gate !== expected) return@synchronized null
        if (kind == GateAccessKind.EXCLUSIVE && references == 1) {
            exclusiveChildrenDrained = CompletableDeferred()
        }
        references += 1
        this
    }

    /** Returns true when the final owner released this token. */
    fun releaseReference(): Boolean = synchronized(referenceLock) {
        check(references > 0) { "cutover access reference underflow" }
        references -= 1
        if (kind == GateAccessKind.EXCLUSIVE && references == 1) {
            exclusiveChildrenDrained.complete(Unit)
        }
        if (references == 0) active = false
        references == 0
    }

    fun releaseExclusiveRoot(): Boolean = synchronized(referenceLock) {
        if (!active || kind != GateAccessKind.EXCLUSIVE || references != 1) {
            return@synchronized false
        }
        references = 0
        active = false
        true
    }

    fun hasOnlyExclusiveRoot(): Boolean = synchronized(referenceLock) {
        active && kind == GateAccessKind.EXCLUSIVE && references == 1
    }

    suspend fun awaitOnlyExclusiveRoot() {
        val drained = synchronized(referenceLock) {
            check(active && kind == GateAccessKind.EXCLUSIVE) { "exclusive cutover lease is not active" }
            exclusiveChildrenDrained
        }
        drained.await()
        check(hasOnlyExclusiveRoot()) { "exclusive cutover work did not drain" }
    }

    private companion object {
        fun completedSignal(): CompletableDeferred<Unit> =
            CompletableDeferred<Unit>().also { it.complete(Unit) }
    }
}

private fun CutoverAccessResult.Unavailable.toException() =
    CutoverAccessUnavailableException(reason, identity)

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

private sealed interface GateDataAvailability {
    val revision: Long

    data class Available(override val revision: Long) : GateDataAvailability
    data class Unavailable(
        val reason: CutoverUnavailableReason,
        val identity: CutoverRestoreIdentity?,
        override val revision: Long
    ) : GateDataAvailability
}

private fun GateState.toDataAvailability(revision: Long): GateDataAvailability = when (this) {
    is GateState.Open -> GateDataAvailability.Available(revision)
    is GateState.Draining -> GateDataAvailability.Unavailable(
        CutoverUnavailableReason.CUTOVER_IN_PROGRESS,
        owner.restoreIdentity,
        revision
    )
    is GateState.Exclusive -> GateDataAvailability.Unavailable(
        CutoverUnavailableReason.CUTOVER_IN_PROGRESS,
        owner.restoreIdentity,
        revision
    )
    is GateState.RecoveryRequired -> GateDataAvailability.Unavailable(
        CutoverUnavailableReason.RECOVERY_REQUIRED,
        identity,
        revision
    )
}

private fun GateDataAvailability.hasSameVisibilityAs(other: GateDataAvailability): Boolean =
    when {
        this is GateDataAvailability.Available && other is GateDataAvailability.Available -> true
        this is GateDataAvailability.Unavailable && other is GateDataAvailability.Unavailable ->
            reason == other.reason && identity == other.identity
        else -> false
    }

private fun GateState.leaseId(): Long = (this as GateState.Exclusive).leaseId
