// Environment Maintenance contract v1 (#140 dual-app quick reset).
//
// ADDITIVE to the frozen IEnvironmentControlV1 surface — the six control
// operations, their method set, signatures and wire codes are untouched. This
// interface exposes ONE provider-side recovery command so the Auto app can
// reset the QWY schedule in the same operator action ("quick reset"):
//
//   scheduleReset() — productization of the provider's own schedule_reset
//   operation (generation+1, pointer back to the first item, exhausted
//   cleared, last-applied residue removed, effective profile re-anchored and
//   re-published). Authorization rides the SAME CallerAuthorizer principal as
//   the control channel (Binder UID -> single package -> current signer ->
//   active pairing); an unpaired caller gets NOT_PAIRED, never a reset.
//
// The AIDL descriptor is bound to v1 permanently. Published method semantics
// are never rewritten in place; a non-backward-compatible v2 uses a new
// package/interface.
package io.github.terryyyc.fakexxx.contract.v1;

import io.github.terryyyc.fakexxx.contract.v1.MaintenanceResultV1;

interface IEnvironmentMaintenanceV1 {
    MaintenanceResultV1 scheduleReset();
}
