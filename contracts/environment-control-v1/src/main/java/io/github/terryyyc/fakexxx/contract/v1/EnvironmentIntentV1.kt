package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * What the consumer wants the environment to be for one attempt.
 *
 * Spec §6.3. This is the object the canonical digest in [CanonicalIntentDigestV1]
 * is computed over; `runId` and `attemptId` are part of it, which is what makes
 * `acceptedIntentHash` an attribution proof and not merely a coordinate check.
 *
 * `requiredVerificationWire` carries a [VerificationLevelV1] wire code. It is an
 * `Int` and not the enum for the reason documented on [VerificationLevelV1].
 */
@Parcelize
data class EnvironmentIntentV1(
    val runId: String,
    val attemptId: String,
    val profileRef: String,
    val scheduleRef: String,
    val latitude: Double,
    val longitude: Double,
    /** [VerificationLevelV1] wire code. */
    val requiredVerificationWire: Int,
    val notBeforeEpochMs: Long,
    val deadlineEpochMs: Long,
) : Parcelable
