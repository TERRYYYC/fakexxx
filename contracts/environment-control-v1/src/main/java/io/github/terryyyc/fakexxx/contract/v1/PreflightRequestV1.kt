package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Preflight ask: "would this intent be allowed right now?". Spec §6.3.2. */
@Parcelize
data class PreflightRequestV1(
    val intent: EnvironmentIntentV1,
    val idempotencyKey: String,
    val callerProtocolVersion: Int,
) : Parcelable
