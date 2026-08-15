package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Preflight verdict. Spec §6.3.2.
 *
 * [blockingReasonWires] empty means preflight passed. A non-empty list is the
 * complete set of reasons, so the consumer can show every blocker at once
 * instead of discovering them one failed attempt at a time.
 */
@Parcelize
data class PreflightReportV1(
    val acceptedIntentHash: String,
    /** [ScheduleDecisionV1] wire code. */
    val scheduleDecisionWire: Int,
    /** Non-null exactly when the decision is WAIT_UNTIL; null otherwise. */
    val waitUntilEpochMs: Long?,
    /** [VerificationLevelV1] wire code the provider could actually achieve. */
    val achievableVerificationLevelWire: Int,
    /** [ContinuityCoverageV1] wire code. */
    val continuityCoverageWire: Int,
    val environmentRevision: Long,
    /** [ContractErrorCodeV1] wire codes; empty means preflight passed. */
    val blockingReasonWires: List<Int>,
    /**
     * Schedule projection group (§6.7.1, v1.55). The item and version this report
     * is about, plus whether the schedule is exhausted. A report that does not
     * name its item cannot be told apart from a report about the item that was
     * current a moment ago, which is exactly the confusion an advance creates.
     *
     * **Group invariant**: all three are null together when the provider has no
     * active schedule, and all three are non-null together otherwise. The handler
     * must NOT substitute sentinel values (empty string, zero) for null — that is
     * the round-5 sentinel anti-pattern at the wire layer.
     *
     * Prior to v1.55 these fields were non-nullable and the handler used `?: ""`
     * / `?: 0L` when no schedule existed — sentinel values masquerading as real
     * data (`EnvironmentControlHandler.kt:113-114`). Now nullable, consistent
     * with [CapabilitySnapshotV1]'s schedule projection group.
     */
    val scheduleItemId: String?,
    val scheduleVersion: Long?,
    val exhausted: Boolean?,
) : Parcelable
