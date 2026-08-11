// Environment Control contract v1.
//
// Spec: feature-specs/2026-08-09-cellrebel-qianwangyou-a-plus.md §6.1.
//
// The AIDL descriptor is bound to v1 permanently. Published method and field
// semantics are never rewritten in place; a non-backward-compatible v2 uses a
// new package/interface and is negotiated explicitly through discover()'s
// compatibility matrix.
//
// The service may be exported across apps but has no network surface. Every
// call resolves its real caller from Binder.getCallingUid(); a request never
// gets to state who it is (INV-02).
package io.github.terryyyc.fakexxx.contract.v1;

import io.github.terryyyc.fakexxx.contract.v1.CapabilitySnapshotV1;
import io.github.terryyyc.fakexxx.contract.v1.PreflightRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.PreflightReportV1;
import io.github.terryyyc.fakexxx.contract.v1.ApplyRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ApplyReceiptV1;
import io.github.terryyyc.fakexxx.contract.v1.ObserveRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.EnvironmentObservationV1;
import io.github.terryyyc.fakexxx.contract.v1.ReleaseRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.ReleaseReceiptV1;
import io.github.terryyyc.fakexxx.contract.v1.CompleteAndAdvanceRequestV1;
import io.github.terryyyc.fakexxx.contract.v1.AdvanceReceiptV1;

interface IEnvironmentControlV1 {
    CapabilitySnapshotV1 discover();
    PreflightReportV1 preflight(in PreflightRequestV1 request);
    ApplyReceiptV1 apply(in ApplyRequestV1 request);
    EnvironmentObservationV1 observe(in ObserveRequestV1 request);
    ReleaseReceiptV1 release(in ReleaseRequestV1 request);

    // Complete the current schedule item and advance to the next (§6.7.3).
    //
    // The provider is the sole executor of the advance because it owns the
    // schedule order; Auto is the sole judge of quota because it owns the
    // ledger. This method is the single seam between those two ownerships, and
    // it is a compare-and-advance: the request's expectedCurrentItemId and
    // expectedScheduleVersion are preconditions, so a caller that lost the race
    // is rejected rather than silently advancing someone else's item.
    AdvanceReceiptV1 completeAndAdvance(in CompleteAndAdvanceRequestV1 request);
}
