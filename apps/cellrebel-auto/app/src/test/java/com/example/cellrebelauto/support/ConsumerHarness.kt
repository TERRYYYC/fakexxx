package com.example.cellrebelauto.support

import com.example.cellrebelauto.automation.advance.AdvanceCoordinator
import com.example.cellrebelauto.automation.advance.ProviderGateway
import com.example.cellrebelauto.automation.advance.QuotaReader
import com.example.cellrebelauto.automation.advance.ScheduleContext
import io.github.terryyyc.fakexxx.contract.v1.AdvanceOutcomeV1
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CanonicalAdvanceReceiptDigestV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.ContinuityCoverageV1
import io.github.terryyyc.fakexxx.contract.v1.ContractV1
import io.github.terryyyc.fakexxx.contract.v1.DeliveryModeV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import io.github.terryyyc.fakexxx.contract.v1.ScheduleDecisionV1
import io.github.terryyyc.fakexxx.contract.v1.VerificationLevelV1

/**
 * Test harness for the Auto consumer's advance protocol.
 *
 * All interfaces are `suspend` (matching the production signatures).
 * Tests call coordinator methods inside `runBlocking { ... }`.
 */
class ConsumerHarness private constructor(
    val schedule: FakeSchedule,
    val quotaLedger: TrustedQuotaLedger,
    private val gateway: FakeProviderGateway,
) {
    val coordinator: AdvanceCoordinator = AdvanceCoordinator(gateway, quotaLedger)

    companion object {
        private val DEFAULT_ITEMS = listOf("item-1", "item-2", "item-3")

        fun create(
            scheduleId: String = "schedule-1",
            items: List<String> = DEFAULT_ITEMS,
        ): ConsumerHarness {
            val schedule = FakeSchedule(scheduleId, items)
            val quotaLedger = TrustedQuotaLedger()
            val gateway = FakeProviderGateway(schedule)
            return ConsumerHarness(schedule, quotaLedger, gateway)
        }
    }

    private var quotaSeq = 0

    fun scheduleContext(
        ledgerRef: String = "auto:ledger:run-1:${schedule.currentItemId}",
        verifiedAtMs: Long = 1000L,
    ): ScheduleContext = ScheduleContext(
        scheduleId = schedule.scheduleId,
        currentItemId = schedule.currentItemId,
        scheduleVersion = schedule.scheduleVersion,
        ledgerRef = ledgerRef,
        verifiedAtElapsedRealtimeMs = verifiedAtMs,
    )

    fun commitQuota(taskId: String, count: Int): Int {
        repeat(count) {
            quotaLedger.commitEntry(taskId, "$taskId:auto-${quotaSeq++}")
        }
        return quotaLedger.countSync(taskId)
    }

    fun commitQuotaEntry(taskId: String, attemptKey: String): Boolean =
        quotaLedger.commitEntry(taskId, attemptKey)

    fun overrideNextObservation(observation: EnvironmentObservationV1) {
        gateway.nextObservationOverride = observation
    }

    fun overrideNextDiscoverSnapshot(snapshot: CapabilitySnapshotV1) {
        gateway.nextDiscoverOverride = snapshot
    }

    fun corruptNextReceiptDigest() {
        gateway.corruptNextDigest = true
    }
}

/**
 * Minimal schedule state machine matching the provider's pointer/version/exhausted
 * triple.
 */
class FakeSchedule(
    val scheduleId: String,
    private val items: List<String>,
) {
    var currentItemId: String = items.first()
        private set
    var scheduleVersion: Long = 1L
        private set
    var exhausted: Boolean = false
        private set
    var advanceCount: Int = 0
        private set

    private val idempotencyStore = mutableMapOf<String, AdvanceReceiptV1>()

    fun advance(
        request: CompleteAndAdvanceRequestV1,
        corruptDigest: Boolean = false,
    ): AdvanceReceiptV1 {
        idempotencyStore[request.idempotencyKey]?.let { return it }

        val fromItem = currentItemId
        val currentIndex = items.indexOf(currentItemId)
        val isLast = currentIndex == items.size - 1

        val outcome: AdvanceOutcomeV1
        val toItem: String?

        if (isLast) {
            outcome = AdvanceOutcomeV1.EXHAUSTED
            toItem = null
            exhausted = true
        } else {
            outcome = AdvanceOutcomeV1.ADVANCED
            toItem = items[currentIndex + 1]
            currentItemId = toItem
        }

        scheduleVersion += 1
        advanceCount += 1

        val receipt = buildReceipt(
            outcome = outcome,
            fromItem = fromItem,
            toItem = toItem,
            versionAfter = scheduleVersion,
            requestDigest = request.requestDigest,
            idempotencyKey = request.idempotencyKey,
            corrupt = corruptDigest,
        )
        idempotencyStore[request.idempotencyKey] = receipt
        return receipt
    }

    private fun buildReceipt(
        outcome: AdvanceOutcomeV1,
        fromItem: String,
        toItem: String?,
        versionAfter: Long,
        requestDigest: String,
        idempotencyKey: String,
        corrupt: Boolean = false,
    ): AdvanceReceiptV1 {
        val bare = AdvanceReceiptV1(
            outcomeWire = outcome.wire,
            advancedFromItemId = fromItem,
            advancedToItemId = toItem,
            scheduleVersionAfter = versionAfter,
            effectiveIntentHash = "intent-hash-${toItem ?: fromItem}",
            effectiveEnvironmentRevision = versionAfter * 100,
            receiptDigest = "",
        )
        val digest = if (corrupt) {
            "corrupted-digest-not-valid"
        } else {
            CanonicalAdvanceReceiptDigestV1.compute(bare, requestDigest, idempotencyKey)
        }
        return bare.copy(receiptDigest = digest)
    }

    fun honestObservation(leaseId: String): EnvironmentObservationV1 =
        EnvironmentObservationV1(
            leaseId = leaseId,
            acceptedIntentHash = "intent-hash-$currentItemId",
            observedAtEpochMs = System.currentTimeMillis(),
            observedAtElapsedRealtimeMs = 5000L,
            environmentRevision = scheduleVersion * 100,
            environmentFingerprint = "fp-$currentItemId",
            continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
            continuitySinceEpochMs = null,
            continuitySinceElapsedRealtimeMs = null,
            deliveryModeWire = DeliveryModeV1.SYSTEM_MOCK.wire,
            verificationLevelWire = VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
            effectiveLatitude = 39.9042,
            effectiveLongitude = 116.4074,
            isMock = true,
            scheduleDecisionWire = ScheduleDecisionV1.ALLOWED_NOW.wire,
            evidenceRefs = emptyList(),
            scheduleItemId = currentItemId,
            scheduleVersion = scheduleVersion,
        )

    fun honestSnapshot(): CapabilitySnapshotV1 = CapabilitySnapshotV1(
        serviceVersion = "1.0.0-test",
        supportedModeWires = listOf(DeliveryModeV1.SYSTEM_MOCK.wire),
        supportedVerificationLevelWires = listOf(
            VerificationLevelV1.SYSTEM_MOCK_INDEPENDENTLY_VERIFIED.wire,
        ),
        continuityCoverageWire = ContinuityCoverageV1.FULL.wire,
        environmentRevision = scheduleVersion * 100,
        profileRefs = listOf("profile-1"),
        scheduleRefs = listOf(scheduleId),
        currentScheduleId = scheduleId,
        currentItemId = currentItemId,
        scheduleVersion = scheduleVersion,
        exhausted = exhausted,
    )
}

/**
 * Trusted quota ledger with attempt-keyed idempotency.
 * Implements [QuotaReader] (suspend). The `countSync` helper is for
 * non-suspend test setup code.
 */
class TrustedQuotaLedger : QuotaReader {
    private val entries = mutableMapOf<String, String>()

    fun commitEntry(taskId: String, attemptKey: String): Boolean {
        if (entries.containsKey(attemptKey)) return false
        entries[attemptKey] = taskId
        return true
    }

    override suspend fun countTrustedEntries(taskId: String): Int = countSync(taskId)

    fun countSync(taskId: String): Int = entries.count { it.value == taskId }
}

/**
 * Fake provider gateway. Implements suspend [ProviderGateway].
 */
class FakeProviderGateway(
    private val schedule: FakeSchedule,
) : ProviderGateway {

    var nextObservationOverride: EnvironmentObservationV1? = null
    var nextDiscoverOverride: CapabilitySnapshotV1? = null
    var corruptNextDigest: Boolean = false

    override suspend fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
        val corrupt = corruptNextDigest
        corruptNextDigest = false
        return schedule.advance(request, corruptDigest = corrupt)
    }

    override suspend fun observe(leaseId: String, context: ScheduleContext): EnvironmentObservationV1 {
        val override = nextObservationOverride
        if (override != null) {
            nextObservationOverride = null
            return override
        }
        return schedule.honestObservation(leaseId)
    }

    override suspend fun discover(): CapabilitySnapshotV1 {
        val override = nextDiscoverOverride
        if (override != null) {
            nextDiscoverOverride = null
            return override
        }
        return schedule.honestSnapshot()
    }
}

class CrashSimulation(message: String) : RuntimeException(message)
