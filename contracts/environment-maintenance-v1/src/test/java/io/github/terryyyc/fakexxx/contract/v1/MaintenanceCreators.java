package io.github.terryyyc.fakexxx.contract.v1;

import android.os.Parcelable;

/**
 * Test-only bridge to the kotlin-parcelize-generated static CREATOR (see
 * ContractCreators in the control contract module for the rationale).
 */
public final class MaintenanceCreators {

    private MaintenanceCreators() {
    }

    public static Parcelable.Creator<MaintenanceResultV1> maintenanceResult() {
        return MaintenanceResultV1.CREATOR;
    }
}
