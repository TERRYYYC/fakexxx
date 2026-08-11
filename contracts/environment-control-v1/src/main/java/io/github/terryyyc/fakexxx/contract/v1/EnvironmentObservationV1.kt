package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One observation of the effective environment, bound to a lease. Spec §6.3.
 *
 * Trusted quota requires a pre- and a post-observation around every CellRebel
 * execution, both bound to the same lease (INV-07), with matching revision,
 * fingerprint and [acceptedIntentHash] (INV-08, INV-23).
 */
@Parcelize
data class EnvironmentObservationV1(
    val leaseId: String,
    /**
     * Canonical digest of the intent currently in effect for this lease (§6.3.1).
     *
     * Binds the observation to an intent. Coverage, revision, fingerprint, lease
     * and verification level together only prove "nothing relevant changed during
     * the test" — they do not prove the environment was at **this attempt's**
     * address. Without this field a partially applied `apply`, leftover state from
     * the previous address, or a lease reused after the intent changed can satisfy
     * every other predicate while the trusted count lands on the wrong address.
     */
    val acceptedIntentHash: String,
    val observedAtEpochMs: Long,
    /**
     * The ONLY comparable clock (§6.4.2). SystemClock.elapsedRealtime() is device
     * monotonic, counts from boot, is comparable across processes, and is immune
     * to NTP correction, timezone and the user changing the clock. All bracketing
     * and continuity comparison must use this field.
     *
     * observedAtEpochMs stays, but for humans and audit only: a wall clock pulled
     * back by NTP inside the test window makes a POST observation numerically
     * earlier than the completion it is supposed to follow, and the predicate then
     * holds while covering nothing.
     */
    val observedAtElapsedRealtimeMs: Long,
    val environmentRevision: Long,
    val environmentFingerprint: String,
    /** [ContinuityCoverageV1] wire code. */
    val continuityCoverageWire: Int,
    val continuitySinceEpochMs: Long?,
    /** Start of the continuity window on elapsedRealtime. Trust decisions use
     *  THIS field, never the epoch one (§6.4.2). */
    val continuitySinceElapsedRealtimeMs: Long?,
    /** [DeliveryModeV1] wire code, or null when the provider cannot determine it. */
    val deliveryModeWire: Int?,
    /** [VerificationLevelV1] wire code. */
    val verificationLevelWire: Int,
    val effectiveLatitude: Double?,
    val effectiveLongitude: Double?,
    val isMock: Boolean?,
    /** [ScheduleDecisionV1] wire code. */
    val scheduleDecisionWire: Int,
    val evidenceRefs: List<String>,
    /**
     * Which schedule item this observation is evidence FOR (§6.7.1 / §6.7.5).
     *
     * This is what makes post-advance verification possible at all. One profile
     * may be reused by several schedule items, so matching the profile, the
     * coordinates or even the environmentFingerprint does NOT prove the
     * observation belongs to the item the receipt claims to have advanced to.
     * Auto compares this against AdvanceReceiptV1.advancedToItemId; without it
     * the only available check is "the environment looks right", which is
     * precisely wrong-environment attribution.
     */
    val scheduleItemId: String,
    val scheduleVersion: Long,
) : Parcelable
