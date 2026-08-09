---
feature_ids:
  - profile-unavailable-state
  - cellular-hook-verification
topics:
  - android
  - xposed
  - cellular
  - profiles
doc_kind: implementation-plan
created: 2026-07-30
---

# Profile `--` (Report Unavailable) Implementation Plan

> **Owner:** 砚砚 / gpt-5.6-sol
> **Goal:** Add a third profile field state that makes supported Android public APIs report
> platform-native “unavailable” values without confusing it with passthrough.

## Original requirement

> “需要对档案添加一个数据类型，即为空、不上报的场景，我建议为 `--`。”

Source: co-creator, 2026-07-30 19:26 UTC. The agreed interpretation is a true third state:
blank remains passthrough, `--` forces the public API's native unavailable result, and a concrete
value remains spoofing.

## Terminal contract

Every editable profile field has exactly one persisted state:

| Editor input | Typed Room column | `unavailable_fields` | App observes |
|---|---:|---:|---|
| blank | `NULL` | absent | real device value (passthrough) |
| `--` | `NULL` | present | that public surface’s native unavailable representation |
| concrete value | parsed value | absent | spoofed value |

The published envelope is schema v3:

```json
{
  "schemaVersion": 3,
  "mode": "always_on",
  "fields": {"tac": 4095},
  "unavailable": ["lac", "operator_name"]
}
```

`fields` and `unavailable` must be disjoint. `unavailable` is sorted and contains only unique
editable columns whose Layer 1 capability is `SUPPORTED`. An invalid envelope is rejected as a
whole; the hook keeps its last-known-good snapshot.

## Architecture

The representation is orthogonal rather than overloading a typed sentinel:

1. Room stores the selected column names as canonical JSON in one nullable
   `unavailable_fields` metadata column. Migration 1→2 adds the column without changing existing
   rows, so every old profile remains passthrough.
2. `ConfigPrefsSync` excludes that metadata column from `fields`, validates it, and publishes the
   canonical schema-v3 `unavailable` array.
3. `MainHook` validates the entire v3 envelope before replacing `CURRENT`.
4. `Snapshot` retains both typed spoof values and the immutable unavailable-column set.
5. A surface resolver selects the public API representation at the call site. It never assigns
   one universal sentinel to a profile field.
6. The editor renders blank / `--` / value as mutually exclusive states and never stores the
   display token in a numeric/text data column.

## Surface census

This feature initially supports the cellular and carrier fields already covered by the real-device
acceptance matrix. Every supported field must have a resolution on every public surface it reaches.

| Surface | Fields | Unavailable representation |
|---|---|---|
| `CellIdentity*` integer getter/constructor | cellular identity ints, including `lac`/`cid` | `Integer.MAX_VALUE` |
| `CellIdentityNr.nci` | `nci` | `Long.MAX_VALUE` |
| `CellIdentity*.getMccString/getMncString` and constructor PLMN strings | `mcc`, `mnc` | `null` |
| legacy identity int getters | `mcc`, `mnc` | `Integer.MAX_VALUE` |
| `GsmCellLocation` | `lac`, `cid` | `-1` |
| `CellSignalStrength*` | cellular signal ints | `Integer.MAX_VALUE`, never fluctuated |
| `TelephonyManager` carrier/SIM text getters | six operator/SIM text fields | `""` |
| `CellIdentity` / `ServiceState` / `NetworkRegistrationInfo` carrier text getters | `operator_name`, `operator_numeric` | `null` |
| `TelephonyManager` network/phone type getters | type fields | platform `UNKNOWN`/`NONE` constant `0` |
| `ServiceState` state | `service_state` | unsupported: no UNKNOWN/empty constant |
| data state/activity | `data_state`, `data_activity` | API 29+: unknown (`-1`); API 24–28: disconnected (`0`) / activity none (`0`) |
| `TelephonyDisplayInfo` | `override_network_type` | `OVERRIDE_NETWORK_TYPE_NONE` (`0`) |
| `PhysicalChannelConfig` | band/bandwidth fields | `0` |
| `PhysicalChannelConfig` | physical cell id | `-1` |
| neighbor-cell structured list | `neighbor_cells_json` | unsupported until the mixed serving/neighbor public list can be filtered without leaking real neighbors |

Wi-Fi, IP/routing, location and booleans remain explicitly unsupported in this cellular change.
The UI must not offer `--` for them. Adding support later requires adding all of their surfaces and
tests before changing the Layer 1 capability verdict.

## Stateful-object census and transitions

| Object | State | Transition |
|---|---|---|
| `ProfileEntity` | typed columns + canonical unavailable set | editor save atomically makes each field passthrough, unavailable, or spoof |
| published config | schema/version + flat fields + unavailable set | repository sync validates and publishes one coherent envelope |
| `MainHook.CURRENT` | last-known-good `Snapshot` | replace only after complete envelope validation |
| editor draft | displayed token/value map | selecting `--` clears typed value; entering a value clears unavailable; blank clears both |

Required transitions:

1. blank → `--`: add to unavailable set, keep typed column null;
2. `--` → concrete value: remove from unavailable set, parse/store the value;
3. concrete value → blank: typed column null, absent from unavailable set;
4. concrete value → `--`: typed column null, add to unavailable set;
5. malformed intersection: reject publication and preserve last-known-good;
6. unknown/unsupported unavailable key: reject publication and preserve last-known-good;
7. schema v2 payload at a v3 reader: accept its legacy `fields` contract with no unavailable set;
8. Room migration: existing rows receive null metadata and preserve current behavior.

## Invariants

- Empty Room value remains passthrough; `--` is never serialized into a typed profile column.
- A configured spoof value and explicit unavailable state cannot coexist for the same field.
- All unavailable sets are unique, sorted at persistence/publication boundaries, and immutable in
  hook snapshots.
- Unsupported fields fail closed in editor, transport and hook validation.
- Signal fluctuation never changes an unavailable sentinel.
- One profile field may resolve differently on different Android surfaces.
- Release and debug code consume the same production schema; the debug acceptance path remains
  isolated and never writes Room.

## Adversarial cases

- duplicate unavailable entries;
- an unavailable key missing from `FieldSpec`;
- an explicitly unsupported key such as `is_roaming`;
- a key present in both `fields` and `unavailable`;
- corrupt/non-array Room metadata;
- unsupported schema version outside the v2/v3 compatibility window;
- `lac`/`cid` observed through both `CellIdentityGsm` and `GsmCellLocation`;
- `mcc`/`mnc` observed through both integer and nullable-string APIs;
- unavailable signal value with fluctuation enabled;
- edit `--` to a concrete value and save twice;
- process reload after a migrated v1 database.

## TDD checkpoints

1. **Surface resolver**
   - Red: dual-surface `lac`/`cid`, PLMN nullable strings, network/service/display/physical
     constants, unknown `(field, surface)` fail closed.
   - Green: add the resolver and expand `UnavailableSpec` only for fields whose complete cellular
     surface census is implemented.
2. **Snapshot and last-known-good**
   - Red: parse unavailable set; reject intersection, duplicate, unknown and unsupported keys;
     prove the old snapshot survives rejection.
   - Green: immutable set in `Snapshot`; schema-v3 envelope validation in `MainHook`.
3. **Room and publication**
   - Red: migration preserves old row; canonical round trip; metadata never leaks into `fields`.
   - Green: DB v2 migration, entity column, repository and schema-v3 publication.
4. **Editor**
   - Red: blank/`--`/value transitions and unsupported-field rejection.
   - Green: state mapping, save behavior, visible `--` action and explanatory copy.
5. **Acceptance**
   - Add representative multi-surface unavailable assertions to the debug payload/probe.
   - Run JVM tests, Python tests, Debug/Release assembly, release isolation checks, and the real
     device matrix when the attached acceptance device is available.

## Architecture ownership

- **Architecture cell:** profile persistence → flat config transport → hook snapshot → Android
  public surfaces.
- **Map delta:** none; this extends the existing cell with one orthogonal state and does not add a
  new store, queue, router or process boundary.
- **Why:** Android’s unavailable representation belongs at the public-surface call site, while the
  profile and transport only own the user’s unavailable decision.

## Validation evidence

Captured on 2026-07-30 from
`/Users/terry/Desktop/coding/FakeGps-test-verify-ux`:

- JVM: 211 tests, 0 failures/errors;
- Python acceptance/verdict: 18 tests, 0 failures;
- Debug and minified Release APK assembly: success;
- `lintVitalRelease`: success;
- Moto g54 5G / API 35 real-device matrix:
  `configured=274, verified=274, failed=0, restored=true`;
- recovery: SIGKILL durable record restored; database unchanged; hook payload restored;
- UI dogfood: opened the effective profile, selected GSM MCC “不上报”, observed draft `--`,
  “透传” reversal action and explicit “目标 App 将看到该 API 的无数据值” copy, then reverted the
  draft without saving user data.
