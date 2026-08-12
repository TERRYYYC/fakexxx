package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1

/**
 * Single-writer revision owner (§6.6 L1–L6, INV-25).
 *
 * environmentRevision and continuityCoverage are shared mutable state across the
 * main process, :hook_verify and hooked processes. This class is the ONE owner:
 *  - L1 sole owner of the underlying storage namespace [REVISION_NAMESPACE]
 *  - L2 all bumps/observes arrive via synchronous IPC to the owner
 *  - L3 serialized durable read-modify-write; monotonicity survives restart
 *  - L4 a bump ACK happens only AFTER the durable commit
 *  - L5 every observe reflects all previously ACKed bumps
 *  - L6 each owner start allocates+persists a NEW generation; when continuity
 *    with the previous generation's observation window cannot be proven, bump
 *    revision and degrade coverage to PARTIAL/NONE
 *
 * A lost or late bump looks exactly like "coverage FULL and revision unchanged"
 * — the false-trust INV-08/09 exists to prevent. "Probably won't drop" is not
 * accepted; concurrency and crash-injection tests are (M-MP-01..03, M-CR-09).
 *
 * Lossy event sources (FileObserver-class) must self-report: a resubscribe or
 * any unprovable gap is an OBSERVER_GAP bump + degrade — "no events received"
 * is never "nothing changed" (M-MP-03).
 */
class ContinuityTracker(
    private val storage: DurableKv,
    private val clock: MonotonicClock,
) {
    companion object {
        /** The only namespace this owner writes; static guard M-BP-08 scans for foreign writers. */
        const val REVISION_NAMESPACE: String = "integration.v1.revision"
        private const val KEY_GENERATION = "generation"
        private const val KEY_REVISION = "revision"
        private const val KEY_COVERAGE = "coverage"
        private const val KEY_CONTINUITY_SINCE = "continuity_since"
    }

    private var _generation: Long

    init {
        // §6.6 L6: each owner start allocates+persists a NEW generation.
        // When generation > 1, continuity with the previous generation's
        // observation window cannot be proven → bump revision + degrade
        // coverage to NONE (M-MP-02).
        _generation = storage.transaction {
            val prev = storage.read(REVISION_NAMESPACE, KEY_GENERATION)?.toLong() ?: 0L
            val next = prev + 1L
            storage.write(REVISION_NAMESPACE, KEY_GENERATION, next.toString())
            // First generation starts with revision 1; subsequent ones inherit the
            // persisted revision and keep counting monotonically.
            val currentRevision = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong()
            if (currentRevision == null) {
                storage.write(REVISION_NAMESPACE, KEY_REVISION, "1")
            } else if (prev > 0L) {
                // Not the first generation → unprovable continuity → bump revision
                storage.write(REVISION_NAMESPACE, KEY_REVISION, (currentRevision + 1L).toString())
            }
            // New generation starts with NONE coverage (unproven continuity)
            storage.write(REVISION_NAMESPACE, KEY_COVERAGE,
                ContinuityCoverageV1.NONE.wire.toString())
            storage.write(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE, "")
            next
        }
    }

    /** Generation persisted for THIS owner instantiation (§6.6 L6). */
    val generation: Long
        get() = _generation

    /** Serialized durable bump; returns the post-commit revision (ACK after commit, L3/L4). */
    fun bump(reason: RevisionBumpReason): Long = storage.transaction {
        val current = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong() ?: 0L
        val next = current + 1L
        storage.write(REVISION_NAMESPACE, KEY_REVISION, next.toString())
        next
    }

    /** Reflects every ACKed bump (L5); coverage honest per §6.4 rules. */
    fun snapshot(): RevisionSnapshot {
        val revision = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong() ?: 1L
        val coverageWire = storage.read(REVISION_NAMESPACE, KEY_COVERAGE)?.toInt()
            ?: ContinuityCoverageV1.NONE.wire
        val continuitySince = storage.read(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE)
            ?.takeIf { it.isNotEmpty() }?.toLong()
        return RevisionSnapshot(
            revision = revision,
            coverageWire = coverageWire,
            generation = _generation,
            continuitySinceElapsedRealtimeMs = continuitySince,
        )
    }

    /** Lossy observer self-report (§6.6): bump + degrade, never silent. */
    fun reportObserverGap() {
        storage.transaction {
            val current = storage.read(REVISION_NAMESPACE, KEY_REVISION)?.toLong() ?: 0L
            storage.write(REVISION_NAMESPACE, KEY_REVISION, (current + 1L).toString())
            storage.write(REVISION_NAMESPACE, KEY_COVERAGE,
                ContinuityCoverageV1.PARTIAL.wire.toString())
        }
    }

    /** Mark that full-coverage continuity is established from now (window start). */
    fun markContinuityEstablished() {
        storage.transaction {
            storage.write(REVISION_NAMESPACE, KEY_COVERAGE,
                ContinuityCoverageV1.FULL.wire.toString())
            storage.write(REVISION_NAMESPACE, KEY_CONTINUITY_SINCE,
                clock.elapsedRealtimeMs().toString())
        }
    }
}
