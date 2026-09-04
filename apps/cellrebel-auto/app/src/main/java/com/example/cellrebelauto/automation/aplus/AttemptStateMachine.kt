package com.example.cellrebelauto.automation.aplus

/**
 * Attempt lifecycle states (§8.1). The terminal state is [CLOSED]; a terminal attempt cannot be
 * revived (INV-22) — a new run must create a new attempt.
 *
 * # Attempt 状态机状态枚举（§8.1）；CLOSED 为终态，不可复活（INV-22）
 */
enum class AttemptState {
    CREATED,
    APPLY_PENDING,
    ENV_APPLIED,
    PRE_OBSERVED,
    CELLREBEL_START_PENDING,
    CELLREBEL_RUNNING,
    POST_OBSERVE_PENDING,
    DECIDING,
    QUOTA_COMMITTED,
    UNVERIFIED_RECORDED,
    RECOVERY_REQUIRED,
    RELEASE_PENDING,
    ADVANCE_PENDING,
    ADVANCE_OBSERVING,
    ADVANCE_STATE_READBACK,
    CLOSED
}

/**
 * Guards the §8.1 transitions: a terminal state rejects all events (INV-22), and any non-RELEASED
 * lease blocks a new apply (INV-28).
 *
 * PRE-FREEZE SKELETON (RED): [isTerminal] is correct (trivial enum membership, not behavior), but
 * [canBeginApply] always returns true — it allows beginning an apply on a terminal state and with
 * a non-released lease. Tests asserting those are blocked will FAIL until GREEN.
 *
 * # 状态守卫骨架（RED）：canBeginApply 恒 true，允许绕过终态/未释放 lease；GREEN 修复
 */
class AttemptGuard {
    fun isTerminal(state: AttemptState): Boolean = state == AttemptState.CLOSED

    /**
     * GREEN (INV-22 / INV-28): an apply may begin only from a NON-terminal state AND with the prior
     * lease fully released. A terminal attempt cannot be revived (a new run creates a new attempt);
     * a non-released lease blocks a fresh apply.
     */
    fun canBeginApply(state: AttemptState, leaseReleased: Boolean): Boolean =
        !isTerminal(state) && leaseReleased
}
