package name.caiyao.fakegps.integration.v1

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
    }

    /** Generation persisted for THIS owner instantiation (§6.6 L6). */
    val generation: Long
        get() = TODO("Task 3 GREEN: allocate+persist per owner start")

    /** Serialized durable bump; returns the post-commit revision (ACK after commit, L3/L4). */
    fun bump(reason: RevisionBumpReason): Long = TODO("Task 3 GREEN")

    /** Reflects every ACKed bump (L5); coverage honest per §6.4 rules. */
    fun snapshot(): RevisionSnapshot = TODO("Task 3 GREEN")

    /** Lossy observer self-report (§6.6): bump + degrade, never silent. */
    fun reportObserverGap(): Unit = TODO("Task 3 GREEN")

    /** Mark that full-coverage continuity is established from now (window start). */
    fun markContinuityEstablished(): Unit = TODO("Task 3 GREEN")
}
