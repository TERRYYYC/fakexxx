package name.caiyao.fakegps.integration.v1

/**
 * Pure decision logic for schedule (re)initialization (§6.7.1, spec v1.57/v1.72).
 *
 * M-AD-24 (frozen ledger row, §10): a reinit that clears `exhausted` MUST also
 * increment `scheduleVersion`. Otherwise a same-topology reset makes
 * `exhausted true→false` with the version unchanged, and a consumer holding
 * `(currentItemId, scheduleVersion)` from the old generation cannot
 * distinguish it from the new one — stale CAS preconditions and completion
 * proofs become reusable across generations (exactly one advance per item is
 * then unenforceable).
 *
 * Extracted from QwyScheduleStore as a pure function so the invariant is
 * pinnable in a JVM unit lane — the store itself is SharedPreferences-bound
 * and unreachable from unit tests (the same retrieval/decision split as
 * SignerLookupPolicy).
 *
 * The policy encodes three rules:
 *  1. No existing schedule → initialize at version 1, pointer at first item,
 *     exhausted = false (a fresh generation).
 *  2. Same itemIds as the existing schedule → NO-OP. Nothing changes — not
 *     the pointer, not exhausted, not the version. A same-topology "reinit"
 *     is not a reinit: there is no generation change to record, and clearing
 *     exhausted here (without a bump) is precisely the M-AD-24 bug.
 *  3. Different itemIds → new generation: version + 1, pointer reset to the
 *     first item, exhausted = false, advance count reset. The clear and the
 *     bump are one atomic decision — no caller can observe one without the
 *     other.
 */
object ScheduleReinitPolicy {

    /** Immutable snapshot of the schedule state the decision is made against. */
    data class ExistingState(
        val scheduleId: String?,
        val scheduleVersion: Long,
        val itemIds: List<String>,
        val exhausted: Boolean,
    )

    /**
     * The write plan for a reinit. `null` write fields mean "leave unchanged"
     * (only possible for a no-op, where nothing is written at all).
     */
    sealed interface ReinitPlan {
        /** Nothing to do — same topology, no generation change. */
        data object NoOp : ReinitPlan

        /** A new generation: every field is written together. */
        data class Initialize(
            val scheduleId: String,
            val scheduleVersion: Long,
            val itemIds: List<String>,
            val currentItemId: String?,
            val exhausted: Boolean,
            val advanceCount: Long,
        ) : ReinitPlan
    }

    fun decide(existing: ExistingState, newItemIds: List<String>): ReinitPlan {
        // Rule 2: same topology is not a reinit. In particular exhausted is
        // preserved — a terminal schedule stays terminal until the item set
        // actually changes and a new generation (with a version bump) begins.
        if (existing.scheduleId != null && existing.itemIds == newItemIds) {
            return ReinitPlan.NoOp
        }

        // Rules 1 & 3: fresh init at version 1, or a new generation at V+1.
        // The exhausted clear rides the same write as the version bump —
        // M-AD-24's invariant is structural here: this is the ONLY branch that
        // clears exhausted, and it always bumps.
        val version = if (existing.scheduleId == null) 1L else existing.scheduleVersion + 1
        return ReinitPlan.Initialize(
            scheduleId = QwyScheduleStore.DEFAULT_SCHEDULE_ID,
            scheduleVersion = version,
            itemIds = newItemIds,
            currentItemId = newItemIds.firstOrNull(),
            exhausted = false,
            advanceCount = 0L,
        )
    }
}
