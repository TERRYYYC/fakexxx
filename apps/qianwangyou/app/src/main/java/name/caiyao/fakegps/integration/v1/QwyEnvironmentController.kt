package name.caiyao.fakegps.integration.v1

import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1

/**
 * Seam between the v1 provider and qianwangyou's existing capabilities
 * (profile / System Mock / Hook / schedule). Adapters CALL existing logic, they
 * never duplicate it (Task 3 GREEN rule; §5 boundary table).
 *
 * Schedule identity (§6.7.1) is owned here: scheduleId / scheduleItemId /
 * scheduleVersion / currentItemId. The current item pointer is explicit and
 * durable — row order, first-row and profile-table order are projections and
 * must never be used as order truth.
 */
interface QwyEnvironment {

    /** Current schedule identity truth (§6.7.1). Null when no active schedule. */
    fun scheduleSnapshot(): ScheduleSnapshot?

    /**
     * Atomically advance currentItemId to the next item. Called only by the
     * handler after §6.7.4 preconditions passed inside the same durable
     * transaction that persists the advance receipt (§6.7.5).
     */
    fun advancePointer(fromItemId: String): AdvancePointerOutcome

    /** Apply the intent through existing qwy capabilities (System Mock / Hook). */
    fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome

    /** Clean up environment for a lease; must report honestly (INV-21). */
    fun cleanup(leaseId: String): CleanupOutcome

    /** Effective environment as observable right now. */
    fun observeEffective(): EffectiveEnvironment

    /** ScheduleDecisionV1 wire for the referenced schedule at this moment. */
    fun scheduleDecisionWire(scheduleRef: String): Int

    /**
     * Relevant-change event port (§6.4 list): provider wiring subscribes the
     * revision owner here so profile/mode/owner/permission changes bump the
     * revision even when the post state equals the pre state (M-RC-03). An
     * unwired listener is exactly the false-trust INV-08 red case.
     */
    fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit)
}

data class ScheduleSnapshot(
    val scheduleId: String,
    val scheduleVersion: Long,
    val currentItemId: String?,
    /** Stable item ids in authoritative order; ids never change on reorder (§6.7.1). */
    val itemIds: List<String>,
    /**
     * Durable exhausted discriminator: M-AD-10 retains the current item after
     * the last completion, so "already exhausted" (→ wire 16 on a further
     * advance, M-AD-11) is NOT derivable from the pointer alone and must be a
     * durable bit that survives owner restarts.
     */
    val exhausted: Boolean,
)

sealed class AdvancePointerOutcome {
    data class Advanced(val toItemId: String, val versionAfter: Long) : AdvancePointerOutcome()

    /** Last item completed: terminal, not a failure (§6.7.4). Pointer retained. */
    data class Exhausted(val versionAfter: Long) : AdvancePointerOutcome()
}

data class ApplyOutcome(
    val effectiveLatitude: Double?,
    val effectiveLongitude: Double?,
    val deliveryModeWire: Int?,
    val verificationLevelWire: Int,
)

sealed class CleanupOutcome {
    object Complete : CleanupOutcome()
    data class Incomplete(val residualReasonWires: List<Int>) : CleanupOutcome()
}

data class EffectiveEnvironment(
    val latitude: Double?,
    val longitude: Double?,
    val isMock: Boolean?,
    val deliveryModeWire: Int?,
    val verificationLevelWire: Int,
    val environmentFingerprint: String,
    val evidenceRefs: List<String>,
)

/**
 * Production adapter over qianwangyou's mockprovider / hook / config / schedule
 * capabilities.
 *
 * BLOCKED — NOT AN OVERSIGHT. The schedule half of this seam has no existing
 * logic to call, and this interface's own rule is that adapters CALL existing
 * logic rather than duplicate it.
 *
 * Spec §1036/§1040 place schedule ownership in qianwangyou as an operator-facing
 * feature: "operator 在千网游中维护地址计划（顺序/优先级归千网游）" and "地址、
 * 经纬度与优先级不再由 Auto 导入 ... 它们属于千网游的 schedule item". qwy today
 * has profiles (SpoofConfig) and HookRefreshScheduler (a hook-refresh timer, not
 * a schedule); there is no scheduleId, no ordered stable itemIds, no durable
 * currentItemId pointer and no operator UI for any of it.
 *
 * Inventing that state inside the provider would create a second source of
 * order truth — the exact thing §5 and §6.7.1 forbid — so it is escalated as a
 * scope question rather than absorbed here as an implementation detail.
 *
 * Until that is settled these members stay TODO() and
 * ProviderReachabilityGuardTest stays red on purpose: a red guard naming a real
 * gap is worth more than a green one standing on invented truth.
 */
class QwyEnvironmentController(
    @Suppress("UNUSED_PARAMETER") context: android.content.Context,
) : QwyEnvironment {
    override fun scheduleSnapshot(): ScheduleSnapshot? =
        TODO("Task 3 GREEN: adapt qwy schedule identity (§6.7.1)")

    override fun advancePointer(fromItemId: String): AdvancePointerOutcome =
        TODO("Task 3 GREEN: explicit durable currentItemId pointer (§6.7.1/§6.7.5)")

    override fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome =
        TODO("Task 3 GREEN: call existing System Mock/Hook capabilities")

    override fun cleanup(leaseId: String): CleanupOutcome =
        TODO("Task 3 GREEN: honest cleanup reporting (INV-21)")

    override fun observeEffective(): EffectiveEnvironment =
        TODO("Task 3 GREEN: effective environment observation")

    override fun scheduleDecisionWire(scheduleRef: String): Int =
        TODO("Task 3 GREEN: schedule decision from qwy schedule owner")

    override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit): Unit =
        TODO("Task 3 GREEN: subscribe revision owner to qwy relevant-change sources")
}
