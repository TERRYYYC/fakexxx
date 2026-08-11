package com.example.cellrebelauto.recovery

/**
 * Reconciles an attempt left in a non-terminal state after a crash or an interrupted external
 * call (§8.1 RECOVERY_REQUIRED, §10 crash/recovery matrix), and answers the schedule-advance
 * consumer gate (Issue #5 addendum / §5 boundary).
 *
 * PRE-FREEZE SKELETON (RED): [reconcile] always returns [ReconcileOutcome.INSUFFICIENT_EVIDENCE]
 * and [scheduleAdvanced] always returns [ScheduleAdvanceState.NOT_ADVANCED] — both IGNORE the
 * injected [log]. GREEN implements:
 *  - [reconcile]: read [DurableRecoveryLog.receiptFor]; call [DurableRecoveryLog.recordApply] with
 *    the same idempotency key + canonical digest; map result — fresh apply ⇒ ADVANCED_TO_RELEASE,
 *    same-key/same-digest ⇒ REPLAYED_APPLY, same-key/different-digest ⇒ IDEMPOTENCY_CONFLICT
 *    (INV-13); record a checkpoint; mismatch / stale-version / exhausted never skips an item or
 *    duplicates trusted-quota / local effects (INV-15).
 *  - [scheduleAdvanced]: ADVANCED only when a durable receipt exists for [idempotencyKey] AND an
 *    independent observe() matches the effective intent/revision ([intentRevisionMatches]); without
 *    a receipt Auto never assumes the schedule advanced.
 *
 * Tests inject a seeded [DurableRecoveryLog] and assert crash-window effects (apply count, receipt
 * presence, conflict) rather than the stub's return value — so a constant-return implementation
 * that ignores the log cannot pass (the durable effect assertions stay red).
 *
 * # 恢复协调器骨架（RED）：恒 INSUFFICIENT / NOT_ADVANCED 且忽略 log；真实 reconcile 是 GREEN
 */
class RecoveryCoordinator(private val log: DurableRecoveryLog) {

    /**
     * Reconcile a non-terminal attempt after crash/restart. RED: always INSUFFICIENT_EVIDENCE,
     * ignoring [log] entirely.
     *
     * @param idempotencyKey the frozen idempotency key for this attempt's apply (INV-13).
     * @param requestDigest the §6.3.4 canonical digest of the apply request (NOT the result digest).
     */
    fun reconcile(
        attemptId: Long,
        idempotencyKey: String,
        requestDigest: String,
        now: Long
    ): ReconcileOutcome = ReconcileOutcome.INSUFFICIENT_EVIDENCE

    /**
     * Schedule-advance consumer gate. Without a durable receipt Auto never assumes the schedule
     * advanced (§5 boundary / Issue #5 addendum). RED: always NOT_ADVANCED, ignoring [log].
     *
     * @param intentRevisionMatches true iff an independent observe() matches the effective
     *        intent / revision that the receipt claims to have advanced past.
     */
    fun scheduleAdvanced(
        attemptId: Long,
        idempotencyKey: String,
        intentRevisionMatches: Boolean,
        now: Long
    ): ScheduleAdvanceState = ScheduleAdvanceState.NOT_ADVANCED
}

enum class ReconcileOutcome {
    ADVANCED_TO_RELEASE,
    REPLAYED_APPLY,
    /** Same idempotency key, different canonical request digest (INV-13). */
    IDEMPOTENCY_CONFLICT,
    INSUFFICIENT_EVIDENCE
}

enum class ScheduleAdvanceState { ADVANCED, NOT_ADVANCED }
