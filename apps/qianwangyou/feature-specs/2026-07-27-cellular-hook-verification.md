---
feature_ids:
  - cellular-hook-verification
topics:
  - android
  - xposed
  - cellular
  - acceptance-testing
doc_kind: implementation-plan
created: 2026-07-27
---

# Cellular Hook Verification Implementation Plan

> **Owner:** 砚砚 / gpt-5.6-sol  
> **Goal:** Prove, on a real Android device, that every configured cellular identity, signal,
> carrier, service-state, display-info, and physical-channel field reaches the public Android API
> observed by an app, without modifying the user's saved profiles.

**Architecture:** Add a debug-only acceptance activity that temporarily publishes a schema-v3
field-map payload to the existing XSharedPreferences transport, waits for the hook refresh, runs an
expanded public-API probe, and restores the real database-backed payload in `finally`. Before any
override, a debug-only recovery record durably stores the previous transport payload; the next
debug process launch republishes it if the acceptance process was killed. A host-side verdict
module compares exact configured matrices and an enabled-fluctuation behavior scenario with the
probe JSON. Each transport envelope carries a unique session ID and exposes the same ID through a
session-specific operator-name marker; the activity requires that public getter to match before
starting the full probe. The shell harness owns an additional idempotent restore path for
process/signal failures. Production builds contain no acceptance component, and the Room database
is never written by the test.

**Tech Stack:** Kotlin, Java/Xposed, Android public telephony APIs, Gradle/JUnit 4, Python 3
`unittest`, Bash/ADB, moto g54 5G on Android 15.

## Finish line

The work is complete only when all of the following are true:

1. One unattended command installs the debug APK, injects a distinct cellular matrix with a
   per-run public marker, reads it back through public Android APIs, restores the real config, and
   exits non-zero on any mismatch or stale hook snapshot.
2. The matrix verifies synchronous and callback paths for LTE, GSM, WCDMA, NR, carrier/network,
   service state, display info, and physical channel config on API 35.
3. Every configured and API-supported field has an exact `verified` verdict. `missing`,
   `real_value`, `error`, and silent omission are failures.
4. The original Room rows are byte-for-byte/logically unchanged, and the normal database-backed
   transport is republished after success, assertion failure, shell signal, probe exception, or
   acceptance-process SIGKILL followed by the next debug app launch.
5. `testDebugUnitTest`, Python unit tests, Debug/Release assembly, `lintVitalRelease`, and the real
   device acceptance run are green.
6. The release manifest and APK do not contain the debug acceptance activity, recovery
   application, recovery record, or payload validator.

## Terminal report schema

The probe emits one JSON object under `FakeGPSProbe`:

```json
{
  "sessionId": "acceptance-...",
  "apiLevel": 35,
  "cellInfo": {
    "sync": {
      "lte": {},
      "gsm": {},
      "wcdma": {},
      "nr": {}
    },
    "request": {
      "lte": {},
      "gsm": {},
      "wcdma": {},
      "nr": {}
    },
    "requestCompleted": true
  },
  "telephony": {},
  "callback": {
    "completed": true,
    "cellInfo": {
      "lte": {},
      "gsm": {},
      "wcdma": {},
      "nr": {}
    },
    "serviceState": {},
    "displayInfo": {},
    "physicalChannel": {},
    "physicalChannelDelivery": "hook_replay_after_permission_denied"
  },
  "errors": []
}
```

The verdict tool emits one line per expected field:

```text
VERIFIED cellInfo.sync.lte.tac expected=4095 observed=4095
FAILED   cellInfo.sync.nr.nci expected=68719400000 observed=<missing>
```

Its final JSON summary is stable and machine-readable:

```json
{"configured": 55, "verified": 55, "failed": 0, "restored": true}
```

## Acceptance matrix

Use valid, intentionally distinctive values that do not match the attached device's real Kyivstar
environment:

| Group | Profile columns / public observations |
|---|---|
| GSM identity | `mcc`, `mnc`, `lac`, `cid`, `arfcn`, `bsic` |
| GSM signal | `gsm_rssi`, `gsm_ber`, `gsm_ta` |
| WCDMA identity | `mcc`, `mnc`, `lac`, `cid`, `psc`, `uarfcn` |
| WCDMA signal | `wcdma_rssi`, `wcdma_rscp`, `wcdma_ecno` |
| LTE identity | `mcc`, `mnc`, `tac`, `ci`, `pci`, `earfcn`, `lte_bandwidth` |
| LTE signal | `lte_rssi`, `lte_rsrp`, `lte_rsrq`, `lte_sinr`, `lte_cqi`, `lte_ta` |
| NR identity | `mcc`, `mnc`, `nci`, `nrarfcn`, `nr_pci`, `nr_tac` |
| NR signal | `nr_ss_rsrp`, `nr_ss_rsrq`, `nr_ss_sinr`, `nr_csi_rsrp`, `nr_csi_rsrq`, `nr_csi_sinr` |
| Signal controls | Exact matrices omit the controls; a separate `signal_fluctuation_enabled=1`, `signal_fluctuation_range_db=6` behavior scenario requires all seven allowed LTE RSRP values across 256 public-getter calls on each of three delivery paths |
| Carrier/network | `network_type`, `data_network_type`, `voice_network_type`, `operator_name`, `operator_numeric`, `sim_operator`, `sim_operator_name`, `sim_country_iso`, `network_country_iso`, `is_roaming`, `phone_type` |
| State/display | `service_state`, `data_state`, `data_activity`, `override_network_type` |
| Physical channel | `band`, `channel_bandwidth`, `cell_bandwidth_downlink`, `physical_cell_id` |
| Neighbor cells | Distinct GSM, LTE, and WCDMA identities/signals from `neighbor_cells_json`, each observed as `registered=false` |

Both `TelephonyManager.getAllCellInfo()` and `requestCellInfoUpdate()` must return the configured
serving and neighbor cell sets. Telephony callbacks must expose the configured
service/display/physical-channel values.

### Serving-RAT construction state machine

`Snapshot` owns field decisions; Android owns the incoming `CellInfo` topology; `HookUtils` owns
the pure projection from those two inputs to the returned list. No second persisted RAT selector is
introduced. Shared identity fields are projections, not evidence that a specific RAT exists.

| Profile event | Serving-list transition | Neighbor transition |
|---|---|---|
| no RAT-specific identity field | preserve Android's serving RAT objects; apply shared/getter decisions at read time | register every real neighbor in the weak bypass registry |
| configure GSM `arfcn/bsic` | construct/replace GSM only | preserve real neighbors unless explicit neighbor JSON replaces them |
| configure WCDMA `psc/uarfcn` | construct/replace WCDMA only | same |
| configure LTE `tac/ci/pci/earfcn/lte_bandwidth` | construct/replace LTE only | same |
| configure NR `nci/nrarfcn/nr_pci/nr_tac` | construct/replace NR only (API 29+) | same |
| configure multiple RAT-specific groups | construct exactly those groups; never infer another RAT from shared fields | same |
| configure only MCC/MNC/LAC/CID | preserve Android topology and project onto compatible existing serving identities | real neighbors remain bypassed |
| mark fields unavailable only | do not construct any serving RAT | existing surface-specific getters still return native unknown values |
| configure `neighbor_cells_json` | does not select a serving RAT | construct the explicit neighbor set under the existing neighbor contract |

Invariants:

- INV-RAT-1: `mcc/mnc/lac/cid` never select GSM, WCDMA, LTE or NR construction;
- INV-RAT-2: each constructed serving RAT has at least one configured, non-unavailable identity
  field owned only by that RAT;
- INV-RAT-3: absent an explicit RAT construction decision, the framework's serving topology is
  preserved and shared fields are getter projections;
- INV-RAT-4: multiple constructed serving RATs correspond one-for-one with explicitly configured
  RAT-specific groups;
- INV-RAT-5: unavailable-only and signal-only decisions never fabricate identity objects;
- INV-RAT-6: every cell-list delivery path, subscription-topology guard, builder and real-cell
  preservation path consumes the same canonical reconstruction predicate;
- INV-RAT-7: real neighbors are entered in the weak bypass registry whenever the framework list is
  passed through, so global serving getter hooks never rewrite them.

Adversarial matrix: shared MCC/MNC + LTE; shared MCC/MNC + NR; LAC/CID + WCDMA; shared-only on a
real LTE baseline; unavailable-only LAC/CID/PSC; signal-only; explicit GSM + LTE; WCDMA-only
PSC; blank profile.

### API-35 physical-channel permission boundary

`TelephonyCallback.PhysicalChannelConfigListener` is guarded by
`android.permission.READ_PRECISE_PHONE_STATE`, which is `signature|privileged` on the attached
Android 15 device. Root cannot grant that permission to an ordinary debug APK without turning the
test package into a privileged/system app.

The acceptance probe therefore separates ordinary callbacks from the physical-channel callback.
It first calls `registerTelephonyCallback` for the physical listener so the real production Xposed
before-hook instruments the concrete callback class. After the framework rejects registration at
its privileged permission boundary, the probe locally replays one empty callback. The production
hook must replace that empty list with a real `PhysicalChannelConfig` instance, and every public
getter is then asserted exactly. The report records
`physicalChannelDelivery=hook_replay_after_permission_denied`; a missing replacement, constructor
failure, getter mismatch, unexpected exception, or missing ordinary callback remains a hard
failure. This verifies the module's callback interception without claiming that an unprivileged
third-party app can receive a framework event Android does not allow it to register for.

Two unavailable observations, `band=0` and `physicalCellId=-1`, equal the no-arg Builder defaults.
Exact observation plus a full-profile negative control therefore cannot independently distinguish
their individual getter hooks from a per-method no-op. They are explicitly excluded from the
dynamic-negative-control claim. A production-consumed `PhysicalChannelHookRegistry` plus JVM
census covers those methods (and the two sibling bandwidth getters); dynamic acceptance still
proves callback replacement and the physical-channel hook group with non-default fields.

## Override lifecycle and invariants

| Current state | Event | Next state | Required effect |
|---|---|---|---|
| `idle` | valid debug intent | `published` | First durably record the current transport payload in debug-private recovery state, then publish the schema-v3 test payload with top-level session ID and session-specific operator marker; do not touch Room |
| `idle` | invalid/missing payload | `aborted` | Log validation error; do not alter transport |
| `published` | hook refresh delay elapsed | `probing` | Read the public network-operator getter and probe only if it exposes this run's session marker |
| `probing` | success or exception | `restoring` | Emit exactly one terminal probe/error record |
| `restoring` | database-backed sync commits and recovery record clears | `restored` | Log restore marker; finish activity |
| any nonterminal | shell exit/signal/timeout | `restoring` | Host trap launches the normal activity to republish saved config |
| any nonterminal | process death / host loss / reboot | `restoring` | On the next debug process launch, publish the durable previous payload before normal app startup |

Invariants:

- The acceptance path never inserts, updates, deletes, copies, or replaces `fakegps.db`, its WAL,
  or any profile row.
- The test payload exists only in the debug XSharedPreferences transport and carries a unique
  `acceptanceSessionId`. Its `operator_name` contains a derived public marker; a stale loaded
  snapshot from an otherwise identical matrix cannot satisfy the current run.
- Restore is idempotent: activity `finally`, next-launch durable recovery, and host trap may all
  run. The recovery record clears only after the previous payload publish commits.
- A stale probe line from another session cannot satisfy the current run.
- Test mode is absent from release manifests and release bytecode entry points.
- Exact scenarios omit signal fluctuation controls so equality remains deterministic. A separate
  enabled scenario proves the controls reached the hook by observing the complete configured range,
  not merely one value equal to the base RSRP.

## Task 1: Lock the debug payload contract

**Files:**

- Create: `app/src/debug/java/name/caiyao/fakegps/probe/HookAcceptancePayload.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/probe/HookAcceptancePayloadTest.kt`

**Red:**

- Reject missing/blank session IDs, non-object `fields`, unsupported schema versions, and keys
  outside the profile field map.
- Prove the canonical envelope is
  `{schemaVersion:3, acceptanceSessionId:"...", mode:"always_on", fields:{...}, unavailable:[]}`.
- Reject an envelope session or public operator marker that differs from the activity session.
- Prove numeric values retain integer/long width (`nci` must not round through `Double`).

Run:

```bash
./gradlew testDebugUnitTest --tests '*HookAcceptancePayloadTest'
```

Expected: new tests fail because the payload builder does not exist.

**Green:**

- Implement the smallest pure payload validator/builder used by the debug activity.
- Keep it in the debug source set so release compilation cannot reference it.

## Task 2: Add a debug-only transactional probe entry point

**Files:**

- Create: `app/src/debug/AndroidManifest.xml`
- Create: `app/src/debug/java/name/caiyao/fakegps/probe/HookAcceptanceActivity.kt`
- Modify: `app/src/main/java/name/caiyao/fakegps/config/ConfigPrefsSync.kt` only if a narrow
  internal publish primitive is required; do not expose a production test API.

**Red:**

- Build/manifest inspection must show the component missing before implementation.
- Add unit coverage for payload validation and explicit restore-state decisions.

**Green:**

- Export the acceptance activity only in the debug manifest.
- Accept base64url UTF-8 JSON and session ID extras.
- Publish with `commit()`, wait for the existing 3-second hook refresh, invoke the probe, then call
  `ConfigPrefsSync.sync(applicationContext)` in `finally`.
- Before entering `probing`, require `TelephonyManager.getNetworkOperatorName()` to equal the
  session-specific marker carried by the published payload. A stale hook snapshot fails and
  restores without emitting a normal report.
- Before publishing, atomically persist the current transport payload in a debug-only recovery
  record. Recover any pending record from the debug `Application` before ordinary app startup, and
  clear it only after restore publication commits.
- Log `published`, `probing`, and `restored` markers with the same session ID.
- Never open Room or the database from the acceptance activity.

## Task 3: Expand the public-API probe

**Files:**

- Modify: `app/src/main/java/name/caiyao/fakegps/probe/HookProbe.kt`
- Create: `app/src/debug/java/name/caiyao/fakegps/probe/HookProbeRunner.kt`
- Create: debug/release variants of
  `name/caiyao/fakegps/probe/DebugHookProbeController.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/probe/ProbeFieldContractTest.kt`
- Create: `app/src/test/java/name/caiyao/fakegps/probe/HookProbeRunnerTest.kt`

**Red:**

- Contract tests enumerate every matrix path and fail while WCDMA, NR, full signals, service,
  display, physical-channel, and callback cell paths are absent.

**Green:**

- Collect all API-35 identity/signal getters for the first cell of each radio type.
- Always exercise both cached `allCellInfo` and `requestCellInfoUpdate`.
- Register a composite `TelephonyCallback` for service state, display info, physical channel, and
  cell info; wait with a bounded latch and unregister in `finally`.
- Dispatch the blocking probe on a lifecycle-owned worker; route result/state transitions back
  through the activity main executor and suppress queued completion after destruction.
- Include API level, session ID, errors, and callback timeouts in the JSON.
- Shut down executors so the probe cannot leak threads.

## Task 4: Extract a deterministic verdict engine

**Files:**

- Create: `scripts/hook_verdict.py`
- Create: `scripts/test_hook_verdict.py`

**Red:**

- Test exact numeric/string/boolean equality, missing paths, stale session IDs, API-gated paths,
  callback mismatches, probe errors, restore-marker absence, and complete integer-set matchers for
  enabled signal fluctuation.
- Test that `0`, `false`, and empty strings are compared rather than treated as missing.

Run:

```bash
python3 -m unittest scripts.test_hook_verdict
```

Expected: tests fail because the verdict module does not exist.

**Green:**

- Implement pure parsing/comparison functions and a CLI.
- Print deterministic per-field lines and a final JSON summary.
- Exit `1` for any configured supported field that is missing or different; exit `2` for harness
  misuse or malformed input.

## Task 5: Turn the shell script into an isolated acceptance transaction

**Files:**

- Modify: `scripts/test-hook.sh`

**Red:**

- Run against the current device with the existing three-field profile and prove the old script
  cannot verify the full cellular matrix.

**Green:**

- Add `--cellular-matrix` while retaining `--current-profile` diagnostics.
- Preflight: one rooted API-33+ development device, awake/unlocked, debug acceptance activity
  installed, required permissions granted, and Xposed self-hook active.
- Snapshot the current DB query output and transport fingerprint without writing either.
- Generate a unique session, launch the debug activity with the canonical payload, collect only
  matching-session logs, and call `hook_verdict.py`.
- Run two full exact matrices plus one enabled-fluctuation behavior scenario; require all configured
  fields to be credited by an observable verdict path.
- Arm a debug-only post-publish hold, SIGKILL the process, relaunch the normal debug activity, and
  prove the durable record restored the exact pre-test transport fingerprint before clearing.
- Install `EXIT`, `INT`, `TERM`, and timeout restore traps that relaunch normal `ComposeActivity`.
- Assert post-run DB output equals pre-run output and the activity emitted `restored`.

## Task 6: Run the real-device matrix and fix uncovered hook gaps

**Files (only when a failing assertion proves a gap):**

- Modify: `app/src/main/java/name/caiyao/fakegps/hook/HookUtils.java`
- Modify: `app/src/main/java/name/caiyao/fakegps/hook/Snapshot.java`
- Modify: `app/src/main/java/name/caiyao/fakegps/hook/CellConstructorCompat.java`
- Add/modify focused tests under `app/src/test/java/name/caiyao/fakegps/hook/`

Run:

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
./scripts/test-hook.sh --cellular-matrix
```

For each failure:

1. Capture the configured value, public observed value, hook log, and call path.
2. Write the smallest red JVM contract test reproducing the mapping/constructor/state bug.
3. Fix the root cause across the same failure-mode family.
4. Re-run the focused unit test, then the full device matrix.

Do not weaken the verdict, mark supported fields unavailable, or add hardcoded fake fallbacks to
make the matrix green.

## Task 7: Quality gate, release isolation, and independent review

Run:

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export ANDROID_HOME='/Users/terry/Library/Android/sdk'
./gradlew clean testDebugUnitTest assembleDebug assembleRelease lintVitalRelease
python3 -m unittest scripts.test_cellular_acceptance_matrix scripts.test_hook_verdict
./scripts/test-hook.sh --cellular-matrix
```

Inspect the release artifact:

```bash
"$ANDROID_HOME/build-tools/36.1.0/aapt" dump xmltree \
  app/build/outputs/apk/release/app-release-unsigned.apk AndroidManifest.xml |
  rg 'HookAcceptance(Activity|Application|Recovery|Payload)'
```

Expected: `rg` exits `1` because all debug-only acceptance and recovery classes are absent.

Then:

1. Run `quality-gate`.
2. Request a non-author, cross-individual review on the final HEAD.
3. Process findings with red-green evidence.
4. Run `merge-gate` and squash merge.

## Explicitly out of scope

- No writes to saved profile rows or database files.
- No release-build test backdoor or exported production provider.
- No Wi-Fi/location acceptance redesign in this work item.
- No dependency addition.
- No broad Compose redesign here; Fable 5 owns that independent UI workstream.
- No claim that an API-35 device proves OEM/API compatibility for every Android release; JVM
  constructor-shape tests and the existing API-24 guards remain the compatibility evidence.
