package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Complete the current schedule item and advance to the next one. Spec §6.7.3.
 *
 * This is a COMPARE-and-advance, not a blind increment. [expectedCurrentItemId]
 * and [expectedScheduleVersion] are preconditions, not hints:
 *
 *  - a stale [expectedCurrentItemId] fails with `SCHEDULE_ITEM_MISMATCH` (14),
 *    which is the single code that stops both a wrong-item advance and a double
 *    advance — a caller holding a stale current item produces the same mismatch
 *    either way;
 *  - a stale [expectedScheduleVersion] fails with `SCHEDULE_VERSION_STALE` (15),
 *    because the schedule moved while Auto was proving quota and the completion
 *    result no longer provably belongs to the same item.
 *
 * [idempotencyKey] with an identical [requestDigest] must return the ORIGINAL
 * receipt and must not advance a second time; the same key with a different
 * digest is `IDEMPOTENCY_CONFLICT` (12). Without that, "retry" and "advance
 * again" are the same call.
 */
@Parcelize
data class CompleteAndAdvanceRequestV1(
    val leaseId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val expectedScheduleId: String,
    val expectedScheduleVersion: Long,
    val expectedCurrentItemId: String,
    val completionProof: CompletionProofV1,
    val callerProtocolVersion: Int,
) : Parcelable
