package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Release a lease and undo what this caller's lease established. Spec §6.3.2. */
@Parcelize
data class ReleaseRequestV1(
    val leaseId: String,
    val operationId: String,
    val idempotencyKey: String,
) : Parcelable
