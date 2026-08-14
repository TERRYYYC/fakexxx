package io.github.terryyyc.fakexxx.contract.v1

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Public result carrier for every [IEnvironmentControlV1] call.
 *
 * Business failures are part of the app-public contract: the provider returns
 * this Parcelable with [resultKindWire] = [ContractResultKindV1.ERROR] and a
 * stable [errorCodeWire]. It must not rely on hidden framework exception classes
 * for the wire path. Binder death and `RemoteException` remain transport
 * failures outside this carrier.
 *
 * The carrier schema is explicitly versioned by [resultSchemaVersion]. The v1
 * schema is frozen by class name and field order; incompatible additions require
 * a new result carrier/interface version rather than appending fields to this
 * class. kotlin-parcelize reads fields positionally, so the declaration order
 * below is part of the wire contract.
 *
 * Invariants expected from a conforming peer:
 *
 *  - ERROR: [errorCodeWire] is non-null and all payload fields are null.
 *  - DISCOVER/PREFLIGHT/APPLY/OBSERVE/RELEASE/COMPLETE_AND_ADVANCE:
 *    [errorCodeWire] is null and exactly the matching payload field is non-null.
 *
 * A consumer that receives an invalid tuple fails closed as a response anomaly;
 * this class does not throw from `init` because unparcel-time crashes would move
 * the failure back onto the transport path.
 */
@Parcelize
data class EnvironmentControlResultV1(
    val resultSchemaVersion: Int,
    /** [ContractResultKindV1] wire code. */
    val resultKindWire: Int,
    /** [ContractErrorCodeV1] wire code; non-null only when [resultKindWire] is ERROR. */
    val errorCodeWire: Int?,
    /** Human diagnosis only. No machine decision may depend on this string. */
    val diagnosticMessage: String?,
    val capabilitySnapshot: CapabilitySnapshotV1?,
    val preflightReport: PreflightReportV1?,
    val applyReceipt: ApplyReceiptV1?,
    val environmentObservation: EnvironmentObservationV1?,
    val releaseReceipt: ReleaseReceiptV1?,
    val advanceReceipt: AdvanceReceiptV1?,
) : Parcelable {
    fun resultKindOrNull(): ContractResultKindV1? = ContractResultKindV1.fromWire(resultKindWire)

    fun errorCodeOrInternalFailure(): ContractErrorCodeV1? =
        errorCodeWire?.let(ContractErrorCodeV1::fromWireOrInternalFailure)

    companion object {
        const val SCHEMA_VERSION: Int = 1

        fun failure(errorCodeWire: Int, diagnosticMessage: String? = null): EnvironmentControlResultV1 =
            EnvironmentControlResultV1(
                resultSchemaVersion = SCHEMA_VERSION,
                resultKindWire = ContractResultKindV1.ERROR.wire,
                errorCodeWire = errorCodeWire,
                diagnosticMessage = diagnosticMessage,
                capabilitySnapshot = null,
                preflightReport = null,
                applyReceipt = null,
                environmentObservation = null,
                releaseReceipt = null,
                advanceReceipt = null,
            )

        fun discover(value: CapabilitySnapshotV1): EnvironmentControlResultV1 =
            success(ContractResultKindV1.DISCOVER, capabilitySnapshot = value)

        fun preflight(value: PreflightReportV1): EnvironmentControlResultV1 =
            success(ContractResultKindV1.PREFLIGHT, preflightReport = value)

        fun apply(value: ApplyReceiptV1): EnvironmentControlResultV1 =
            success(ContractResultKindV1.APPLY, applyReceipt = value)

        fun observe(value: EnvironmentObservationV1): EnvironmentControlResultV1 =
            success(ContractResultKindV1.OBSERVE, environmentObservation = value)

        fun release(value: ReleaseReceiptV1): EnvironmentControlResultV1 =
            success(ContractResultKindV1.RELEASE, releaseReceipt = value)

        fun completeAndAdvance(value: AdvanceReceiptV1): EnvironmentControlResultV1 =
            success(ContractResultKindV1.COMPLETE_AND_ADVANCE, advanceReceipt = value)

        private fun success(
            kind: ContractResultKindV1,
            capabilitySnapshot: CapabilitySnapshotV1? = null,
            preflightReport: PreflightReportV1? = null,
            applyReceipt: ApplyReceiptV1? = null,
            environmentObservation: EnvironmentObservationV1? = null,
            releaseReceipt: ReleaseReceiptV1? = null,
            advanceReceipt: AdvanceReceiptV1? = null,
        ): EnvironmentControlResultV1 =
            EnvironmentControlResultV1(
                resultSchemaVersion = SCHEMA_VERSION,
                resultKindWire = kind.wire,
                errorCodeWire = null,
                diagnosticMessage = null,
                capabilitySnapshot = capabilitySnapshot,
                preflightReport = preflightReport,
                applyReceipt = applyReceipt,
                environmentObservation = environmentObservation,
                releaseReceipt = releaseReceipt,
                advanceReceipt = advanceReceipt,
            )
    }
}
