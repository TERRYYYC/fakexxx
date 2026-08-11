package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Auto's proof that the current schedule item reached its trusted-success quota.
 * Spec §6.7.2.
 *
 * Auto owns the quota and the completion judgement; the provider RECORDS this and
 * must not recompute it. If the provider recomputed completion it would become a
 * second owner of the quota, which is the §5 ban on "Qianwangyou infers CellRebel
 * completion" — the same dual-ownership defect that put two sort orders on one
 * schedule.
 *
 * [scheduleItemId] binds the proof to a specific item, so a proof cannot be
 * replayed against whatever item happens to be current later.
 */
@Parcelize
data class CompletionProofV1(
    val scheduleItemId: String,
    val trustedSuccessCount: Int,
    val quotaRequired: Int,
    val ledgerRef: String,
    val verifiedAtElapsedRealtimeMs: Long,
) : Parcelable
