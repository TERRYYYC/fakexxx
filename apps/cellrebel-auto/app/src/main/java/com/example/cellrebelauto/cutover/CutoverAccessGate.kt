package com.example.cellrebelauto.cutover

import android.util.Log
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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
class CutoverAccessGate private constructor(
    initial: GateState,
    private val admissionWaitBudgetMs: Long,
    private val retryBudgetMs: Long,
    private val retryBaseBackoffMs: Long,
    private val retryMaxBackoffMs: Long
) {
    private val mutex = Mutex()
    private var state: GateState = initial
    private var nextLeaseId = 1L
    private var dataAvailabilityRevision = 0L
    private val dataAvailability = MutableStateFlow(initial.toDataAvailability(dataAvailabilityRevision))
    private val currentAccess = ThreadLocal<GateAccessContext?>()

    // ---- #164: admission-retry diagnostics -----------------------------------------------
    // Retry logging goes through a pluggable sink: the default forwards to android.util.Log
    // (runCatching-wrapped so plain-JVM unit tests without Robolectric never trip the stub),
    // tests install a capturing sink. Counters are observability for tests and triage.
    @Volatile
    internal var retryLogSink: ((String) -> Unit)? = null

    private val retryEnqueued = AtomicLong()
    private val retryAttempts = AtomicLong()
    private val retryDropped = AtomicLong()

    /** FIFO of admission-rejected commands; non-null ⇒ a retry tick is scheduled or running. */
    private val retryLock = Any()
    private var retryQueue: ArrayDeque<RetryEntry>? = null
    private var retryTickScheduled = false

    internal fun gateRetryStats(): GateRetryStats = GateRetryStats(
        enqueued = retryEnqueued.get(),
        attempts = retryAttempts.get(),
        dropped = retryDropped.get()
    )

    internal data class GateRetryStats(val enqueued: Long, val attempts: Long, val dropped: Long)

    private fun logRetry(message: String) {
        val sink = retryLogSink
        if (sink != null) {
            runCatching { sink("[GateRetry] $message") }
            return
        }
        runCatching { Log.w(GATE_RETRY_LOG_TAG, message) }
    }

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

    private suspend fun acquireNormalAccess(): CutoverAccessResult<GateAccessContext> {
        // #161 fast path: an uncontended gate decides inline — no suspension, no event loop — so
        // callers that must not enter the coroutine world (guardExecutor on a submitting thread)
        // pay zero suspension in the common case. Only a contended mutex suspends.
        tryAcquireNormalAccessWithoutSuspending()?.let { return it }
        return mutex.withLock { admissionDecisionLocked() }
    }

    /** Non-suspending admission probe; null means "mutex contended — caller decides how to wait". */
    private fun tryAcquireNormalAccessWithoutSuspending(): CutoverAccessResult<GateAccessContext>? {
        if (!mutex.tryLock()) return null
        try {
            return admissionDecisionLocked()
        } finally {
            mutex.unlock()
        }
    }

    private fun admissionDecisionLocked(): CutoverAccessResult<GateAccessContext> = when (val current = state) {
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
     * submitted without an owner acquires synchronously on the submitting thread (often the
     * Android main thread via a Room Flow emit chain).
     *
     * #161: "synchronously" must never mean "parked indefinitely". Admission takes the
     * uncontended fast path inline; only a genuinely contended mutex suspends, inside a bounded
     * wait ([admissionWaitBudgetMs]). This is the exact shape of the #161 engine freeze: main
     * parked in `runBlocking { acquireNormalAccess() }` while the mutex owner needed main to
     * progress, and the attempt watchdog — on the same dispatcher — died with it.
     *
     * #164 (regression over #162's fail-closed throw): the caller of a wrapped executor is not
     * only PlanRepository — Room's own InvalidationTracker (`TriggerBasedInvalidationTracker
     * .createFlow`) runs table-invalidation refreshes through the gated queryExecutor from
     * whatever thread drives collection, and nothing in that framework path handles (or may
     * handle) a business gate exception. #162's admission failure escaped along that emit chain
     * to the main thread and killed the process (mi14: FATAL 10s after a PASS). Cutover is
     * transient, so on THIS path admission failure must not throw at all: the command is
     * requeued on an independent scheduler (never on the submitting thread — the #161 red line)
     * and re-admitted with exponential backoff ([retryBaseBackoffMs] doubling up to
     * [retryMaxBackoffMs]). Direct, exception-aware callers (`withNormalAccessBlockingOrThrow`)
     * keep the #162 typed failure; only the executor wrapper retries.
     *
     * Ordering: requeued commands wait in a per-gate FIFO and are re-admitted head-first by a
     * single scheduler; a submission that arrives while the queue is non-empty joins the tail
     * instead of overtaking — requeued commands run in submission order. (A submission with an
     * empty queue still uses the inline fast path; an inherited owner always dispatches
     * immediately — both pre-existing semantics.) Room does not require FIFO of a query executor
     * (production wraps `Dispatchers.IO`, a pooling dispatcher), serializes transactions itself,
     * and its invalidation refresh loop is self-serializing, so the one observable cost — a fresh
     * command waiting behind the head's remaining backoff (≤ cap, typically one base step) — is
     * the deliberate price of not dropping and not reordering.
     *
     * Last resort: if [retryBudgetMs] lapses (gate wedged far beyond any real cutover — e.g.
     * RECOVERY_REQUIRED with no restore in sight), the command is DROPPED with a WARN and a
     * counter, never thrown: dropping is bad (a suspended DAO caller never resumes), but throwing
     * here crashes the process, and an unbounded queue instead would pin memory for the lifetime
     * of the wedge.
     */
    fun guardExecutor(delegate: Executor): Executor = Executor { command ->
        val inherited = currentAccess.get()?.retainIfActiveFor(this)
        if (inherited != null) {
            dispatchGuardedCommand(delegate, inherited, command)
        } else {
            when (val admission = acquireNormalAccessForSubmission()) {
                is SubmissionAdmission.Granted ->
                    dispatchGuardedCommand(delegate, admission.access, command)
                is SubmissionAdmission.Rejected -> requeueAfterAdmissionFailure(
                    RetryEntry(delegate, command, submittedAtNanos = System.nanoTime(), attempts = 0),
                    admission.describe(admissionWaitBudgetMs)
                )
            }
        }
    }

    private fun dispatchGuardedCommand(
        delegate: Executor,
        access: GateAccessContext,
        command: Runnable
    ) {
        try {
            delegate.execute { runGuardedCommand(access, command) }
        } catch (failure: Throwable) {
            releaseAccessReferenceBlocking(access)
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
            releaseAccessReferenceBlocking(access)
        }
    }

    // ---- #164: admission-retry queue — FIFO, single shared scheduler, bounded budget ----

    private class RetryEntry(
        val delegate: Executor,
        val command: Runnable,
        val submittedAtNanos: Long,
        var attempts: Int
    )

    private sealed interface SubmissionAdmission {
        data class Granted(val access: GateAccessContext) : SubmissionAdmission

        data class Rejected(val reason: CutoverUnavailableReason, val contended: Boolean) :
            SubmissionAdmission
    }

    private fun SubmissionAdmission.Rejected.describe(admissionWaitBudgetMs: Long): String =
        if (contended) {
            "$reason (gate mutex contended past the ${admissionWaitBudgetMs}ms admission budget)"
        } else {
            reason.name
        }

    /**
     * #161/#162: blocking admission for non-suspending submitters — decided inline when
     * uncontended, bounded wait when the mutex is contended. #164: the outcome is a value, not a
     * throw. The executor path requeues rejections (a framework submitter must never receive a
     * gate exception); coroutine and direct blocking callers translate
     * [CutoverAccessResult.Unavailable] into their own typed failure.
     */
    private fun acquireNormalAccessForSubmission(): SubmissionAdmission {
        tryAcquireNormalAccessWithoutSuspending()?.let { probe ->
            return when (probe) {
                is CutoverAccessResult.Granted -> SubmissionAdmission.Granted(probe.value)
                is CutoverAccessResult.Unavailable ->
                    SubmissionAdmission.Rejected(probe.reason, contended = false)
            }
        }
        val admitted = runBlocking {
            withTimeoutOrNull(admissionWaitBudgetMs) { acquireNormalAccess() }
        } ?: return SubmissionAdmission.Rejected(
            CutoverUnavailableReason.CUTOVER_IN_PROGRESS,
            contended = true
        )
        return when (admitted) {
            is CutoverAccessResult.Granted -> SubmissionAdmission.Granted(admitted.value)
            is CutoverAccessResult.Unavailable ->
                SubmissionAdmission.Rejected(admitted.reason, contended = false)
        }
    }

    private fun requeueAfterAdmissionFailure(entry: RetryEntry, reason: String) {
        val episodeStart: Boolean
        synchronized(retryLock) {
            val queue = retryQueue ?: ArrayDeque<RetryEntry>().also { retryQueue = it }
            episodeStart = queue.isEmpty()
            queue.addLast(entry)
            retryEnqueued.incrementAndGet()
            if (!retryTickScheduled) {
                scheduleRetryTickLocked(failedAttempts = entry.attempts)
            }
        }
        // Log once per episode: a cutover window rejects every submission, not just the first.
        if (episodeStart) {
            logRetry(
                "admission unavailable ($reason); command requeued with backoff " +
                    "(base ${retryBaseBackoffMs}ms, cap ${retryMaxBackoffMs}ms, budget ${retryBudgetMs}ms)"
            )
        }
    }

    /** Runs on the shared daemon scheduler; must never block (probes only, no runBlocking). */
    private fun drainRetryQueue() {
        val nowNanos = System.nanoTime()
        val admitted = ArrayList<Pair<GateAccessContext, RetryEntry>>()
        synchronized(retryLock) {
            retryTickScheduled = false
            val queue = retryQueue ?: return
            while (queue.isNotEmpty()) {
                val head = queue.first()
                if (nowNanos - head.submittedAtNanos >= retryBudgetMs * 1_000_000) {
                    queue.removeFirst()
                    val dropped = retryDropped.incrementAndGet()
                    logRetry(
                        "retry budget (${retryBudgetMs}ms) exhausted after ${head.attempts} " +
                            "attempt(s); command DROPPED (drop #$dropped) — its suspended caller " +
                            "will not resume; gate=${gatePhaseLabel()}"
                    )
                    continue
                }
                when (val probe = tryAcquireNormalAccessWithoutSuspending()) {
                    null -> {
                        recordFailedAttempt(head, "gate mutex contended")
                        scheduleRetryTickLocked(head.attempts - 1)
                        return
                    }
                    is CutoverAccessResult.Unavailable -> {
                        recordFailedAttempt(head, probe.reason.name)
                        scheduleRetryTickLocked(head.attempts - 1)
                        return
                    }
                    is CutoverAccessResult.Granted -> {
                        queue.removeFirst()
                        admitted += probe.value to head
                    }
                }
            }
            retryQueue = null
        }
        // Dispatch OFF the lock: commands run arbitrary DAO work. The scheduler is single-threaded,
        // so admitted commands still dispatch (and run) in queue order. A delegate that rejects an
        // admitted command requeues and dies by budget — never thrown, the tick must survive.
        for ((access, entry) in admitted) {
            try {
                entry.delegate.execute { runGuardedCommand(access, entry.command) }
            } catch (failure: Throwable) {
                releaseAccessReferenceBlocking(access)
                requeueAfterAdmissionFailure(
                    entry,
                    "delegate rejected an admitted command: $failure"
                )
            }
        }
    }

    private fun recordFailedAttempt(entry: RetryEntry, reason: String) {
        entry.attempts += 1
        val attemptsTotal = retryAttempts.incrementAndGet()
        if (entry.attempts == 1 || entry.attempts % RETRY_LOG_ATTEMPT_INTERVAL == 0) {
            logRetry(
                "admission still unavailable ($reason); retry #${entry.attempts} queued " +
                    "(lifetime retries: $attemptsTotal)"
            )
        }
    }

    /** Backoff doubles per failed attempt, capped; call under [retryLock]. */
    private fun scheduleRetryTickLocked(failedAttempts: Int) {
        val backoffMs = (retryBaseBackoffMs shl minOf(failedAttempts, 16))
            .coerceAtMost(retryMaxBackoffMs)
        retryTickScheduled = true
        sharedRetryScheduler().schedule({ drainRetryQueue() }, backoffMs, TimeUnit.MILLISECONDS)
    }

    /** Uncontended best-effort phase label for diagnostics; races are log-noise only. */
    private fun gatePhaseLabel(): String = when (val current = state) {
        is GateState.Open -> "OPEN"
        is GateState.Draining -> "DRAINING"
        is GateState.Exclusive -> "EXCLUSIVE"
        is GateState.RecoveryRequired -> "RECOVERY_REQUIRED"
    }

    /**
     * Blocking mirror of [releaseAccessReference] for non-suspending contexts (the guarded
     * command's finally, and guardExecutor's synchronous-rejection catch branch).
     *
     * Admission may fail closed; release may not — a dropped normal-access slot wedges the next
     * drain ("normal access outlived its drain") for the whole process. So: uncontended fast path
     * inline; contended bounded waits with retry. Retries make progress the moment the holder
     * does, and a holder that permanently needs the releasing thread is unresolvable by any
     * design — documented here rather than papered over with a leak.
     */
    private fun releaseAccessReferenceBlocking(access: GateAccessContext) {
        if (!access.releaseReference() || access.kind != GateAccessKind.NORMAL) return
        while (!tryReleaseNormalAccessWithoutSuspending()) {
            val released = runBlocking {
                withTimeoutOrNull(admissionWaitBudgetMs) { releaseNormalAccess() }
            }
            if (released != null) return
        }
    }

    private fun tryReleaseNormalAccessWithoutSuspending(): Boolean {
        if (!mutex.tryLock()) return false
        try {
            releaseNormalAccessLocked()
            return true
        } finally {
            mutex.unlock()
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
        mutex.withLock { releaseNormalAccessLocked() }
    }

    private fun releaseNormalAccessLocked() {
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
        private const val GATE_RETRY_LOG_TAG = "GateRetry"

        /** #161: blocking admissions never park longer than this before failing closed. */
        internal const val DEFAULT_ADMISSION_WAIT_BUDGET_MS = 5_000L

        /**
         * #164: total wall-clock budget a command may spend in the admission-retry queue before it
         * is dropped (never thrown). Sized at ~24x a long cutover exclusive window so a drop only
         * happens when the gate is wedged far beyond any real cutover (e.g. RECOVERY_REQUIRED).
         */
        internal const val DEFAULT_RETRY_BUDGET_MS = 120_000L
        internal const val RETRY_BASE_BACKOFF_MS = 250L
        internal const val RETRY_MAX_BACKOFF_MS = 5_000L

        /** #164: per-entry retry log throttling — first attempt, then every 8th. */
        private const val RETRY_LOG_ATTEMPT_INTERVAL = 8

        @Volatile
        private var sharedRetrySchedulerRef: ScheduledExecutorService? = null

        /** #164: one daemon scheduler drives every gate's retry ticks; ticks never block. */
        private fun sharedRetryScheduler(): ScheduledExecutorService {
            sharedRetrySchedulerRef?.let { return it }
            synchronized(this) {
                sharedRetrySchedulerRef?.let { return it }
                return Executors.newSingleThreadScheduledExecutor { runnable ->
                    Thread(runnable, "CutoverGateRetry").apply { isDaemon = true }
                }.also { sharedRetrySchedulerRef = it }
            }
        }

        fun open(): CutoverAccessGate =
            open(admissionWaitBudgetMs = DEFAULT_ADMISSION_WAIT_BUDGET_MS)

        internal fun open(admissionWaitBudgetMs: Long): CutoverAccessGate = open(
            admissionWaitBudgetMs = admissionWaitBudgetMs,
            retryBudgetMs = DEFAULT_RETRY_BUDGET_MS,
            retryBaseBackoffMs = RETRY_BASE_BACKOFF_MS,
            retryMaxBackoffMs = RETRY_MAX_BACKOFF_MS
        )

        internal fun open(
            admissionWaitBudgetMs: Long,
            retryBudgetMs: Long,
            retryBaseBackoffMs: Long,
            retryMaxBackoffMs: Long
        ): CutoverAccessGate = CutoverAccessGate(
            GateState.Open(activeNormalAccesses = 0),
            admissionWaitBudgetMs,
            retryBudgetMs,
            retryBaseBackoffMs,
            retryMaxBackoffMs
        )

        fun recoveryRequired(identity: CutoverRestoreIdentity? = null): CutoverAccessGate =
            CutoverAccessGate(
                GateState.RecoveryRequired(identity),
                DEFAULT_ADMISSION_WAIT_BUDGET_MS,
                DEFAULT_RETRY_BUDGET_MS,
                RETRY_BASE_BACKOFF_MS,
                RETRY_MAX_BACKOFF_MS
            )

        fun fromJournal(journal: CutoverRestoreJournal?): CutoverAccessGate {
            val open = journal == null || journal.phase == CutoverRestorePhase.READY ||
                journal.phase == CutoverRestorePhase.ROLLED_BACK
            return if (open) {
                open()
            } else {
                recoveryRequired(journal.identity)
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
