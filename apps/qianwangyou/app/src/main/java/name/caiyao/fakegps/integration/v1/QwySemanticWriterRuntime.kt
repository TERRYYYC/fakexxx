package name.caiyao.fakegps.integration.v1

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/** Reads the exact canonical digest used to register the current QWY session. */
fun interface QwySemanticDigestProvider {
    fun current(): String?
}

/**
 * Independent health gate supplied by the provider composition root.
 *
 * This must include the stable, complete oracle snapshot check. Coordinator
 * readiness alone only proves that a client token is still locally bound; it
 * cannot attest hook coverage, build identity, owner identity, or provider
 * enabled state.
 */
fun interface QwySemanticSessionHealth {
    fun isHealthyFor(semanticDigest: String): Boolean
}

/** A selected authoritative lane became ambiguous; callers must not report success. */
class QwySemanticWriterAmbiguityException internal constructor(
    message: String,
) : IllegalStateException(message)

/**
 * Process-global adapter used by QWY writers that are constructed outside the
 * Environment Control graph (settings and Room repositories).
 *
 * Absence is deliberately a legacy/non-authoritative lane: the local write is
 * executed unchanged. Once a healthy lane is installed, however, there is no
 * fallback. A missing digest, lost endpoint, unhealthy oracle, thrown local
 * write, or uncertain finish fails closed and never returns a successful value.
 */
object QwySemanticWriterRuntime {
    private data class Lane(
        val coordinator: QwySemanticMutationCoordinator,
        val semanticDigestProvider: QwySemanticDigestProvider,
        val sessionHealth: QwySemanticSessionHealth,
        val mutationIdFactory: (String) -> String,
    )

    private class Installation(
        private val lane: Lane,
    ) : AutoCloseable {
        @Volatile
        private var closed = false

        override fun close() {
            if (closed) return
            withSelectionLock {
                if (!closed) {
                    if (installedLane === lane) installedLane = null
                    closed = true
                }
            }
        }
    }

    /**
     * Serializes lane publication with both selected and legacy fallback
     * writers. It is reentrant because central publishers join an outer writer
     * on the same thread; installation can therefore never validate a digest,
     * publish a lane, and then be overtaken by a writer that already selected
     * the unbracketed fallback.
     */
    private val selectionLock = ReentrantLock(true)
    private val processNonce = UUID.randomUUID().toString()
    private val mutationCounter = AtomicLong(0L)
    private val activeAuthoritativeBrackets = AtomicInteger(0)
    private val currentThreadAuthoritativeDepth = ThreadLocal.withInitial { 0 }

    @Volatile
    private var installedLane: Lane? = null

    /**
     * Installs exactly one already-registered, independently healthy process
     * session. A second live owner is split brain and is rejected rather than
     * replacing the first one.
     */
    fun install(
        coordinator: QwySemanticMutationCoordinator,
        semanticDigestProvider: QwySemanticDigestProvider,
        sessionHealth: QwySemanticSessionHealth,
        mutationIdFactory: (String) -> String = ::nextMutationId,
    ): AutoCloseable = installWithExclusivePreparation(
        coordinator = coordinator,
        semanticDigestProvider = semanticDigestProvider,
        sessionHealth = sessionHealth,
        mutationIdFactory = mutationIdFactory,
        prepare = {},
    )

    /**
     * Installs a lane after running [prepare] while every fallback writer is
     * excluded. Production uses this to publish a fresh oracle registration
     * boundary before the lane can authorize FULL continuity.
     */
    fun installWithExclusivePreparation(
        coordinator: QwySemanticMutationCoordinator,
        semanticDigestProvider: QwySemanticDigestProvider,
        sessionHealth: QwySemanticSessionHealth,
        mutationIdFactory: (String) -> String = ::nextMutationId,
        prepare: () -> Unit,
    ): AutoCloseable = withSelectionLock {
        check(installedLane == null) {
            "a QWY semantic writer lane is already installed"
        }
        prepare()
        val initialDigest = readDigest(semanticDigestProvider)
            ?: throw IllegalStateException(
                "cannot install QWY semantic writer lane without a canonical digest",
            )
        check(coordinator.isReadyFor(initialDigest)) {
            "cannot install QWY semantic writer lane before session registration"
        }
        check(readHealth(sessionHealth, initialDigest)) {
            "cannot install QWY semantic writer lane without complete oracle health"
        }
        val lane = Lane(
            coordinator = coordinator,
            semanticDigestProvider = semanticDigestProvider,
            sessionHealth = sessionHealth,
            mutationIdFactory = mutationIdFactory,
        )
        installedLane = lane
        Installation(lane)
    }

    /** FULL authority requires this exact installed lane and live digest. */
    fun isInstalledAndHealthyFor(semanticDigest: String): Boolean = withSelectionLock {
        if (semanticDigest.isBlank()) return@withSelectionLock false
        val lane = installedLane ?: return@withSelectionLock false
        readDigest(lane.semanticDigestProvider) == semanticDigest &&
            lane.coordinator.isReadyFor(semanticDigest) &&
            readHealth(lane.sessionHealth, semanticDigest)
    }

    /** Whether this process has selected the no-fallback authoritative lane. */
    fun hasInstalledLane(): Boolean = withSelectionLock { installedLane != null }

    /**
     * True when the installed A session is still authoritative but the exact
     * local projection digest is B. Readiness must retain that lane so the
     * refresh publisher can fence the detected excursion and restore A.
     */
    fun canRepairExternalProjectionFor(localSemanticDigest: String): Boolean =
        withSelectionLock {
            val lane = installedLane ?: return@withSelectionLock false
            synchronized(lane) {
                val registeredDigest = lane.coordinator.registeredSemanticDigest()
                    ?: return@synchronized false
                val observedDigest = readDigest(lane.semanticDigestProvider)
                    ?: return@synchronized false
                localSemanticDigest.isNotBlank() &&
                    observedDigest == localSemanticDigest &&
                    observedDigest != registeredDigest &&
                    readHealth(lane.sessionHealth, registeredDigest)
            }
        }

    private inline fun <T> withSelectionLock(block: () -> T): T {
        selectionLock.lock()
        return try {
            block()
        } finally {
            selectionLock.unlock()
        }
    }

    /** Synchronous settings-writer entry point. */
    fun <T> mutate(
        kind: String,
        operation: (authoritativeLaneSelected: Boolean) -> T,
    ): T {
        require(kind.isNotBlank()) { "semantic mutation kind is required" }
        // Handler-owned mutations already hold the coordinator monitor. Join
        // them before taking the process writer lock; taking selection/lane
        // first would invert the external-writer order (writer -> coordinator)
        // and deadlock against this path (coordinator -> writer).
        val activeOuterLane = installedLane
        if (activeOuterLane != null &&
            activeOuterLane.coordinator.isMutationInFlightOnCurrentThread()
        ) {
            return operation(true)
        }
        return withSelectionLock {
            val lane = installedLane ?: return@withSelectionLock operation(false)
            executeSelected(lane, kind, operation)
        }
    }

    /** Cross-thread callback suppression while an external writer owns odd state. */
    fun isAuthoritativeMutationInFlight(): Boolean =
        activeAuthoritativeBrackets.get() > 0

    /** True only for callbacks synchronously caused by this thread's selected writer. */
    fun isAuthoritativeMutationInFlightOnCurrentThread(): Boolean =
        (currentThreadAuthoritativeDepth.get() ?: 0) > 0

    /**
     * Registers idempotent projection cleanup for post-work authority or
     * finish ambiguity. It runs on this same thread before the coordinator's
     * first uncertain finish; false means no authoritative local-work phase is
     * active and callers must rely on their ordinary inline failure cleanup.
     */
    fun registerUncertainCompensation(operation: () -> Unit): Boolean {
        val lane = installedLane ?: return false
        if (installedLane !== lane) return false
        return lane.coordinator.registerUncertainCompensationOnCurrentThread(operation)
    }

    /**
     * Shares the process writer lock without opening an oracle mutation.
     *
     * The mock-provider refresh loop uses this to decide, atomically with every
     * semantic writer, whether a refresh is only an identical location sample
     * (which is deliberately outside the journal) or a provider reconfigure
     * that must immediately enter [mutate]. The block must not change semantic
     * state unless it does so through a nested [mutate] call.
     */
    fun <T> serializeSelection(operation: () -> T): T =
        withSelectionLock(operation)

    /**
     * Fences a detected external B projection while restoring the registered A
     * digest. The remote mutation begins with A and is deliberately reported as
     * changed even though the repaired after-digest is A: the journal records
     * the observed excursion, not merely the equal endpoints.
     *
     * False means authority disappeared before local work began; callers retain
     * the active refresh session and retry. Once [operation] begins, any
     * ambiguity throws and must follow the ordinary failed-refresh cleanup path.
     */
    fun repairExternalProjection(
        kind: String,
        operation: () -> Unit,
    ): Boolean {
        require(kind.isNotBlank()) { "semantic mutation kind is required" }
        return withSelectionLock {
            // Before any authoritative lane is installed this remains the
            // product's legacy/non-authoritative refresh path. NONE continuity
            // is already explicit; cache healing must not be disabled merely
            // because production attestation is unavailable.
            val lane = installedLane ?: run {
                operation()
                return@withSelectionLock true
            }
            synchronized(lane) {
                repairExternalProjectionLocked(lane, kind, operation)
            }
        }
    }

    /**
     * Suspend-writer entry point for Room repositories.
     *
     * The existing coordinator deliberately owns one synchronous monitor across
     * remote begin/work/finish. Run that monitor on Dispatchers.IO and bridge
     * the suspend DAO work there, so UI callers remain suspended rather than
     * blocking their thread while the exact same authority bracket is retained.
     */
    suspend fun <T> mutateSuspend(
        kind: String,
        operation: suspend (authoritativeLaneSelected: Boolean) -> T,
    ): T {
        require(kind.isNotBlank()) { "semantic mutation kind is required" }
        val activeOuterLane = installedLane
        if (activeOuterLane != null &&
            activeOuterLane.coordinator.isMutationInFlightOnCurrentThread()
        ) {
            return operation(true)
        }
        return withContext(Dispatchers.IO) {
            val inheritedElements = currentCoroutineContext()
                .minusKey(Job)
                .minusKey(ContinuationInterceptor)
            withSelectionLock {
                val lane = installedLane
                if (lane == null) {
                    runBlocking(inheritedElements) { operation(false) }
                } else {
                    executeSelected(lane, kind) { selected ->
                        // Keep transaction/thread-context elements (notably Room's)
                        // while the dedicated blocking bridge owns dispatch locally.
                        runBlocking(inheritedElements) { operation(selected) }
                    }
                }
            }
        }
    }

    private fun <T> executeSelected(
        lane: Lane,
        kind: String,
        operation: (Boolean) -> T,
    ): T = synchronized(lane) {
        executeSelectedLocked(lane, kind, operation)
    }

    private fun repairExternalProjectionLocked(
        lane: Lane,
        kind: String,
        operation: () -> Unit,
    ): Boolean {
        ensureStillSelected(lane, "before external projection repair")
        val registeredDigest = lane.coordinator.registeredSemanticDigest() ?: return false
        val observedDigest = readDigest(lane.semanticDigestProvider)
        if (!readHealth(lane.sessionHealth, registeredDigest)) return false
        if (observedDigest == registeredDigest) {
            // The current digest may already have been registered from B while
            // the desired service/controller target is A. This is an ordinary
            // selected mutation rather than an external-excursion repair.
            executeSelectedLocked(lane, kind) {
                operation()
                Unit
            }
            return true
        }
        val mutationId = try {
            lane.mutationIdFactory(kind)
        } catch (_: Exception) {
            return false
        }
        if (mutationId.isBlank()) return false

        var operationStarted = false
        activeAuthoritativeBrackets.incrementAndGet()
        currentThreadAuthoritativeDepth.set(
            (currentThreadAuthoritativeDepth.get() ?: 0) + 1,
        )
        val result = try {
            lane.coordinator.runMutation(mutationId, registeredDigest) {
                operationStarted = true
                operation()
                if (installedLane !== lane) {
                    return@runMutation QwySemanticMutationWork.Uncertain()
                }
                val repairedDigest = readDigest(lane.semanticDigestProvider)
                    ?: return@runMutation QwySemanticMutationWork.Uncertain()
                if (repairedDigest != registeredDigest) {
                    QwySemanticMutationWork.Uncertain(repairedDigest)
                } else {
                    QwySemanticMutationWork.Changed(Unit, repairedDigest)
                }
            }
        } finally {
            val remaining = (currentThreadAuthoritativeDepth.get() ?: 1) - 1
            if (remaining == 0) currentThreadAuthoritativeDepth.remove()
            else currentThreadAuthoritativeDepth.set(remaining)
            activeAuthoritativeBrackets.decrementAndGet()
        }

        when (result) {
            is QwySemanticMutationResult.Uncertain -> {
                if (!operationStarted && !result.remoteMutationBegan) return false
                throw ambiguous(
                    "authoritative $kind mutation became uncertain: ${result.reason}",
                )
            }
            is QwySemanticMutationResult.ProvedNoOp -> throw ambiguous(
                "authoritative $kind repair did not record the detected excursion",
            )
            is QwySemanticMutationResult.Changed -> Unit
        }
        ensureStillSelected(lane, "after external projection repair")
        if (!lane.coordinator.isReadyFor(registeredDigest) ||
            !readHealth(lane.sessionHealth, registeredDigest)
        ) {
            throw ambiguous("authoritative session lost health after $kind")
        }
        return true
    }

    /** Digest read, remote begin, local work, and finish are one process writer critical section. */
    private fun <T> executeSelectedLocked(
        lane: Lane,
        kind: String,
        operation: (Boolean) -> T,
    ): T {
        ensureStillSelected(lane, "before begin")
        if (lane.coordinator.isMutationInFlightOnCurrentThread()) {
            // ConfigPrefsSync is a central writer and can be called from an
            // already-bracketed handler/repository/settings operation. It is
            // part of that exact mutation, not a nested mutation with a new ID.
            return operation(true)
        }
        val beforeDigest = readDigest(lane.semanticDigestProvider)
            ?: throw ambiguous("canonical digest unavailable before $kind")
        if (!lane.coordinator.isReadyFor(beforeDigest) ||
            !readHealth(lane.sessionHealth, beforeDigest)
        ) {
            throw ambiguous("authoritative session is not healthy before $kind")
        }

        val mutationId = try {
            lane.mutationIdFactory(kind)
        } catch (_: Exception) {
            throw ambiguous("mutation id allocation failed before $kind")
        }
        if (mutationId.isBlank()) {
            throw ambiguous("mutation id allocation returned blank before $kind")
        }

        activeAuthoritativeBrackets.incrementAndGet()
        currentThreadAuthoritativeDepth.set(
            (currentThreadAuthoritativeDepth.get() ?: 0) + 1,
        )
        val result = try {
            lane.coordinator.runMutation(mutationId, beforeDigest) {
                val value = operation(true)
                if (installedLane !== lane) {
                    return@runMutation QwySemanticMutationWork.Uncertain()
                }
                val afterDigest = readDigest(lane.semanticDigestProvider)
                    ?: return@runMutation QwySemanticMutationWork.Uncertain()
                if (afterDigest == beforeDigest) {
                    QwySemanticMutationWork.ProvedNoOp(value, afterDigest)
                } else {
                    QwySemanticMutationWork.Changed(value, afterDigest)
                }
            }
        } finally {
            val remaining = (currentThreadAuthoritativeDepth.get() ?: 1) - 1
            if (remaining == 0) currentThreadAuthoritativeDepth.remove()
            else currentThreadAuthoritativeDepth.set(remaining)
            activeAuthoritativeBrackets.decrementAndGet()
        }

        val value = when (result) {
            is QwySemanticMutationResult.Changed -> result.value
            is QwySemanticMutationResult.ProvedNoOp -> result.value
            is QwySemanticMutationResult.Uncertain -> throw ambiguous(
                "authoritative $kind mutation became uncertain: ${result.reason}",
            )
        }
        val afterDigest = when (result) {
            is QwySemanticMutationResult.Changed -> result.afterDigest
            is QwySemanticMutationResult.ProvedNoOp -> result.afterDigest
            is QwySemanticMutationResult.Uncertain -> error("handled above")
        }

        ensureStillSelected(lane, "after finish")
        if (!lane.coordinator.isReadyFor(afterDigest) ||
            !readHealth(lane.sessionHealth, afterDigest)
        ) {
            throw ambiguous("authoritative session lost health after $kind")
        }
        return value
    }

    private fun ensureStillSelected(lane: Lane, phase: String) {
        if (installedLane !== lane) {
            throw ambiguous("semantic writer lane changed $phase")
        }
    }

    private fun readDigest(provider: QwySemanticDigestProvider): String? = try {
        provider.current()?.takeIf(String::isNotBlank)
    } catch (_: Exception) {
        null
    }

    private fun readHealth(
        health: QwySemanticSessionHealth,
        semanticDigest: String,
    ): Boolean = try {
        health.isHealthyFor(semanticDigest)
    } catch (_: Exception) {
        false
    }

    private fun nextMutationId(kind: String): String =
        "qwy-writer-$processNonce-${mutationCounter.incrementAndGet()}-$kind"

    private fun ambiguous(message: String) = QwySemanticWriterAmbiguityException(message)
}
