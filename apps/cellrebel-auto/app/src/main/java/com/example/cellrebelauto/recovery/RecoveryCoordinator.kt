package com.example.cellrebelauto.recovery

/**
 * Reconciles an attempt left in a non-terminal state after a crash or an interrupted external
 * call (§8.1 RECOVERY_REQUIRED, §10 crash/recovery matrix), and answers the schedule-advance
 * consumer gate.
 *
 * PRE-FREEZE SKELETON (RED): [reconcile] always returns [ReconcileOutcome.INSUFFICIENT_EVIDENCE]
 * and [scheduleAdvanced] always returns [ScheduleAdvanceState.NOT_ADVANCED]. GREEN implements:
 * replay the same idempotency key + canonical digest, reconcile the durable receipt (INV-13);
 * same-key+same-digest replays are idempotent, same-key+different-digest ⇒ conflict; mismatch /
 * stale-version / exhausted cannot skip an item or duplicate trusted-quota / local effects;
 * after a successful advance receipt Auto independently observes and matches the effective
 * intent/revision before the next attempt. Tests expecting ADVANCED / REPLAYED_APPLY therefore
 * FAIL until GREEN.
 *
 * # 恢复协调器骨架（RED）：恒 INSUFFICIENT / NOT_ADVANCED；真实 reconcile 是 GREEN
 */
class RecoveryCoordinator {

    /** Reconcile a non-terminal attempt after crash/restart. RED: always insufficient. */
    fun reconcile(attemptId: Long): ReconcileOutcome = ReconcileOutcome.INSUFFICIENT_EVIDENCE

    /**
     * Schedule-advance consumer gate. Without a durable receipt Auto never assumes the schedule
     * advanced (§5 boundary / Issue #5 addendum). RED: always NOT_ADVANCED regardless of inputs.
     *
     * @param hasDurableReceipt true iff Auto holds a durable advance receipt for this attempt
     * @param intentRevisionMatches true iff an independent observe() matches the effective
     *        intent / revision that the receipt claims to have advanced past
     */
    fun scheduleAdvanced(
        attemptId: Long,
        hasDurableReceipt: Boolean,
        intentRevisionMatches: Boolean
    ): ScheduleAdvanceState = ScheduleAdvanceState.NOT_ADVANCED
}

enum class ReconcileOutcome { ADVANCED_TO_RELEASE, REPLAYED_APPLY, INSUFFICIENT_EVIDENCE }
enum class ScheduleAdvanceState { ADVANCED, NOT_ADVANCED }
