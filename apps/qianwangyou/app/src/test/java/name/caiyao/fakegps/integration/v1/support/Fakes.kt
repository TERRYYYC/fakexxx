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
 */
class InMemoryDurableKv : DurableKv {
    private val data = HashMap<String, HashMap<String, String>>()
    private val lock = Any()

    override fun read(namespace: String, key: String): String? =
        synchronized(lock) { data[namespace]?.get(key) }

    override fun write(namespace: String, key: String, value: String) {
        synchronized(lock) { data.getOrPut(namespace) { HashMap() }[key] = value }
    }

    override fun keys(namespace: String): Set<String> =
        synchronized(lock) { data[namespace]?.keys?.toSet() ?: emptySet() }

    override fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }
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
 * Fake of qianwangyou's existing capabilities. Deterministic and steerable:
 * tests mutate schedule/version, break cleanup, or fire relevant-change events
 * (which the production wiring must forward into the revision owner — M-RC-03).
 */
class FakeQwyEnvironment : QwyEnvironment {
    var scheduleId: String = "sched-1"
    var scheduleVersion: Long = 7L
    var currentItemId: String? = "item-1"
    var itemIds: MutableList<String> = mutableListOf("item-1", "item-2", "item-3")

    var applyCount: Int = 0
    var cleanupCount: Int = 0
    var advanceCount: Int = 0

    var cleanupOutcome: CleanupOutcome = CleanupOutcome.Complete
    var effectiveLatitude: Double? = null
    var effectiveLongitude: Double? = null
    var isMock: Boolean? = true
    var fingerprint: String = "fp-1"
    var evidenceRefs: List<String> = listOf("qwy:audit:1")

    private var relevantChangeListener: ((RevisionBumpReason) -> Unit)? = null

    override fun scheduleSnapshot(): ScheduleSnapshot? =
        ScheduleSnapshot(scheduleId, scheduleVersion, currentItemId, itemIds.toList())

    override fun advancePointer(fromItemId: String): AdvancePointerOutcome {
        advanceCount += 1
        val idx = itemIds.indexOf(fromItemId)
        check(idx >= 0) { "advancePointer from unknown item $fromItemId" }
        return if (idx == itemIds.lastIndex) {
            AdvancePointerOutcome.Exhausted(scheduleVersion)
        } else {
            currentItemId = itemIds[idx + 1]
            AdvancePointerOutcome.Advanced(itemIds[idx + 1], scheduleVersion)
        }
    }

    override fun applyEnvironment(intent: EnvironmentIntentV1): ApplyOutcome {
        applyCount += 1
        effectiveLatitude = intent.latitude
        effectiveLongitude = intent.longitude
        return ApplyOutcome(
            effectiveLatitude = intent.latitude,
            effectiveLongitude = intent.longitude,
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        )
    }

    override fun cleanup(leaseId: String): CleanupOutcome {
        cleanupCount += 1
        return cleanupOutcome
    }

    override fun observeEffective(): EffectiveEnvironment = EffectiveEnvironment(
        latitude = effectiveLatitude,
        longitude = effectiveLongitude,
        isMock = isMock,
        deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
        verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        environmentFingerprint = fingerprint,
        evidenceRefs = evidenceRefs,
    )

    override fun scheduleDecisionWire(scheduleRef: String): Int = 1 // ALLOWED_NOW

    override fun setRelevantChangeListener(listener: (RevisionBumpReason) -> Unit) {
        relevantChangeListener = listener
    }

    /**
     * M-RC-03: an external app steals the mock-location owner and gives it back.
     * The post state equals the pre state — revision must change anyway.
     */
    fun hijackAndRestoreMockOwner() {
        relevantChangeListener?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
        relevantChangeListener?.invoke(RevisionBumpReason.PERMISSION_OR_OWNER_CHANGED)
    }
}
