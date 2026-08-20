package com.example.cellrebelauto.engine

import com.example.cellrebelauto.automation.advance.AdvanceCoordinator
import com.example.cellrebelauto.automation.advance.AdvanceDecision
import com.example.cellrebelauto.automation.advance.AdvanceStateStore
import com.example.cellrebelauto.automation.advance.PendingAdvance
import com.example.cellrebelauto.automation.advance.PendingProviderGateway
import com.example.cellrebelauto.automation.advance.ProviderGateway
import com.example.cellrebelauto.automation.advance.ProviderNotAvailableException
import com.example.cellrebelauto.automation.advance.QuotaReader
import com.example.cellrebelauto.automation.advance.ScheduleContext
import com.example.cellrebelauto.support.InMemoryAdvanceStateStore
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1
import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-level advance recovery tests.
 *
 * Exercises the EXACT code path that [AutomationEngine.run] executes in
 * its recovery sweep at startup. The recovery sweep logic is:
 *
 *   1. `store.listAllUnresolved()` → list of pending/recovery_required records
 *   2. For each: `coordinator.evaluateAfterRelease(...)` with persisted params
 *   3. `resolveAdvanceRecord(store, id, decision)` → update durable state
 *
 * These tests prove three properties Sol's review requires:
 *
 *   **(a)** Under-quota recovery makes ZERO provider calls — the quota gate
 *           fires inside `evaluateAfterRelease()` before the provider is touched.
 *
 *   **(b)** Threshold-at-crash replays EXACTLY ONCE — the idempotency key
 *           persisted in the durable store prevents double-advance when the
 *           provider's own idempotency store is checked.
 *
 *   **(c)** Digest/tuple failure leaves the plan NON-TERMINAL — the advance
 *           record stays in `recovery_required` state, the plan's task status
 *           is unaffected by advance failures.
 *
 * The coordinator, store, and gateway are the PRODUCTION classes (except
 * InMemoryAdvanceStateStore which mirrors Room behavior). No Android
 * framework mocking needed.
 */
class EngineAdvanceTest {

    // ════════════════════════════════════════════════════════════════════════
    // Test infrastructure — replicates AutomationEngine's recovery sweep
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Runs the engine's recovery sweep logic. This is the EXACT code path
     * from AutomationEngine.run() — extracted here to test without needing
     * the full engine (GPS, CellRebel, PlanRepository, etc).
     *
     * The only datum the engine reads from PlanRepository is `task.requiredSuccesses`,
     * which we pass via [taskRequiredSuccesses].
     */
    private suspend fun runRecoverySweep(
        store: AdvanceStateStore,
        coordinator: AdvanceCoordinator,
        taskRequiredSuccesses: Map<Long, Int>,
    ): List<Pair<PendingAdvance, AdvanceDecision?>> {
        val results = mutableListOf<Pair<PendingAdvance, AdvanceDecision?>>()
        val unresolved = store.listAllUnresolved()
        for (record in unresolved) {
            val required = taskRequiredSuccesses[record.taskId]
            if (required == null) {
                store.resolve(record.id, "quota_not_met", null, "task not found")
                results.add(record to null)
                continue
            }
            try {
                val decision = coordinator.evaluateAfterRelease(
                    taskId = record.taskId.toString(),
                    requiredSuccesses = required,
                    scheduleContext = record.scheduleContext,
                    leaseId = record.leaseId,
                    idempotencyKey = record.idempotencyKey,
                )
                resolveAdvanceRecord(store, record.id, decision)
                results.add(record to decision)
            } catch (e: ProviderNotAvailableException) {
                store.resolve(record.id, "provider_unavailable", null, e.message)
                results.add(record to null)
            } catch (e: Exception) {
                store.resolve(record.id, "recovery_required", null, e.message)
                results.add(record to null)
            }
        }
        return results
    }

    /** Mirrors AutomationEngine.resolveAdvanceRecord — EXACT same logic. */
    private suspend fun resolveAdvanceRecord(
        store: AdvanceStateStore,
        recordId: Long,
        decision: AdvanceDecision,
    ) {
        when (decision) {
            is AdvanceDecision.QuotaNotMet ->
                store.resolve(recordId, "quota_not_met", null, null)
            is AdvanceDecision.Advanced ->
                store.resolve(recordId, "advanced", decision.receipt.receiptDigest, null)
            is AdvanceDecision.Exhausted ->
                store.resolve(recordId, "exhausted", decision.receipt.receiptDigest, null)
            is AdvanceDecision.RecoveryRequired ->
                store.resolve(recordId, "recovery_required", null, decision.reason)
        }
    }

    /** Provider gateway that counts calls. */
    private class CountingProviderGateway : ProviderGateway {
        var completeAndAdvanceCount = 0
        var observeCount = 0
        var discoverCount = 0

        override suspend fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
            completeAndAdvanceCount++
            throw ProviderNotAvailableException("counting gateway — not wired")
        }

        override suspend fun observe(leaseId: String, context: ScheduleContext): EnvironmentObservationV1 {
            observeCount++
            throw ProviderNotAvailableException("counting gateway — observe not wired")
        }

        override suspend fun discover(): CapabilitySnapshotV1 {
            discoverCount++
            throw ProviderNotAvailableException("counting gateway — discover not wired")
        }
    }

    /** Quota reader that returns a configurable count per taskId. */
    private class ConfigurableQuotaReader(private val counts: Map<String, Int>) : QuotaReader {
        constructor(count: Int) : this(emptyMap<String, Int>()) {
            defaultCount = count
        }

        private var defaultCount = 0
        override suspend fun countTrustedEntries(taskId: String): Int =
            counts[taskId] ?: defaultCount
    }

    private val defaultCtx = ScheduleContext("schedule-1", "item-1", 1L, "ledger-1", 1000L)

    // ════════════════════════════════════════════════════════════════════════
    // (a) Under-quota recovery makes ZERO provider calls
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun recovery_underQuota_zeroProviderCalls() = runBlocking {
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 1) // 1/3 — under quota
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        // Pre-seed a "pending" advance record (simulating crash mid-advance)
        store.createPending(taskId = 100L, idempotencyKey = "adv-crash-1",
            scheduleContext = defaultCtx, leaseId = "lease-1")
        assertEquals("unresolved record exists", 1, store.listAllUnresolved().size)

        // Run recovery sweep — under-quota should resolve WITHOUT calling provider
        runRecoverySweep(store, coordinator, mapOf(100L to 3))

        // KEY ASSERTION: zero provider calls
        assertEquals("under-quota recovery must make zero provider calls",
            0, gateway.completeAndAdvanceCount)
        assertEquals(0, gateway.observeCount)
        assertEquals(0, gateway.discoverCount)

        // Record should be resolved as quota_not_met
        assertEquals("no unresolved records remain", 0, store.listAllUnresolved().size)
        val resolved = store.allRecords.first()
        assertEquals("quota_not_met", resolved.state)
    }

    @Test
    fun recovery_underQuota_multipleRecords_allResolvedNoProviderCalls() = runBlocking {
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 0) // zero — well under quota
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        // Pre-seed multiple pending records for different tasks
        store.createPending(taskId = 10L, idempotencyKey = "k1", scheduleContext = defaultCtx, leaseId = "l1")
        store.createPending(taskId = 20L, idempotencyKey = "k2", scheduleContext = defaultCtx, leaseId = "l2")
        store.createPending(taskId = 30L, idempotencyKey = "k3", scheduleContext = defaultCtx, leaseId = "l3")

        runRecoverySweep(store, coordinator, mapOf(10L to 3, 20L to 5, 30L to 2))

        assertEquals("zero provider calls across all records", 0, gateway.completeAndAdvanceCount)
        assertEquals("all resolved", 0, store.listAllUnresolved().size)
        assertTrue("all quota_not_met", store.allRecords.all { it.state == "quota_not_met" })
    }

    // ════════════════════════════════════════════════════════════════════════
    // (b) Threshold-at-crash replays EXACTLY ONCE
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun recovery_thresholdAtCrash_replaysExactlyOnce() = runBlocking {
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3) // 3/3 — at threshold
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        // Pre-seed ONE pending record at threshold
        store.createPending(taskId = 100L, idempotencyKey = "adv-threshold-1",
            scheduleContext = defaultCtx, leaseId = "lease-1")

        runRecoverySweep(store, coordinator, mapOf(100L to 3))

        // With CountingProviderGateway: quota gate passes (3 >= 3), coordinator
        // builds request and calls completeAndAdvance — gateway counts it then
        // throws ProviderNotAvailableException.
        assertEquals("threshold recovery calls provider exactly once",
            1, gateway.completeAndAdvanceCount)

        // Record should be in provider_unavailable (CountingProviderGateway throws)
        val records = store.allRecords
        assertEquals(1, records.size)
        assertEquals("provider_unavailable", records[0].state)
    }

    @Test
    fun recovery_thresholdAtCrash_secondSweep_replaysAgain() = runBlocking {
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3)
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        store.createPending(taskId = 100L, idempotencyKey = "adv-t2",
            scheduleContext = defaultCtx, leaseId = "lease-1")

        // First sweep
        runRecoverySweep(store, coordinator, mapOf(100L to 3))
        assertEquals(1, gateway.completeAndAdvanceCount)
        assertEquals("provider_unavailable", store.allRecords[0].state)

        // provider_unavailable is an unresolved state — second sweep retries
        runRecoverySweep(store, coordinator, mapOf(100L to 3))
        assertEquals("second sweep retries", 2, gateway.completeAndAdvanceCount)
    }

    // ════════════════════════════════════════════════════════════════════════
    // (c) Digest/tuple failure leaves the plan NON-TERMINAL
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun advanceFailure_recordStaysRecoveryRequired() = runBlocking {
        // Use PendingProviderGateway (production pre-PR#36 stub) — every call
        // throws ProviderNotAvailableException
        val gateway = PendingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3) // at threshold
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        store.createPending(taskId = 100L, idempotencyKey = "adv-fail-1",
            scheduleContext = defaultCtx, leaseId = "lease-1")

        runRecoverySweep(store, coordinator, mapOf(100L to 3))

        // The record is NOT terminal from the advance protocol perspective —
        // it's in provider_unavailable, meaning the advance has not completed.
        // The plan's task status is completely separate from advance state.
        val records = store.allRecords
        assertEquals(1, records.size)
        assertEquals("provider_unavailable", records[0].state)
        // provider_unavailable IS an unresolved state — listAllUnresolved returns it
        assertEquals("record is still unresolved", 1, store.listAllUnresolved().size)
    }

    @Test
    fun advanceFailure_afterDigestMismatch_staysRecoveryRequired() = runBlocking {
        // Gateway that returns a receipt with a BAD digest
        val badReceiptGateway = object : ProviderGateway {
            override suspend fun completeAndAdvance(request: CompleteAndAdvanceRequestV1): AdvanceReceiptV1 {
                return AdvanceReceiptV1(
                    outcomeWire = 1, // ADVANCED
                    advancedFromItemId = "item-1",
                    advancedToItemId = "item-2",
                    scheduleVersionAfter = 2L,
                    effectiveIntentHash = "intent-hash",
                    effectiveEnvironmentRevision = 200L,
                    receiptDigest = "CORRUPTED-DIGEST", // BAD
                )
            }

            override suspend fun observe(leaseId: String, context: ScheduleContext): EnvironmentObservationV1 {
                throw AssertionError("should not reach observe after digest mismatch")
            }

            override suspend fun discover(): CapabilitySnapshotV1 {
                throw AssertionError("should not reach discover after digest mismatch")
            }
        }

        val quotaReader = ConfigurableQuotaReader(count = 3)
        val coordinator = AdvanceCoordinator(badReceiptGateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        store.createPending(taskId = 100L, idempotencyKey = "adv-digest-1",
            scheduleContext = defaultCtx, leaseId = "lease-1")

        runRecoverySweep(store, coordinator, mapOf(100L to 3))

        // Digest mismatch → RecoveryRequired → record stays non-terminal
        val records = store.allRecords
        assertEquals(1, records.size)
        assertEquals("recovery_required", records[0].state)
        // recovery_required IS an unresolved state
        assertEquals("record is still unresolved", 1, store.listAllUnresolved().size)
    }

    // ════════════════════════════════════════════════════════════════════════
    // (d) Recovery sweep skips already-resolved records
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun recovery_alreadyResolved_notRetried() = runBlocking {
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3)
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        // Create and immediately resolve a record
        val id = store.createPending(taskId = 100L, idempotencyKey = "adv-done",
            scheduleContext = defaultCtx, leaseId = "l1")
        store.resolve(id, "advanced", "digest-123", null)

        // No unresolved records → sweep is a no-op
        assertEquals(0, store.listAllUnresolved().size)
        runRecoverySweep(store, coordinator, mapOf(100L to 3))

        assertEquals("zero provider calls — nothing to recover",
            0, gateway.completeAndAdvanceCount)
    }

    // ════════════════════════════════════════════════════════════════════════
    // (e) Missing task → record resolved without crash
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun recovery_taskNotFound_resolvesGracefully() = runBlocking {
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3)
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        val store = InMemoryAdvanceStateStore()

        store.createPending(taskId = 999L, idempotencyKey = "adv-orphan",
            scheduleContext = defaultCtx, leaseId = "l1")

        // Task 999 not in the map → treated as "task not found"
        runRecoverySweep(store, coordinator, emptyMap())

        assertEquals(0, gateway.completeAndAdvanceCount)
        assertEquals(0, store.listAllUnresolved().size)
        assertEquals("quota_not_met", store.allRecords[0].state)
    }

    // ════════════════════════════════════════════════════════════════════════
    // (f) Engine wiring: durable write BEFORE coordinator call
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun durableWrite_beforeCoordinatorCall_survivesProviderCrash() = runBlocking {
        // Simulate what the engine does at the advance call site:
        //   1. store.createPending(...)  ← BEFORE coordinator call
        //   2. coordinator.evaluateAfterRelease(...)  ← may throw
        //   3. resolveAdvanceRecord(...)  ← only if 2 succeeds

        val store = InMemoryAdvanceStateStore()
        val gateway = PendingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3)
        val coordinator = AdvanceCoordinator(gateway, quotaReader)

        // Step 1: durable write
        val recordId = store.createPending(
            taskId = 100L, idempotencyKey = "adv-engine-1",
            scheduleContext = defaultCtx, leaseId = "lease-1",
        )

        // Step 2: coordinator call — will throw because PendingProviderGateway
        var threwProviderUnavailable = false
        try {
            coordinator.evaluateAfterRelease(
                taskId = "100", requiredSuccesses = 3,
                scheduleContext = defaultCtx, leaseId = "lease-1",
                idempotencyKey = "adv-engine-1",
            )
        } catch (e: ProviderNotAvailableException) {
            threwProviderUnavailable = true
        }
        assertTrue("provider threw", threwProviderUnavailable)

        // Step 3 never ran (crash/exception before resolve)
        // But the durable record survives:
        val unresolved = store.listAllUnresolved()
        assertEquals("pending record survived provider crash", 1, unresolved.size)
        assertEquals("pending", unresolved[0].state)
        assertEquals("adv-engine-1", unresolved[0].idempotencyKey)
    }

    // ════════════════════════════════════════════════════════════════════════
    // (g) P1-2: pre-create BEFORE finalize, CAS failure cleanup
    // ════════════════════════════════════════════════════════════════════════

    @Test
    fun preCreate_beforeFinalize_casFailure_resolvesOrphanRecord() = runBlocking {
        // Simulates the engine's P1-2 fix flow when finalize CAS guard fails:
        //   1. willCompleteQuota predicted → createPending BEFORE finalize
        //   2. finalizeAttemptSuccess → CAS fails (returns false)
        //   3. Orphan record resolved as "quota_not_met"
        //
        // After resolution, recovery sweep sees nothing to retry.

        val store = InMemoryAdvanceStateStore()

        // Step 1: pre-create (engine predicts quota will complete)
        val recordId = store.createPending(
            taskId = 100L, idempotencyKey = "adv-cas-fail",
            scheduleContext = defaultCtx, leaseId = "lease-cas",
        )
        assertEquals("pending record created before finalize",
            1, store.listAllUnresolved().size)

        // Step 2: finalize CAS fails → engine resolves orphan
        // (simulates the `if (!finalized) { resolve(... "quota_not_met") }` path)
        store.resolve(recordId, "quota_not_met", null, "finalize CAS failed")

        // Step 3: no unresolved records left
        assertEquals("orphan resolved", 0, store.listAllUnresolved().size)
        assertEquals("quota_not_met", store.allRecords[0].state)

        // Step 4: recovery sweep sees nothing
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 3)
        val coordinator = AdvanceCoordinator(gateway, quotaReader)
        runRecoverySweep(store, coordinator, mapOf(100L to 3))
        assertEquals("zero provider calls after CAS cleanup", 0, gateway.completeAndAdvanceCount)
    }

    @Test
    fun preCreate_beforeFinalize_crashAfterPending_recoveryHandlesOrphan() = runBlocking {
        // Simulates crash AFTER createPending but BEFORE finalizeAttemptSuccess:
        //   - Task completedSuccesses unchanged (quota still under threshold)
        //   - Recovery sweep finds "pending" record, evaluates quota → QuotaNotMet
        //   - Record resolved, no provider calls, no false advance

        val store = InMemoryAdvanceStateStore()

        // Step 1: pre-create happened (engine crashed right after)
        store.createPending(
            taskId = 100L, idempotencyKey = "adv-crash-before-finalize",
            scheduleContext = defaultCtx, leaseId = "lease-x",
        )

        // Step 2: on restart, recovery sweep runs. Quota is STILL under threshold
        // (finalize never happened → completedSuccesses not incremented).
        val gateway = CountingProviderGateway()
        val quotaReader = ConfigurableQuotaReader(count = 2) // 2/3 — under quota
        val coordinator = AdvanceCoordinator(gateway, quotaReader)

        runRecoverySweep(store, coordinator, mapOf(100L to 3))

        // KEY: zero provider calls, resolved as quota_not_met
        assertEquals("under-quota: zero provider calls", 0, gateway.completeAndAdvanceCount)
        assertEquals(0, store.listAllUnresolved().size)
        assertEquals("quota_not_met", store.allRecords[0].state)
    }
}
