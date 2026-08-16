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
    private val log: DurableRecoveryLog,
    // R6-F2（§11.7）：三个 reader 是协调器**构造期拥有**的依赖，不再逐调用注入。
    // scheduleAdvanced 只接受协调器拥有的 (attemptId, key, now)；GREEN 用真实身份调用每个 reader
    // 取事实再 AND。默认 no-op 仅服务于不驱动 scheduleAdvanced 的构造（如 reconcile 测试）——
    // scheduleAdvanced 的生产/测试构造必传真实 reader。这彻底消除了 R5 残留的"闭包答案仍由调用方注入"。
    private val observe: ObserveIntentAcquirer = ObserveIntentAcquirer { _ -> false },
    private val receiptRevision: ReceiptRevisionAcquirer = ReceiptRevisionAcquirer { _, _ -> false },
    private val trustedQuota: TrustedQuotaAcquirer = TrustedQuotaAcquirer { _ -> false }
) {

    /** Number of [reconcile] invocations — the engine must NOT reconcile a non-APPLY_PENDING phase
     *  (Sol round-23 P1-3): an illegal reconcile is observable through this counter. */
    var reconcileInvocationCount = 0
        private set

    /**
     * Reconcile a non-terminal attempt after crash/restart (§8.1 RECOVERY_REQUIRED). Returns a TYPED
     * result carrying the durable apply receipt + provider lease the engine MUST persist — the lease is
     * NOT invented by the caller; it comes back from the (idempotent) apply (Sol round-9 P1-3: the
     * M-CR-02 window has no pre-existing lease, the replay yields it).
     *
     * GREEN orchestration (contract v1 frozen):
     *  1. `receiptFor(key)` with the SAME digest ⇒ **REPLAYED_APPLY** — do NOT call the executor
     *     (at-most-once; the receipt already proves the apply); repair the window-c checkpoint if
     *     missing (R5-F2: bind it to the receipt key).
     *  2. `receiptFor(key)` with a DIFFERENT digest ⇒ **IDEMPOTENCY_CONFLICT** — do NOT call the
     *     executor, prior receipt preserved (INV-13).
     *  3. no receipt ⇒ `executor.apply(...)` (the external call) → `recordReceipt(...)` →
     *     `recordCheckpoint(...)` ⇒ **ADVANCED_TO_RELEASE** with the fresh receipt + lease.
     *  Across a crash the executor MAY be called twice; the PROVIDER's idempotency keeps the EFFECT at
     *  one (M-CR-02 window (b) recovery).
     *
     * @param idempotencyKey the frozen idempotency key for this attempt's apply (INV-13).
     * @param requestDigest the §6.3.4 canonical digest of the apply request (NOT the result digest).
     */
    fun reconcile(
        attemptId: Long,
        idempotencyKey: String,
        requestDigest: String,
        now: Long
    ): ReconcileResult {
        reconcileInvocationCount++
        val prior = log.receiptFor(idempotencyKey)
        if (prior != null) {
            if (prior.requestDigest != requestDigest) {
                // Same key + different canonical digest ⇒ INV-13 conflict; NO executor call, prior preserved.
                return ReconcileResult.IdempotencyConflict
            }
            // Same key + same digest ⇒ idempotent replay: NO executor call (at-most-once). Repair the
            // window-c checkpoint if the prior process crashed after the receipt but before it (R5-F2).
            log.recordCheckpoint(attemptId, "RECONCILED_REPLAY", prior.idempotencyKey, now)
            return ReconcileResult.ReplayedApply(prior, prior.leaseId)
        }
        // No durable receipt ⇒ M-CR-02 window (b): re-invoke the executor (the provider idempotently
        // no-ops if it already applied), record the receipt + checkpoint, advance with the fresh lease.
        val outcome = executor.apply(attemptId, idempotencyKey, requestDigest, now)
        if (outcome.leaseId == null) {
            // A fail-closed executor yields no lease — the apply is not proven; fail closed.
            return ReconcileResult.InsufficientEvidence
        }
        // R44 (Sol GREEN-review-3 F3): the reconcile-path receipt also carries the verbatim
        // ApplyReceiptV1 proof fields — the M-CR-02 window-(b) idempotent re-apply returns them
        // from the provider, and the durable §7.1 OperationReceipt must not drop them on write.
        val receipt = log.recordReceipt(
            idempotencyKey, requestDigest, outcome.outcome, now, outcome.leaseId,
            operationId = outcome.operationId,
            acceptedIntentHash = outcome.acceptedIntentHash,
            appliedAtEpochMs = outcome.appliedAtEpochMs,
            environmentRevision = outcome.environmentRevision,
            verificationLevelWire = outcome.verificationLevelWire
        ) ?: return ReconcileResult.InsufficientEvidence // receipt not durable ⇒ apply unproven
        log.recordCheckpoint(attemptId, "ADVANCED_TO_RELEASE", receipt.idempotencyKey, now)
        return ReconcileResult.AdvancedToRelease(receipt, outcome.leaseId!!)
    }

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
     * R6-F2 ownership rewrite (§11.7): Sol's round-5 verdict — R5's identity-bearing acquirers were STILL
     * per-call params, so a combined attack relayed caller-supplied closures whose ANSWER the gate ANDed
     * without the acquisition being bound to anything the coordinator owns. The readers are now
     * **coordinator-owned constructor dependencies**; [scheduleAdvanced] accepts only the coordinator-owned
     * (attemptId, key, now). The GREEN body forwards those REAL owned values into each constructor-owned
     * reader to acquire each fact, then ANDs. The test seeds **identity-keyed** fakes (a Map from the real
     * identity → fact) so the fact is LOOKED UP by identity, not caller-supplied: forwarding the real
     * identity yields the seeded fact; forwarding a wrong/garbage identity yields the default (false) and
     * the result flips. This defeats the caller-injected-answer oracle with no residual.
     *
     * PRE-FREEZE SKELETON: always returns [ScheduleAdvanceState.NOT_ADVANCED], IGNORING [log] and ALL
     * THREE constructor-owned readers (zero reader calls under skeleton). The §6.4-positive fixture
     * (durable receipt + all three facts seeded true for the real identity) stays RED: the ADVANCED
     * assertion fails, and the reader-call/identity assertions fail too. GREEN reads the receipt, calls
     * all three readers with the real owned identity, ANDs the acquired facts, ADVANCEs, and records a
     * window-c checkpoint bound to the receipt.
     */
    fun scheduleAdvanced(
        attemptId: Long,
        idempotencyKey: String,
        now: Long
    ): ScheduleAdvanceState {
        // Receipt FIRST (§5 boundary): without a durable receipt Auto never assumes the schedule
        // advanced — regardless of any acquired fact.
        val receipt = log.receiptFor(idempotencyKey) ?: return ScheduleAdvanceState.NOT_ADVANCED
        // Acquire each fact INSIDE the gate from the coordinator-owned readers, forwarding the REAL
        // owned identity — never caller-supplied booleans (R6-F2). All three are acquired unconditionally
        // (no short-circuit): each reader call is itself part of the observable contract.
        val observeMatch = observe.matches(attemptId)
        val revisionFresh = receiptRevision.isFresh(idempotencyKey, now)
        val quotaHasCapacity = trustedQuota.hasCapacity(attemptId)
        return if (observeMatch && revisionFresh && quotaHasCapacity) {
            // Window-c checkpoint bound to the advanced receipt (R5-F2 durable half).
            log.recordCheckpoint(attemptId, "SCHEDULE_ADVANCED", receipt.idempotencyKey, now)
            ScheduleAdvanceState.ADVANCED
        } else {
            ScheduleAdvanceState.NOT_ADVANCED
        }
    }

    /**
     * Normal-path apply dispatch (§8.1 BEGIN_APPLY→APPLY_RECEIPT): drive the external apply for
     * [idempotencyKey] + [requestDigest] and record the durable receipt. Returns the [ApplyOutcome]
     * carrying the provider lease ([ApplyOutcome.leaseId]) — the normal path MUST obtain its lease from
     * here, never invent it (Sol round-9 P1-2: the normal chain forges APPLY_RECEIPT if it never drives
     * the provider). A fail-closed executor yields `leaseId == null` (no lease).
     *
     * This is orchestration over the two seams ([ExternalApplyExecutor] + [DurableRecoveryLog]); the
     * GREEN body is their production bindings (RPC + Room), not this call order.
     *
     * # 正路径 apply 派发：驱动执行器 + 落 receipt；lease 由 ApplyOutcome 带回（绝不凭空发明）
     */
    fun dispatchApply(
        attemptId: Long,
        idempotencyKey: String,
        requestDigest: String,
        now: Long
    ): ApplyOutcome {
        // INV-13 conflict preflight (Sol round-14 P1-1 / round-15 P2-3): a known conflicting receipt
        // (same key + different digest) MUST fail-closed BEFORE the provider call with the canonical
        // IDEMPOTENCY_CONFLICT outcome — zero external effect, no lease.
        val prior = log.receiptFor(idempotencyKey)
        if (prior != null && prior.requestDigest != requestDigest) {
            return ApplyOutcome(outcome = "IDEMPOTENCY_CONFLICT", providerHadAlreadyApplied = false, leaseId = null)
        }
        val outcome = executor.apply(attemptId, idempotencyKey, requestDigest, now)
        if (outcome.leaseId != null) {
            // R43 (Sol GREEN-review P1-5): the provider lease persists ATOMICALLY with the receipt —
            // a crash between this line and the attempt-owner markAplusLease still recovers the lease
            // from the receipt replay (ApplyReceiptV1.leaseId is part of the durable proof).
            val receipt = log.recordReceipt(
                idempotencyKey, requestDigest, outcome.outcome, now, outcome.leaseId,
                operationId = outcome.operationId,
                acceptedIntentHash = outcome.acceptedIntentHash,
                appliedAtEpochMs = outcome.appliedAtEpochMs,
                environmentRevision = outcome.environmentRevision,
                verificationLevelWire = outcome.verificationLevelWire
            )
            if (receipt == null) {
                // Receipt not durable (storage failed) → fail-closed: the apply is NOT proven, no lease.
                return ApplyOutcome(outcome = "RECEIPT_NOT_DURABLE", providerHadAlreadyApplied = false, leaseId = null)
            }
        }
        return outcome
    }

    /**
     * Lease-release convergence (§8.1 BEGIN_RELEASE→RELEASE_RECEIPT; §8.2: no fresh apply until
     * RELEASED): drive the external release for [leaseId] and record the DURABLE release receipt.
     * Returns the typed [RecordedReleaseReceipt], or null when the release is not durable (fail-closed).
     *
     * LEASE-BOUND + DURABLE (Sol round-8 P1-4 / round-9 P1-5): drives [ExternalApplyExecutor.release]
     * (the provider effect) and records the receipt bound to [leaseId] + [releaseDigest] — a Boolean
     * "released" is not a proof. The normal path MUST release through here, never emit RELEASE_RECEIPT
     * without the provider call.
     *
     * # lease 释放收敛：驱动 release + 落 lease-bound release receipt（typed，非 Boolean）
     */
    fun releaseLease(
        attemptId: Long,
        idempotencyKey: String,
        leaseId: String,
        releaseDigest: String,
        now: Long
    ): RecordedReleaseReceipt? {
        // INV-13 conflict preflight (Sol round-14 P1-2): any existing receipt is authoritative. Replay
        // ONLY the exact tuple (key + lease + digest) with a RELEASED outcome; any mismatch (same key /
        // different lease-or-digest, same lease / different key-or-digest, or FAILED) is fail-closed with
        // ZERO provider call and the prior receipt preserved.
        val byKey = log.releaseReceiptForKey(idempotencyKey)
        val byLease = log.releaseReceiptFor(leaseId)
        // Dual-index consistency (Sol round-15 P1-2 / round-16 P1-4): a PARTIAL (key-only or lease-only) or
        // DIVERGENT index is fail-closed; only "both null" (no receipt) proceeds to a fresh provider call,
        // and "both non-null + equal" replays when the exact tuple + RELEASED hold.
        if (byKey == null && byLease == null) {
            val releaseOutcome = executor.release(attemptId, idempotencyKey, leaseId, releaseDigest, now)
            if (releaseOutcome.outcome != "RELEASED") {
                // Release incomplete / failed → fail-closed (§8.1 RELEASE_INCOMPLETE → RECOVERY_REQUIRED).
                return null
            }
            return log.recordReleaseReceipt(idempotencyKey, leaseId, releaseDigest, releaseOutcome.outcome, now)
        }
        if (byKey == null || byLease == null || byKey != byLease) {
            return null
        }
        if (byKey.leaseId == leaseId && byKey.releaseDigest == releaseDigest && byKey.resultOutcome == "RELEASED") {
            return byKey
        }
        return null
    }
}

/**
 * Typed result of a recovery reconcile (Sol round-9 P1-3): carries the durable apply receipt + provider
 * lease the engine must persist — the lease comes back from the (idempotent) apply, never pre-seeded.
 */
sealed class ReconcileResult {
    /** A fresh apply recovered: the executor applied + receipt recorded; [leaseId] is the acquired lease. */
    data class AdvancedToRelease(val applyReceipt: RecordedReceipt, val leaseId: String) : ReconcileResult()

    /**
     * A prior apply replayed (same key + digest): no executor call; [leaseId] is the lease recorded on
     * the durable receipt (null for legacy receipts that predate the lease column — the caller fails
     * closed on null rather than inventing a lease).
     */
    data class ReplayedApply(val applyReceipt: RecordedReceipt, val leaseId: String?) : ReconcileResult()

    /** Same idempotency key, different canonical request digest (INV-13). */
    object IdempotencyConflict : ReconcileResult()

    /** Not enough evidence to reconcile (fail-closed). */
    object InsufficientEvidence : ReconcileResult()
}

enum class ScheduleAdvanceState { ADVANCED, NOT_ADVANCED }

/**
 * Acquires (internally, never caller-supplied) whether the independent live observation matches the
 * intent revision the receipt claims to have advanced past (Sol round-4 §11.2 F2; R5-F2 identity-bearing).
 *
 * R5-F2: the acquirer takes the [attemptId] the gate is deciding for, which the COORDINATOR owns and
 * injects. Sol's round-4 combined attack (§11.7) greened the ZERO-ARG closure variant by relaying a
 * caller-supplied boolean without ever binding the acquisition to the receipt's real attempt identity.
 * Making the closure identity-bearing lets the test assert the gate acquired the fact for the REAL
 * attempt (not a default/garbage identity), defeating a caller-injection oracle that forwards a wrong
 * identity. Residual note: the acquirer pattern still delegates the ANSWER to the caller-supplied
 * closure; R5-self-gate judges whether that residual is closeable without moving acquisition INTO the
 * gate (which would change the coordinator's wiring — the receipt-lease escalation trigger).
 *
 * # 观察匹配获取器（R5-F2 承载 attemptId 身份）：协调器注入真实 attempt 身份，杜绝零参闭包的调用方注入
 */
fun interface ObserveIntentAcquirer {
    /** @param attemptId the attempt the gate is deciding for (coordinator-owned, never caller-supplied). */
    fun matches(attemptId: Long): Boolean
}

/**
 * Acquires whether the receipt's revision is still fresh relative to the live schedule (§11.2 F2; R5-F2).
 * Identity-bearing on the [idempotencyKey] the receipt is bound to and the [now] at which freshness is judged.
 *
 * # receipt 版本新鲜度获取器（R5-F2 承载 idempotencyKey+now 身份）
 */
fun interface ReceiptRevisionAcquirer {
    /** @param idempotencyKey the receipt key (coordinator-owned). @param now the judgment instant. */
    fun isFresh(idempotencyKey: String, now: Long): Boolean
}

/**
 * Acquires whether the task's trusted quota still has capacity (is NOT yet exhausted) (§11.2 F2; R5-F2).
 * Identity-bearing on the [attemptId] whose task's quota is consulted.
 *
 * # 可信配额容量获取器（R5-F2 承载 attemptId 身份）
 */
fun interface TrustedQuotaAcquirer {
    /** @param attemptId the attempt whose task's trusted quota is consulted (coordinator-owned). */
    fun hasCapacity(attemptId: Long): Boolean
}
