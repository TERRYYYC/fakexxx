package com.example.cellrebelauto.automation.selfheal

import com.example.cellrebelauto.automation.aplus.AttemptState

/**
 * #138 — zombie attempt SAFE finalization policy (pure, JVM-testable).
 *
 * A "zombie" is a non-terminal A+ attempt left behind by a dead process whose §8.1 reconcile can
 * never succeed AND whose intent validity window has irrevocably expired. Field truth (issue #138,
 * device ZY22JHW9M4, attempt 875): an `APPLY_PENDING` owner with no local receipt and
 * `allowExternalApply=false` (anchor drift / protocol skew / null capabilities) fails every Resume
 * with the same `InsufficientEvidence` pause — the ONLY escape was operator DB surgery
 * (`UPDATE test_attempts SET status='interrupted' WHERE status='starting' AND aplusState='APPLY_PENDING'`).
 * This policy lets the ENGINE perform the exact same surgery (手术等价语义) as a last resort, so the
 * operator never has to.
 *
 * SAFE ORDERING (the load-bearing design decision): finalization is offered ONLY AFTER reconcile
 * has already failed with `InsufficientEvidence`. It must NEVER preempt reconcile, because for a
 * crash-window-(b) apply that DID land on the provider (local receipt lost), the same-key idempotent
 * replay is the correct self-heal: it recovers the lease and release-converges it. Killing such an
 * owner would strand a provider lease until EXPIRED (the second #179 domino).
 *
 * STALENESS GATE: an attempt whose intent window `[startedAt, startedAt + testTimeoutMs]` has
 * expired by a wide margin can never produce evidence again — re-driving it would mint a stale
 * intent. Past the threshold it is honestly dead; the task simply gets a FRESH attempt (fresh
 * window) from the normal admission path. Threshold = max(2× testTimeoutMs, 5 min floor): at or
 * above the watchdog's own semantics (safety net, never a second normal-path timeout), conservative
 * enough that every transient recovery (binder not ready / provider cold start — issue #138 form 2)
 * still gets its pause-and-retry chances first.
 *
 * SCOPE GUARDS (each one is a hard constraint from the ops iron rules):
 *  - Phase: APPLY_PENDING ONLY — the single §8.1 phase where the apply may have produced NO durable
 *    external effect. CREATED never dispatched anything (its recovery re-admits through a fresh
 *    preflight); every later phase holds (or held) a lease and MUST release-converge, never be
 *    blind-interrupted. A lease-holding RUNNING attempt always stays running for engine self-heal
 *    (A-line iron rule).
 *  - Receipt-free: no `operation_receipts` row for the attempt's apply idempotency key. A durable
 *    receipt makes reconcile replay WITHOUT any provider call — such an owner is recoverable and is
 *    never a zombie.
 *  - Execution-free: no `currentExecutionId` — CellRebel was never dispatched, so nothing observed
 *    can be miscounted (绝不把实际未跑的行标 completed；本策略只产出 interrupted).
 *
 * # 僵尸 attempt 安全终结策略（#138）：仅在 reconcile 自愈失败后、且无收据/无 lease/无执行/超龄
 * # 四重守卫齐备时，才允许引擎内化现场手术（interrupted + endedAt，绝无收据、绝不推配额）
 */
object ZombieAttemptPolicy {

    /** Stall floor when the test timeout is small (mirrors the watchdog's safety-net stance). */
    const val MIN_STALL_MS: Long = 300_000L

    /** Multiplier over the attempt's own test timeout before an APPLY_PENDING owner counts as stale. */
    const val STALL_TIMEOUT_MULTIPLIER: Long = 2L

    /** Typed failureReason written onto the finalized row (distinguishes it from operator surgery's INTERRUPTED). */
    const val FAILURE_REASON: String = "ZOMBIE_FINALIZED"

    /** Append-only audit event type recording THAT the engine — not the operator — fired. */
    const val AUDIT_EVENT_TYPE: String = "ZOMBIE_ATTEMPT_FINALIZED"

    /**
     * Staleness threshold for one attempt: `max(2× testTimeoutMs, 5 min floor)`.
     * # 超龄阈值 = max(2×testTimeoutMs, 5min)；意图窗口已过且宽限充分，恢复自愈机会已给足
     */
    fun stallThresholdMs(testTimeoutMs: Long): Long =
        maxOf(MIN_STALL_MS, STALL_TIMEOUT_MULTIPLIER * testTimeoutMs)

    /**
     * The only §8.1 phase eligible for safe finalization.
     * # 仅 APPLY_PENDING 可安全终结（唯一可能无外部效果的已派发阶段）
     */
    fun isFinalizablePhase(aplusState: String?): Boolean =
        aplusState == AttemptState.APPLY_PENDING.name

    /**
     * An attempt is past its intent window plus the stall margin when `startedAt + threshold <= now`.
     * # 超龄判定：startedAt + 阈值 <= now
     */
    fun isStale(startedAt: Long, testTimeoutMs: Long, nowMs: Long): Boolean =
        startedAt + stallThresholdMs(testTimeoutMs) <= nowMs
}
