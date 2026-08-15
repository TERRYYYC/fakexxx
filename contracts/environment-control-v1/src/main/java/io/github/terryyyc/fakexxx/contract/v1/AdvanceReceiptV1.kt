package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result of a completion/advance handshake. Spec §6.7.3 / §6.7.5.
 *
 * [outcomeWire] is an Int wire code, never a Kotlin enum: an enum inside a
 * @Parcelize class is written by name and read with valueOf(), so a constant
 * added by a newer peer crashes an older reader inside the Binder transaction
 * instead of failing closed (§6.2).
 *
 * [advancedToItemId] is null when the schedule is exhausted — a TERMINAL state,
 * not a failure: the current item is retained and the schedule does not wrap.
 *
 * A receipt is the provider's own account of what it did. It is NOT evidence that
 * the environment actually changed, and recomputing [receiptDigest] does not
 * upgrade it: the digest proves the provider framed its own fields and bound
 * them to this request, not that any state was persisted.
 *
 * Non-terminal advance: Auto must independently observe() and compare against
 * [effectiveIntentHash] / [effectiveEnvironmentRevision] (§6.7.5).
 *
 * Terminal (EXHAUSTED) advance: observe() is structurally inapplicable, because
 * [advancedToItemId] is null while EnvironmentObservationV1.scheduleItemId is
 * non-null, so that leg can never hold. Auto must instead read schedule state
 * back independently via a fresh discover() -- NOT preflight(), which carries no
 * currentScheduleId and names its field scheduleItemId, so it cannot establish
 * schedule identity at all. Validate the projection group is all-non-null first,
 * then require all FOUR legs (§6.7.5, v1.68): currentScheduleId == the schedule
 * this advance targeted, currentItemId == [advancedFromItemId], scheduleVersion
 * == [scheduleVersionAfter], exhausted == true; otherwise RECOVERY_REQUIRED.
 *
 * Trusting the receipt alone -- on either path -- is the entry point for
 * wrong-environment attribution.
 */
@Parcelize
data class AdvanceReceiptV1(
    val outcomeWire: Int,
    val advancedFromItemId: String,
    val advancedToItemId: String?,
    /**
     * Always `expectedScheduleVersion + 1`, regardless of whether outcome is
     * [AdvanceOutcomeV1.ADVANCED] or [AdvanceOutcomeV1.EXHAUSTED] (§6.7.1,
     * v1.56). A terminal advance atomically sets the exhausted bit AND
     * increments the version in the same CAS transaction.
     */
    val scheduleVersionAfter: Long,
    val effectiveIntentHash: String,
    val effectiveEnvironmentRevision: Long,
    val receiptDigest: String,
) : Parcelable
