# environment-maintenance-v1 — dual-app quick reset contract (#140)

Additive sibling of `environment-control-v1`. The frozen six-op control
surface (interface `IEnvironmentControlV1`, its wire codes, its compatibility
governance) is untouched; this module exists because the canonical-spec
binding of the control module forbids appending new DTOs/methods to it.

Surface:

- `IEnvironmentMaintenanceV1.scheduleReset(): MaintenanceResultV1` — provider
  side reset of the QWY schedule (generation+1, pointer to first item,
  exhausted cleared, last-applied residue removed, effective profile
  re-anchored + re-published). Exposed by
  `name.caiyao.fakegps.integration.v1.EnvironmentMaintenanceService`
  (exported, explicit ComponentName only, same pairing authorization as the
  control channel: `MaintenanceContractV1.SERVICE_CLASS_NAME`).
- `MaintenanceResultV1` — typed result carrier (OK / ERROR with frozen
  `MaintenanceResetErrorCodeV1` wires; PUBLISH_FAILED is a partial success
  that still carries the committed `scheduleVersionAfter`).

Consumed by both app Gradle roots via the same `include` + projectDir
override pattern as environment-control-v1.
