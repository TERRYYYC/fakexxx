package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Immutable proof that an intent was applied and a lease issued. Spec §6.3.
 *
 * The consumer stores this verbatim and never rewrites it; recovery replays the
 * same idempotency key to retrieve this exact receipt again.
 */
@Parcelize
data class ApplyReceiptV1(
    val operationId: String,
    val idempotencyKey: String,
    val leaseId: String,
    /** Canonical digest of the intent the provider actually accepted (§6.3.1). */
    val acceptedIntentHash: String,
    val appliedAtEpochMs: Long,
    val environmentRevision: Long,
    /** [VerificationLevelV1] wire code. */
    val verificationLevelWire: Int,
) : Parcelable
