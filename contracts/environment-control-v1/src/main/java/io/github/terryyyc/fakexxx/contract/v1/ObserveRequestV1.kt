package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Ask for a fresh observation of a lease's effective environment. Spec §6.3.2. */
@Parcelize
data class ObserveRequestV1(
    val leaseId: String,
    val operationId: String,
    /**
     * The digest the consumer computed locally for the intent it believes is in
     * effect. The provider returns ENVIRONMENT_DRIFT when it does not match the
     * lease's current intent, which catches lease reuse after an intent switch.
     */
    val expectedIntentHash: String,
) : Parcelable
