package name.caiyao.fakegps.integration.v1

import name.caiyao.fakegps.integration.v1.ScheduleReinitPolicy.ExistingState
import name.caiyao.fakegps.integration.v1.ScheduleReinitPolicy.ReinitPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M-AD-24 (spec v1.57/v1.72, §10): an external schedule (re)initialization
 * that clears `exhausted` MUST also increment `scheduleVersion`. Otherwise a
 * same-topology reset transitions `exhausted true→false` with the version
 * unchanged, and a consumer holding `(currentItemId, scheduleVersion)` from
 * the old generation cannot distinguish old from new — stale CAS
 * preconditions and completion proofs become reusable across generations.
 *
 * The invariant is pinned against the pure [ScheduleReinitPolicy] — the
 * decision half of QwyScheduleStore (the store applies whatever plan this
 * returns, in one SharedPreferences commit).
 */
class ScheduleReinitPolicyTest {

    private fun state(
        version: Long = 7L,
        itemIds: List<String> = listOf("profile-1", "profile-2", "profile-3"),
        exhausted: Boolean = true,
    ) = ExistingState(
        scheduleId = QwyScheduleStore.DEFAULT_SCHEDULE_ID,
        scheduleVersion = version,
        itemIds = itemIds,
        exhausted = exhausted,
    )

    /**
     * THE M-AD-24 pin, stated as a property over every plan the policy can
     * produce: whenever the plan writes exhausted=false over an exhausted
     * existing state, the version it writes is strictly greater than the one
     * it read. There is no plan that clears the bit at the same version.
     */
    @Test
    fun mAd24_anyPlanThatClearsExhaustedBumpsVersion() {
        val exhaustedStates = listOf(
            // same topology (the dangerous case the row is about)
            state(exhausted = true),
            // changed topology (subset / superset / disjoint)
            state(exhausted = true, itemIds = listOf("profile-1", "profile-2")),
            state(exhausted = true, itemIds = listOf("profile-1", "profile-2", "profile-3", "profile-4")),
            state(exhausted = true, itemIds = listOf("profile-9", "profile-10")),
            // empty item set — degenerate but the invariant must not depend on it
            state(exhausted = true, itemIds = emptyList()),
        )
        val candidateNewSets = listOf(
            emptyList(),
            listOf("profile-1", "profile-2", "profile-3"),           // same as first state
            listOf("profile-1", "profile-2"),                        // same as second
            listOf("profile-1", "profile-2", "profile-3", "profile-4"),
            listOf("profile-9", "profile-10"),
            listOf("profile-7"),                                     // disjoint
        )

        for (existing in exhaustedStates) {
            for (newItems in candidateNewSets) {
                val plan = ScheduleReinitPolicy.decide(existing, newItems)
                when (plan) {
                    is ReinitPlan.NoOp -> {
                        // Not a reinit: NOTHING changed — the bit survived.
                        assertTrue(
                            "same-topology no-op must preserve exhausted, got clear for " +
                                "existing=${existing.itemIds} new=$newItems",
                            existing.exhausted,
                        )
                    }
                    is ReinitPlan.Initialize -> {
                        // A reinit happened: the clear must ride a bump.
                        assertTrue(
                            "M-AD-24 violated: plan clears exhausted at version " +
                                "${plan.scheduleVersion} over existing ${existing.scheduleVersion} " +
                                "(existing=${existing.itemIds} new=$newItems)",
                            plan.scheduleVersion > existing.scheduleVersion,
                        )
                    }
                }
            }
        }
    }

    /** Fresh install: no schedule yet → initialize at version 1, not exhausted. */
    @Test
    fun firstInitializationStartsAtVersionOne() {
        val plan = ScheduleReinitPolicy.decide(
            existing = ExistingState(scheduleId = null, scheduleVersion = 0L, itemIds = emptyList(), exhausted = false),
            newItemIds = listOf("profile-1", "profile-2"),
        )
        assertTrue(plan is ReinitPlan.Initialize)
        plan as ReinitPlan.Initialize
        assertEquals(1L, plan.scheduleVersion)
        assertEquals("profile-1", plan.currentItemId)
        assertEquals(false, plan.exhausted)
        assertEquals(0L, plan.advanceCount)
    }

    /**
     * Same topology over an exhausted schedule is a NO-OP — a terminal
     * schedule stays terminal until the item set actually changes. This is
     * the complement of M-AD-24: since the policy refuses to "reinit" without
     * a topology change, there is no same-topology clear to bump for.
     */
    @Test
    fun sameTopologyReinitIsNoOp_exhaustedSurvives() {
        val items = listOf("profile-1", "profile-2")
        val plan = ScheduleReinitPolicy.decide(state(version = 9L, itemIds = items, exhausted = true), items)
        assertTrue("same itemIds must be a no-op", plan is ReinitPlan.NoOp)
    }

    /**
     * Changed topology starts a new generation: version V+1, pointer reset to
     * the first item, exhausted cleared, advance count reset — all in ONE
     * plan (the store commits them together).
     */
    @Test
    fun changedTopologyStartsNewGeneration() {
        val plan = ScheduleReinitPolicy.decide(
            state(version = 9L, itemIds = listOf("profile-1", "profile-2"), exhausted = true),
            listOf("profile-5", "profile-6", "profile-7"),
        )
        assertTrue(plan is ReinitPlan.Initialize)
        plan as ReinitPlan.Initialize
        assertEquals(10L, plan.scheduleVersion)
        assertEquals("profile-5", plan.currentItemId)
        assertEquals(false, plan.exhausted)
        assertEquals(0L, plan.advanceCount)
    }

    /**
     * Sensitivity (the M-AD-24 bug made concrete): a hypothetical plan that
     * cleared exhausted at the SAME version is exactly what the row forbids.
     * This documents the failure shape the property test above rejects —
     * if someone "simplifies" the policy to reset exhausted on every init
     * call (including same-topology), the property test turns red.
     */
    @Test
    fun sensitivity_aSameVersionClearWouldViolateTheProperty() {
        // The buggy plan, constructed by hand: clear at same version.
        val buggyClear = ReinitPlan.Initialize(
            scheduleId = QwyScheduleStore.DEFAULT_SCHEDULE_ID,
            scheduleVersion = 7L, // NOT bumped
            itemIds = listOf("profile-1", "profile-2", "profile-3"),
            currentItemId = "profile-1",
            exhausted = false,
            advanceCount = 0L,
        )
        val existingVersion = 7L
        assertTrue(
            "a same-version clear must be recognizable as an M-AD-24 violation",
            buggyClear.exhausted.not().let { cleared ->
                cleared && buggyClear.scheduleVersion <= existingVersion
            },
        )
    }
}
