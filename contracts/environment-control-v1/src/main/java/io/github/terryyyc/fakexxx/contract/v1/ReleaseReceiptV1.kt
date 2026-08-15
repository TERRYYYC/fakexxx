package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Result of a release. Spec §6.3.2. */
@Parcelize
data class ReleaseReceiptV1(
    val operationId: String,
    val idempotencyKey: String,
    val leaseId: String,
    val releasedAtEpochMs: Long,
    val environmentRevision: Long,
    /**
     * false = the provider could not prove cleanup completed. The consumer must
     * then pause the plan and surface manual recovery (INV-21); it must not treat
     * an unproven release as done and move to the next address.
     */
    val releaseComplete: Boolean,
    /** [ContractErrorCodeV1] wire codes describing residual state. */
    val residualReasonWires: List<Int>,
) : Parcelable
