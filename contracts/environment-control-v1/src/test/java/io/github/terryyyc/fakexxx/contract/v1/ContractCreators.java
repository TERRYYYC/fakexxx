package io.github.terryyyc.fakexxx.contract.v1;

import android.os.Parcelable;

/**
 * Test-only bridge to the kotlin-parcelize-generated static CREATORs.
 *
 * The parcelize plugin emits {@code public static final Parcelable.Creator CREATOR}
 * onto each contract class in bytecode (verified via javap), but Kotlin test
 * sources cannot resolve that synthetic field directly. The golden byte-layout
 * tests must decode through the REAL generated CREATOR — the same static an
 * AIDL stub uses on the read side — rather than {@link Parcel#readParcelable},
 * because writeToParcel output carries no class-name prefix.
 */
public final class ContractCreators {

    private ContractCreators() {
    }

    public static Parcelable.Creator<EnvironmentIntentV1> environmentIntent() {
        return EnvironmentIntentV1.CREATOR;
    }

    public static Parcelable.Creator<PreflightRequestV1> preflightRequest() {
        return PreflightRequestV1.CREATOR;
    }

    public static Parcelable.Creator<PreflightReportV1> preflightReport() {
        return PreflightReportV1.CREATOR;
    }

    public static Parcelable.Creator<CapabilitySnapshotV1> capabilitySnapshot() {
        return CapabilitySnapshotV1.CREATOR;
    }

    public static Parcelable.Creator<EnvironmentObservationV1> environmentObservation() {
        return EnvironmentObservationV1.CREATOR;
    }

    public static Parcelable.Creator<ApplyRequestV1> applyRequest() {
        return ApplyRequestV1.CREATOR;
    }

    public static Parcelable.Creator<ApplyReceiptV1> applyReceipt() {
        return ApplyReceiptV1.CREATOR;
    }

    public static Parcelable.Creator<ObserveRequestV1> observeRequest() {
        return ObserveRequestV1.CREATOR;
    }

    public static Parcelable.Creator<ReleaseRequestV1> releaseRequest() {
        return ReleaseRequestV1.CREATOR;
    }

    public static Parcelable.Creator<ReleaseReceiptV1> releaseReceipt() {
        return ReleaseReceiptV1.CREATOR;
    }

    public static Parcelable.Creator<CompleteAndAdvanceRequestV1> completeAndAdvanceRequest() {
        return CompleteAndAdvanceRequestV1.CREATOR;
    }

    public static Parcelable.Creator<CompletionProofV1> completionProof() {
        return CompletionProofV1.CREATOR;
    }

    public static Parcelable.Creator<AdvanceReceiptV1> advanceReceipt() {
        return AdvanceReceiptV1.CREATOR;
    }

    public static Parcelable.Creator<EnvironmentControlResultV1> environmentControlResult() {
        return EnvironmentControlResultV1.CREATOR;
    }
}
