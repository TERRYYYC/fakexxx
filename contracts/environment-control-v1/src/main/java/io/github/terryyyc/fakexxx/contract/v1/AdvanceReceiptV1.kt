package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Result of a completion/advance handshake. Spec §6.7.3 / §6.7.5.
 *
 * [outcomeWire] is an Int wire code, never a Kotlin enum: an enum inside a
 * @Parcelize class is written by name and read with valueOf(), so a constant
 * added by a newer peer crashes an older reader inside the Binder transaction
 * instead of failing closed (§6.2).
 *
 * [advancedToItemId] is null when the schedule is exhausted — a TERMINAL state,
 * not a failure: the current item is retained and the schedule does not wrap.
 *
 * A receipt is the provider's own account of what it did. It is NOT evidence that
 * the environment actually changed: after advancing, Auto must independently
 * observe() and compare against [effectiveIntentHash] /
 * [effectiveEnvironmentRevision] (§6.7.5). Trusting the receipt alone is the
 * entry point for wrong-environment attribution.
 */
@Parcelize
data class AdvanceReceiptV1(
    val outcomeWire: Int,
    val advancedFromItemId: String,
    val advancedToItemId: String?,
    val scheduleVersionAfter: Long,
    val effectiveIntentHash: String,
    val effectiveEnvironmentRevision: Long,
    val receiptDigest: String,
) : Parcelable
