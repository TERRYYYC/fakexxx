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
    RELEASE_INCOMPLETE
}

/**
 * The §8.1 attempt state-transition function.
 *
 * PRE-FREEZE SKELETON (RED): [next] always returns [current] — no transition ever fires. GREEN
 * implements the §8.1 table, including:
 *  - `CELLREBEL_START_PENDING` + `PRE_EXISTING_RUN` ⇒ `CELLREBEL_RUNNING` (classified, old result
 *    NOT counted — §8.6.2 wire 2);
 *  - `RELEASE_PENDING` + `RELEASE_INCOMPLETE` ⇒ `RECOVERY_REQUIRED` (pause plan, do NOT advance to
 *    the next address);
 *  - `CLOSED` + any event ⇒ `CLOSED` (idempotent terminal sink — a sealed template, INV-22: a
 *    terminal attempt cannot be revived; a new run creates a new attempt).
 *
 * Because the stub returns [current], the PRE_EXISTING_RUN and RELEASE_INCOMPLETE transitions FAIL
 * until GREEN (the stub leaves the state unchanged), while the CLOSED sink already holds (returning
 * CLOSED from CLOSED is correct). Tests assert the specific §8.1 transitions.
 *
 * # §8.1 状态迁移骨架（RED）：恒返回 current（不迁移）；GREEN 实现 §8.1 表
 */
object AttemptTransitions {

    /** RED: no-op — returns [current] for every event. GREEN implements the §8.1 table. */
    fun next(current: AttemptState, event: AttemptEvent): AttemptState = current
}
