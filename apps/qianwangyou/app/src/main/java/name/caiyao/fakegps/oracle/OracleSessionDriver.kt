package name.caiyao.fakegps.oracle

import android.os.Binder
import android.os.IBinder
import name.caiyao.fakegps.integration.v1.QwySemanticMutation
import name.caiyao.fakegps.integration.v1.QwySemanticMutationSource

/**
 * App-side QWY oracle session driver (#155, parent #66) — the producer half
 * that never existed: without it, `registerQwySession` had no app caller and
 * COVERAGE_QWY_SERVICE_GENERATION (bit 5) was structurally unreachable, so
 * every observation classified INVALID and coverage stayed NONE.
 *
 * Lifecycle: [attach] subscribes to the client registry. When the system-server
 * oracle arrives (or is replayed to a late-attaching driver), ONE session is
 * registered with the digest the observer would compute — [semanticDigest] must
 * be wired to the same tracker/environment collaborators the observer reads, or
 * the registered baseline can never match an observation's recomputation. The
 * driver holds the local death-token strong reference; registry clear/death
 * drops it, and system_server clears bits 5/6 through its own death path.
 *
 * Bracketing ([QwySemanticMutationSource]): begin/finish/registration serialize
 * on one lock. A registration landing while a bracket is open WAITS instead of
 * proceeding — the state machine would retire the open token as generation
 * loss, turning the later finish into an unknown-token invariant poison. The
 * registrar's Binder thread therefore blocks for at most one in-flight semantic
 * mutation, which is bounded by the mutation itself.
 *
 * Failure semantics (fail-closed): a begin the oracle rejects (session absent,
 * baseline drift) drops the token and returns null — the state machine has
 * already cleared the session with a +2 discontinuity, so the next observation
 * is NONE no matter what the caller does. The next mutation attempt re-registers
 * with a fresh digest (self-heal); a registration that fails (e.g. a covered
 * platform mutation in flight) stays unregistered and fail-closed until a later
 * arrival.
 */
class OracleSessionDriver(
    private val registry: OracleClientRegistry<IAuthoritativeContinuityOracle>,
    private val semanticDigest: () -> String,
    private val deathTokenFactory: () -> IBinder = ::Binder,
) : QwySemanticMutationSource {

    // java.lang.Object, not kotlin.Any: wait/notify are only resolvable on the
    // Java type, and the registration-vs-bracket handshake needs them.
    private val lock = Object()

    /** Strong reference keeping the registered death token from being collected. */
    private var deathToken: IBinder? = null
    private var registeredOracleBinder: IBinder? = null
    private var openBrackets = 0

    private val listener = OracleRegistryListener<IAuthoritativeContinuityOracle> { oracle ->
        try {
            when (oracle) {
                null -> if (registry.current() == null) {
                    synchronized(lock) { dropTokenLocked() }
                }

                else -> if (registry.current() === oracle) {
                    synchronized(lock) {
                        // Never publish a session boundary across an open mutation.
                        while (openBrackets > 0) lock.wait()
                        // Superseded (or cleared) while waiting — the newer
                        // notification owns the outcome.
                        if (registry.current() !== oracle) return@synchronized
                        registerSessionLocked(oracle)
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: RuntimeException) {
            // Arrival bookkeeping must never break the registrar's Binder call;
            // a failed registration simply stays fail-closed (no bits, no FULL).
        }
    }

    /** Subscribes to oracle arrivals; an oracle already present is replayed. */
    fun attach() {
        registry.addListener(listener)
    }

    /** Unsubscribes and drops the local session evidence. */
    fun detach() {
        registry.removeListener(listener)
        synchronized(lock) { dropTokenLocked() }
    }

    override fun begin(mutationId: String): QwySemanticMutation? {
        val oracle = registry.current() ?: return null
        synchronized(lock) {
            if (deathToken == null) {
                // Self-heal a session the oracle side already invalidated: the
                // re-registration records its own +2 discontinuity, so this is
                // an honest boundary, never a rewrite of history.
                if (registry.current() !== oracle) return null
                registerSessionLocked(oracle)
            }
            val token = deathToken ?: return null
            val before = semanticDigest()
            // Count the bracket OPEN before the Binder call: a concurrent
            // arrival must wait for it, not retire it mid-begin.
            openBrackets += 1
            val raw = try {
                oracle.beginQwySemanticMutation(mutationId, before, token)
            } catch (_: Exception) {
                openBrackets -= 1
                dropTokenLocked()
                return null
            }
            return DriverMutation(oracle, raw, before, token)
        }
    }

    private fun registerSessionLocked(oracle: IAuthoritativeContinuityOracle) {
        val oracleBinder = try {
            oracle.asBinder()
        } catch (_: RuntimeException) {
            null
        }
        if (deathToken != null && oracleBinder != null && oracleBinder == registeredOracleBinder) {
            return // already registered against this oracle with a live token
        }
        dropTokenLocked()
        val token = deathTokenFactory()
        deathToken = token
        try {
            oracle.registerQwySession(semanticDigest(), token)
        } catch (_: Exception) {
            dropTokenLocked()
            return
        }
        registeredOracleBinder = oracleBinder
    }

    private fun dropTokenLocked() {
        deathToken = null
        registeredOracleBinder = null
    }

    private inner class DriverMutation(
        private val oracle: IAuthoritativeContinuityOracle,
        private val raw: Long,
        override val beforeDigest: String,
        private val token: IBinder,
    ) : QwySemanticMutation {

        private var completed = false

        override fun finish(changed: Boolean, uncertain: Boolean, afterDigest: String) {
            synchronized(lock) {
                if (completed) return
                completed = true
                try {
                    // Under the driver lock on purpose: a concurrent arrival
                    // waits for the finish instead of retiring this token.
                    oracle.finishQwySemanticMutation(raw, changed, uncertain, afterDigest)
                } catch (e: Exception) {
                    // The call did not land (dead binder / poisoned producer).
                    // Drop so the next attempt re-registers; if the failure left
                    // the token active in system_server, its own session-death
                    // path retires it.
                    dropTokenLocked()
                    throw e
                } finally {
                    openBrackets -= 1
                    if (openBrackets == 0) lock.notifyAll()
                }
            }
        }
    }
}
