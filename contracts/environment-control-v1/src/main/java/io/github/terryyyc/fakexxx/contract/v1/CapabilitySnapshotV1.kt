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
    /**
     * Schedule projection group: identity of the schedule item that is currently
     * effective, its version, and whether the schedule is exhausted (§6.7.1,
     * v1.54 state model).
     *
     * Without these, Auto cannot construct the expectedScheduleId /
     * expectedCurrentItemId / expectedScheduleVersion preconditions that §6.7.4
     * requires, so SCHEDULE_IDENTITY_MISMATCH, SCHEDULE_ITEM_MISMATCH and
     * SCHEDULE_VERSION_STALE would exist as wire codes that nothing can ever
     * legitimately trigger -- guards that are present and unreachable.
     *
     * **WHEN this group is read is part of the contract (§6.7.3, v1.72).** The
     * advance preconditions must come from the projection group captured when the
     * attempt was OPENED, persisted, and replayed verbatim. Reading this group
     * again just before `completeAndAdvance` and using THAT is the defect the
     * identity leg exists to stop: it answers "which schedule is effective now",
     * not "which schedule did this completion belong to".
     *
     * **Group invariant**: all four fields are null together when the provider has
     * no active schedule (a legal state at discover() time), and all four are
     * non-null together otherwise. Null must be read as "no schedule", never as
     * "any item" or "not exhausted". [exhausted] = true means the schedule's
     * last item has been completed; [currentItemId] retains the last item (never
     * null while exhausted). See §6.7.4 v1.54 frozen state model.
     */
    val currentScheduleId: String?,
    val currentItemId: String?,
    val scheduleVersion: Long?,
    val exhausted: Boolean?,
) : Parcelable
