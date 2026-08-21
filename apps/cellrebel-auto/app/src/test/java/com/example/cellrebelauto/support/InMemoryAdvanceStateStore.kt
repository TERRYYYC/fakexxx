package com.example.cellrebelauto.support

import com.example.cellrebelauto.automation.advance.AdvanceStateStore
import com.example.cellrebelauto.automation.advance.PendingAdvance
import com.example.cellrebelauto.automation.advance.ScheduleContext

/**
 * In-memory [AdvanceStateStore] for tests.
 *
 * Mirrors what Room would do: durable across "crash" (which in tests is just
 * "don't reset this object between calls"). The test creates one instance,
 * writes advance state, simulates a crash by creating a NEW coordinator/engine
 * but keeping this store, and verifies the recovery sweep reads the
 * persisted records.
 */
class InMemoryAdvanceStateStore : AdvanceStateStore {
    private var nextId = 1L
    private val records = mutableMapOf<Long, MutablePendingAdvance>()

    /** Expose all records for test assertions. */
    val allRecords: List<PendingAdvance>
        get() = records.values.map { it.toPendingAdvance() }

    override suspend fun createPending(
        taskId: Long,
        idempotencyKey: String,
        scheduleContext: ScheduleContext,
        leaseId: String,
    ): Long {
        val id = nextId++
        records[id] = MutablePendingAdvance(
            id = id,
            taskId = taskId,
            idempotencyKey = idempotencyKey,
            scheduleContext = scheduleContext,
            leaseId = leaseId,
            state = "pending",
        )
        return id
    }

    override suspend fun getUnresolved(taskId: Long): PendingAdvance? =
        records.values
            .filter { it.taskId == taskId && it.state in UNRESOLVED_STATES }
            .maxByOrNull { it.id }
            ?.toPendingAdvance()

    override suspend fun resolve(
        id: Long,
        outcome: String,
        receiptDigest: String?,
        reason: String?,
    ) {
        records[id]?.let {
            it.state = outcome
            it.receiptDigest = receiptDigest
            it.reason = reason
        }
    }

    override suspend fun listAllUnresolved(): List<PendingAdvance> =
        records.values
            .filter { it.state in UNRESOLVED_STATES }
            .map { it.toPendingAdvance() }

    private data class MutablePendingAdvance(
        val id: Long,
        val taskId: Long,
        val idempotencyKey: String,
        val scheduleContext: ScheduleContext,
        val leaseId: String,
        var state: String,
        var receiptDigest: String? = null,
        var reason: String? = null,
    ) {
        fun toPendingAdvance() = PendingAdvance(id, taskId, idempotencyKey, scheduleContext, leaseId, state)
    }

    companion object {
        // provider_unavailable is TERMINAL (R5 P1-1): synthetic identity must
        // not be replayed when a real gateway arrives.
        private val UNRESOLVED_STATES = setOf("pending", "recovery_required")
    }
}
