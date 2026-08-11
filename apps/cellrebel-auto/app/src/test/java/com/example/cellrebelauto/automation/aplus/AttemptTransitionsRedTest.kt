package com.example.cellrebelauto.automation.aplus

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The §8.1 attempt state-transition function (Issue #5 Task 4, area 4 extension).
 *
 * TRUSTWORTHY RED: the assertions span a DIVERSE set of (state, event) → next mappings whose
 * targets are many distinct states, so no constant-returning transition function can satisfy them
 * all. The skeleton [AttemptTransitions.next] returns `current` for every event, so every
 * forward-transition assertion FAILS until GREEN implements the §8.1 table.
 *
 * Spec-critical cases Sol named explicitly:
 *  - CELLREBEL_START_PENDING + PRE_EXISTING_RUN ⇒ CELLREBEL_RUNNING (§8.6.2 wire 2: the run is
 *    classified but its result is NOT counted as a trusted new completion);
 *  - RELEASE_PENDING + RELEASE_INCOMPLETE ⇒ RECOVERY_REQUIRED (pause the plan, do NOT advance to
 *    the next address while a lease is partially held);
 *  - CLOSED + any event ⇒ CLOSED (INV-22 sealed template: a terminal attempt cannot be revived; a
 *    new run creates a new attempt).
 *
 * The "no spurious advance" cases anchor that GREEN does not move on irrelevant events.
 *
 * # §8.1 状态机迁移（RED）：跨多目标态的映射，杜绝常量函数；含 PRE_EXISTING_RUN / RELEASE_INCOMPLETE / CLOSED 汇
 */
class AttemptTransitionsRedTest {

    // ---- forward transitions (RED: skeleton returns `current` ≠ target) ----

    @Test
    fun `CREATED plus BEGIN_APPLY advances to APPLY_PENDING`() {
        assertEquals(AttemptState.APPLY_PENDING, AttemptTransitions.next(AttemptState.CREATED, AttemptEvent.BEGIN_APPLY))
    }

    @Test
    fun `APPLY_PENDING plus APPLY_RECEIPT advances to ENV_APPLIED`() {
        assertEquals(AttemptState.ENV_APPLIED, AttemptTransitions.next(AttemptState.APPLY_PENDING, AttemptEvent.APPLY_RECEIPT))
    }

    @Test
    fun `ENV_APPLIED plus PRE_OBSERVATION_OK advances to PRE_OBSERVED`() {
        assertEquals(AttemptState.PRE_OBSERVED, AttemptTransitions.next(AttemptState.ENV_APPLIED, AttemptEvent.PRE_OBSERVATION_OK))
    }

    @Test
    fun `PRE_OBSERVED plus START_CELLREBEL advances to CELLREBEL_START_PENDING`() {
        assertEquals(
            AttemptState.CELLREBEL_START_PENDING,
            AttemptTransitions.next(AttemptState.PRE_OBSERVED, AttemptEvent.START_CELLREBEL)
        )
    }

    @Test
    fun `CELLREBEL_START_PENDING plus NEW_RUN_OBSERVED advances to CELLREBEL_RUNNING`() {
        assertEquals(
            AttemptState.CELLREBEL_RUNNING,
            AttemptTransitions.next(AttemptState.CELLREBEL_START_PENDING, AttemptEvent.NEW_RUN_OBSERVED)
        )
    }

    @Test
    fun `a pre-existing run is classified to CELLREBEL_RUNNING but never counted as a new completion`() {
        // §8.6.2 wire 2 / Sol-named: the screen was already RUNNING — belongs to a prior attempt.
        assertEquals(
            "PRE_EXISTING_RUN must move to CELLREBEL_RUNNING (classified, not trusted-counted)",
            AttemptState.CELLREBEL_RUNNING,
            AttemptTransitions.next(AttemptState.CELLREBEL_START_PENDING, AttemptEvent.PRE_EXISTING_RUN)
        )
    }

    @Test
    fun `CELLREBEL_RUNNING plus COMPLETION_OBSERVED advances to POST_OBSERVE_PENDING`() {
        assertEquals(
            AttemptState.POST_OBSERVE_PENDING,
            AttemptTransitions.next(AttemptState.CELLREBEL_RUNNING, AttemptEvent.COMPLETION_OBSERVED)
        )
    }

    @Test
    fun `POST_OBSERVE_PENDING plus POST_OBSERVATION_OK advances to DECIDING`() {
        assertEquals(
            AttemptState.DECIDING,
            AttemptTransitions.next(AttemptState.POST_OBSERVE_PENDING, AttemptEvent.POST_OBSERVATION_OK)
        )
    }

    @Test
    fun `DECIDING plus TRUST_POLICY_PASS commits quota`() {
        assertEquals(
            AttemptState.QUOTA_COMMITTED,
            AttemptTransitions.next(AttemptState.DECIDING, AttemptEvent.TRUST_POLICY_PASS)
        )
    }

    @Test
    fun `DECIDING plus TRUST_POLICY_FAIL records unverified`() {
        assertEquals(
            AttemptState.UNVERIFIED_RECORDED,
            AttemptTransitions.next(AttemptState.DECIDING, AttemptEvent.TRUST_POLICY_FAIL)
        )
    }

    @Test
    fun `QUOTA_COMMITTED plus BEGIN_RELEASE advances to RELEASE_PENDING`() {
        assertEquals(
            AttemptState.RELEASE_PENDING,
            AttemptTransitions.next(AttemptState.QUOTA_COMMITTED, AttemptEvent.BEGIN_RELEASE)
        )
    }

    @Test
    fun `UNVERIFIED_RECORDED plus BEGIN_RELEASE still releases the lease`() {
        assertEquals(
            AttemptState.RELEASE_PENDING,
            AttemptTransitions.next(AttemptState.UNVERIFIED_RECORDED, AttemptEvent.BEGIN_RELEASE)
        )
    }

    @Test
    fun `RELEASE_PENDING plus RELEASE_RECEIPT closes the attempt`() {
        assertEquals(
            AttemptState.CLOSED,
            AttemptTransitions.next(AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_RECEIPT)
        )
    }

    // ---- Sol-named recovery transition (RED: skeleton returns RELEASE_PENDING) ----

    @Test
    fun `an incomplete release routes to RECOVERY_REQUIRED - the plan must not advance`() {
        // §8.1 / Sol-named: the lease did not fully clear — pause for recovery, do NOT advance.
        assertEquals(
            "RELEASE_INCOMPLETE must route to RECOVERY_REQUIRED (pause, do not advance)",
            AttemptState.RECOVERY_REQUIRED,
            AttemptTransitions.next(AttemptState.RELEASE_PENDING, AttemptEvent.RELEASE_INCOMPLETE)
        )
    }

    // ---- sealed-template sink (passes skeleton; valid GREEN) ----

    @Test
    fun `a CLOSED attempt is an idempotent sink - BEGIN_APPLY cannot revive it`() {
        assertEquals(
            AttemptState.CLOSED,
            AttemptTransitions.next(AttemptState.CLOSED, AttemptEvent.BEGIN_APPLY)
        )
    }

    @Test
    fun `a CLOSED attempt stays CLOSED under any event`() {
        // INV-22 sealed template: terminal is terminal for every event.
        for (event in AttemptEvent.entries) {
            assertEquals(
                "CLOSED + $event must stay CLOSED",
                AttemptState.CLOSED,
                AttemptTransitions.next(AttemptState.CLOSED, event)
            )
        }
    }

    // ---- no spurious advance (passes skeleton; anchors GREEN non-advancement) ----

    @Test
    fun `an irrelevant event does not advance a fresh attempt`() {
        // A completion observed before any run started is meaningless — the state must not advance.
        assertEquals(
            AttemptState.CREATED,
            AttemptTransitions.next(AttemptState.CREATED, AttemptEvent.COMPLETION_OBSERVED)
        )
    }
}
