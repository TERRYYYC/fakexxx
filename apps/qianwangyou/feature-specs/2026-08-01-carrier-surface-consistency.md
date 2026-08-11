---
feature_ids:
  - cellular-hook-verification
  - profile-unavailable-state
topics:
  - android
  - xposed
  - cellular
  - carrier
doc_kind: implementation-plan
created: 2026-08-01
---

# Carrier Surface Consistency Implementation Plan

**Feature:** `cellular-hook-verification` — `feature-specs/2026-07-27-cellular-hook-verification.md`
**Goal:** One profile carrier decision is observed consistently through Android's manager, cell-identity, service-state, network-registration and callback surfaces.
**Acceptance Criteria:** `operator_name` controls both alpha-name getters and rebuilt identity metadata; `operator_numeric` controls registered-PLMN getters; `is_roaming` controls manager and state-object getters; blank remains passthrough and `--` remains the platform-native empty string; all paths are represented in the deterministic acceptance matrix.
**Architecture cell:** profile config → hook snapshot → Android telephony public surfaces
**Map delta:** none
**Map delta why:** This extends the existing cellular projection cell and creates no new process, store, queue or lifecycle owner.
**Architecture:** Keep `Snapshot` as the only runtime configuration state. Getter hooks project its carrier fields onto every public object surface, while rebuilt `CellIdentity` values receive the same alpha metadata so their internal state agrees with their getters.
**Tech Stack:** Java 17, Kotlin, Xposed API 82, Android API 24–35, JUnit 4, Python `unittest`
**前端验证:** No — this changes hook behavior and acceptance probes, not UI.

---

## Finish line

For a target app process that has loaded a profile snapshot:

- `operator_name` is returned by `TelephonyManager.getNetworkOperatorName()`,
  `CellIdentity.getOperatorAlphaLong/Short()` and
  `ServiceState.getOperatorAlphaLong/Short()`;
- `operator_numeric` is returned by `TelephonyManager.getNetworkOperator()`,
  `ServiceState.getOperatorNumeric()` and `NetworkRegistrationInfo.getRegisteredPlmn()`;
- `is_roaming` is returned by `TelephonyManager.isNetworkRoaming()`,
  `ServiceState.getRoaming()` and both NetworkRegistrationInfo roaming getters
  (`isRoaming()` plus API 34+ `isNetworkRoaming()`);
- `PhoneStateListener` and `TelephonyCallback` need no carrier-specific state mutation: their
  delivered `ServiceState` and `CellInfo` objects expose the same hooked getters;
- a rebuilt GSM/WCDMA/LTE identity stores the configured alpha name in its constructor metadata.
- MCC/MNC integer profile values use one canonical string projection on constructor, getter and
  configured-neighbor surfaces (`mcc=46 → "046"`, `mnc=0/3 → "00"/"03"`); verification applies
  the same field rule and reports `AMBIGUOUS` when the genuine baseline is identical.

Not building: Cellular-Pro private/native hooks, modem protocol rewriting, or implicit
`operator_numeric` → MCC/MNC coupling. Those would cross the public Android surface boundary or
collapse independently editable profile fields.

## Stateful-object gate

No new stateful object is introduced. `MainHook.CURRENT` remains the only lifecycle owner and
carrier values are pure read-time projections. Rebuilt identity metadata is derived from the same
snapshot during object construction and is not persisted separately.

Invariants:

- INV-1: null profile field means passthrough on every corresponding surface;
- INV-2: `operator_name == ""` remains an explicit unavailable value, not passthrough;
- INV-3: configured alpha metadata never discards bands, additional PLMNs or CSG metadata;
- INV-4: callback observation is derived from the object getter contract, not a second carrier
  state machine;
- INV-5: neighbor identity bypass semantics remain unchanged.
- INV-6: every integer-to-PLMN string boundary uses the canonical width resolver; API 24–27 integer
  getters remain integers and are not padded.
- INV-7: physical-channel unavailable getters whose result equals Builder defaults are installed
  from one production registry and covered by a JVM census; the device matrix does not call those
  paths independent dynamic negative controls.
- INV-8: carrier and shared PLMN/area fields are pure projections and never select a serving RAT;
  the canonical construction state machine is defined in
  `feature-specs/2026-07-27-cellular-hook-verification.md`.

Adversarial cases: only name configured; only numeric configured; roaming false; explicit empty
operator name; rebuilt identity with real non-carrier metadata; API level where a surface class or
method does not exist.

### Task 1: Lock carrier projection and constructor metadata

**Files:**
- Modify: `app/src/test/java/name/caiyao/fakegps/hook/CellConstructorCompatTest.java`
- Create: `app/src/test/java/name/caiyao/fakegps/hook/CarrierSurfaceCoverageTest.java`
- Modify: `app/src/main/java/name/caiyao/fakegps/hook/CellIdentityMetadata.java`

1. Add a failing test proving configured operator name replaces both alpha values without losing
   bands, additional PLMNs or CSG info.
2. Add a failing bytecode contract requiring all CellIdentity, ServiceState and
   NetworkRegistrationInfo carrier getter names in the production hook registry.
3. Run the two targeted test classes and verify the expected failures.
4. Add the minimal immutable metadata projection and hook registrations.
5. Re-run the targeted tests and keep all existing constructor compatibility tests green.

### Task 2: Apply carrier projection to every rebuilt identity

**Files:**
- Modify: `app/src/main/java/name/caiyao/fakegps/hook/HookUtils.java`
- Modify: `app/src/test/java/name/caiyao/fakegps/hook/CellConstructorCompatTest.java`

1. Add failing coverage for serving identity metadata and configured neighbor metadata.
2. Pass `Snapshot.operatorName` into GSM, WCDMA and LTE metadata projection at construction time.
3. Verify null preserves the real metadata and empty string remains a real configured value.

### Task 3: Extend deterministic public-surface acceptance

**Files:**
- Modify: `app/src/main/java/name/caiyao/fakegps/probe/HookProbe.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/probe/ProbeFieldContract.kt`
- Modify: `app/src/test/java/name/caiyao/fakegps/probe/ProbeFieldContractTest.kt`
- Modify: `scripts/cellular_acceptance_matrix.py`
- Modify: `scripts/test_cellular_acceptance_matrix.py`

1. Add failing contract expectations for identity alpha names, direct ServiceState carrier fields,
   NetworkRegistrationInfo PLMN/roaming, and callback ServiceState carrier fields.
2. Extend the probe using API-gated public getters only.
3. Extend the matrix so every new path maps back to exactly one profile source field, including
   serving `mnc=0 → "00"` and configured-neighbor `mnc=3 → "03"`.
4. Run the Kotlin/JVM contract tests and Python matrix tests.

### Task 4: Regression and handoff

1. Run targeted JVM tests, then all `testDebugUnitTest` tests.
2. Run all Python acceptance/verdict tests.
3. Build Debug and minified Release APKs and run `lintVitalRelease`.
4. Do not install or run instrumentation until the co-creator separately authorizes device writes.
5. Run `quality-gate`, commit the implementation with Why and thread provenance, then request an
   independent cross-individual review.
