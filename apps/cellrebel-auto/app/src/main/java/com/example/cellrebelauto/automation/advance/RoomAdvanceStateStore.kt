package com.example.cellrebelauto.automation.advance

import com.example.cellrebelauto.db.AdvanceRecordDao
import com.example.cellrebelauto.model.plan.AdvanceRecord

/**
 * Production [AdvanceStateStore] backed by Room via [AdvanceRecordDao].
 *
 * Flattens [ScheduleContext] into individual columns on write, and
 * reconstructs it on read. This avoids Room TypeConverters and keeps
 * the migration SQL trivial.
 *
 * # 生产用推进状态存储，由 Room DAO 提供持久化。
 * # ScheduleContext 拍平存取，避免 TypeConverter。
 */
class RoomAdvanceStateStore(
    private val dao: AdvanceRecordDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : AdvanceStateStore {

    override suspend fun createPending(
        taskId: Long,
        idempotencyKey: String,
        scheduleContext: ScheduleContext,
        leaseId: String,
    ): Long = dao.insert(
        AdvanceRecord(
            taskId = taskId,
            idempotencyKey = idempotencyKey,
            scheduleId = scheduleContext.scheduleId,
            currentItemId = scheduleContext.currentItemId,
            scheduleVersion = scheduleContext.scheduleVersion,
            ledgerRef = scheduleContext.ledgerRef,
            verifiedAtElapsedRealtimeMs = scheduleContext.verifiedAtElapsedRealtimeMs,
            leaseId = leaseId,
            state = "pending",
            createdAt = nowMs(),
        ),
    )

    override suspend fun getUnresolved(taskId: Long): PendingAdvance? =
        dao.getUnresolved(taskId)?.toPendingAdvance()

    override suspend fun resolve(
        id: Long,
        outcome: String,
        receiptDigest: String?,
        reason: String?,
    ) {
        dao.resolve(id, outcome, receiptDigest, reason)
    }

    override suspend fun listAllUnresolved(): List<PendingAdvance> =
        dao.listAllUnresolved().map { it.toPendingAdvance() }
}

/** Map Room entity → domain model, reconstructing the flattened ScheduleContext. */
private fun AdvanceRecord.toPendingAdvance() = PendingAdvance(
    id = id,
    taskId = taskId,
    idempotencyKey = idempotencyKey,
    scheduleContext = ScheduleContext(
        scheduleId = scheduleId,
        currentItemId = currentItemId,
        scheduleVersion = scheduleVersion,
        ledgerRef = ledgerRef,
        verifiedAtElapsedRealtimeMs = verifiedAtElapsedRealtimeMs,
    ),
    leaseId = leaseId,
    state = state,
)
