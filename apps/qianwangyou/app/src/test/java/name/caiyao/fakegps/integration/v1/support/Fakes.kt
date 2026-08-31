package name.caiyao.fakegps.integration.v1.support

import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentIntentV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1
import name.caiyao.fakegps.integration.v1.AdvancePointerOutcome
import name.caiyao.fakegps.integration.v1.ApplyOutcome
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuityOracle
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySnapshot
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuitySource
import name.caiyao.fakegps.integration.v1.AuthoritativeContinuityState
import name.caiyao.fakegps.integration.v1.AuthoritativeCoverageMask
import name.caiyao.fakegps.integration.v1.AuthoritativeMutationOutcome
import name.caiyao.fakegps.integration.v1.AuthoritativeMutationToken
import name.caiyao.fakegps.integration.v1.AuthoritativeOracleHealth
import name.caiyao.fakegps.integration.v1.CleanupOutcome
import name.caiyao.fakegps.integration.v1.ContinuityEvidenceCapability
import name.caiyao.fakegps.integration.v1.DurableKv
import name.caiyao.fakegps.integration.v1.EffectiveEnvironment
import name.caiyao.fakegps.integration.v1.MonotonicClock
import name.caiyao.fakegps.integration.v1.PackageIdentityResolver
import name.caiyao.fakegps.integration.v1.QwyEnvironment
import name.caiyao.fakegps.integration.v1.QwySemanticDigestV1
import name.caiyao.fakegps.integration.v1.QwySemanticClientDeathToken
import name.caiyao.fakegps.integration.v1.QwySemanticMutationEndpoint
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

    /**
     * Fresh handle over committed backing only. Writes buffered by an open
     * transaction are deliberately absent, matching what a restarted process
     * or independent FileDurableKv handle can observe before commit.
     */
    fun reopenCommitted(): InMemoryDurableKv = synchronized(lock) {
        val reopened = InMemoryDurableKv()
        data.forEach { (namespace, entries) ->
            reopened.data[namespace] = HashMap(entries)
        }
        reopened
    }

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

/** Controllable authoritative source used by the provider's JVM lane. */
class FakeAuthoritativeOracleSource(
    private val capability: () -> ContinuityEvidenceCapability,
    ownerUid: Int,
    ownerPackage: String,
) : AuthoritativeContinuitySource {
    private var state = AuthoritativeContinuityState(
        ownerUid = ownerUid,
        ownerPackage = ownerPackage,
        gpsProviderEnabled = true,
        networkProviderEnabled = true,
        installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1,
        health = AuthoritativeOracleHealth.HEALTHY,
        qwySemanticDigest = "fake-qwy-generation-1",
        lastCompletedQwyMutationId = null,
    )

    var oracle = AuthoritativeContinuityOracle(
        bootId = "fake-boot-1",
        oracleInstanceId = "fake-oracle-instance-1",
        initialState = state,
    )
        private set

    val scriptedSnapshots = ArrayDeque<AuthoritativeContinuitySnapshot>()
    var failNextRead: Boolean = false

    override fun snapshot(): AuthoritativeContinuitySnapshot? {
        if (scriptedSnapshots.isNotEmpty()) return scriptedSnapshots.removeFirst()
        if (failNextRead) {
            failNextRead = false
            throw IllegalStateException("simulated authoritative Binder read failure")
        }
        return when (capability()) {
            ContinuityEvidenceCapability.COMPLETE -> oracle.snapshot()
            ContinuityEvidenceCapability.INCOMPLETE -> oracle.snapshot().copy(
                installedCoverageMask = AuthoritativeCoverageMask.REQUIRED_V1 xor
                    AuthoritativeCoverageMask.BUILD_ATTESTATION,
                health = AuthoritativeOracleHealth.HOOKS_INCOMPLETE,
            )
            ContinuityEvidenceCapability.UNAVAILABLE -> null
        }
    }

    fun currentState(): AuthoritativeContinuityState = state

    fun registerSemanticSession(semanticDigest: String) {
        val next = state.copy(qwySemanticDigest = semanticDigest)
        state = next
        if (oracle.snapshot().health == AuthoritativeOracleHealth.SESSION_UNCERTAIN) {
            oracle.registerRecoveredSession(next)
        } else {
            val token = oracle.beginMutation()
            oracle.finishMutation(token, AuthoritativeMutationOutcome.CHANGED, next)
        }
    }

    /** Mirrors the remote QWY session death discontinuity before a new owner registers. */
    fun ownerProcessDied() {
        changed(
            semanticDigest = state.qwySemanticDigest,
            mutationId = state.lastCompletedQwyMutationId,
        )
    }

    fun replaceOracle(
        bootId: String = "fake-boot-2",
        oracleInstanceId: String = "fake-oracle-instance-2",
    ) {
        oracle = AuthoritativeContinuityOracle(
            bootId = bootId,
            oracleInstanceId = oracleInstanceId,
            initialState = state,
        )
    }

    fun finishSemanticMutation(
        token: AuthoritativeMutationToken,
        mutationId: String,
        changed: Boolean,
        uncertain: Boolean,
        afterDigest: String?,
    ) {
        val next = if (!changed && !uncertain) {
            // A proved no-op changes journal sequence only. Semantic state and
            // the most recent completed correlation remain byte-for-byte stable.
            state
        } else {
            state.copy(
                qwySemanticDigest = afterDigest ?: state.qwySemanticDigest,
                lastCompletedQwyMutationId = if (changed && !uncertain) mutationId else null,
            )
        }
        state = next
        oracle.finishMutation(
            token = token,
            outcome = when {
                uncertain -> AuthoritativeMutationOutcome.UNCERTAIN
                changed -> AuthoritativeMutationOutcome.CHANGED
                else -> AuthoritativeMutationOutcome.PROVED_NO_OP
            },
            state = next,
        )
    }

    fun changed(
        ownerUid: Int? = state.ownerUid,
        ownerPackage: String? = state.ownerPackage,
        gpsEnabled: Boolean = state.gpsProviderEnabled,
        networkEnabled: Boolean = state.networkProviderEnabled,
        semanticDigest: String? = state.qwySemanticDigest,
        mutationId: String? = null,
    ) {
        val next = state.copy(
            ownerUid = ownerUid,
            ownerPackage = ownerPackage,
            gpsProviderEnabled = gpsEnabled,
            networkProviderEnabled = networkEnabled,
            qwySemanticDigest = semanticDigest,
            lastCompletedQwyMutationId = mutationId,
        )
        val token = oracle.beginMutation()
        state = next
        oracle.finishMutation(token, AuthoritativeMutationOutcome.CHANGED, next)
    }
}

/** Pure endpoint adapter that drives the same oracle state machine as Binder. */
class FakeQwySemanticMutationEndpoint(
    private val source: FakeAuthoritativeOracleSource,
) : QwySemanticMutationEndpoint {
    private data class Active(
        val mutationId: String,
        val token: AuthoritativeMutationToken,
    )

    private var nextToken = 1L
    private val active = linkedMapOf<Long, Active>()
    var beginCount = 0
        private set
    var finishCount = 0
        private set

    override fun registerCurrentSession(
        semanticDigest: String,
        clientDeathToken: QwySemanticClientDeathToken,
    ) {
        check(clientDeathToken.isAlive())
        source.registerSemanticSession(semanticDigest)
    }

    override fun beginMutation(
        mutationId: String,
        beforeDigest: String,
        clientDeathToken: QwySemanticClientDeathToken,
    ): Long {
        check(clientDeathToken.isAlive())
        check(source.currentState().qwySemanticDigest == beforeDigest)
        val wireToken = nextToken++
        active[wireToken] = Active(mutationId, source.oracle.beginMutation())
        beginCount += 1
        return wireToken
    }

    override fun finishMutation(
        token: Long,
        changed: Boolean,
        uncertain: Boolean,
        afterDigest: String?,
    ) {
        val mutation = checkNotNull(active.remove(token))
        source.finishSemanticMutation(
            token = mutation.token,
            mutationId = mutation.mutationId,
            changed = changed,
            uncertain = uncertain,
            afterDigest = afterDigest,
        )
        finishCount += 1
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
    /** Strong by default so existing matrix happy paths model a complete oracle. */
    var continuityCapability: ContinuityEvidenceCapability =
        ContinuityEvidenceCapability.COMPLETE
    var authoritativeSemanticMutations: Boolean = false
    /** Test hook for revision/effective-read linearization regressions. */
    var beforeObserveEffective: (() -> Unit)? = null
    /** Runs at the external apply boundary, before any fake environment mutation. */
    var beforeApplyEnvironment: (() -> Unit)? = null
    /** Runs after the fake environment mutated but before apply returns. */
    var afterApplyEnvironmentMutation: (() -> Unit)? = null
    /** Runs at the external cleanup boundary, before any fake mutation. */
    var beforeCleanup: (() -> Unit)? = null
    /** Runs after cleanup mutated the fake environment but before it returns. */
    var afterCleanupEnvironmentMutation: (() -> Unit)? = null
    /**
     * Explicit raw OS-sample seam for freshness regressions. Null keeps older
     * matrix scenarios independent by modeling a live source that advances on
     * every read; a non-null map is replayed byte-for-byte until the test moves it.
     */
    var verifiedSourceElapsedRealtimeMs: Map<String, Long>? = null
    private var syntheticEvidenceElapsedRealtimeMs: Long = 1_000_000L

    // --- call-count instrumentation (memory by design; counts across restarts) ---
    var applyCount: Int = 0
    var cleanupCount: Int = 0
    var advanceCount: Int = 0
    var projectionConvergenceCount: Int = 0

    /**
     * Crash injection at the qwy-side pointer mutation (Terra PR#22 round-2:
     * the §6.7.5 window between the provider's receipt commit and the external
     * pointer apply). One-shot: throws BEFORE any schedule state is touched,
     * then re-arms to false so the recovery/replay path runs normally.
     */
    var failNextAdvancePointer: Boolean = false

    /**
     * Production-parity crash seam: the durable schedule pointer has moved,
     * but the target item's framework projection has not converged yet.
     */
    var failNextProjectionAfterPointer: Boolean = false

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

    override fun convergeAdvance(
        fromItemId: String,
        expectedToItemId: String?,
        expectedVersionAfter: Long,
    ): AdvancePointerOutcome {
        if (failNextAdvancePointer) {
            failNextAdvancePointer = false
            throw SimulatedWriteCrash(SCHEDULE_NAMESPACE, "advancePointer")
        }
        beforeAdvancePointer?.invoke()
        val idx = itemIds.indexOf(fromItemId)
        check(idx >= 0) { "advancePointer from unknown item $fromItemId" }
        val targetItemId = itemIds.getOrNull(idx + 1)
        check(targetItemId == expectedToItemId) {
            "expected target $expectedToItemId does not match schedule target $targetItemId"
        }
        val alreadyAdvanced = if (targetItemId == null) {
            exhausted && currentItemId == fromItemId
        } else {
            currentItemId == targetItemId
        }
        if (!alreadyAdvanced) {
            check(currentItemId == fromItemId && !exhausted) {
                "cannot converge advance from=$fromItemId current=$currentItemId exhausted=$exhausted"
            }
            check(scheduleVersion + 1L == expectedVersionAfter) {
                "expected version $expectedVersionAfter cannot follow $scheduleVersion"
            }
            advanceCount += 1
            // Spec v1.56: every logical advance (terminal and non-terminal)
            // bumps the schedule version exactly once.
            scheduleVersion += 1
            if (targetItemId == null) {
                exhausted = true
            } else {
                currentItemId = targetItemId
            }
        } else {
            check(scheduleVersion == expectedVersionAfter) {
                "already-advanced version $scheduleVersion does not match $expectedVersionAfter"
            }
        }

        if (failNextProjectionAfterPointer) {
            failNextProjectionAfterPointer = false
            throw SimulatedWriteCrash(SCHEDULE_NAMESPACE, "projectAdvancedEnvironment")
        }

        // Harness fidelity: release clears the old provider projection, while
        // a successful non-terminal advance must establish the new item before
        // the handler clears its pending marker.
        if (targetItemId != null) {
            val resolved = coordinateForItem(targetItemId)
                ?: error("no qwy-owned coordinates for advanced item $targetItemId")
            effectiveLatitude = resolved.first
            effectiveLongitude = resolved.second
            lastAppliedVerificationLevelWire = applyVerificationLevelWire
            projectionConvergenceCount += 1
        }

        return if (targetItemId == null) {
            AdvancePointerOutcome.Exhausted(scheduleVersion)
        } else {
            AdvancePointerOutcome.Advanced(targetItemId, scheduleVersion)
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
        beforeApplyEnvironment?.invoke()
        applyCount += 1
        // KB-8 (v1.62): the intent no longer carries coordinates — the
        // provider resolves them from the current schedule item. The fake
        // models the same KB-8 world: fixed qwy-owned coordinates per item.
        val resolved = coordinateForItem(currentItemId)
        effectiveLatitude = resolved?.first
        effectiveLongitude = resolved?.second
        lastAppliedVerificationLevelWire = applyVerificationLevelWire
        afterApplyEnvironmentMutation?.invoke()
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
        beforeCleanup?.invoke()
        cleanupCount += 1
        effectiveLatitude = null
        effectiveLongitude = null
        lastAppliedVerificationLevelWire = VerificationLevelV1.NONE.wire
        afterCleanupEnvironmentMutation?.invoke()
        return cleanupOutcome
    }

    override fun observeEffective(): EffectiveEnvironment {
        beforeObserveEffective?.invoke()
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
        val sourceTimes = verifiedSourceElapsedRealtimeMs ?: run {
            syntheticEvidenceElapsedRealtimeMs += 1L
            linkedMapOf(
                "gps" to syntheticEvidenceElapsedRealtimeMs,
                "network" to syntheticEvidenceElapsedRealtimeMs,
            )
        }
        return EffectiveEnvironment(
            latitude = effectiveLatitude,
            longitude = effectiveLongitude,
            isMock = if (verified) isMock else false,
            deliveryModeWire = if (verified) DeliveryModeV1.SYSTEM_MOCK.wire else null,
            verificationLevelWire = level,
            environmentFingerprint = fingerprint,
            evidenceRefs = evidenceRefs,
            evidenceObservedAtElapsedRealtimeMs = sourceTimes.values.minOrNull(),
            verifiedSourceElapsedRealtimeMs = if (verified) sourceTimes else emptyMap(),
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

    override fun continuityEvidenceCapability(): ContinuityEvidenceCapability =
        continuityCapability

    override fun authoritativeSemanticDigest(ownerGeneration: Long): String =
        QwySemanticDigestV1.compute(
            ownerGeneration = ownerGeneration,
            activeMode = "always_on",
            activeProfileRef = currentItemId,
            schedule = scheduleSnapshot(),
            effectiveLatitude = effectiveLatitude,
            effectiveLongitude = effectiveLongitude,
            projectionActive = effectiveLatitude != null && effectiveLongitude != null,
        )

    override fun authoritativeSemanticMutationEnabled(): Boolean =
        authoritativeSemanticMutations

    override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {
        relevantChangeListener = listener
    }

    override fun abortOwnerStart() {
        relevantChangeListener = null
    }

    /**
     * M-RC-03: an external app steals the mock-location owner and gives it
     * back. The post state equals the pre state — revision must change anyway.
     */
    fun hijackAndRestoreMockOwner() {
        relevantChangeListener?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
        relevantChangeListener?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
    }

    fun emitRelevantChange(reason: RevisionBumpReason) {
        relevantChangeListener?.invoke(reason)
    }
}

/**
 * F-15: recording sink for the [name.caiyao.fakegps.integration.v1.DiagnosticLog]
 * seam. The step-3b species line must be observable from the JVM lane WITHOUT
 * mocking android.util.Log, and the recorder doubles as the assertion surface
 * for branch-distinguishability (four branches → four mutually exclusive
 * `STALE_LEASE_SPECIES=` tokens).
 */
class RecordingDiagnosticLog : name.caiyao.fakegps.integration.v1.DiagnosticLog {
    private val _lines = mutableListOf<String>()
    val lines: List<String> get() = _lines

    override fun warn(tag: String, message: String) {
        _lines += "$tag: $message"
    }

    fun clear() = _lines.clear()
}
