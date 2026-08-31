package name.caiyao.fakegps.integration.v1

import android.app.AppOpsManager

/** Process-lifetime source for mock-location AppOp owner changes. */
internal fun interface MockLocationOwnerChangeSource {
    /** Returns false when the watcher could not be installed. */
    fun start(onChanged: () -> Unit): Boolean

    /** Current AppOps truth; registration alone does not prove ownership. */
    fun isCurrentOwner(): Boolean = false

    /**
     * History/fence strength, not current-state accuracy. Public Android
     * callbacks are asynchronous and therefore at most INCOMPLETE.
     */
    fun continuityEvidenceCapability(): ContinuityEvidenceCapability =
        ContinuityEvidenceCapability.INCOMPLETE

    /** Unregisters any process-lifetime watcher installed by [start]. */
    fun stop() {}
}

/**
 * Bridges the Android AppOps callback into the revision owner's typed reason.
 *
 * The provider runtime is a process singleton. Rebinding replaces only the
 * revision callback; it never installs a second OS watcher. Android drops the
 * watcher when the process dies, and the next process builds a fresh source.
 */
internal class QwyRelevantChangeMonitor(
    private val source: MockLocationOwnerChangeSource,
) {
    private var listener: ((RevisionBumpReason) -> Unit)? = null
    private var startAttempted = false
    private var active = false
    private var closed = false

    @Synchronized
    fun bind(listener: (RevisionBumpReason) -> Unit): Boolean {
        check(!closed) { "relevant-change monitor is closed" }
        this.listener = listener
        if (!startAttempted) {
            startAttempted = true
            active = source.start {
                synchronized(this) {
                    this.listener
                }?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
            }
        }
        return active
    }

    fun canVerifyCurrentOwner(): Boolean {
        val monitoringActive = synchronized(this) { active }
        return monitoringActive && runCatching(source::isCurrentOwner).getOrDefault(false)
    }

    fun continuityEvidenceCapability(): ContinuityEvidenceCapability {
        val monitoringActive = synchronized(this) { active }
        if (!monitoringActive) return ContinuityEvidenceCapability.UNAVAILABLE
        return runCatching(source::continuityEvidenceCapability)
            .getOrDefault(ContinuityEvidenceCapability.UNAVAILABLE)
    }

    /** Process-local publisher/service events use the same revision route. */
    fun reportRelevantChange(reason: RevisionBumpReason) {
        synchronized(this) { listener }?.invoke(reason)
    }

    /** Idempotently detaches the callback and unregisters the OS watcher. */
    fun shutdown() {
        val shouldStop = synchronized(this) {
            if (closed) return
            closed = true
            listener = null
            active = false
            startAttempted
        }
        if (shouldStop) source.stop()
    }
}

/** Public-API AppOps watcher scoped to this application's own UID/package. */
internal class AndroidMockLocationOwnerChangeSource(
    private val appOpsManager: AppOpsManager,
    private val packageName: String,
    private val uid: Int,
) : MockLocationOwnerChangeSource {
    @Volatile
    private var registeredListener: AppOpsManager.OnOpChangedListener? = null

    override fun continuityEvidenceCapability(): ContinuityEvidenceCapability =
        ContinuityEvidenceCapability.INCOMPLETE

    override fun start(onChanged: () -> Unit): Boolean = runCatching {
        val listener = AppOpsManager.OnOpChangedListener { op, changedPackage ->
            if (op == AppOpsManager.OPSTR_MOCK_LOCATION &&
                (changedPackage == null || changedPackage == packageName)
            ) {
                // Conservatively bump on every matching callback. A false
                // positive only loses continuity; suppressing a real owner
                // change could manufacture it.
                onChanged()
            }
        }
        appOpsManager.startWatchingMode(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            packageName,
            listener,
        )
        registeredListener = listener
    }.isSuccess

    override fun stop() {
        val listener = registeredListener ?: return
        registeredListener = null
        appOpsManager.stopWatchingMode(listener)
    }

    @Suppress("DEPRECATION")
    override fun isCurrentOwner(): Boolean = runCatching {
        // checkOpNoThrow(String, uid, package) exists from API 19. Its
        // pre-Baklava contract is an early AppOps mode check rather than a
        // security authorization, which is exactly the fail-closed signal this
        // read side needs; the actual provider mutation remains the authority.
        appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_MOCK_LOCATION,
            uid,
            packageName,
        ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
}
