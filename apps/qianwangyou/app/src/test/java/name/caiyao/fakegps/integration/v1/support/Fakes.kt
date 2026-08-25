package name.caiyao.fakegps.integration.v1.support

import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.AdvancePointerOutcome
import name.caiyao.fakegps.integration.v1.ApplyOutcome
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.DurableKv
import name.caiyao.fakegps.integration.v1.EffectiveEnvironment
import name.caiyao.fakegps.integration.v1.MonotonicClock
import name.caiyao.fakegps.integration.v1.PackageIdentityResolver
import name.caiyao.fakegps.integration.v1.QwyEnvironment
import name.caiyao.fakegps.integration.v1.RevisionBumpReason
import name.caiyao.fakegps.integration.v1.ScheduleSnapshot
import name.caiyao.fakegps.integration.v1.SignerLookup

/**
 * In-process fakes for the qwy owner-red lane (§10.1: in-process fakes plus
 * fault injection between durable writes and external calls).
 *
 * Restart convention: a component rebuilt over the SAME [InMemoryDurableKv] is
 * "the owner process after a restart" — only durable state survives.
 *
 * F-2 fidelity rules (deepseek-flash advisory on af06993):
 *  - the fake's SCHEDULE state (version / currentItemId / exhausted
 *    discriminator) is kv-backed, never memory-only — a pointer that only
 *    lives in fake memory would let a non-persisting implementation pass the
 *    restart tests (the exact fake-green dsf caught);
 *  - transactions are REAL: writes inside [DurableKv.transaction] buffer and
 *    commit atomically, and an exception discards the buffer (rollback);
 *    without rollback, "crash between two writes" cannot be expressed;
 *  - [failOnWrite] injects a write fault to simulate a crash mid-operation.
 * Instrumentation counters (applyCount etc.) are call-counters and stay in
 * memory deliberately — they count invocations across a whole scenario,
 * including across restarts, and are not durability claims.
 */
class SimulatedWriteCrash(namespace: String, key: String) :
    RuntimeException("simulated write crash at $namespace/$key")

class InMemoryDurableKv : DurableKv {
    private val data = HashMap<String, HashMap<String, String>>()
    private val lock = Any()
    private var txBuffer: HashMap<Pair<String, String>, String>? = null

    /** Write-fault injection: return true to crash this write (F-2 item 2). */
    var failOnWrite: ((namespace: String, key: String) -> Boolean)? = null

    override fun read(namespace: String, key: String): String? = synchronized(lock) {
        txBuffer?.get(namespace to key) ?: data[namespace]?.get(key)
    }

    override fun write(namespace: String, key: String, value: String) {
        synchronized(lock) {
            if (failOnWrite?.invoke(namespace, key) == true) {
                throw SimulatedWriteCrash(namespace, key)
            }
            val buffer = txBuffer
            if (buffer != null) {
                buffer[namespace to key] = value
            } else {
                data.getOrPut(namespace) { HashMap() }[key] = value
            }
        }
    }

    override fun keys(namespace: String): Set<String> = synchronized(lock) {
        val committed = data[namespace]?.keys?.toSet() ?: emptySet()
        val buffered = txBuffer?.keys?.filter { it.first == namespace }?.map { it.second } ?: emptyList()
        committed + buffered
    }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) {
        if (txBuffer != null) return@synchronized block() // nested tx joins the outer one
        txBuffer = HashMap()
        try {
            val result = block()
            // Commit: flush the buffer only on success.
            txBuffer!!.forEach { (nsKey, value) ->
                data.getOrPut(nsKey.first) { HashMap() }[nsKey.second] = value
            }
            result
        } finally {
            // On exception the buffer is discarded — rollback.
            txBuffer = null
        }
    }
}

/**
 * Wall clock and monotonic clock are independently steerable — the whole point
 * of §6.4.2 is that they disagree under NTP/user changes and only elapsed
 * counts. [simulateReboot] resets the elapsed epoch like a device boot does.
 */
class FakeMonotonicClock(
    var elapsed: Long = 1_000_000L,
    var epoch: Long = 1_700_000_000_000L,
) : MonotonicClock {
    override fun elapsedRealtimeMs(): Long = elapsed
    override fun epochMs(): Long = epoch

    /** Normal passage of time: both clocks move. */
    fun advance(ms: Long) {
        elapsed += ms
        epoch += ms
    }

    /** NTP / user wall-clock jump: epoch moves, elapsed must not care (M-LS-10). */
    fun jumpWallClock(deltaMs: Long) {
        epoch += deltaMs
    }

    /** Device reboot: elapsedRealtime restarts near zero (M-LS-13). */
    fun simulateReboot() {
        elapsed = 5_000L
    }
}

class FakeIdentityResolver : PackageIdentityResolver {
    val packagesByUid = HashMap<Int, List<String>>()
    val signersByPackage = HashMap<String, SignerLookup?>()

    override fun packagesForUid(uid: Int): List<String> = packagesByUid[uid] ?: emptyList()

    override fun signerLookup(applicationId: String): SignerLookup? = signersByPackage[applicationId]

    fun register(uid: Int, applicationId: String, signerDigest: String, versionCode: Long = 1L) {
        packagesByUid[uid] = listOf(applicationId)
        signersByPackage[applicationId] = SignerLookup(
            currentSignerDigests = listOf(signerDigest),
            hasMultipleSigners = false,
            legacyApi = false,
            versionCode = versionCode,
        )
    }
}

/**
 * Fake of qianwangyou's existing capabilities. Deterministic and steerable.
 *
 * Schedule state is DURABLE (kv-backed) because the real qwy schedule store is
 * durable — see the F-2 fidelity rules above. Config knobs (cleanupOutcome,
 * itemIds) and call-counters are memory state by design.
 */
class FakeQwyEnvironment(private val kv: DurableKv) : QwyEnvironment {

    companion object {
        const val SCHEDULE_NAMESPACE = "fakeqwy.schedule"
    }

    // --- config (memory by design) ---
    var scheduleId: String = "sched-1"
    var itemIds: MutableList<String> = mutableListOf("item-1", "item-2", "item-3")
    var cleanupOutcome: CleanupOutcome = CleanupOutcome.Complete
    var isMock: Boolean? = true
    var fingerprint: String = "fp-1"
    var evidenceRefs: List<String> = listOf("qwy:audit:1")

    // --- call-count instrumentation (memory by design; counts across restarts) ---
    var applyCount: Int = 0
    var cleanupCount: Int = 0
    var advanceCount: Int = 0

    /**
     * Crash injection at the qwy-side pointer mutation (Terra PR#22 round-2:
     * the §6.7.5 window between the provider's receipt commit and the external
     * pointer apply). One-shot: throws BEFORE any schedule state is touched,
     * then re-arms to false so the recovery/replay path runs normally.
     */
    var failNextAdvancePointer: Boolean = false

    /**
     * Test seam for the §6.7.5 window Terra's interleaving lives in: invoked at
     * the external pointer-apply point, AFTER the failNext check but BEFORE the
     * pointer actually moves. Lets a test act while an advance is committed but
     * its external mutation is still pending.
     */
    var beforeAdvancePointer: (() -> Unit)? = null
    var effectiveLatitude: Double? = null
    var effectiveLongitude: Double? = null

    private var relevantChangeListener: ((RevisionBumpReason) -> Unit)? = null

    // --- durable schedule state (kv-backed; survives restart ONLY via kv) ---
    var scheduleVersion: Long
        get() = kv.read(SCHEDULE_NAMESPACE, "version")?.toLong() ?: 7L
        set(value) = kv.write(SCHEDULE_NAMESPACE, "version", value.toString())

    var currentItemId: String?
        get() = when (kv.read(SCHEDULE_NAMESPACE, "currentPresent")) {
            "0" -> null
            else -> kv.read(SCHEDULE_NAMESPACE, "current") ?: "item-1"
        }
        set(value) {
            if (value == null) {
                kv.write(SCHEDULE_NAMESPACE, "currentPresent", "0")
            } else {
                kv.write(SCHEDULE_NAMESPACE, "currentPresent", "1")
                kv.write(SCHEDULE_NAMESPACE, "current", value)
            }
        }

    /** Durable exhausted discriminator (M-AD-10/11: current item is retained, so exhaustion needs its own durable bit). */
    var exhausted: Boolean
        get() = kv.read(SCHEDULE_NAMESPACE, "exhausted") == "1"
        set(value) = kv.write(SCHEDULE_NAMESPACE, "exhausted", if (value) "1" else "0")

    /**
     * Durable schedule-PRESENCE discriminator (KB-6 row 17, first leg): the qwy
     * DB can genuinely hold NO schedule (§6.7.4 three-state model, state 1),
     * and that state is durable too — a memory-only "no schedule" flag would
     * be the same fake-green shape F-2 killed for the pointer. Default (key
     * absent) = a schedule exists, so no existing scenario changes behaviour.
     */
    var hasSchedule: Boolean
        get() = kv.read(SCHEDULE_NAMESPACE, "present") != "0"
        set(value) = kv.write(SCHEDULE_NAMESPACE, "present", if (value) "1" else "0")

    override fun scheduleSnapshot(): ScheduleSnapshot? =
        if (!hasSchedule) {
            null
        } else {
            ScheduleSnapshot(scheduleId, scheduleVersion, currentItemId, itemIds.toList(), exhausted)
        }

    override fun advancePointer(fromItemId: String): AdvancePointerOutcome {
        if (failNextAdvancePointer) {
            failNextAdvancePointer = false
            throw SimulatedWriteCrash(SCHEDULE_NAMESPACE, "advancePointer")
        }
        beforeAdvancePointer?.invoke()
        advanceCount += 1
        val idx = itemIds.indexOf(fromItemId)
        check(idx >= 0) { "advancePointer from unknown item $fromItemId" }
        // Spec v1.56: every advance (terminal and non-terminal) bumps the
        // schedule version by exactly 1. Production QwyScheduleStore does this;
        // the fake must model it or the fidelity gap hides V+1 evidence.
        scheduleVersion += 1
        return if (idx == itemIds.lastIndex) {
            exhausted = true
            AdvancePointerOutcome.Exhausted(scheduleVersion)
        } else {
            currentItemId = itemIds[idx + 1]
            AdvancePointerOutcome.Advanced(itemIds[idx + 1], scheduleVersion)
        }
    }

    /**
     * F14 (C5): the REAL controller computes this from the actual publish
     * outcome (ConfigPrefsSync success → VERIFIED, failure → NONE; P1-2 fix).
     * The fake exposes it as a knob so tests can model a failed publish and
     * pin that the handler's receipt reports the COMPUTED level — not a
     * constant.
     *
     * R4 P3: the SAME publish outcome must drive the observe surface too —
     * production records it via recordLastApplied(verified) at apply time and
     * observeEffective() reads lastApplied.verified back, so a failed publish
     * shows NONE on BOTH the receipt AND the observation. A fake that lets the
     * knob drive only ApplyOutcome cannot reproduce the C5 cross-surface
     * contradiction (receipt NONE while observe VERIFIED); see
     * observeEffective().
     */
    var applyVerificationLevelWire: Int = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire

    /**
     * Mirror of production's recordLastApplied(verified) → lastApplied.verified:
     * the verification level of the most recent apply, consumed by
     * observeEffective() so both surfaces report the same publish outcome.
     * null = no apply yet (cold start) → observe reports VERIFIED (the fake's
     * pre-apply default, matching the production cold-start passthrough that
     * reports what the hook transport actually holds).
     */
    private var lastAppliedVerificationLevelWire: Int? = null

    override fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome {
        applyCount += 1
        // KB-8 (v1.62): the intent no longer carries coordinates — the
        // provider resolves them from the current schedule item. The fake
        // models the same KB-8 world: fixed qwy-owned coordinates per item.
        val resolved = coordinateForItem(currentItemId)
        effectiveLatitude = resolved?.first
        effectiveLongitude = resolved?.second
        lastAppliedVerificationLevelWire = applyVerificationLevelWire
        return ApplyOutcome(
            effectiveLatitude = resolved?.first,
            effectiveLongitude = resolved?.second,
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = applyVerificationLevelWire,
        )
    }

    /** KB-8 fake: qwy-owned coordinate table keyed by schedule item id. */
    var itemCoordinates: MutableMap<String, Pair<Double, Double>> = mutableMapOf(
        "item-1" to (31.2304000 to 121.4737000),
        "item-2" to (31.2314000 to 121.4747000),
        "item-3" to (31.2324000 to 121.4757000),
    )

    private fun coordinateForItem(itemId: String?): Pair<Double, Double>? =
        itemId?.let { itemCoordinates[it] }

    override fun cleanup(leaseId: String): CleanupOutcome {
        cleanupCount += 1
        return cleanupOutcome
    }

    override fun observeEffective(): EffectiveEnvironment {
        // R4 P3 (C5 cross-surface): the fake publish outcome must drive BOTH
        // surfaces. Production: applyEnvironment records the publish result
        // (recordLastApplied(verified)); observeEffective reads it back. So a
        // failed publish (knob = NONE) makes the RECEIPT and the OBSERVATION
        // agree on NONE — the C5 contradiction (receipt verif=1 while observe
        // verified=false) is only reproducible when the fake lets the apply
        // outcome reach the observe surface. null = no apply yet (cold start)
        // → report the fake's pre-apply VERIFIED default.
        val level = lastAppliedVerificationLevelWire
            ?: VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        val verified = level == VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        return EffectiveEnvironment(
            latitude = effectiveLatitude,
            longitude = effectiveLongitude,
            isMock = if (verified) isMock else false,
            deliveryModeWire = if (verified) DeliveryModeV1.SYSTEM_MOCK.wire else null,
            verificationLevelWire = level,
            environmentFingerprint = fingerprint,
            evidenceRefs = evidenceRefs,
        )
    }

    override fun scheduleDecisionWire(scheduleRef: String): Int = 1 // ALLOWED_NOW

    /**
     * F-17 honest fake: mirrors the production precondition set — no current
     * item, or no qwy-owned coordinates for it, means apply cannot reach
     * VERIFIED, so preflight must not claim it either. (The fake models no
     * gateway, so that production leg has no fake counterpart by construction.)
     */
    override fun achievableVerificationLevelWire(): Int =
        if (coordinateForItem(currentItemId) != null) {
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire
        } else {
            VerificationLevelV1.NONE.wire
        }

    override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {
        relevantChangeListener = listener
    }

    /**
     * M-RC-03: an external app steals the mock-location owner and gives it
     * back. The post state equals the pre state — revision must change anyway.
     */
    fun hijackAndRestoreMockOwner() {
        relevantChangeListener?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
        relevantChangeListener?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
    }
}
