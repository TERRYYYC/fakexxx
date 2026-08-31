package name.caiyao.fakegps.integration.v1

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
            synchronized(installationLock) {
                if (!closed) {
                    if (installedLane === lane) installedLane = null
                    closed = true
                }
            }
        }
    }

    private val installationLock = Any()
    private val processNonce = UUID.randomUUID().toString()
    private val mutationCounter = AtomicLong(0L)
    private val activeAuthoritativeBrackets = AtomicInteger(0)

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
    ): AutoCloseable {
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
        synchronized(installationLock) {
            check(installedLane == null) {
                "a QWY semantic writer lane is already installed"
            }
            installedLane = lane
        }
        return Installation(lane)
    }

    /** Synchronous settings-writer entry point. */
    fun <T> mutate(
        kind: String,
        operation: (authoritativeLaneSelected: Boolean) -> T,
    ): T {
        require(kind.isNotBlank()) { "semantic mutation kind is required" }
        val lane = installedLane ?: return operation(false)
        return executeSelected(lane, kind, operation)
    }

    /** Cross-thread callback suppression while an external writer owns odd state. */
    fun isAuthoritativeMutationInFlight(): Boolean =
        activeAuthoritativeBrackets.get() > 0

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
        val lane = installedLane ?: return operation(false)
        return withContext(Dispatchers.IO) {
            val inheritedElements = currentCoroutineContext()
                .minusKey(Job)
                .minusKey(ContinuationInterceptor)
            executeSelected(lane, kind) { selected ->
                // Keep transaction/thread-context elements (notably Room's)
                // while the dedicated blocking bridge owns dispatch locally.
                runBlocking(inheritedElements) { operation(selected) }
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
