package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Ask the provider to make the intent effective and issue a lease. Spec §6.3.2.
 *
 * [idempotencyKey] is what makes crash recovery safe: the same key with the same
 * payload must return the original receipt rather than applying twice, and the
 * same key with a different payload must be rejected (INV-13).
 */
@Parcelize
data class ApplyRequestV1(
    val intent: EnvironmentIntentV1,
    val idempotencyKey: String,
    val callerProtocolVersion: Int,
) : Parcelable
