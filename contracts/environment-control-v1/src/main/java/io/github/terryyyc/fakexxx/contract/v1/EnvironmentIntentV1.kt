package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * What the consumer wants the environment to be for one attempt.
 *
 * Spec §6.3. This is the object the canonical digest in [CanonicalIntentDigestV1]
 * is computed over; `runId` and `attemptId` are part of it, which is what makes
 * `acceptedIntentHash` an attribution proof and not merely a schedule-item check.
 *
 * ## KB-8: coordinate ownership belongs to the provider
 *
 * `latitude` / `longitude` were removed. The provider (Qianwangyou) is the sole
 * coordinate authority: it resolves the effective location from its own schedule
 * item data. Auto passes `profileRef` and `scheduleRef` as item references;
 * it never sends, holds, or asserts coordinates over the contract boundary.
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
    /** [VerificationLevelV1] wire code. */
    val requiredVerificationWire: Int,
    val notBeforeEpochMs: Long,
    val deadlineEpochMs: Long,
) : Parcelable
