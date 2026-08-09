---
feature_ids:
  - cellular-hook-verification
  - profile-unavailable-state
topics:
  - android
  - xposed
  - verification
  - carrier
doc_kind: bug-report
created: 2026-08-01
---

# Pre-review surface-contract gaps

## Report

The fresh-context scan of implementation SHA `c5effbef` found 0 P1 and 10 P2 issues. The visible
symptoms were inconsistent carrier values across public APIs, a spoofed value presented as a real
baseline, API-level contract drift, and verifier/UI summaries that could contradict their evidence.

## Reproduction and expected behavior

- In a self-hooked debug process, configure a cellular field and compare the normal observation
  with the guarded baseline. Before the fix, `getAllCellInfo()` still rebuilt the list, so both
  readings could contain configured constructor values. The guarded reading must be real or absent.
- Configure carrier values or `--` and read TelephonyManager, CellIdentity, ServiceState and
  NetworkRegistrationInfo. Concrete values must agree; unknown must use each surface's native
  empty representation, and API 34+ roaming must not bypass the hook.
- Feed legacy schema v2, ambiguous fields and unavailable negative controls into the verification
  pipeline. The UI and terminal summary must describe the same accepted contract and evidence.

## Root cause

Three coordinate mistakes combined:

1. baseline bypass lived only in selected getter hooks instead of the single snapshot read used by
   every hook group, while asynchronous cell refresh could not inherit the ThreadLocal guard;
2. a profile-level unavailable decision was materialized once and reused across Android surfaces
   whose native unknown values differ (`""`, `null`, `0` or `-1`) and across API levels;
3. probe/UI helpers encoded convenient SDK and counting assumptions instead of sharing the runtime
   compatibility contract and preserving configured-field accounting.

## Fix

- route every HookUtils snapshot read through one baseline-aware accessor and skip asynchronous
  cell refresh during the narrow ThreadLocal extraction;
- resolve carrier object text as nullable, data state by API level, cover API 34+
  `isNetworkRoaming()`, and preserve real-neighbor bypass for carrier-only profiles;
- collect identity carrier getters from API 28, share schema compatibility with
  `TransportSchemaContract`, explain ambiguous partial results, and keep negative controls outside
  configured-field counts;
- update the feature truth sources for v2 compatibility, per-surface carrier nulls, neighbor-list
  fail-closed behavior and API-dependent data-state values.

## Verification

- red tests reproduced the missing baseline predicate, nullable carrier objects, API-dependent
  data state, API 34 roaming census, ambiguous copy and negative-control count inflation;
- `testDebugUnitTest`: 234 tests, 0 failures/errors/skips;
- `assembleDebug`, minified `assembleRelease` and `lintVitalRelease`: successful;
- Python cellular matrix + verdict suites: 24 tests, OK;
- full `lintDebug`: 20 existing baseline errors, 0 new errors.

No device write was performed; runtime acceptance remains a separate authorized step.
