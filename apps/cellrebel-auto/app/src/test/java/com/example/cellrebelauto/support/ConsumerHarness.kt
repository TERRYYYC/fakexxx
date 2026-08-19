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
 * Simulates a provider that faithfully executes the advance protocol, with
 * injectable faults for negative test paths. The harness owns a mini schedule
 * state machine mirroring the provider's pointer/version/exhausted triple.
 *
 * Design: mirrored from `ProviderHarness` on the qwy side — same idiom but
 * from the consumer's perspective. The harness owns `FakeSchedule` (provider
 * state) and `TrustedQuotaLedger` (Auto state), and `AdvanceCoordinator` is
 * the system under test.
 *
 * P1-1 fix: The coordinator takes [QuotaReader] and [ProviderGateway] interfaces
 * — the SAME interfaces the production code uses (AutomationEngine with Room-
 * backed QuotaReader and AIDL-backed ProviderGateway). The test doubles
 * ([TrustedQuotaLedger], [FakeProviderGateway]) implement those interfaces.
 *
 * P1-2 fix: The coordinator reads quota via [QuotaReader.countTrustedEntries],
 * never via a caller-supplied `quotaCount: Int`. After a crash, recovery
 * re-enters `evaluateAfterRelease` and the coordinator re-reads from the
 * durable store — a stale pre-crash snapshot cannot bypass the gate.
 *
 * P1-3 fix: [TrustedQuotaLedger] has attempt-keyed idempotency (UNIQUE
 * constraint analog). Double-committing the same attempt counts once.
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

    /** Default schedule context from the current schedule state. */
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

    /**
     * Register N trusted quota entries for a task, auto-generating unique
     * attempt keys. Returns the count after registration.
     */
    fun commitQuota(taskId: String, count: Int): Int {
        repeat(count) {
            quotaLedger.commitEntry(taskId, "$taskId:auto-${quotaSeq++}")
        }
        return quotaLedger.countTrustedEntries(taskId)
    }

    /**
     * Register a single trusted quota entry with an explicit attempt key.
     * Use when the test needs to control idempotency behavior (M-AD-19).
     * Returns true if the entry was new, false if deduplicated.
     */
    fun commitQuotaEntry(taskId: String, attemptKey: String): Boolean =
        quotaLedger.commitEntry(taskId, attemptKey)

    /**
     * Override the observation the provider will return on the next `observe()`.
     * Use for fault injection (M-AD-17, M-AD-18).
     */
    fun overrideNextObservation(observation: EnvironmentObservationV1) {
        gateway.nextObservationOverride = observation
    }

    /**
     * Override the discover snapshot the provider will return.
     * Use for fault injection (M-AD-23, M-AD-27).
     */
    fun overrideNextDiscoverSnapshot(snapshot: CapabilitySnapshotV1) {
        gateway.nextDiscoverOverride = snapshot
    }

    /**
     * Override the receipt digest computation to produce a bad digest.
     * Use for M-AD-16 (EXHAUSTED receipt digest mismatch).
     */
    fun corruptNextReceiptDigest() {
        gateway.corruptNextDigest = true
    }
}

/**
 * Minimal schedule state machine matching the provider's pointer/version/exhausted
 * triple. The provider side owns these; this is the harness's simulation.
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

    /** Idempotency store: key → receipt */
    private val idempotencyStore = mutableMapOf<String, AdvanceReceiptV1>()

    /**
     * Advance the pointer. Returns the receipt.
     *
     * @param corruptDigest if true, produce a receipt with a corrupted digest.
     *   The advance is real (idempotency consumed, pointer moved) but the receipt
     *   is unverifiable. Used for M-AD-16 (digest mismatch).
     *
     * P1-3 fix: idempotency check runs BEFORE the corrupt flag is evaluated.
     * A replayed key returns the ORIGINAL receipt (honest or corrupt),
     * never a second advance with a different corruption state.
     */
    fun advance(
        request: CompleteAndAdvanceRequestV1,
        corruptDigest: Boolean = false,
    ): AdvanceReceiptV1 {
        // Idempotency check — BEFORE corruption. A replayed key returns
        // the cached receipt, not a new one. This is the P1-3 fix:
        // advanceWithCorruptDigest must not bypass the idempotency store.
        idempotencyStore[request.idempotencyKey]?.let { return it }

        val fromItem = currentItemId
        val currentIndex = items.indexOf(currentItemId)
        val isLast = currentIndex == items.size - 1

        val outcome: AdvanceOutcomeV1
        val toItem: String?

        if (isLast) {
            // Terminal: EXHAUSTED
            outcome = AdvanceOutcomeV1.EXHAUSTED
            toItem = null
            exhausted = true
            // currentItemId retains the last item (M-AD-10)
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
            receiptDigest = "", // computed below
        )
        val digest = if (corrupt) {
            "corrupted-digest-not-valid"
        } else {
            CanonicalAdvanceReceiptDigestV1.compute(bare, requestDigest, idempotencyKey)
        }
        return bare.copy(receiptDigest = digest)
    }

    /** Build the observation that matches the CURRENT schedule state (honest). */
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

    /** Build the discover snapshot that matches the CURRENT schedule state (honest). */
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
 * Auto-side trusted quota ledger with attempt-keyed idempotency.
 *
 * Implements [QuotaReader] — the same interface the production coordinator
 * reads from (production = Room DAO, test = this class).
 *
 * P1-3 fix: entries are keyed by `attemptKey` (UNIQUE constraint analog).
 * Double-committing the same attempt key for a task counts as one entry.
 * In production, Room enforces this via a UNIQUE constraint on
 * `TrustedQuotaEntry(taskId, attemptKey)`. This prevents crash-recovery
 * from inflating the quota count.
 */
class TrustedQuotaLedger : QuotaReader {
    /** attemptKey → taskId (UNIQUE on attemptKey) */
    private val entries = mutableMapOf<String, String>()

    /**
     * Commit a quota entry. Returns `true` if the entry was new, `false` if
     * the attempt key was already committed (idempotent dedup).
     */
    fun commitEntry(taskId: String, attemptKey: String): Boolean {
        if (entries.containsKey(attemptKey)) return false
        entries[attemptKey] = taskId
        return true
    }

    override fun countTrustedEntries(taskId: String): Int =
        entries.count { it.value == taskId }
}

/**
 * Fake provider gateway for test control. Implements the provider protocol
 * faithfully unless fault-injection flags are set.
 */
class FakeProviderGateway(
    private val schedule: FakeSchedule,
) : ProviderGateway {

    /** If set, the next observe() returns this instead of the honest observation. */
    var nextObservationOverride: EnvironmentObservationV1? = null

    /** If set, the next discover() returns this instead of the honest snapshot. */
    var nextDiscoverOverride: CapabilitySnapshotV1? = null

    /** If true, the next completeAndAdvance produces a corrupt receipt digest. */
    var corruptNextDigest: Boolean = false

    override fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
        val corrupt = corruptNextDigest
        corruptNextDigest = false
        return schedule.advance(request, corruptDigest = corrupt)
    }

    override fun observe(leaseId: String, context: ScheduleContext): EnvironmentObservationV1 {
        val override = nextObservationOverride
        if (override != null) {
            nextObservationOverride = null
            return override
        }
        return schedule.honestObservation(leaseId)
    }

    override fun discover(): CapabilitySnapshotV1 {
        val override = nextDiscoverOverride
        if (override != null) {
            nextDiscoverOverride = null
            return override
        }
        return schedule.honestSnapshot()
    }
}

/** Marker exception for crash simulation in tests. */
class CrashSimulation(message: String) : RuntimeException(message)
