package com.example.cellrebelauto.automation.aplus

import com.example.cellrebelauto.db.AuditEventDao
import com.example.cellrebelauto.model.audit.AutoAuditEvent

/**
 * The production call site that wires the frozen §8.1 state machine ([AttemptTransitions] /
 * [APlusRunTemplate]) into a DURABLE effect — appending the at-most-once audit stream
 * ([AuditEventDao], §7.1). Issue #5 R5-F4 (§11.7).
 *
 * WHY THIS EXISTS. Sol's round-4 combined attack implemented the full §8.1 table inside
 * [AttemptTransitions.next] with NO production caller: every [AttemptTransitionsRedTest] case and the
 * AREA-5c canonical-path `fold` greened, yet the table was never consulted by any code that persists
 * anything — so the engine could keep driving the legacy counter path while the §8.1 machine sat dead.
 * That is the "no-call-site data-table oracle". This driver is the missing call site: in GREEN,
 * [driveTransition] consults [AttemptTransitions.next] to compute the resulting state AND appends an
 * audit event ([AuditEventDao]) bound to the real attempt, so a correct-but-unwired table stays RED.
 *
 * The R5-F4 RED (see `APlusAttemptDriverRedTest`) drives BOTH a single transition and the full frozen
 * canonical happy path THROUGH this production entry — never through [AttemptTransitions.next] directly —
 * and asserts (a) the advanced/terminal state (the table was consulted) AND (b) one durable audit row
 * per transition bound to the real attempt id (the durable effect). A table with no persisting caller
 * fails (a) under the skeleton and (b) under any table-only attack.
 *
 * # §8.1 状态机的生产调用点（R5-F4）：把冻结状态机接入持久审计流，杜绝"实现完整但无调用点的数据表 oracle"
 */
class APlusAttemptDriver(
    private val auditDao: AuditEventDao,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    /**
     * Drive ONE §8.1 transition for [attemptId] from [current] under [event]: consult the frozen state
     * machine ([AttemptTransitions.next]), append a durable audit event recording the transition bound
     * to [attemptId], and return the resulting state.
     *
     * GREEN (contract v1 frozen): the audit row is appended for EVERY driven transition (including
     * no-op transitions — the audit stream records that the event was seen; at-most-once per call),
     * with a monotonic `seq` derived from the stream length + 1 (single-writer per attempt; the
     * engine serializes transitions per attempt).
     *
     * @return the resulting [AttemptState] (unchanged [current] when the (state, event) tuple is a
     *   no-op per §8.1).
     */
    suspend fun driveTransition(
        attemptId: Long,
        current: AttemptState,
        event: AttemptEvent
    ): AttemptState {
        require(event != AttemptEvent.RELEASE_RECEIPT) {
            "RELEASE_RECEIPT requires an authoritative ReleaseReceiptRoute"
        }
        val next = AttemptTransitions.next(current, event)
        appendAudit(attemptId, current, event, next)
        return next
    }

    /** Drive the conditional §8.1 release-receipt edge without losing its authoritative route. */
    suspend fun driveReleaseReceipt(
        attemptId: Long,
        current: AttemptState,
        route: ReleaseReceiptRoute
    ): AttemptState {
        val next = AttemptTransitions.nextAfterReleaseReceipt(current, route)
        appendAudit(attemptId, current, AttemptEvent.RELEASE_RECEIPT, next, route)
        return next
    }

    private suspend fun appendAudit(
        attemptId: Long,
        current: AttemptState,
        event: AttemptEvent,
        next: AttemptState,
        releaseRoute: ReleaseReceiptRoute? = null
    ) {
        val seq = auditDao.count().toLong() + 1
        auditDao.insert(
            AutoAuditEvent(
                seq = seq,
                attemptId = attemptId,
                correlationRef = null,
                eventType = event.name,
                payloadDigest = buildString {
                    append("$current->$next")
                    releaseRoute?.let { append("[$it]") }
                },
                recordedAt = nowMs()
            )
        )
    }
}
