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
     * Schedule-advance consumer gate (Sol round-4 §11.2 F2). Without a durable receipt Auto never
     * assumes the schedule advanced (§5 boundary / Issue #5 addendum). ADVANCED requires ALL of: a
     * durable receipt for [idempotencyKey] AND three facts that the gate ACQUIRES INSIDE this method
     * (never caller-supplied): [observe] (the independent live observation matches the intent the
     * receipt claims to have advanced past), [receiptRevision] (the receipt's revision is still fresh
     * vs the live schedule), and [trustedQuota] (the task's trusted quota still has capacity).
     *
     * Why inject acquirers instead of booleans: the round-3 signature passed three caller-supplied
     * booleans, which a `receipt≠null ∧ (a ∧ b ∧ c)` impl can satisfy by ANDing whatever the test
     * passed — the facts are never independently observed, so the gate is a false oracle the test can
     * only assert the branching of, not the acquisition. By moving the acquisition inside the gate, the
     * GREEN body MUST call each acquirer to learn the fact, and the test asserts (via recording fakes)
     * that the calls happened AND that each negative fact independently gates the decision (§11.2). A
     * hardcode-ADVANCED bad impl that ignores the acquirers is caught twice: the ADVANCED case asserts
     * acquirer calls (zero ⇒ fail), and the three negatives assert NOT_ADVANCED (hardcode ⇒ ADVANCED ⇒ fail).
     *
     * PRE-FREEZE SKELETON (§11.4 F2 — GREEN body frozen pending contract-v1 freeze #3): always returns
     * [ScheduleAdvanceState.NOT_ADVANCED], IGNORING [log] and ALL THREE acquirers. The recording-fake
     * acquirers register zero calls under the skeleton, so the §6.4-positive fixture (durable receipt +
     * all three facts confirming) stays RED: the ADVANCED assertion fails, and (dormant behind it) the
     * acquirer-call assertion would also fail. GREEN reads the receipt, calls all three acquirers, ANDs
     * the acquired facts, ADVANCEs, and records a window-c checkpoint bound to the receipt.
     *
     * @param observe acquires whether the independent live observation matches the receipt's intent.
     * @param receiptRevision acquires whether the receipt's revision is fresh (not stale).
     * @param trustedQuota acquires whether the task's trusted quota still has capacity (not exhausted).
     */
    fun scheduleAdvanced(
        attemptId: Long,
        idempotencyKey: String,
        now: Long,
        observe: ObserveIntentAcquirer,
        receiptRevision: ReceiptRevisionAcquirer,
        trustedQuota: TrustedQuotaAcquirer
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

/**
 * Acquires (internally, never caller-supplied) whether the independent live observation matches the
 * intent revision the receipt claims to have advanced past (Sol round-4 §11.2 F2). Injected into
 * [RecoveryCoordinator.scheduleAdvanced] so the gate cannot be fooled by a caller-supplied boolean.
 *
 * # 观察匹配获取器：内部获取"独立观察是否匹配 receipt 声称的 intent"，杜绝调用方布尔注入
 */
fun interface ObserveIntentAcquirer {
    /** @return true iff the live observation matches the receipt's claimed intent. */
    fun matches(): Boolean
}

/**
 * Acquires whether the receipt's revision is still fresh relative to the live schedule (§11.2 F2).
 *
 * # receipt 版本新鲜度获取器：receipt 版本相对当前 schedule 是否仍新鲜
 */
fun interface ReceiptRevisionAcquirer {
    /** @return true iff the receipt's revision is fresh (not stale). */
    fun isFresh(): Boolean
}

/**
 * Acquires whether the task's trusted quota still has capacity (is NOT yet exhausted) (§11.2 F2).
 *
 * # 可信配额容量获取器：任务的可信配额是否仍有余量（未满）
 */
fun interface TrustedQuotaAcquirer {
    /** @return true iff the task's trusted quota still has capacity. */
    fun hasCapacity(): Boolean
}
