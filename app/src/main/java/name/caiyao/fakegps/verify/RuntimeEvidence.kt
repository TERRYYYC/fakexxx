package name.caiyao.fakegps.verify

/** Stable, value-free release evidence consumed by the host acceptance harness. */
object RuntimeEvidence {
    const val PROBE_TAG = "FakeGPS-Probe"
    private const val HOOK_PREFIX = "FakeGPS-Hook:"

    @JvmStatic
    fun probeRequested(request: ProbeRequest): String =
        "event=requested requestId=${request.requestId} fp=${request.fingerprint}"

    @JvmStatic
    fun probeStarted(request: ProbeRequest): String =
        "event=started requestId=${request.requestId} fp=${request.fingerprint}"

    @JvmStatic
    fun probeDelivered(request: ProbeRequest, fields: Int): String =
        "event=delivered requestId=${request.requestId} fp=${request.fingerprint} fields=$fields"

    @JvmStatic
    fun probeFailed(request: ProbeRequest, failure: ProbeFailure): String =
        "event=failed requestId=${request.requestId} fp=${request.fingerprint} reason=${failure.name}"

    @JvmStatic
    fun probeIgnored(observed: ProbeRequest): String =
        "event=ignored requestId=${observed.requestId} fp=${observed.fingerprint} reason=STALE_RESULT"

    @JvmStatic
    fun schedulerOwned(process: String, intervalMs: Long): String =
        "$HOOK_PREFIX event=scheduler_owned process=$process intervalMs=$intervalMs"

    @JvmStatic
    fun intervalChanged(process: String, fromMs: Long, toMs: Long): String =
        "$HOOK_PREFIX event=interval_changed process=$process fromMs=$fromMs toMs=$toMs"

    // ---- Phase B: event-driven refresh evidence ----

    @JvmStatic
    fun observerArmed(process: String, dirPath: String): String =
        "$HOOK_PREFIX event=observer_armed process=$process dir=$dirPath"

    @JvmStatic
    fun observerArmFailed(process: String, error: String): String =
        "$HOOK_PREFIX event=observer_arm_failed process=$process error=$error"

    @JvmStatic
    fun timerFallback(process: String, intervalMs: Long): String =
        "$HOOK_PREFIX event=timer_fallback process=$process intervalMs=$intervalMs"

    // ---- Fused client runtime discovery evidence (2026-08-04 design) ----

    @JvmStatic
    fun fusedFactoryArmed(overloads: Int): String =
        "$HOOK_PREFIX event=fused_factory_armed overloads=$overloads"

    @JvmStatic
    fun fusedClientDiscovered(runtimeClass: String, loaderId: Int): String =
        "$HOOK_PREFIX event=fused_client_discovered class=$runtimeClass loader=$loaderId"

    @JvmStatic
    fun fusedSurfaceHooked(surface: String, owner: String): String =
        "$HOOK_PREFIX event=fused_surface_hooked surface=$surface owner=$owner"

    @JvmStatic
    fun fusedClientRejected(reason: String): String =
        "$HOOK_PREFIX event=fused_client_rejected reason=$reason"

    @JvmStatic
    fun fusedSurfaceMissing(method: String): String =
        "$HOOK_PREFIX event=fused_surface_missing method=$method"

    /**
     * Task delivery install outcome for one runtime task class — planned vs actually
     * installed vs already-present vs failed, with a bounded failure reason when any
     * install failed (Sol R8 #2: "planner found N" must never masquerade as "N installed").
     */
    @JvmStatic
    fun fusedDeliverySummary(
        taskClass: String,
        planned: Int,
        installed: Int,
        already: Int,
        failed: Int,
        reason: String?,
    ): String =
        "$HOOK_PREFIX event=fused_delivery_summary task=$taskClass planned=$planned" +
            " installed=$installed already=$already failed=$failed" +
            (if (reason != null) " reason=$reason" else "")

    /** First time a fused-returned Task's success listener is actually wrapped. */
    @JvmStatic
    fun fusedListenerWrapped(taskClass: String): String =
        "$HOOK_PREFIX event=fused_listener_wrapped task=$taskClass"

    /**
     * Per-delivery evidence: proves a hooked surface is still delivering, and whether the
     * value it was about to carry was still the active profile.
     *
     * Every other fused event above is install-time, so their absence cannot distinguish
     * "delivering quietly" from "stopped delivering". This one is emitted edge-triggered
     * plus on a heartbeat (see DeliveryEvidencePolicy), and [deliveries] reports how many
     * deliveries the line covers.
     *
     * Two disjoint axes, because they answer different questions and one vocabulary serving
     * both inverted the verdict once already:
     *  - [classification] describes what the target app RECEIVED (EQUALS_PROFILE /
     *    NOT_EQUAL / NO_OBSERVED). This is what "delivered" means; a snap-back shows here.
     *  - [input] describes what it would have received WITHOUT the hook
     *    (INPUT_EQUALS_PROFILE / INPUT_DIFFERS_PROFILE / INPUT_ABSENT). INPUT_DIFFERS is a
     *    positive fact — proof a real update was displaced — never a failure.
     *
     * Stays value-free by construction: both axes are fixed tokens, never a coordinate.
     */
    @JvmStatic
    fun fusedDelivered(
        surface: String,
        classification: String,
        input: String,
        deliveries: Int,
    ): String =
        "$HOOK_PREFIX event=fused_delivered surface=$surface" +
            " class=$classification input=$input deliveries=$deliveries"
}
