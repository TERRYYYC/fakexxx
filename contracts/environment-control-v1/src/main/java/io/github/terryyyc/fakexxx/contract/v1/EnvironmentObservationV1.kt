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
    val environmentRevision: Long,
    val environmentFingerprint: String,
    /** [ContinuityCoverageV1] wire code. */
    val continuityCoverageWire: Int,
    val continuitySinceEpochMs: Long?,
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
) : Parcelable
