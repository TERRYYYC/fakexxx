package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Complete the current schedule item and advance to the next one. Spec §6.7.3.
 *
 * This is a COMPARE-and-advance, not a blind increment. [expectedScheduleId],
 * [expectedCurrentItemId] and [expectedScheduleVersion] are preconditions, not
 * hints:
 *
 *  - a mismatched [expectedScheduleId] fails with `SCHEDULE_IDENTITY_MISMATCH`
 *    (17) and is checked FIRST, because §6.7.1 permits two schedules to reuse the
 *    same `(itemId, scheduleVersion)`; without it the CAS passes on the wrong
 *    schedule and the advance really is committed there;
 *  - a stale [expectedCurrentItemId] fails with `SCHEDULE_ITEM_MISMATCH` (14),
 *    which is the single code that stops both a wrong-item advance and a double
 *    advance — a caller holding a stale current item produces the same mismatch
 *    either way;
 *  - a stale [expectedScheduleVersion] fails with `SCHEDULE_VERSION_STALE` (15),
 *    because the schedule moved while Auto was proving quota and the completion
 *    result no longer provably belongs to the same item.
 *
 * ## Where these three values must come from (spec §6.7.3, frozen in v1.72)
 *
 * Having the fields is not the same as having the protection. All three MUST be
 * captured from ONE `discover()` projection group when the attempt is opened,
 * persisted with `attemptId` / [idempotencyKey] BEFORE the external run starts,
 * and replayed VERBATIM here — including after a crash (§4.4).
 *
 * They MUST NOT be refreshed from a fresh `discover()` at advance time. If Auto
 * re-reads `currentScheduleId` just before submitting, and the device has since
 * switched from schedule A to B, then this field, the [requestDigest] and the
 * provider's CAS all validate B *consistently* — and the completion earned on A
 * advances B. The field cannot distinguish "the A this completion belongs to"
 * from "the B I just read": same type, same shape, different value precisely
 * when the guard is needed.
 *
 * It is also NOT `EnvironmentIntentV1.scheduleRef`, which §6.3 defines as a
 * stable reference to a schedule ITEM — a different object from the effective
 * schedule's id. Both are non-null `String`, so nothing would object.
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
