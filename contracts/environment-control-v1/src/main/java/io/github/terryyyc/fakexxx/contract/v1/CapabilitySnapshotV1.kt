package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Provider capability snapshot returned by `discover()`. Spec §6.3.
 *
 * The `...Wires` lists carry enum wire codes, ascending and de-duplicated so the
 * wire representation of a given capability set is deterministic. A consumer that
 * meets an unknown code in these lists must fail closed with
 * [ContractErrorCodeV1.INCOMPATIBLE_PROTOCOL] (§6.7), not ignore it.
 */
@Parcelize
data class CapabilitySnapshotV1(
    val protocolVersion: Int = ContractV1.PROTOCOL_VERSION,
    val serviceVersion: String,
    /** [DeliveryModeV1] wire codes; ascending, de-duplicated. */
    val supportedModeWires: List<Int>,
    /** [VerificationLevelV1] wire codes; ascending, de-duplicated. */
    val supportedVerificationLevelWires: List<Int>,
    /** [ContinuityCoverageV1] wire code. */
    val continuityCoverageWire: Int,
    val environmentRevision: Long,
    val profileRefs: List<String>,
    val scheduleRefs: List<String>,
) : Parcelable
