package com.example.cellrebelauto.automation.aplus

/**
 * Events that drive the §8.1 attempt state machine.
 *
 * # §8.1 attempt 状态机事件
 */
enum class AttemptEvent {
    BEGIN_APPLY,
    APPLY_RECEIPT,
    CRASH_RECOVER,
    PRE_OBSERVATION_OK,
    OBSERVATION_UNTRUSTED,
    START_CELLREBEL,
    NEW_RUN_OBSERVED,
    /** Start interaction found the screen already RUNNING — belongs to a prior run (§8.6.2 wire 2). */
    PRE_EXISTING_RUN,
    COMPLETION_OBSERVED,
    TIMEOUT_INTERRUPTED,
    POST_OBSERVATION_OK,
    TRUST_POLICY_PASS,
    TRUST_POLICY_FAIL,
    BEGIN_RELEASE,
    RECONCILE,
    RELEASE_RECEIPT,
    /** Release did not fully clear the lease — recovery required, do NOT advance (§8.1). */
    RELEASE_INCOMPLETE,
    ADVANCE_RECEIPT_VERIFIED,
    ADVANCE_DIGEST_MISMATCH,
    ADVANCE_EXHAUSTED_VERIFIED,
    EXHAUSTED_STATE_CONFIRMED,
    EXHAUSTED_STATE_MISMATCH,
    OBSERVED_TUPLE_MATCHES,
    OBSERVED_TUPLE_MISMATCH
}

/** The authoritative §8.1 predicate that chooses the state after a durable release receipt. */
enum class ReleaseReceiptRoute {
    NOT_COMMITTED,
    COMMITTED_UNDER_QUOTA,
    COMMITTED_QUOTA_REACHED
}

/**
 * The §8.1 attempt state-transition function.
 *
 * GREEN (contract v1 frozen): the FULL §8.1 table, including:
 *  - `CELLREBEL_START_PENDING` + `PRE_EXISTING_RUN` ⇒ `CELLREBEL_RUNNING` (classified, old result
 *    NOT counted — §8.6.2 wire 2);
 *  - `RELEASE_PENDING` + `RELEASE_INCOMPLETE` ⇒ `RECOVERY_REQUIRED` (pause plan, do NOT advance to
 *    the next address);
 *  - `CLOSED` + any event ⇒ `CLOSED` (idempotent terminal sink — a sealed template, INV-22: a
 *    terminal attempt cannot be revived; a new run creates a new attempt).
 *
 * Events not defined for a state leave it unchanged (§8.1 is an explicit table — an undefined
 * (state, event) tuple is a no-op, never an error path that crashes the engine).
 *
 * # §8.1 状态迁移函数（GREEN）：完整 §8.1 表；未定义的 (state,event) 元组为 no-op
 */
object AttemptTransitions {

    /**
     * The release receipt is the one §8.1 event whose target depends on authoritative ledger state.
     * Keeping the route mandatory prevents a caller from silently treating quota-reached as CLOSED.
     */
    fun nextAfterReleaseReceipt(
        current: AttemptState,
        route: ReleaseReceiptRoute
    ): AttemptState {
        if (current == AttemptState.CLOSED) return AttemptState.CLOSED
        if (current != AttemptState.RELEASE_PENDING) return current
        return when (route) {
            ReleaseReceiptRoute.NOT_COMMITTED,
            ReleaseReceiptRoute.COMMITTED_UNDER_QUOTA -> AttemptState.CLOSED
            ReleaseReceiptRoute.COMMITTED_QUOTA_REACHED -> AttemptState.ADVANCE_PENDING
        }
    }

    /** The full §8.1 transition table. Unlisted (state, event) tuples are no-ops (stay in current). */
    fun next(current: AttemptState, event: AttemptEvent): AttemptState {
        // INV-22: CLOSED is the idempotent terminal sink — every repeated event stays CLOSED.
        if (current == AttemptState.CLOSED) return AttemptState.CLOSED
        return when (current) {
            AttemptState.CREATED -> when (event) {
                AttemptEvent.BEGIN_APPLY -> AttemptState.APPLY_PENDING
                else -> current
            }
            AttemptState.APPLY_PENDING -> when (event) {
                AttemptEvent.APPLY_RECEIPT -> AttemptState.ENV_APPLIED
                AttemptEvent.CRASH_RECOVER -> AttemptState.APPLY_PENDING // same-key replay / fetch old receipt
                else -> current
            }
            AttemptState.ENV_APPLIED -> when (event) {
                AttemptEvent.PRE_OBSERVATION_OK -> AttemptState.PRE_OBSERVED
                AttemptEvent.OBSERVATION_UNTRUSTED -> AttemptState.RELEASE_PENDING
                else -> current
            }
            AttemptState.PRE_OBSERVED -> when (event) {
                AttemptEvent.START_CELLREBEL -> AttemptState.CELLREBEL_START_PENDING
                else -> current
            }
            AttemptState.CELLREBEL_START_PENDING -> when (event) {
                AttemptEvent.NEW_RUN_OBSERVED -> AttemptState.CELLREBEL_RUNNING
                AttemptEvent.PRE_EXISTING_RUN -> AttemptState.CELLREBEL_RUNNING // §8.6.2 wire 2: classified, old result NOT counted
                else -> current
            }
            AttemptState.CELLREBEL_RUNNING -> when (event) {
                AttemptEvent.COMPLETION_OBSERVED -> AttemptState.POST_OBSERVE_PENDING
                AttemptEvent.TIMEOUT_INTERRUPTED -> AttemptState.RECOVERY_REQUIRED
                else -> current
            }
            AttemptState.POST_OBSERVE_PENDING -> when (event) {
                AttemptEvent.POST_OBSERVATION_OK -> AttemptState.DECIDING
                else -> current
            }
            AttemptState.DECIDING -> when (event) {
                AttemptEvent.TRUST_POLICY_PASS -> AttemptState.QUOTA_COMMITTED
                AttemptEvent.TRUST_POLICY_FAIL -> AttemptState.UNVERIFIED_RECORDED
                else -> current
            }
            AttemptState.QUOTA_COMMITTED -> when (event) {
                AttemptEvent.BEGIN_RELEASE -> AttemptState.RELEASE_PENDING
                else -> current
            }
            AttemptState.UNVERIFIED_RECORDED -> when (event) {
                AttemptEvent.BEGIN_RELEASE -> AttemptState.RELEASE_PENDING
                else -> current
            }
            AttemptState.RECOVERY_REQUIRED -> when (event) {
                AttemptEvent.RECONCILE -> AttemptState.RELEASE_PENDING
                else -> current
            }
            AttemptState.RELEASE_PENDING -> when (event) {
                // The route-aware overload owns this conditional §8.1 edge. A bare receipt event
                // cannot prove whether the trusted quota has reached the task threshold.
                AttemptEvent.RELEASE_RECEIPT -> current
                AttemptEvent.RELEASE_INCOMPLETE -> AttemptState.RECOVERY_REQUIRED // pause, never advance
                else -> current
            }
            AttemptState.ADVANCE_PENDING -> when (event) {
                AttemptEvent.CRASH_RECOVER -> AttemptState.ADVANCE_PENDING
                AttemptEvent.ADVANCE_RECEIPT_VERIFIED -> AttemptState.ADVANCE_OBSERVING
                AttemptEvent.ADVANCE_EXHAUSTED_VERIFIED -> AttemptState.ADVANCE_STATE_READBACK
                AttemptEvent.ADVANCE_DIGEST_MISMATCH -> AttemptState.RECOVERY_REQUIRED
                else -> current
            }
            AttemptState.ADVANCE_OBSERVING -> when (event) {
                AttemptEvent.OBSERVED_TUPLE_MATCHES -> AttemptState.CLOSED
                AttemptEvent.OBSERVED_TUPLE_MISMATCH,
                AttemptEvent.ADVANCE_DIGEST_MISMATCH -> AttemptState.RECOVERY_REQUIRED
                else -> current
            }
            AttemptState.ADVANCE_STATE_READBACK -> when (event) {
                AttemptEvent.EXHAUSTED_STATE_CONFIRMED -> AttemptState.CLOSED
                AttemptEvent.EXHAUSTED_STATE_MISMATCH,
                AttemptEvent.ADVANCE_DIGEST_MISMATCH -> AttemptState.RECOVERY_REQUIRED
                else -> current
            }
            AttemptState.CLOSED -> AttemptState.CLOSED // unreachable (handled above) — kept exhaustive
        }
    }
}
