package com.example.cellrebelauto.automation.advance

/**
 * Durable store for advance protocol state (§8.1 state machine edges).
 *
 * Each advance attempt creates a [PendingAdvance] record BEFORE calling the
 * coordinator. The record's lifecycle:
 *
 *   create(pending) → evaluateAfterRelease → resolve(outcome)
 *
 * If the process crashes between create and resolve, the record survives in
 * "pending" state. The recovery sweep at engine start replays these records
 * with the persisted idempotency key — the provider's idempotency store
 * ensures at-most-once execution.
 *
 * Production: Room entity ([AdvanceRecord] + [AdvanceRecordDao]).
 * Tests: [InMemoryAdvanceStateStore].
 */
interface AdvanceStateStore {
    /** Create a pending advance record. Returns the record's durable ID. */
    suspend fun createPending(
        taskId: Long,
        idempotencyKey: String,
        scheduleContext: ScheduleContext,
        leaseId: String,
    ): Long

    /** Find the latest unresolved (pending or recovery_required) advance for a task. */
    suspend fun getUnresolved(taskId: Long): PendingAdvance?

    /** Resolve a record with a terminal outcome. */
    suspend fun resolve(id: Long, outcome: String, receiptDigest: String?, reason: String?)

    /** List ALL unresolved records (for recovery sweep). */
    suspend fun listAllUnresolved(): List<PendingAdvance>
}

/**
 * In-flight advance record. The `state` field tracks the §8.1 edge:
 *
 * - `"pending"`: created, coordinator not yet called (or crashed mid-call)
 * - `"quota_not_met"`: coordinator returned QuotaNotMet (terminal, no advance)
 * - `"advanced"`: non-terminal advance independently verified (terminal)
 * - `"exhausted"`: terminal advance independently verified (terminal)
 * - `"recovery_required"`: digest/tuple mismatch detected (non-terminal — must retry)
 * - `"provider_unavailable"`: provider structurally absent (TERMINAL — R5 P1-1).
 *   Records with synthesized identity (pre-PR#36) must not be replayed
 *   when a real gateway arrives. New sessions create fresh records.
 */
data class PendingAdvance(
    val id: Long,
    val taskId: Long,
    val idempotencyKey: String,
    val scheduleContext: ScheduleContext,
    val leaseId: String,
    val state: String,
)
