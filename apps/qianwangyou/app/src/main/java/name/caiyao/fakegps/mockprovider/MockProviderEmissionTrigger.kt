package name.caiyao.fakegps.mockprovider

/**
 * #173: synchronous, in-process "deliver the published spoof payload to the
 * system mock providers NOW" capability.
 *
 * Why it exists: apply's legacy linkage (#168) republishes the payload inside
 * the owner's semantic bracket, but the FIRST delivery of the new coordinates
 * used to wait for [MockProviderService]'s 1 Hz refresh — a tick phase that
 * does not respect the bracket. The first emission then landed outside the
 * bracket as a changed covered mutation (+2) with no owner ack, and the
 * resulting unacked cursor backlog made #167's guard 4 refuse every later ack
 * on the row (mi14 attempt 69: highest 512 < baseline 522, row boundary dead
 * end). Triggering the delivery synchronously inside the republish bracket
 * makes its covered mutation deep-aggregate into the owner's own +2, which the
 * existing #166/#167 ack covers.
 *
 * Registration model: [MockProviderService] registers a delegate on create and
 * clears it on destroy. The contract side calls through this hub; with no
 * live service there is nothing to deliver (and no 1 Hz source either), so the
 * hub answers false and the caller degrades to the pre-#173 behavior honestly.
 */
fun interface MockProviderEmissionTrigger {
    /**
     * Delivers the currently published payload to the system mock providers
     * and returns when the delivery pipeline has completed (or given up).
     * True = the pipeline ran to a Running state; false = nothing was
     * delivered (no service, pipeline busy past its bound, or the refresh
     * stopped the session). Never throws.
     */
    fun deliverPublishedNow(): Boolean
}

/** The one process-wide registration point (service and contract share a process). */
object ProcessMockProviderEmission : MockProviderEmissionTrigger {

    @Volatile
    private var delegate: MockProviderEmissionTrigger? = null

    fun register(delegate: MockProviderEmissionTrigger?) {
        this.delegate = delegate
    }

    override fun deliverPublishedNow(): Boolean =
        try {
            delegate?.deliverPublishedNow() ?: false
        } catch (_: Throwable) {
            // Best-effort by contract: a failed immediate delivery degrades to
            // the next 1 Hz tick (the pre-#173 behavior), never a fake success.
            false
        }
}
