package com.example.cellrebelauto.recovery

/**
 * Reconciles an attempt left non-terminal after a crash or an interrupted external call (§8.1
 * RECOVERY_REQUIRED, §10 crash/recovery matrix), and answers the schedule-advance consumer gate
 * (Issue #5 addendum / §5 boundary).
 *
 * The coordinator is the ONLY place that decides WHEN to drive the [ExternalApplyExecutor] and WHEN
 * to record a receipt on the [DurableRecoveryLog]. Keeping those two seams separate (Sol round-3
 * Finding 2) is what makes the three crash windows distinguishable and defeats a "false oracle" GREEN
 * that never calls a provider:
 *
 *  - [reconcile] GREEN orchestration:
 *    1. `receiptFor(key)` with the SAME digest ⇒ **REPLAYED_APPLY** — do NOT call the executor
 *       (at-most-once; the receipt already proves the apply);
 *    2. `receiptFor(key)` with a DIFFERENT digest ⇒ **IDEMPOTENCY_CONFLICT** — do NOT call the
 *       executor, prior receipt preserved (INV-13);
 *    3. no receipt ⇒ `executor.apply(...)` (the external call) → `recordReceipt(...)` →
 *       `recordCheckpoint(...)` ⇒ **ADVANCED_TO_RELEASE**.
 *    Across a crash the executor MAY be called twice; the PROVIDER's idempotency keeps the EFFECT at
 *    one (M-CR-02 window (b) recovery). A coordinator that returns ADVANCED without calling the
 *    executor fails the tests' provider-effect / invocation-count assertions.
 *
 * PRE-FREEZE SKELETON (RED): [reconcile] always returns [ReconcileOutcome.INSUFFICIENT_EVIDENCE] and
 * [scheduleAdvanced] always returns [ScheduleAdvanceState.NOT_ADVANCED] — both IGNORE the injected
 * executor and log. Tests assert DURABLE EFFECTS (provider effect count, invocation count, receipt
 * presence) through the seams, so a constant-return skeleton cannot pass them.
 *
 * # 恢复协调器骨架（RED）：恒 INSUFFICIENT / NOT_ADVANCED，忽略 executor+log；GREEN 编排执行器与 receipt
 */
class RecoveryCoordinator(
    private val executor: ExternalApplyExecutor,
    private val log: DurableRecoveryLog
) {

    /**
     * Reconcile a non-terminal attempt after crash/restart. RED: always [ReconcileOutcome.INSUFFICIENT_EVIDENCE],
     * ignoring [executor] and [log] entirely.
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
     * advanced (§5 boundary / Issue #5 addendum). ADVANCED requires ALL of: a durable receipt for
     * [idempotencyKey], [intentRevisionMatches] (independent observe() agrees), [receiptRevisionIsStale]
     * false, and [quotaExhausted] false — so a `receipt≠null ∧ boolean` impl that ignores stale /
     * exhausted cannot pass the negatives. RED: always [ScheduleAdvanceState.NOT_ADVANCED], ignoring [log].
     *
     * @param intentRevisionMatches true iff an independent observe() matches the effective
     *        intent / revision the receipt claims to have advanced past.
     * @param receiptRevisionIsStale true iff the receipt's revision is stale relative to the live
     *        schedule (a stale receipt must NOT advance).
     * @param quotaExhausted true iff the task's trusted quota is already complete (an exhausted task
     *        must NOT advance again).
     */
    fun scheduleAdvanced(
        attemptId: Long,
        idempotencyKey: String,
        intentRevisionMatches: Boolean,
        receiptRevisionIsStale: Boolean,
        quotaExhausted: Boolean,
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
