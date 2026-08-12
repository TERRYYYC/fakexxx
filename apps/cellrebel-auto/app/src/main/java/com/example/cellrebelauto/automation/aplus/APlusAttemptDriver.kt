package com.example.cellrebelauto.automation.aplus

import com.example.cellrebelauto.db.AuditEventDao

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
 * Green-wiring boundary: under GREEN the engine/coordinator drives an attempt through this entry at each
 * §8.1 lifecycle step (the GREEN body, frozen pre-freeze). The RED proves the table is reachable ONLY via
 * a persisting entry; the engine-loop wiring itself is GREEN body, complementary to R5-F1/F3 (does the
 * engine drive TRUSTED completion) — not duplicated here.
 *
 * PRE-FREEZE SKELETON (RED): [driveTransition] returns [current] UNCHANGED and appends NO audit event —
 * it neither consults [AttemptTransitions] nor touches the audit stream. GREEN body frozen pending the
 * contract-v1 freeze (no GREEN authorization pre-freeze).
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
     * RED skeleton: returns [current] and appends nothing (no [AttemptTransitions] consult, no audit).
     *
     * @return the resulting [AttemptState]; under the skeleton this is [current] unchanged.
     */
    suspend fun driveTransition(
        attemptId: Long,
        current: AttemptState,
        event: AttemptEvent
    ): AttemptState = current
}
