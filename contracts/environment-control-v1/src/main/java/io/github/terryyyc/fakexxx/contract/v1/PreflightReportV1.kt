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
) : Parcelable
